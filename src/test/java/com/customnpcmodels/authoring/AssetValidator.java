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
package com.customnpcmodels.authoring;

import com.customnpcmodels.inject.AssetBundle;
import com.customnpcmodels.inject.AssetCodec;
import com.customnpcmodels.inject.Clip;
import com.customnpcmodels.inject.Mesh;
import com.customnpcmodels.inject.MeshMerger;
import com.customnpcmodels.inject.NpcBinding;
import com.customnpcmodels.inject.Rig;
import com.customnpcmodels.inject.SwapBlacklist;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.IntUnaryOperator;

/**
 * Refuses assets the engine would draw wrongly, or throw on, rather than reject.
 * <p>
 * Everything here fails far from its cause at runtime - an index past the end is an
 * {@code ArrayIndexOutOfBoundsException} inside the skinner on the render path, which the draw
 * callback swallows and draws the vanilla model instead, and several of the rest are not errors at
 * all but silently wrong pictures. So the generator runs every conversion output through here and
 * refuses to write a bundle that fails, naming every problem at once.
 * <p>
 * Stricter than {@link com.customnpcmodels.inject.AssetCodec}'s load-time checks on purpose: the
 * codec guards the shipped plugin against a corrupt file, this guards the author against an asset
 * that is well-formed but wrong.
 */
public final class AssetValidator
{
	/** The most vertices and faces a model the renderer uploads can carry; the loader refuses more too. */
	static final int MAX_VERTICES = AssetCodec.MAX_VERTICES;
	static final int MAX_FACES = AssetCodec.MAX_FACES;

	/** The face-sorting buckets are sized by diameter; past this the renderer's arrays overflow. */
	static final int MAX_DIAMETER = 6000;

	/** Render types the lighter draws: gouraud, flat, unshaded. Anything else hides the face. */
	private static final Set<Integer> DRAWN_RENDER_TYPES = AssetCodec.DRAWN_RENDER_TYPES;

	/** Rig transform types the engine knows: pivot, translate, rotate, scale, alpha. */
	private static final Set<Integer> RIG_TYPES = Set.of(0, 1, 2, 3, 5);

	private AssetValidator()
	{
	}

	/**
	 * Throws naming every problem in the bundle, or returns quietly when there are none.
	 *
	 * @param liveFrameCount the frame count of the live sequence a clip is keyed to, or -1 when it
	 *     is unknown - a clip whose count differs is indexed past its end, or never reaches its tail
	 */
	public static void requireValid(AssetBundle bundle, IntUnaryOperator liveFrameCount)
	{
		List<String> problems = validate(bundle, liveFrameCount);
		if (!problems.isEmpty())
		{
			throw new IllegalStateException("The bundle has " + problems.size() + " problem(s):\n  "
				+ String.join("\n  ", problems));
		}
	}

	public static List<String> validate(AssetBundle bundle, IntUnaryOperator liveFrameCount)
	{
		List<String> problems = new ArrayList<>();

		for (Mesh mesh : bundle.getMeshes().values())
		{
			problems.addAll(validateMesh(mesh));
		}

		for (Rig rig : bundle.getRigs().values())
		{
			problems.addAll(validateRig(rig));
		}

		for (Clip clip : bundle.getClips())
		{
			problems.addAll(validateClip(clip, bundle.getRigs(), liveFrameCount.applyAsInt(clip.getSequenceId())));
		}

		Set<Integer> boundNpcs = new HashSet<>();
		for (NpcBinding binding : bundle.getBindings())
		{
			problems.addAll(validateBinding(binding, bundle, boundNpcs));
		}

		return problems;
	}

	public static List<String> validateMesh(Mesh mesh)
	{
		List<String> problems = new ArrayList<>();
		String name = "mesh " + mesh.getId();

		float[] vx = mesh.getVerticesX();
		float[] vy = mesh.getVerticesY();
		float[] vz = mesh.getVerticesZ();
		if (vx == null || vy == null || vz == null)
		{
			problems.add(name + " is missing a vertex axis");
			return problems;
		}

		// Only verticesX and faceIndices1 define the counts; everything else is indexed by them
		int vertices = vx.length;
		if (vy.length != vertices || vz.length != vertices)
		{
			problems.add(name + " has " + vertices + ", " + vy.length + " and " + vz.length
				+ " vertices on its three axes");
			return problems;
		}

		int[] i1 = mesh.getFaceIndices1();
		int[] i2 = mesh.getFaceIndices2();
		int[] i3 = mesh.getFaceIndices3();
		if (i1 == null || i2 == null || i3 == null)
		{
			problems.add(name + " is missing a face index column");
			return problems;
		}

		int faces = i1.length;
		if (i2.length != faces || i3.length != faces)
		{
			problems.add(name + " names " + faces + ", " + i2.length + " and " + i3.length + " face corners");
			return problems;
		}

		for (int face = 0; face < faces; face++)
		{
			if (!inRange(i1[face], vertices) || !inRange(i2[face], vertices) || !inRange(i3[face], vertices))
			{
				problems.add(name + " face " + face + " names a vertex outside 0.." + (vertices - 1));
				break;
			}
		}

		if (mesh.getFaceColors() == null)
		{
			// The one per-face column with no null path: the lighter reads it for every face
			problems.add(name + " has no face colors");
		}
		else
		{
			checkFaceColumn(problems, name, "face colors", mesh.getFaceColors().length, faces);
		}

		// Optional columns: null means absent, and an empty array with faces present is an index
		// out of bounds on the first face rather than an absence
		checkOptionalColumn(problems, name, "render types",
			mesh.getFaceRenderTypes() == null ? -1 : mesh.getFaceRenderTypes().length, faces);
		checkOptionalColumn(problems, name, "transparencies",
			mesh.getFaceTransparencies() == null ? -1 : mesh.getFaceTransparencies().length, faces);
		checkOptionalColumn(problems, name, "render priorities",
			mesh.getFaceRenderPriorities() == null ? -1 : mesh.getFaceRenderPriorities().length, faces);
		checkOptionalColumn(problems, name, "face textures",
			mesh.getFaceTextures() == null ? -1 : mesh.getFaceTextures().length, faces);
		checkOptionalColumn(problems, name, "texture coordinates",
			mesh.getTextureCoords() == null ? -1 : mesh.getTextureCoords().length, faces);

		byte[] renderTypes = mesh.getFaceRenderTypes();
		if (renderTypes != null && renderTypes.length == faces)
		{
			int hidden = 0;
			int firstHidden = -1;
			for (int face = 0; face < faces; face++)
			{
				if (!DRAWN_RENDER_TYPES.contains((int) renderTypes[face]))
				{
					if (firstHidden == -1)
					{
						firstHidden = face;
					}
					hidden++;
				}
			}
			if (hidden > 0)
			{
				problems.add(name + " has " + hidden + " face(s) with a render type outside {0, 1, 3}, "
					+ "which the lighter hides - first is face " + firstHidden
					+ " (type " + renderTypes[firstHidden] + ")");
			}
		}

		checkVertexGroups(problems, name, mesh.getVertexGroups(), vertices);

		if (vertices > MAX_VERTICES)
		{
			problems.add(name + " has " + vertices + " vertices, past the " + MAX_VERTICES + " ceiling");
		}
		if (faces > MAX_FACES)
		{
			problems.add(name + " has " + faces + " faces, past the " + MAX_FACES + " ceiling");
		}

		int diameter = diameter(vx, vy, vz);
		if (diameter >= MAX_DIAMETER)
		{
			problems.add(name + " has a diameter of " + diameter + ", past the " + MAX_DIAMETER + " ceiling");
		}

		if (faces > 0 && problems.isEmpty())
		{
			int flat = deadNormalFaces(vx, vy, vz, i1, i2, i3);
			if (flat * 2 > faces)
			{
				// The lighter truncates each edge to an int before the cross product, so geometry
				// authored at sub-unit scale - metres read as RS units, typically - lights every face
				// as if it had no normal, with no error anywhere
				problems.add(name + " has " + flat + " of " + faces + " faces whose normal truncates "
					+ "to zero - the geometry is too small for the lighter (RS units are 128 per tile)");
			}
		}

		return problems;
	}

	/**
	 * Each vertex belongs to exactly one group, so a transform moves it once. A vertex in two groups
	 * is transformed twice, and a vertex in none never moves at all while its neighbours do.
	 */
	private static void checkVertexGroups(List<String> problems, String name, int[][] groups, int vertices)
	{
		if (groups == null)
		{
			return;
		}

		if (groups.length == 0)
		{
			problems.add(name + " has an empty vertex group table - an unrigged mesh carries null");
			return;
		}

		int[] owner = new int[vertices];
		java.util.Arrays.fill(owner, -1);
		for (int group = 0; group < groups.length; group++)
		{
			if (groups[group] == null)
			{
				continue;
			}
			for (int member : groups[group])
			{
				if (!inRange(member, vertices))
				{
					problems.add(name + " vertex group " + group + " names vertex " + member
						+ " outside 0.." + (vertices - 1));
					return;
				}
				if (owner[member] != -1)
				{
					problems.add(name + " vertex " + member + " is in both group " + owner[member]
						+ " and group " + group + ", so it would be transformed twice");
					return;
				}
				owner[member] = group;
			}
		}

		int unbound = 0;
		for (int vertex = 0; vertex < vertices; vertex++)
		{
			if (owner[vertex] == -1)
			{
				unbound++;
			}
		}
		if (unbound > 0)
		{
			problems.add(name + " has " + unbound + " vertices in no group, which would hold still "
				+ "while the faces around them move");
		}
	}

	public static List<String> validateRig(Rig rig)
	{
		List<String> problems = new ArrayList<>();
		String name = "rig " + rig.getId();

		for (int transform = 0; transform < rig.getTransformCount(); transform++)
		{
			int type = rig.getType(transform);
			if (!RIG_TYPES.contains(type))
			{
				problems.add(name + " transform " + transform + " has unknown type " + type);
			}

			int[] members;
			try
			{
				members = rig.getGroups(transform);
			}
			catch (ArrayIndexOutOfBoundsException ex)
			{
				problems.add(name + " has fewer group sets than its " + rig.getTransformCount() + " transforms");
				return problems;
			}
			if (members == null)
			{
				problems.add(name + " transform " + transform + " has no group set");
			}
		}

		return problems;
	}

	/**
	 * @param liveFrameCount the frame count of the live sequence this clip is keyed to, or -1 to
	 *     skip that check
	 */
	public static List<String> validateClip(Clip clip, Map<Integer, Rig> rigs, int liveFrameCount)
	{
		List<String> problems = new ArrayList<>();
		String name = "clip " + clip.getSequenceId();

		Rig rig = rigs.get(clip.getRigId());
		if (rig == null)
		{
			problems.add(name + " names rig " + clip.getRigId() + ", which the bundle does not carry");
		}

		if (liveFrameCount >= 0 && clip.getFrameCount() != liveFrameCount)
		{
			problems.add(name + " has " + clip.getFrameCount() + " frames, but live sequence "
				+ clip.getSequenceId() + " has " + liveFrameCount + " - the client indexes the clip by the "
				+ "live frame, so the two must agree");
		}

		for (int frame = 0; frame < clip.getFrameCount(); frame++)
		{
			int ops;
			try
			{
				ops = clip.getOpCount(frame);
				for (int op = 0; op < ops; op++)
				{
					int transform = clip.getTransform(frame, op);
					clip.getDx(frame, op);
					clip.getDy(frame, op);
					clip.getDz(frame, op);
					if (rig != null && !inRange(transform, rig.getTransformCount()))
					{
						problems.add(name + " frame " + frame + " op " + op + " names transform "
							+ transform + " of a " + rig.getTransformCount() + "-transform rig");
						return problems;
					}
				}
			}
			catch (NullPointerException | ArrayIndexOutOfBoundsException ex)
			{
				problems.add(name + " frame " + frame + " has missing or unequal op rows");
				return problems;
			}
		}

		return problems;
	}

	private static List<String> validateBinding(NpcBinding binding, AssetBundle bundle, Set<Integer> boundNpcs)
	{
		List<String> problems = new ArrayList<>();
		String name = "binding '" + binding.getName() + "'";

		if (binding.getNpcIds() == null || binding.getNpcIds().length == 0)
		{
			problems.add(name + " names no NPCs");
		}
		else
		{
			for (int npcId : binding.getNpcIds())
			{
				if (!boundNpcs.add(npcId))
				{
					problems.add(name + " binds NPC " + npcId + ", which another binding already claims");
				}
				if (SwapBlacklist.isBlocked(npcId))
				{
					problems.add(name + " binds NPC " + npcId + ", which is in " + SwapBlacklist.contentOf(npcId)
						+ " and can never be swapped");
				}
			}
		}

		List<Mesh> parts = new ArrayList<>();
		if (binding.getMeshIds() == null || binding.getMeshIds().length == 0)
		{
			problems.add(name + " names no meshes");
		}
		else
		{
			for (int meshId : binding.getMeshIds())
			{
				Mesh mesh = bundle.getMesh(meshId);
				if (mesh == null)
				{
					problems.add(name + " names mesh " + meshId + ", which the bundle does not carry");
				}
				else
				{
					parts.add(mesh);
				}
			}
		}

		if (binding.getRigId() != NpcBinding.STATIC && bundle.getRig(binding.getRigId()) == null)
		{
			problems.add(name + " names rig " + binding.getRigId() + ", which the bundle does not carry");
		}

		short[] find = binding.getRecolorFind();
		short[] replace = binding.getRecolorReplace();
		if ((find == null) != (replace == null) || (find != null && find.length != replace.length))
		{
			problems.add(name + " has unpaired recolors");
		}

		if (binding.getScaleXZ() <= 0 || binding.getScaleY() <= 0)
		{
			problems.add(name + " has scale " + binding.getScaleXZ() + "/" + binding.getScaleY());
		}

		// Stored as signed bytes, as the cache stores them; anything wider would wrap in the codec
		if (binding.getAmbient() < Byte.MIN_VALUE || binding.getAmbient() > Byte.MAX_VALUE
			|| binding.getContrast() < Byte.MIN_VALUE || binding.getContrast() > Byte.MAX_VALUE)
		{
			problems.add(name + " has ambient " + binding.getAmbient() + " and contrast " + binding.getContrast()
				+ "; both must fit a signed byte");
		}

		// The ceilings apply to what is drawn, which is the merge
		if (problems.isEmpty() && parts.size() > 1)
		{
			Mesh merged = MeshMerger.merge(binding.getMeshIds()[0], parts);
			if (merged.getVerticesCount() > MAX_VERTICES || merged.getFaceCount() > MAX_FACES)
			{
				problems.add(name + " merges to " + merged.getVerticesCount() + " vertices and "
					+ merged.getFaceCount() + " faces, past the " + MAX_VERTICES + "/" + MAX_FACES + " ceiling");
			}
		}

		return problems;
	}

	/**
	 * The diameter {@code InjectedModel.calculateBoundsCylinder} would compute at rest, transcribed
	 * rather than called because that needs a bound, posed model.
	 */
	static int diameter(float[] vx, float[] vy, float[] vz)
	{
		float height = 0f;
		float bottom = 0f;
		float xzRadiusSquared = 0f;
		for (int i = 0; i < vx.length; i++)
		{
			height = Math.max(height, -vy[i]);
			bottom = Math.max(bottom, vy[i]);
			xzRadiusSquared = Math.max(xzRadiusSquared, vx[i] * vx[i] + vz[i] * vz[i]);
		}

		int bottomY = (int) Math.ceil(bottom);
		int modelHeight = (int) Math.ceil(height);
		int xzRadius = (int) Math.ceil(Math.sqrt(xzRadiusSquared));
		int radius = (int) Math.ceil(Math.sqrt((double) xzRadius * xzRadius + (double) modelHeight * modelHeight));
		return radius + (int) Math.ceil(Math.sqrt((double) xzRadius * xzRadius + (double) bottomY * bottomY));
	}

	/** Faces whose normal comes out zero once the lighter has truncated their edges to ints. */
	private static int deadNormalFaces(float[] vx, float[] vy, float[] vz, int[] i1, int[] i2, int[] i3)
	{
		int dead = 0;
		for (int face = 0; face < i1.length; face++)
		{
			int a = i1[face];
			int b = i2[face];
			int c = i3[face];

			long abX = (int) (vx[b] - vx[a]);
			long abY = (int) (vy[b] - vy[a]);
			long abZ = (int) (vz[b] - vz[a]);
			long acX = (int) (vx[c] - vx[a]);
			long acY = (int) (vy[c] - vy[a]);
			long acZ = (int) (vz[c] - vz[a]);

			long nx = abY * acZ - acY * abZ;
			long ny = abZ * acX - acZ * abX;
			long nz = abX * acY - acX * abY;
			if (nx == 0 && ny == 0 && nz == 0)
			{
				dead++;
			}
		}
		return dead;
	}

	private static void checkFaceColumn(List<String> problems, String name, String column, int length, int faces)
	{
		if (length != faces)
		{
			problems.add(name + " has " + length + " " + column + " for " + faces + " faces");
		}
	}

	private static void checkOptionalColumn(List<String> problems, String name, String column, int length, int faces)
	{
		if (length < 0)
		{
			return;
		}
		if (length == 0 && faces > 0)
		{
			problems.add(name + " has an empty " + column + " array - an absent column is null, not empty");
			return;
		}
		checkFaceColumn(problems, name, column, length, faces);
	}

	private static boolean inRange(int index, int count)
	{
		return index >= 0 && index < count;
	}
}
