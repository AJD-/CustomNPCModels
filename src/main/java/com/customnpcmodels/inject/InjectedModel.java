/*
 * Copyright (c) 2026, AJD
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package com.customnpcmodels.inject;

import lombok.extern.slf4j.Slf4j;
import net.runelite.api.AABB;
import net.runelite.api.Model;
import net.runelite.api.Node;

/**
 * Geometry this plugin owns, presented to the renderer as a {@link Model}.
 *
 * <h2>Why this can be handed to the renderer at all</h2>
 *
 * {@code Model} is an interface, and the GPU plugin consumes it purely through
 * {@code invokeinterface} - {@code GpuPlugin.drawTemp} to {@code ModelUploader.uploadTempModel} /
 * {@code uploadSortedModel} contains no {@code checkcast} anywhere. Verified against
 * client 1.12.38. 117 HD's zone renderer is the same: {@code ModelStreamingManager.drawTemp}
 * through {@code SceneUploader.uploadTempModel} reads only the interface, and its async path copies
 * the arrays synchronously inside {@code drawTemp}, sized by the vertex and face counts.
 *
 * <h2>The three places this must never go</h2>
 *
 * Everything else in the client casts to the obfuscated concrete model class:
 * <ul>
 *   <li>{@code Client#applyTransformations} - unguarded cast, throws {@link ClassCastException}.
 *       <b>This is why injected geometry cannot use the client's animation system</b> and has to be
 *       skinned in Java instead.</li>
 *   <li>{@code Client#checkClickbox} - unguarded cast. Never reached in practice: the client
 *       registers the clickbox itself after {@code drawTemp} returns, using its own model.</li>
 *   <li>An <b>active</b> {@code RuneLiteObject} - the cast there is caught, so the failure is
 *       silent: it logs "Exception rendering RuneLiteObjectController" and draws nothing. Passing
 *       one to {@code ModelOutlineRenderer} is fine, because that only reads the interface.</li>
 * </ul>
 *
 * <h2>Which methods carry data</h2>
 *
 * Only the ones the renderers and the outline renderer actually read. The accessors gated behind
 * a render flag return null, and that is safe rather than lazy. {@code GpuPlugin.setupGpuFlags}
 * sets only {@code GPU | ZBUF | RENDER_THREADS}, never {@code HILLSKEW}, {@code NORMALS} or
 * {@code UNLIT_FACE_COLORS}, so there the gated accessors are never called. 117 HD's zone renderer
 * does set {@code NORMALS}, and {@code UNLIT_FACE_COLORS} under some shading modes, but null-checks
 * both: missing normals fall back to flat face normals, and missing unlit colors to the lit ones.
 * {@code drawFrustum} and {@code drawOrtho} belong to the software rasterizer, which neither uses.
 * <p>
 * <b>Maintenance cost, deliberately accepted:</b> {@code Model} has no default methods, so a
 * RuneLite release that adds one breaks compilation here. Nothing pins the client version to stop
 * that: {@code build.gradle} resolves {@code latest.release}, matching the example-plugin template,
 * and the Hub rebuilds against whatever is current regardless of what a plugin asks for.
 * <p>
 * That cuts two ways, and the difference matters. Through the Hub the failure is loud and
 * contained - the rebuild fails, the plugin is delisted until it is patched, and no user ever runs
 * a jar missing a method. A <b>sideloaded</b> jar is the dangerous case: it meets whatever client
 * the launcher runs, and a method added since it was compiled surfaces at runtime as
 * {@link AbstractMethodError} inside the uploader - an {@code Error}, which the GPU plugin's
 * {@code catch (Exception)} will not contain.
 * <p>
 * So the mitigation is upkeep rather than a version range: when {@code Model} changes, this
 * class changes with it. The interface was last read in full against client 1.12.38.
 */
@Slf4j
public class InjectedModel implements Model
{
	private int verticesCount;
	private float[] verticesX = new float[0];
	private float[] verticesY = new float[0];
	private float[] verticesZ = new float[0];

	private int faceCount;
	private int[] faceIndices1 = new int[0];
	private int[] faceIndices2 = new int[0];
	private int[] faceIndices3 = new int[0];

	/**
	 * Lit per-corner colors. {@code faceColors3 == -1} means flat shaded and {@code -2} means a
	 * hidden face; the renderer depends on both sentinels, so they must survive the copy intact.
	 */
	private int[] faceColors1 = new int[0];
	private int[] faceColors2 = new int[0];
	private int[] faceColors3 = new int[0];

	private byte[] faceRenderPriorities;
	private byte[] faceTransparencies;
	private byte[] faceBias;
	private short[] faceTextures;
	private byte[] textureFaces;
	private int[] texIndices1;
	private int[] texIndices2;
	private int[] texIndices3;

	private byte transparency;
	private byte overrideAmount;
	private byte overrideHue;
	private byte overrideSaturation;
	private byte overrideLuminance;

	private int radius;
	private int diameter;
	private int bottomY;
	private int modelHeight;
	private int animationHeightOffset;
	private int renderMode;

	/** One-shot guard for the bounds comparison; this runs per NPC per frame. */
	private boolean boundsChecked;

	private int sceneId;
	private int bufferOffset;
	private int uvBufferOffset;

	/**
	 * Prepares this model to be posed from an injected mesh.
	 *
	 * <p>Called once per mesh, not per frame. Face data is taken by reference because it never
	 * changes with the pose; only vertices move. The vertex buffers are sized here and then written
	 * directly by the skinner, so posing copies nothing.
	 *
	 * @param litColors1 per-corner lit colors from {@link Lighter}, baked at rest pose
	 */
	public void bind(Mesh mesh, int[] litColors1, int[] litColors2, int[] litColors3)
	{
		verticesCount = mesh.getVerticesCount();
		faceCount = mesh.getFaceCount();

		if (verticesX.length < verticesCount)
		{
			verticesX = new float[verticesCount];
			verticesY = new float[verticesCount];
			verticesZ = new float[verticesCount];
		}

		faceIndices1 = mesh.getFaceIndices1();
		faceIndices2 = mesh.getFaceIndices2();
		faceIndices3 = mesh.getFaceIndices3();

		faceColors1 = litColors1;
		faceColors2 = litColors2;
		faceColors3 = litColors3;

		faceRenderPriorities = mesh.getFaceRenderPriorities();
		faceTransparencies = mesh.getFaceTransparencies();
		faceTextures = mesh.getFaceTextures();

		// Face texture ids are live texture ids. Authored meshes carry none - the glTF path cannot
		// express RS texturing - so this is null for everything the pipeline produces today.
		//
		// The UV mapping comes with it. ModelUploader.computeUv projects a face's corners onto the
		// plane basis of the triangle named here; with none it emits a hardcoded (0,0), (1,0),
		// (0,1) instead, which stretches the whole image across every face separately.
		textureFaces = mesh.getTextureCoords();
		texIndices1 = mesh.getTexIndices1();
		texIndices2 = mesh.getTexIndices2();
		texIndices3 = mesh.getTexIndices3();
		faceBias = null;

		transparency = 0;
		overrideAmount = 0;
		overrideHue = 0;
		overrideSaturation = 0;
		overrideLuminance = 0;
		animationHeightOffset = 0;
		renderMode = RENDERMODE_DEFAULT;

		System.arraycopy(mesh.getVerticesX(), 0, verticesX, 0, verticesCount);
		System.arraycopy(mesh.getVerticesY(), 0, verticesY, 0, verticesCount);
		System.arraycopy(mesh.getVerticesZ(), 0, verticesZ, 0, verticesCount);
		calculateBoundsCylinder();
	}

	/**
	 * Takes a full copy of another model's geometry into this one's own buffers.
	 *
	 * <p>Copying rather than aliasing is the point: the client's posed model is shared and is
	 * invalidated by the next {@code applyTransformations} call, including the client's own, so
	 * holding a reference to its arrays would be a use-after-free in slow motion.
	 *
	 * <p>The vertex, index and color buffers are grown on demand and reused, so a steady state does
	 * not allocate for those. The per-face columns go through {@code copyOrNull}, which clones
	 * every time: a null there carries meaning to the renderer, and a reused buffer cannot express
	 * one.
	 */
	public void copyFrom(Model source)
	{
		float[] sourceX = source.getVerticesX();
		float[] sourceY = source.getVerticesY();
		float[] sourceZ = source.getVerticesZ();

		int[] sourceI1 = source.getFaceIndices1();
		int[] sourceI2 = source.getFaceIndices2();
		int[] sourceI3 = source.getFaceIndices3();

		int[] sourceC1 = source.getFaceColors1();
		int[] sourceC2 = source.getFaceColors2();
		int[] sourceC3 = source.getFaceColors3();

		// The counts are settled against what actually arrived, before anything is copied, rather
		// than taken from the source's own getters and trusted. A count that overruns its arrays is
		// worse than an empty model: every consumer reads these by count, starting with
		// calculateBoundsCylinder below, and the buffers here are reused between NPCs - so a short
		// column would not read zeroes, it would read the previous NPC's geometry.
		//
		// A client model should never present this way. This class exists to hold geometry the
		// client never made, so it does not get to assume the shape of what it is handed. Nested
		// rather than a varargs helper because this runs per NPC per frame.
		verticesCount = Math.min(source.getVerticesCount(),
			Math.min(length(sourceX), Math.min(length(sourceY), length(sourceZ))));
		faceCount = Math.min(source.getFaceCount(), Math.min(
			Math.min(length(sourceI1), Math.min(length(sourceI2), length(sourceI3))),
			Math.min(length(sourceC1), Math.min(length(sourceC2), length(sourceC3)))));

		verticesX = copy(sourceX, verticesX, verticesCount);
		verticesY = copy(sourceY, verticesY, verticesCount);
		verticesZ = copy(sourceZ, verticesZ, verticesCount);

		faceIndices1 = copy(sourceI1, faceIndices1, faceCount);
		faceIndices2 = copy(sourceI2, faceIndices2, faceCount);
		faceIndices3 = copy(sourceI3, faceIndices3, faceCount);

		faceColors1 = copy(sourceC1, faceColors1, faceCount);
		faceColors2 = copy(sourceC2, faceColors2, faceCount);
		faceColors3 = copy(sourceC3, faceColors3, faceCount);

		// These are legitimately null on most models, and null carries meaning to the renderer -
		// a null transparency array is what puts a model on the opaque path - so do not
		// substitute empty arrays for them
		faceRenderPriorities = copyOrNull(source.getFaceRenderPriorities());
		faceTransparencies = copyOrNull(source.getFaceTransparencies());
		faceBias = copyOrNull(source.getFaceBias());
		faceTextures = copyOrNull(source.getFaceTextures());
		textureFaces = copyOrNull(source.getTextureFaces());
		texIndices1 = copyOrNull(source.getTexIndices1());
		texIndices2 = copyOrNull(source.getTexIndices2());
		texIndices3 = copyOrNull(source.getTexIndices3());

		transparency = source.getTransparency();
		overrideAmount = source.getOverrideAmount();
		overrideHue = source.getOverrideHue();
		overrideSaturation = source.getOverrideSaturation();
		overrideLuminance = source.getOverrideLuminance();

		// modelHeight is deliberately not copied - calculateBoundsCylinder derives it below, the
		// same way the client does
		animationHeightOffset = source.getAnimationHeightOffset();
		renderMode = source.getRenderMode();

		// Bounds are derived rather than copied, so this class is already correct once it holds
		// geometry the client never saw
		calculateBoundsCylinder();

		compareBoundsAgainst(source);
	}

	/**
	 * While the geometry is still a copy of a client model, that model's own bounds are ground
	 * truth for ours - so check them against each other rather than waiting to find out from a
	 * renderer assertion.
	 *
	 * <p>Logged once, because a mismatch is a property of the formula rather than of any one
	 * frame, and this runs per NPC per frame.
	 */
	private void compareBoundsAgainst(Model source)
	{
		// A debug line is the whole output, and this runs per NPC per frame, so it does not run at
		// all unless someone is reading - it also forces a bounds recompute on the client's model
		// below. Tested before the one-shot flag so enabling debug mid-session still gets a check.
		if (!log.isDebugEnabled() || boundsChecked)
		{
			return;
		}
		boundsChecked = true;

		// The client caches its bounds behind a flag, so a model that has been through the renderer
		// before will hand back last frame's numbers - for a shared posed model, possibly another
		// NPC's. Ask it to compute them for the geometry it is holding now, or the comparison is
		// against noise.
		source.calculateBoundsCylinder();

		int sourceRadius = source.getRadius();
		int sourceDiameter = source.getDiameter();
		int sourceBottomY = source.getBottomY();

		if (sourceRadius == radius && sourceDiameter == diameter && sourceBottomY == bottomY)
		{
			log.debug("Injected model bounds match the client's exactly (radius={} diameter={} bottomY={})",
				radius, diameter, bottomY);
			return;
		}

		log.debug("Injected model bounds differ from the client's - "
				+ "radius {} vs {}, diameter {} vs {}, bottomY {} vs {}",
			radius, sourceRadius, diameter, sourceDiameter, bottomY, sourceBottomY);
	}

	/**
	 * Recomputes the bounding cylinder the renderer reads through {@link #getRadius()} and
	 * {@link #getDiameter()} for culling and sorting.
	 *
	 * <p>The client calls this on every model it is about to draw, so it has to be cheap and it has
	 * to be idempotent. A transcription of the client's own bounds routine, kept deliberately
	 * faithful.
	 *
	 * <p>These are not free-form numbers. {@code ModelUploader.uploadSortedModel} buckets each face
	 * by {@code radius + meanDepth} into an array of {@code diameter} slots and asserts the index
	 * lands in {@code [0, diameter)}, so a radius that is too small is an {@code AssertionError}
	 * inside the renderer rather than a cosmetic difference.
	 *
	 * <p>Note the arithmetic is done in floats with a {@link Math#ceil} at each step, and that the
	 * result is asymmetric: {@code radius} uses the extent <em>above</em> the origin and the second
	 * term uses the extent below. That is not an oversight in the original - it leans on the game's
	 * constrained camera pitch, so a model's top is never the far side.
	 */
	@Override
	public void calculateBoundsCylinder()
	{
		// Y is negative upward, so these are extents either side of the origin, both non-negative
		float height = 0f;
		float bottom = 0f;
		float xzRadiusSquared = 0f;

		for (int i = 0; i < verticesCount; i++)
		{
			float x = verticesX[i];
			float y = verticesY[i];
			float z = verticesZ[i];

			if (-y > height)
			{
				height = -y;
			}
			if (y > bottom)
			{
				bottom = y;
			}

			float radiusSquared = x * x + z * z;
			if (radiusSquared > xzRadiusSquared)
			{
				xzRadiusSquared = radiusSquared;
			}
		}

		bottomY = (int) Math.ceil(bottom);
		// The client derives model height here too rather than carrying it separately
		modelHeight = (int) Math.ceil(height);

		int xzRadius = (int) Math.ceil(Math.sqrt(xzRadiusSquared));

		radius = (int) Math.ceil(Math.sqrt(
			(double) xzRadius * xzRadius + (double) modelHeight * modelHeight));
		diameter = radius + (int) Math.ceil(Math.sqrt(
			(double) xzRadius * xzRadius + (double) bottomY * bottomY));
	}

	/** A null column is length zero rather than an error; the counts above are clamped to it. */
	private static int length(float[] values)
	{
		return values == null ? 0 : values.length;
	}

	private static int length(int[] values)
	{
		return values == null ? 0 : values.length;
	}

	private static float[] copy(float[] source, float[] into, int length)
	{
		if (source == null)
		{
			return new float[0];
		}

		float[] target = into.length >= length ? into : new float[length];
		System.arraycopy(source, 0, target, 0, Math.min(length, source.length));
		return target;
	}

	private static int[] copy(int[] source, int[] into, int length)
	{
		if (source == null)
		{
			return new int[0];
		}

		int[] target = into.length >= length ? into : new int[length];
		System.arraycopy(source, 0, target, 0, Math.min(length, source.length));
		return target;
	}

	private static byte[] copyOrNull(byte[] source)
	{
		return source == null ? null : source.clone();
	}

	private static short[] copyOrNull(short[] source)
	{
		return source == null ? null : source.clone();
	}

	private static int[] copyOrNull(int[] source)
	{
		return source == null ? null : source.clone();
	}

	// --- Mesh: the geometry the uploader reads -------------------------------------------------

	@Override
	public int getVerticesCount()
	{
		return verticesCount;
	}

	@Override
	public float[] getVerticesX()
	{
		return verticesX;
	}

	@Override
	public float[] getVerticesY()
	{
		return verticesY;
	}

	@Override
	public float[] getVerticesZ()
	{
		return verticesZ;
	}

	@Override
	public int getFaceCount()
	{
		return faceCount;
	}

	@Override
	public int[] getFaceIndices1()
	{
		return faceIndices1;
	}

	@Override
	public int[] getFaceIndices2()
	{
		return faceIndices2;
	}

	@Override
	public int[] getFaceIndices3()
	{
		return faceIndices3;
	}

	@Override
	public byte[] getFaceTransparencies()
	{
		return faceTransparencies;
	}

	@Override
	public short[] getFaceTextures()
	{
		return faceTextures;
	}

	// --- Model: shading, priorities and bounds -------------------------------------------------

	@Override
	public int[] getFaceColors1()
	{
		return faceColors1;
	}

	@Override
	public int[] getFaceColors2()
	{
		return faceColors2;
	}

	@Override
	public int[] getFaceColors3()
	{
		return faceColors3;
	}

	@Override
	public byte[] getFaceRenderPriorities()
	{
		return faceRenderPriorities;
	}

	@Override
	public byte[] getFaceBias()
	{
		return faceBias;
	}

	@Override
	public byte getTransparency()
	{
		return transparency;
	}

	@Override
	public byte getOverrideAmount()
	{
		return overrideAmount;
	}

	@Override
	public byte getOverrideHue()
	{
		return overrideHue;
	}

	@Override
	public byte getOverrideSaturation()
	{
		return overrideSaturation;
	}

	@Override
	public byte getOverrideLuminance()
	{
		return overrideLuminance;
	}

	@Override
	public byte[] getTextureFaces()
	{
		return textureFaces;
	}

	@Override
	public int[] getTexIndices1()
	{
		return texIndices1;
	}

	@Override
	public int[] getTexIndices2()
	{
		return texIndices2;
	}

	@Override
	public int[] getTexIndices3()
	{
		return texIndices3;
	}

	@Override
	public int getRadius()
	{
		return radius;
	}

	@Override
	public int getDiameter()
	{
		return diameter;
	}

	@Override
	public int getBottomY()
	{
		return bottomY;
	}

	// --- Renderable ----------------------------------------------------------------------------

	@Override
	public Model getModel()
	{
		return this;
	}

	@Override
	public int getModelHeight()
	{
		return modelHeight;
	}

	@Override
	public void setModelHeight(int modelHeight)
	{
		this.modelHeight = modelHeight;
	}

	@Override
	public int getAnimationHeightOffset()
	{
		return animationHeightOffset;
	}

	@Override
	public int getRenderMode()
	{
		return renderMode;
	}

	// --- Renderer-owned scratch state -----------------------------------------------------------
	// The GPU plugin never reads or writes these on this path, and 117 HD's zone renderer only copies
	// them, but they are part of the contract.

	@Override
	public int getSceneId()
	{
		return sceneId;
	}

	@Override
	public void setSceneId(int sceneId)
	{
		this.sceneId = sceneId;
	}

	@Override
	public int getBufferOffset()
	{
		return bufferOffset;
	}

	@Override
	public void setBufferOffset(int bufferOffset)
	{
		this.bufferOffset = bufferOffset;
	}

	@Override
	public int getUvBufferOffset()
	{
		return uvBufferOffset;
	}

	@Override
	public void setUvBufferOffset(int uvBufferOffset)
	{
		this.uvBufferOffset = uvBufferOffset;
	}

	// --- Gated behind render flags ---------------------------------------------------------------
	// Never reached under the GPU plugin, whose setupGpuFlags never sets NORMALS or
	// UNLIT_FACE_COLORS. 117 HD's zone renderer does reach them and null-checks each one, falling
	// back to flat face normals and the lit colours. Returning null rather than throwing keeps a
	// future renderer change from taking the scene down with it.

	@Override
	public short[] getUnlitFaceColors()
	{
		return null;
	}

	@Override
	public int[] getVertexNormalsX()
	{
		return null;
	}

	@Override
	public int[] getVertexNormalsY()
	{
		return null;
	}

	@Override
	public int[] getVertexNormalsZ()
	{
		return null;
	}

	// --- Answered from the bounding cylinder ---------------------------------------------------
	// These are reached. There is no skew and no separate bounding box to keep, so each is served
	// from the cylinder calculateBoundsCylinder already derived.

	@Override
	public Model getUnskewedModel()
	{
		return this;
	}

	@Override
	public int getXYZMag()
	{
		return radius;
	}

	@Override
	public boolean useBoundingBox()
	{
		return false;
	}

	@Override
	public void calculateExtreme(int orientation)
	{
		// Superseded by getAABB, and unused on the GPU path
	}

	@Override
	public AABB getAABB(int orientation)
	{
		// Orientation is ignored: the box is symmetric about the vertical axis because it is
		// derived from the bounding cylinder, so rotating it about Y changes nothing
		return new CylinderAABB(radius, Math.max(bottomY, 1));
	}

	/**
	 * The bounding cylinder expressed as a box, for the one accessor that asks for it.
	 */
	private static final class CylinderAABB implements AABB
	{
		private final int radius;
		private final int height;

		private CylinderAABB(int radius, int height)
		{
			this.radius = radius;
			this.height = height;
		}

		@Override
		public int getCenterX()
		{
			return 0;
		}

		@Override
		public int getCenterY()
		{
			return 0;
		}

		@Override
		public int getCenterZ()
		{
			return 0;
		}

		@Override
		public int getExtremeX()
		{
			return radius;
		}

		@Override
		public int getExtremeY()
		{
			return height;
		}

		@Override
		public int getExtremeZ()
		{
			return radius;
		}
	}

	@Override
	public void drawFrustum(int zero, int xRotate, int yRotate, int zRotate, int xCamera, int yCamera, int zCamera)
	{
		// Software rasterizer entry point; the GPU plugin uploads geometry instead of calling this
	}

	@Override
	public void drawOrtho(int zero, int xRotate, int yRotate, int zRotate, int xCamera, int yCamera, int zCamera,
		int zoom)
	{
		// As drawFrustum
	}

	// --- Mesh transforms ------------------------------------------------------------------------
	// The client applies orientation and position itself when it draws, so nothing calls these on a
	// substituted model. Baking a transform into our buffers would corrupt the next frame's copy.

	@Override
	public Model rotateY90Ccw()
	{
		return this;
	}

	@Override
	public Model rotateY180Ccw()
	{
		return this;
	}

	@Override
	public Model rotateY270Ccw()
	{
		return this;
	}

	@Override
	public Model translate(int x, int y, int z)
	{
		return this;
	}

	@Override
	public Model scale(int x, int y, int z)
	{
		return this;
	}

	// --- Node -----------------------------------------------------------------------------------
	// Inherited because Renderable extends Node. Only meaningful for models living in the client's
	// own linked caches, which this one never does.

	@Override
	public Node getNext()
	{
		return null;
	}

	@Override
	public Node getPrevious()
	{
		return null;
	}

	@Override
	public long getHash()
	{
		return 0;
	}
}
