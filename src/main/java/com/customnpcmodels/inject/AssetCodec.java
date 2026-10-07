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

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Reads and writes {@link AssetBundle} as a compact binary blob.
 * <p>
 * Hand-rolled rather than serialized: Java serialization is off the table for a plugin, and a
 * text format would be several times the size for data that is almost entirely numeric arrays. The
 * payload is gzipped, which matters because vertex and index arrays compress well.
 * <p>
 * Every structure is length-prefixed and the whole thing starts with a magic number and a
 * version, so a bundle produced by an older generator is rejected outright instead of being
 * misread into plausible-looking geometry.
 */
public final class AssetCodec
{
	/** "CNPC" - guards against being handed an unrelated file. */
	private static final int MAGIC = 0x434E5043;

	/**
	 * Bump on any layout change; readers refuse anything they were not written for. Also the format a
	 * downloaded pack must declare, so one built for another plugin version is refused before it is
	 * fetched.
	 */
	public static final int VERSION = 3;

	/** The most vertices and faces a model the renderer uploads can carry. */
	public static final int MAX_VERTICES = 6500;
	public static final int MAX_FACES = 8192;

	/** The furthest a vertex may sit from the model's origin on any axis, as in the engine's models. */
	public static final int MAX_COORDINATE = 32767;

	/** Render types the lighter draws: gouraud, flat, unshaded. Anything else hides the face. */
	public static final Set<Integer> DRAWN_RENDER_TYPES = Set.of(
		Lighter.RENDER_TYPE_GOURAUD, Lighter.RENDER_TYPE_FLAT, Lighter.RENDER_TYPE_UNSHADED);

	/**
	 * The largest bundle file a pack may carry, checked before it is opened. Packs come from disk and
	 * from the hub, so their size is not ours to trust.
	 */
	public static final long MAX_FILE_BYTES = 16L * 1024 * 1024;

	/** The most a bundle may inflate to. Gzip hides the size, so this is counted as it is read. */
	static final long MAX_INFLATED_BYTES = 64L * 1024 * 1024;

	/** Sanity ceilings, so a corrupt length cannot make the reader allocate wildly. */
	private static final int MAX_ENTRIES = 100_000;
	private static final int MAX_ARRAY = 10_000_000;

	/** The most meshes one binding may merge. The client's own NPCs use a handful. */
	static final int MAX_PARTS = 64;

	private AssetCodec()
	{
	}

	public static void write(AssetBundle bundle, OutputStream out) throws IOException
	{
		try (DataOutputStream data = new DataOutputStream(new GZIPOutputStream(out)))
		{
			data.writeInt(MAGIC);
			data.writeInt(VERSION);

			data.writeInt(bundle.getMeshes().size());
			for (Mesh mesh : bundle.getMeshes().values())
			{
				writeMesh(data, mesh);
			}

			data.writeInt(bundle.getRigs().size());
			for (Rig rig : bundle.getRigs().values())
			{
				writeRig(data, rig);
			}

			data.writeInt(bundle.getClips().size());
			for (Clip clip : bundle.getClips())
			{
				writeClip(data, clip);
			}

			data.writeInt(bundle.getBindings().size());
			for (NpcBinding binding : bundle.getBindings())
			{
				writeBinding(data, binding);
			}
		}
	}

	public static AssetBundle read(InputStream in) throws IOException
	{
		return read(in, MAX_INFLATED_BYTES);
	}

	/** {@link #read(InputStream)}, refusing to inflate past {@code maxInflatedBytes}. */
	static AssetBundle read(InputStream in, long maxInflatedBytes) throws IOException
	{
		// Inflated up front, so every length can be checked against the bytes that are actually left
		// before anything is allocated for it
		byte[] inflated;
		try (InputStream limited = new Limited(new GZIPInputStream(in), maxInflatedBytes))
		{
			inflated = limited.readAllBytes();
		}

		try (DataInputStream data = new DataInputStream(new ByteArrayInputStream(inflated)))
		{
			int magic = data.readInt();
			if (magic != MAGIC)
			{
				throw new IOException("Not a custom NPC model bundle (magic " + Integer.toHexString(magic) + ")");
			}

			int version = data.readInt();
			if (version != VERSION)
			{
				throw new IOException("Custom NPC model bundle is version " + version
					+ ", this build reads version " + VERSION + " - regenerate it");
			}

			Map<Integer, Mesh> meshes = new LinkedHashMap<>();
			int meshCount = readCount(data, MAX_ENTRIES);
			for (int i = 0; i < meshCount; i++)
			{
				Mesh mesh = readMesh(data);
				meshes.put(mesh.getId(), mesh);
			}

			Map<Integer, Rig> rigs = new LinkedHashMap<>();
			int rigCount = readCount(data, MAX_ENTRIES);
			for (int i = 0; i < rigCount; i++)
			{
				Rig rig = readRig(data);
				rigs.put(rig.getId(), rig);
			}

			List<Clip> clips = new ArrayList<>();
			Set<Long> clipKeys = new HashSet<>();
			int clipCount = readCount(data, MAX_ENTRIES);
			for (int i = 0; i < clipCount; i++)
			{
				Clip clip = readClip(data);
				if (!clipKeys.add(AssetBundle.clipKey(clip.getRigId(), clip.getSequenceId())))
				{
					// One would silently shadow the other
					throw malformed("Asset clip " + clip.getSequenceId() + " appears twice on rig "
						+ clip.getRigId());
				}
				clips.add(clip);
			}

			List<NpcBinding> bindings = new ArrayList<>();
			Set<Integer> boundNpcs = new HashSet<>();
			Set<Integer> firstMeshes = new HashSet<>();
			int bindingCount = readCount(data, MAX_ENTRIES);
			for (int i = 0; i < bindingCount; i++)
			{
				NpcBinding binding = readBinding(data);
				checkBinding(binding, meshes, rigs, boundNpcs, firstMeshes);
				bindings.add(binding);
			}

			return new AssetBundle(meshes, rigs, clips, bindings);
		}
	}

	private static void writeMesh(DataOutputStream data, Mesh mesh) throws IOException
	{
		data.writeInt(mesh.getId());
		data.writeByte(mesh.getPriority());

		writeFloats(data, mesh.getVerticesX());
		writeFloats(data, mesh.getVerticesY());
		writeFloats(data, mesh.getVerticesZ());

		writeInts(data, mesh.getFaceIndices1());
		writeInts(data, mesh.getFaceIndices2());
		writeInts(data, mesh.getFaceIndices3());

		writeShorts(data, mesh.getFaceColors());
		writeBytes(data, mesh.getFaceRenderTypes());
		writeBytes(data, mesh.getFaceTransparencies());
		writeBytes(data, mesh.getFaceRenderPriorities());
		writeShorts(data, mesh.getFaceTextures());

		writeBytes(data, mesh.getTextureCoords());
		writeInts(data, mesh.getTexIndices1());
		writeInts(data, mesh.getTexIndices2());
		writeInts(data, mesh.getTexIndices3());

		writeIntMatrix(data, mesh.getVertexGroups());
	}

	private static Mesh readMesh(DataInputStream data) throws IOException
	{
		int id = data.readInt();
		int priority = data.readByte();

		float[] vx = readFloats(data);
		float[] vy = readFloats(data);
		float[] vz = readFloats(data);

		int[] i1 = readInts(data);
		int[] i2 = readInts(data);
		int[] i3 = readInts(data);

		short[] colors = readShorts(data);
		byte[] renderTypes = readBytes(data);
		byte[] transparencies = readBytes(data);
		byte[] priorities = readBytes(data);
		short[] textures = readShorts(data);

		byte[] textureCoords = readBytes(data);
		int[] texIndices1 = readInts(data);
		int[] texIndices2 = readInts(data);
		int[] texIndices3 = readInts(data);

		int[][] vertexGroups = readIntMatrix(data);

		checkGeometry(id, vx, vy, vz, i1, i2, i3,
			colors, renderTypes, transparencies, priorities, textures, vertexGroups);
		checkTextureTriangles(id, vx.length, i1.length, textureCoords,
			texIndices1, texIndices2, texIndices3);

		return new Mesh(id, priority, vx, vy, vz, i1, i2, i3,
			colors, renderTypes, transparencies, priorities, textures,
			textureCoords, texIndices1, texIndices2, texIndices3, vertexGroups);
	}

	/**
	 * Refuses a mesh whose geometry blocks disagree with each other.
	 * <p>
	 * Every column of a mesh is written as its own length-prefixed block, so nothing in the
	 * format pairs them and nothing downstream re-checks them either: {@link Mesh} takes its
	 * vertex count from {@code verticesX} alone and its face count from {@code faceIndices1} alone,
	 * and every consumer indexes the rest by those. A short column reads cleanly here and throws
	 * somewhere far away instead.
	 * <p>
	 * Worth being strict about because of where those throws land. A face index past the end of
	 * the vertex arrays throws in {@code Lighter.computeNormals} by way of
	 * {@code ModelCache.ensureBuilt}, which only leaves the NPC vanilla with a debug line. A
	 * vertex group member past the end reaches {@code Skinner} on the render path, where
	 * {@code CustomDrawCallbacks} catches it and quietly draws the vanilla model. Neither failure
	 * names the bundle that caused it. Same contract as the magic and the version: a bundle that is
	 * wrong must not load.
	 */
	private static void checkGeometry(int id, float[] vx, float[] vy, float[] vz,
		int[] i1, int[] i2, int[] i3, short[] colors, byte[] renderTypes, byte[] transparencies,
		byte[] priorities, short[] textures, int[][] vertexGroups) throws IOException
	{
		if (vx == null || vy == null || vz == null)
		{
			throw malformed("Asset mesh " + id
				+ " is missing a vertex axis");
		}

		if (vx.length != vy.length || vx.length != vz.length)
		{
			throw malformed("Asset mesh " + id + " has " + vx.length + ", "
				+ vy.length + " and " + vz.length
				+ " vertices on its three axes");
		}

		if (i1 == null || i2 == null || i3 == null)
		{
			throw malformed("Asset mesh " + id
				+ " is missing a face index column");
		}

		if (i1.length != i2.length || i1.length != i3.length)
		{
			throw malformed("Asset mesh " + id + " names " + i1.length + ", "
				+ i2.length + " and " + i3.length + " face corners");
		}

		int verticesCount = vx.length;
		int faceCount = i1.length;

		// What the renderer can upload. Checked before the per-face loops, which would otherwise walk
		// whatever a hostile file claims
		if (verticesCount > MAX_VERTICES || faceCount > MAX_FACES)
		{
			throw new IOException("Asset mesh " + id + " has " + verticesCount + " vertices and " + faceCount
				+ " faces, past the " + MAX_VERTICES + "/" + MAX_FACES + " ceiling");
		}

		// The engine's own models store 16-bit coordinates. Far past that, the bounds the renderer
		// sorts by stop being representable
		for (int vertex = 0; vertex < verticesCount; vertex++)
		{
			if (Math.abs(vx[vertex]) > MAX_COORDINATE || Math.abs(vy[vertex]) > MAX_COORDINATE
				|| Math.abs(vz[vertex]) > MAX_COORDINATE)
			{
				throw malformed("Asset mesh " + id + " vertex " + vertex + " is at (" + vx[vertex] + ", "
					+ vy[vertex] + ", " + vz[vertex] + "), past the " + MAX_COORDINATE + " a coordinate may reach");
			}
		}

		for (int face = 0; face < faceCount; face++)
		{
			checkVertex(id, "face " + face, i1[face], verticesCount);
			checkVertex(id, "face " + face, i2[face], verticesCount);
			checkVertex(id, "face " + face, i3[face], verticesCount);
		}

		// Face colors are the one per-face column with no null case: Lighter reads them for
		// every untextured face, and the recolor in ModelCache clones them outright
		if (colors == null)
		{
			throw malformed("Asset mesh " + id
				+ " has no face colors");
		}

		checkFaceColumn(id, "face colors", colors, faceCount);
		checkFaceColumn(id, "render types", renderTypes, faceCount);
		checkFaceColumn(id, "transparencies", transparencies, faceCount);
		checkFaceColumn(id, "render priorities", priorities, faceCount);
		checkFaceColumn(id, "face textures", textures, faceCount);

		if (renderTypes != null)
		{
			for (int face = 0; face < faceCount; face++)
			{
				if (!DRAWN_RENDER_TYPES.contains((int) renderTypes[face]))
				{
					throw malformed("Asset mesh " + id + " face " + face + " has render type "
						+ renderTypes[face] + ", which is never drawn");
				}
			}
		}

		if (vertexGroups == null)
		{
			return;
		}

		// A null row is a group nothing is bound to, which Mesh.getVertexGroup handles. A
		// member naming a vertex this mesh does not have is a different thing entirely
		for (int group = 0; group < vertexGroups.length; group++)
		{
			int[] members = vertexGroups[group];
			if (members == null)
			{
				continue;
			}
			for (int member : members)
			{
				checkVertex(id, "vertex group " + group, member, verticesCount);
			}
		}
	}

	private static void checkVertex(int id, String owner, int vertex, int verticesCount)
		throws IOException
	{
		if (vertex < 0 || vertex >= verticesCount)
		{
			throw malformed("Asset mesh " + id + " " + owner + " names vertex "
				+ vertex + " of " + verticesCount);
		}
	}

	// A per-face column is either absent entirely - null is meaningful, a null transparency array is
	// what puts a model on the opaque path - or exactly as long as the face count. Nothing in
	// between: every consumer indexes these by face without checking. textureCoords is the one
	// per-face column not checked here; it is checked in checkTextureTriangles, alongside the
	// triangle table its entries index into.

	private static void checkFaceColumn(int id, String column, byte[] values, int faceCount)
		throws IOException
	{
		if (values != null)
		{
			checkFaceColumn(id, column, values.length, faceCount);
		}
	}

	private static void checkFaceColumn(int id, String column, short[] values, int faceCount)
		throws IOException
	{
		if (values != null)
		{
			checkFaceColumn(id, column, values.length, faceCount);
		}
	}

	private static void checkFaceColumn(int id, String column, int length, int faceCount)
		throws IOException
	{
		if (length != faceCount)
		{
			throw malformed("Asset mesh " + id + " has " + length + " " + column
				+ " for " + faceCount + " faces");
		}
	}

	/**
	 * Refuses a mesh whose texture mapping cannot be drawn.
	 * <p>
	 * The per-face triangle index and the triangles themselves are separate blocks, so nothing
	 * else pairs them: an index past the end of the triangle table reads cleanly here and throws
	 * inside {@code ModelUploader.computeUv} on the first frame that draws the face. Same contract
	 * as the magic and the version - a bundle that is wrong must not load.
	 */
	private static void checkTextureTriangles(int id, int verticesCount, int faceCount,
		byte[] textureCoords, int[] texIndices1, int[] texIndices2, int[] texIndices3)
		throws IOException
	{
		boolean anyNull = texIndices1 == null || texIndices2 == null || texIndices3 == null;
		boolean allNull = texIndices1 == null && texIndices2 == null && texIndices3 == null;
		if (anyNull && !allNull)
		{
			throw malformed("Asset mesh " + id
				+ " has a partial texture triangle table");
		}

		if (!allNull && (texIndices1.length != texIndices2.length
			|| texIndices1.length != texIndices3.length))
		{
			throw malformed("Asset mesh " + id + " names "
				+ texIndices1.length + ", " + texIndices2.length + " and " + texIndices3.length
				+ " texture triangle corners");
		}

		if (!allNull)
		{
			// A corner is a vertex index, and the merge shifts it without re-checking. One past the
			// end reads out of the vertex arrays inside computeUv, a frame after the bundle loaded
			for (int triangle = 0; triangle < texIndices1.length; triangle++)
			{
				checkCorner(id, triangle, texIndices1[triangle], verticesCount);
				checkCorner(id, triangle, texIndices2[triangle], verticesCount);
				checkCorner(id, triangle, texIndices3[triangle], verticesCount);
			}
		}

		if (textureCoords == null)
		{
			return;
		}

		if (textureCoords.length != faceCount)
		{
			throw malformed("Asset mesh " + id + " maps " + textureCoords.length
				+ " faces to texture triangles but has " + faceCount
				+ " faces");
		}

		int triangles = allNull ? 0 : texIndices1.length;
		for (byte coord : textureCoords)
		{
			// -1 is the renderer's own face-as-UV projection and names no triangle
			if (coord != -1 && (coord & 0xFF) >= triangles)
			{
				throw malformed("Asset mesh " + id + " maps a face to texture triangle "
					+ (coord & 0xFF) + " of " + triangles);
			}
		}
	}

	private static void checkCorner(int id, int triangle, int vertex, int verticesCount)
		throws IOException
	{
		if (vertex < 0 || vertex >= verticesCount)
		{
			throw malformed("Asset mesh " + id + " texture triangle " + triangle
				+ " names vertex " + vertex + " of " + verticesCount);
		}
	}

	private static void writeRig(DataOutputStream data, Rig rig) throws IOException
	{
		data.writeInt(rig.getId());
		writeInts(data, rig.getTypes());
		writeIntMatrix(data, rig.getAllGroups());
	}

	private static Rig readRig(DataInputStream data) throws IOException
	{
		int id = data.readInt();
		int[] types = readInts(data);
		int[][] groups = readIntMatrix(data);

		// The two are one table read as two blocks, and nothing downstream re-checks them: the
		// skinner bounds its loop on the type count and then indexes the groups with it, so a short
		// groups block reads cleanly here and throws inside the render path instead. Refusing it is
		// the same contract the magic and version guard - a bundle that is wrong must not load.
		if (types == null || groups == null || types.length != groups.length)
		{
			throw malformed("Asset rig " + id + " names "
				+ (types == null ? "no" : String.valueOf(types.length)) + " transforms but "
				+ (groups == null ? "no" : String.valueOf(groups.length))
				+ " group sets");
		}

		return new Rig(id, types, groups);
	}

	private static void writeClip(DataOutputStream data, Clip clip) throws IOException
	{
		data.writeInt(clip.getSequenceId());
		data.writeInt(clip.getRigId());
		writeIntMatrix(data, clip.getTransforms());
		writeIntMatrix(data, clip.getAllDx());
		writeIntMatrix(data, clip.getAllDy());
		writeIntMatrix(data, clip.getAllDz());
	}

	private static Clip readClip(DataInputStream data) throws IOException
	{
		int sequenceId = data.readInt();
		int rigId = data.readInt();
		int[][] transforms = readIntMatrix(data);
		int[][] dx = readIntMatrix(data);
		int[][] dy = readIntMatrix(data);
		int[][] dz = readIntMatrix(data);
		checkClip(sequenceId, transforms, dx, dy, dz);
		return new Clip(sequenceId, rigId, transforms, dx, dy, dz);
	}

	/**
	 * Refuses a clip whose four op columns disagree.
	 * <p>
	 * The frame list, and each frame's ops, are one table written as four blocks, and the
	 * skinner is the only thing that ever pairs them again - it bounds its loop on
	 * {@code getOpCount}, which is the transform column's own length, and then indexes the three
	 * delta columns with it. A short column reads cleanly here and throws inside
	 * {@code Skinner.apply} on the render path, where {@code CustomDrawCallbacks} catches it
	 * and draws the vanilla model instead: the NPC is silently un-swapped, once per frame, with
	 * nothing above debug level to say why.
	 * <p>
	 * Same contract as the rig's two tables, and for the same reason - a bundle that is wrong
	 * must not load.
	 */
	private static void checkClip(int sequenceId, int[][] transforms, int[][] dx, int[][] dy,
		int[][] dz) throws IOException
	{
		if (transforms == null || dx == null || dy == null || dz == null)
		{
			throw malformed("Asset clip " + sequenceId
				+ " is missing an op column");
		}

		if (transforms.length != dx.length || transforms.length != dy.length
			|| transforms.length != dz.length)
		{
			throw malformed("Asset clip " + sequenceId + " has " + transforms.length
				+ ", " + dx.length + ", " + dy.length + " and " + dz.length
				+ " frames across its four op columns");
		}

		for (int frame = 0; frame < transforms.length; frame++)
		{
			if (transforms[frame] == null || dx[frame] == null
				|| dy[frame] == null || dz[frame] == null)
			{
				throw malformed("Asset clip " + sequenceId + " frame " + frame
					+ " is missing an op column");
			}

			int ops = transforms[frame].length;
			if (dx[frame].length != ops || dy[frame].length != ops || dz[frame].length != ops)
			{
				throw malformed("Asset clip " + sequenceId + " frame " + frame
					+ " has " + ops + ", " + dx[frame].length + ", " + dy[frame].length + " and "
					+ dz[frame].length + " ops across its four columns");
			}
		}
	}

	private static void writeBinding(DataOutputStream data, NpcBinding binding) throws IOException
	{
		data.writeUTF(binding.getName() == null ? "" : binding.getName());
		writeInts(data, binding.getNpcIds());
		writeInts(data, binding.getMeshIds());
		data.writeInt(binding.getRigId());
		data.writeShort(binding.getScaleXZ());
		data.writeShort(binding.getScaleY());
		writeShorts(data, binding.getRecolorFind());
		writeShorts(data, binding.getRecolorReplace());
		data.writeByte(binding.getAmbient());
		data.writeByte(binding.getContrast());
		data.writeInt(binding.getChatheadNpcId());
		data.writeInt(binding.getChatheadMeshId());
		data.writeInt(binding.getChatheadRigId());
	}

	private static NpcBinding readBinding(DataInputStream data) throws IOException
	{
		String name = data.readUTF();
		int[] npcIds = readInts(data);
		int[] meshIds = readInts(data);
		int rigId = data.readInt();
		int scaleXZ = data.readShort();
		int scaleY = data.readShort();
		short[] recolorFind = readShorts(data);
		short[] recolorReplace = readShorts(data);
		int ambient = data.readByte();
		int contrast = data.readByte();
		int chatheadNpcId = data.readInt();
		int chatheadMeshId = data.readInt();
		int chatheadRigId = data.readInt();
		return new NpcBinding(name, npcIds, meshIds, rigId, scaleXZ, scaleY, recolorFind, recolorReplace,
			ambient, contrast, chatheadNpcId, chatheadMeshId, chatheadRigId);
	}

	/**
	 * Refuses a binding the spawn path could not honor.
	 * <p>
	 * Every one of these would otherwise surface far from the bundle: a mesh id that does not
	 * resolve makes {@code ModelCache} refuse a partial merge and quietly leave the NPC vanilla, an
	 * unpaired recolor throws inside the recolor loop, and a non-positive scale collapses the model
	 * to a point. An NPC bound twice would draw whichever binding happened to be indexed last. A rig
	 * that does not resolve leaves the model frozen at rest, and a chathead below
	 * {@link NpcBinding#NO_CHATHEAD} names no NPC at all. Two bindings sharing a first mesh would
	 * share the key a user switches one model off by, and a merge past the ceilings is more than the
	 * renderer can upload.
	 */
	private static void checkBinding(NpcBinding binding, Map<Integer, Mesh> meshes, Map<Integer, Rig> rigs,
		Set<Integer> boundNpcs, Set<Integer> firstMeshes) throws IOException
	{
		String name = binding.getName();
		if (binding.getNpcIds() == null || binding.getNpcIds().length == 0)
		{
			throw malformed("Binding '" + name + "' names no NPCs");
		}

		if (binding.getMeshIds() == null || binding.getMeshIds().length == 0)
		{
			throw malformed("Binding '" + name + "' names no meshes");
		}

		if (binding.getMeshIds().length > MAX_PARTS)
		{
			throw malformed("Binding '" + name + "' names " + binding.getMeshIds().length
				+ " meshes, past the " + MAX_PARTS + " a model may merge");
		}

		List<Mesh> parts = new ArrayList<>();
		for (int meshId : binding.getMeshIds())
		{
			Mesh mesh = meshes.get(meshId);
			if (mesh == null)
			{
				throw malformed("Binding '" + name + "' names mesh " + meshId
					+ ", which the bundle does not carry");
			}
			parts.add(mesh);
		}

		for (int npcId : binding.getNpcIds())
		{
			if (!boundNpcs.add(npcId))
			{
				throw malformed("NPC " + npcId + " is bound more than once, the second time by '"
					+ name + "'");
			}
		}

		short[] find = binding.getRecolorFind();
		short[] replace = binding.getRecolorReplace();
		if ((find == null) != (replace == null) || (find != null && find.length != replace.length))
		{
			throw malformed("Binding '" + name + "' has unpaired recolors");
		}

		if (binding.getScaleXZ() <= 0 || binding.getScaleY() <= 0)
		{
			throw malformed("Binding '" + name + "' has scale " + binding.getScaleXZ() + "/"
				+ binding.getScaleY());
		}

		if (binding.getRigId() != NpcBinding.STATIC && !rigs.containsKey(binding.getRigId()))
		{
			throw malformed("Binding '" + name + "' names rig " + binding.getRigId()
				+ ", which the bundle does not carry");
		}

		if (binding.getChatheadNpcId() < NpcBinding.NO_CHATHEAD)
		{
			throw malformed("Binding '" + name + "' names chathead NPC " + binding.getChatheadNpcId());
		}

		if (binding.hasChathead() && binding.hasCustomChathead())
		{
			throw malformed("Binding '" + name + "' names both a chathead NPC and a chathead mesh");
		}

		if (binding.hasCustomChathead())
		{
			if (!meshes.containsKey(binding.getChatheadMeshId()))
			{
				throw malformed("Binding '" + name + "' names chathead mesh " + binding.getChatheadMeshId()
					+ ", which the bundle does not carry");
			}
			if (binding.getChatheadRigId() != NpcBinding.STATIC && !rigs.containsKey(binding.getChatheadRigId()))
			{
				throw malformed("Binding '" + name + "' names chathead rig " + binding.getChatheadRigId()
					+ ", which the bundle does not carry");
			}
		}

		if (!firstMeshes.add(binding.getMeshIds()[0]))
		{
			throw malformed("Binding '" + name + "' starts with mesh " + binding.getMeshIds()[0]
				+ ", as another binding does");
		}

		if (parts.size() > 1)
		{
			// The merge keeps every face, so this is known before it allocates for them
			long faces = 0;
			for (Mesh part : parts)
			{
				faces += part.getFaceCount();
			}
			if (faces > MAX_FACES)
			{
				throw new IOException("Binding '" + name + "' merges to " + faces + " faces, past the "
					+ MAX_FACES + " ceiling");
			}

			Mesh merged = MeshMerger.merge(binding.getMeshIds()[0], parts);
			if (merged.getVerticesCount() > MAX_VERTICES || merged.getFaceCount() > MAX_FACES)
			{
				throw new IOException("Binding '" + name + "' merges to " + merged.getVerticesCount()
					+ " vertices and " + merged.getFaceCount() + " faces, past the " + MAX_VERTICES + "/"
					+ MAX_FACES + " ceiling");
			}
		}
	}

	// A length of -1 encodes null, which is distinct from an empty array: a null transparency array
	// is what puts a model on the opaque render path, so the difference has to survive a round trip.

	private static void writeFloats(DataOutputStream data, float[] values) throws IOException
	{
		if (values == null)
		{
			data.writeInt(-1);
			return;
		}
		data.writeInt(values.length);
		for (float value : values)
		{
			data.writeFloat(value);
		}
	}

	private static float[] readFloats(DataInputStream data) throws IOException
	{
		int length = data.readInt();
		if (length < 0)
		{
			return null;
		}
		checkLength(data, length, Float.BYTES);
		float[] values = new float[length];
		for (int i = 0; i < length; i++)
		{
			values[i] = data.readFloat();
			if (!Float.isFinite(values[i]))
			{
				throw malformed("A coordinate in the bundle is " + values[i]);
			}
		}
		return values;
	}

	private static void writeInts(DataOutputStream data, int[] values) throws IOException
	{
		if (values == null)
		{
			data.writeInt(-1);
			return;
		}
		data.writeInt(values.length);
		for (int value : values)
		{
			data.writeInt(value);
		}
	}

	private static int[] readInts(DataInputStream data) throws IOException
	{
		int length = data.readInt();
		if (length < 0)
		{
			return null;
		}
		checkLength(data, length, Integer.BYTES);
		int[] values = new int[length];
		for (int i = 0; i < length; i++)
		{
			values[i] = data.readInt();
		}
		return values;
	}

	private static void writeShorts(DataOutputStream data, short[] values) throws IOException
	{
		if (values == null)
		{
			data.writeInt(-1);
			return;
		}
		data.writeInt(values.length);
		for (short value : values)
		{
			data.writeShort(value);
		}
	}

	private static short[] readShorts(DataInputStream data) throws IOException
	{
		int length = data.readInt();
		if (length < 0)
		{
			return null;
		}
		checkLength(data, length, Short.BYTES);
		short[] values = new short[length];
		for (int i = 0; i < length; i++)
		{
			values[i] = data.readShort();
		}
		return values;
	}

	private static void writeBytes(DataOutputStream data, byte[] values) throws IOException
	{
		if (values == null)
		{
			data.writeInt(-1);
			return;
		}
		data.writeInt(values.length);
		data.write(values);
	}

	private static byte[] readBytes(DataInputStream data) throws IOException
	{
		int length = data.readInt();
		if (length < 0)
		{
			return null;
		}
		checkLength(data, length, Byte.BYTES);
		byte[] values = new byte[length];
		data.readFully(values);
		return values;
	}

	private static void writeIntMatrix(DataOutputStream data, int[][] values) throws IOException
	{
		if (values == null)
		{
			data.writeInt(-1);
			return;
		}
		data.writeInt(values.length);
		for (int[] row : values)
		{
			writeInts(data, row);
		}
	}

	private static int[][] readIntMatrix(DataInputStream data) throws IOException
	{
		int length = data.readInt();
		if (length < 0)
		{
			return null;
		}
		// Each row is at least its own length
		checkLength(data, length, Integer.BYTES);
		int[][] values = new int[length][];
		for (int i = 0; i < length; i++)
		{
			values[i] = readInts(data);
		}
		return values;
	}

	private static int readCount(DataInputStream data, int max) throws IOException
	{
		int count = data.readInt();
		if (count < 0 || count > max)
		{
			throw new IOException("Implausible entry count " + count + " in custom NPC model bundle");
		}
		return count;
	}

	/** Refuses a length the bytes left can't hold, at {@code bytesEach} bytes an element. */
	private static void checkLength(DataInputStream data, int length, int bytesEach) throws IOException
	{
		if (length > MAX_ARRAY || (long) length * bytesEach > data.available())
		{
			throw new IOException("Implausible array length " + length + " in custom NPC model bundle");
		}
	}

	/** A bundle that breaks the format's rules. It was written wrong, so the fix is to write it again. */
	private static IOException malformed(String problem)
	{
		return new IOException(problem + "; regenerate the bundle");
	}

	/** {@code bytes} in whole MiB, for messages about the size limits. */
	public static String mebibytes(long bytes)
	{
		return bytes / (1024 * 1024) + " MiB";
	}

	/**
	 * Refuses to read past {@code limit} bytes. The per-array ceilings bound one allocation, not the
	 * total, so a small file that inflates enormously is stopped here instead.
	 */
	static final class Limited extends FilterInputStream
	{
		private final long limit;
		private long read;

		Limited(InputStream in, long limit)
		{
			super(in);
			this.limit = limit;
		}

		@Override
		public int read() throws IOException
		{
			int b = super.read();
			if (b != -1)
			{
				count(1);
			}
			return b;
		}

		@Override
		public int read(byte[] b, int off, int len) throws IOException
		{
			int n = super.read(b, off, len);
			if (n > 0)
			{
				count(n);
			}
			return n;
		}

		@Override
		public long skip(long n) throws IOException
		{
			long skipped = super.skip(n);
			count(skipped);
			return skipped;
		}

		private void count(long n) throws IOException
		{
			read += n;
			if (read > limit)
			{
				throw new IOException("Custom NPC model bundle inflates past " + mebibytes(limit));
			}
		}
	}
}
