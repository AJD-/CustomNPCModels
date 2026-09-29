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

import lombok.Getter;

/**
 * Rest-pose geometry plus the rigging that lets it be animated, owned entirely by this plugin.
 * <p>
 * This is what makes injecting a mesh with no live cache id possible. Everything the renderer
 * needs is here or derived from it, so nothing has to come back through the client.
 * <p>
 * Immutable, and shared across every NPC using it: posing reads from here and writes elsewhere,
 * never back.
 * <p>
 * The generated accessors hand back the live arrays rather than copies. Callers read them into
 * their own buffers; nothing mutates a mesh once it is built.
 */
@Getter
public final class Mesh
{
	private final int id;

	/**
	 * The model-level render priority, used as the per-face fallback when
	 * {@link #faceRenderPriorities} is null.
	 * <p>
	 * Carried because merging two parts, only one of which has a per-face array, has to fill the
	 * other side with this rather than with zero - which is what the client's own
	 * {@code mergeModels} does. Losing it would change draw order on exactly the multipart NPCs
	 * merging exists for, and invisibly to any check that only compares vertices.
	 */
	private final int priority;

	private final int verticesCount;
	private final float[] verticesX;
	private final float[] verticesY;
	private final float[] verticesZ;

	private final int faceCount;
	private final int[] faceIndices1;
	private final int[] faceIndices2;
	private final int[] faceIndices3;

	/** Unlit packed HSL per face; {@link Lighter} turns these into per-corner colors. */
	private final short[] faceColors;
	private final byte[] faceRenderTypes;
	private final byte[] faceTransparencies;
	private final byte[] faceRenderPriorities;
	private final short[] faceTextures;

	/**
	 * Per face, the texture triangle that maps its texture, or -1 for the renderer's own
	 * face-as-UV projection. Null when no face on this mesh names one.
	 *
	 * <p>Carried because dropping it is not neutral. {@code ModelUploader.computeUv} branches on
	 * exactly this array: with a triangle it projects the face's corners onto that triangle's plane
	 * basis, so many faces share one continuous mapping; without one it emits a hardcoded (0,0),
	 * (1,0), (0,1) and every face gets the whole image stretched corner to corner.
	 */
	private final byte[] textureCoords;

	/**
	 * The three vertices of each texture triangle. Indices into this mesh's own vertices, which is
	 * why {@link MeshMerger} has to shift them like face indices.
	 * <p>
	 * All three are null together or none of them is; a texture triangle is one row across them.
	 */
	private final int[] texIndices1;
	private final int[] texIndices2;
	private final int[] texIndices3;

	/**
	 * Vertex indices per transform group, the unpacked form of the model's per-vertex labels. An
	 * empty slot is a group nothing is bound to.
	 * <p>
	 * The accessor hands back the whole table, for callers building a derived mesh. Shared by
	 * reference - a derived mesh rigs identically to the one it came from.
	 */
	private final int[][] vertexGroups;

	public Mesh(
		int id, int priority,
		float[] verticesX, float[] verticesY, float[] verticesZ,
		int[] faceIndices1, int[] faceIndices2, int[] faceIndices3,
		short[] faceColors, byte[] faceRenderTypes, byte[] faceTransparencies,
		byte[] faceRenderPriorities, short[] faceTextures,
		byte[] textureCoords, int[] texIndices1, int[] texIndices2, int[] texIndices3,
		int[][] vertexGroups)
	{
		this.id = id;
		this.priority = priority;
		this.verticesCount = verticesX.length;
		this.verticesX = verticesX;
		this.verticesY = verticesY;
		this.verticesZ = verticesZ;
		this.faceCount = faceIndices1.length;
		this.faceIndices1 = faceIndices1;
		this.faceIndices2 = faceIndices2;
		this.faceIndices3 = faceIndices3;
		this.faceColors = faceColors;
		this.faceRenderTypes = faceRenderTypes;
		this.faceTransparencies = faceTransparencies;
		this.faceRenderPriorities = faceRenderPriorities;
		this.faceTextures = faceTextures;
		this.textureCoords = textureCoords;
		this.texIndices1 = texIndices1;
		this.texIndices2 = texIndices2;
		this.texIndices3 = texIndices3;
		this.vertexGroups = vertexGroups;
	}

	/** True when this mesh carries rigging and can be posed at all. */
	public boolean isRigged()
	{
		return vertexGroups != null && vertexGroups.length > 0;
	}

	/**
	 * Vertices bound to a transform group, or an empty array when the group is out of range.
	 * <p>
	 * Out-of-range is normal rather than exceptional: a rig is shared across a whole category and
	 * addresses more groups than any one mesh uses. The reference implementation makes the same
	 * bounds check.
	 */
	public int[] getVertexGroup(int group)
	{
		if (vertexGroups == null || group < 0 || group >= vertexGroups.length)
		{
			return EMPTY_GROUP;
		}
		int[] members = vertexGroups[group];
		return members == null ? EMPTY_GROUP : members;
	}

	private static final int[] EMPTY_GROUP = new int[0];

	/** How many texture triangles this mesh carries; zero when it has none. */
	public int getTextureTriangleCount()
	{
		return texIndices1 == null ? 0 : texIndices1.length;
	}
}
