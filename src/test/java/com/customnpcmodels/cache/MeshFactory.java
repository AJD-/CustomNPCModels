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
package com.customnpcmodels.cache;

import com.customnpcmodels.inject.Mesh;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import lombok.extern.slf4j.Slf4j;
import net.runelite.cache.definitions.ModelDefinition;

/**
 * Converts a decoded cache model into the bundle mesh form.
 * <p>
 * Used by the glTF exporter and by the tests that need real geometry to check the pipeline
 * against - never by the asset generator, which only reads authored glTF.
 */
@Slf4j
public final class MeshFactory
{
	/**
	 * The old model format's "no bone" group. No rig names it, so a vertex bound here holds its rest
	 * position whatever clip plays.
	 */
	private static final int NO_BONE = 255;

	private MeshFactory()
	{
	}

	/**
	 * Converts one decoded model into a mesh stored under {@code meshId}.
	 * <p>
	 * Parts are converted individually and merged by {@link com.customnpcmodels.inject.MeshMerger},
	 * exactly as the plugin does at spawn.
	 */
	public static Mesh toMesh(int meshId, ModelDefinition part)
	{
		part.computeAnimationTables();

		float[] vx = new float[part.vertexCount];
		float[] vy = new float[part.vertexCount];
		float[] vz = new float[part.vertexCount];
		for (int v = 0; v < part.vertexCount; v++)
		{
			vx[v] = part.vertexX[v];
			vy[v] = part.vertexY[v];
			vz[v] = part.vertexZ[v];
		}

		int[][] partGroups = part.getVertexGroups();
		int[][] vertexGroups = new int[partGroups == null ? 0 : partGroups.length][];
		for (int group = 0; group < vertexGroups.length; group++)
		{
			int[] members = partGroups[group];
			vertexGroups[group] = members == null ? new int[0] : members.clone();
		}
		vertexGroups = bindStrayVertices(meshId, vertexGroups,
			part.faceIndices1, part.faceIndices2, part.faceIndices3);

		checkTextureMapping(meshId, part);

		return new Mesh(meshId, part.priority, vx, vy, vz,
			part.faceIndices1.clone(), part.faceIndices2.clone(), part.faceIndices3.clone(),
			part.faceColors.clone(),
			part.faceRenderTypes == null ? null : part.faceRenderTypes.clone(),
			part.faceTransparencies == null ? null : part.faceTransparencies.clone(),
			part.faceRenderPriorities == null ? null : part.faceRenderPriorities.clone(),
			part.faceTextures == null ? null : part.faceTextures.clone(),
			part.textureCoords == null ? null : part.textureCoords.clone(),
			vertexIndices(part.texIndices1), vertexIndices(part.texIndices2),
			vertexIndices(part.texIndices3),
			vertexGroups);
	}

	/**
	 * Texture triangle corners as vertex indices. The cache reads them with
	 * {@code readUnsignedShort} into a {@code short[]}, so anything past 32767 comes back negative
	 * and has to be unmasked - the loader's own comparisons do the same.
	 */
	private static int[] vertexIndices(short[] corners)
	{
		if (corners == null)
		{
			return null;
		}

		int[] indices = new int[corners.length];
		for (int i = 0; i < corners.length; i++)
		{
			indices[i] = corners[i] & 0xFFFF;
		}
		return indices;
	}

	/**
	 * Refuses geometry whose texture mapping the injected path cannot reproduce.
	 *
	 * <p>{@link com.customnpcmodels.inject.InjectedModel} carries the per-face triangle index and
	 * the triangles, which is what simple projection needs. A live mesh can name the later texture
	 * render types, and drawing one of those as if it were simple projection is silently wrong, so
	 * it stops the conversion instead.
	 */
	static void checkTextureMapping(int meshId, ModelDefinition part)
	{
		if (part.textureRenderTypes == null)
		{
			return;
		}

		for (int triangle = 0; triangle < part.textureRenderTypes.length; triangle++)
		{
			if (part.textureRenderTypes[triangle] != 0)
			{
				throw new IllegalStateException("Model " + meshId + " texture triangle " + triangle
					+ " uses render type " + part.textureRenderTypes[triangle]
					+ "; only simple projection (0) can be injected");
			}
		}
	}

	/**
	 * Binds each vertex a mesh left on {@link #NO_BONE} to the group most of its face-neighbors
	 * use, wherever it shares a face with a vertex that animates.
	 * <p>
	 * A vertex on 255 never moves, so a face joining it to animated vertices tears as soon as they
	 * swing. A part made only of 255 vertices, such as a ground shadow, shares no face with an
	 * animated vertex and is returned untouched. Stray vertices chained to each other resolve over
	 * repeated passes; each pass decides every vertex before applying any, and a tie goes to the
	 * lower group, so the result does not depend on vertex order.
	 */
	static int[][] bindStrayVertices(int meshId, int[][] groups, int[] faces1, int[] faces2, int[] faces3)
	{
		if (groups == null || groups.length <= NO_BONE
			|| groups[NO_BONE] == null || groups[NO_BONE].length == 0)
		{
			return groups;
		}

		int vertexCount = 0;
		for (int[] members : groups)
		{
			for (int vertex : members == null ? new int[0] : members)
			{
				vertexCount = Math.max(vertexCount, vertex + 1);
			}
		}
		for (int face = 0; face < faces1.length; face++)
		{
			vertexCount = Math.max(vertexCount,
				Math.max(faces1[face], Math.max(faces2[face], faces3[face])) + 1);
		}

		int[] owner = new int[vertexCount];
		Arrays.fill(owner, -1);
		for (int group = 0; group < groups.length; group++)
		{
			for (int vertex : groups[group] == null ? new int[0] : groups[group])
			{
				owner[vertex] = group;
			}
		}

		List<Integer> reboundVertices = new ArrayList<>();
		boolean changed = true;
		while (changed)
		{
			int[][] tally = new int[vertexCount][];
			for (int face = 0; face < faces1.length; face++)
			{
				int[] corners = {faces1[face], faces2[face], faces3[face]};
				for (int corner : corners)
				{
					if (owner[corner] != NO_BONE)
					{
						continue;
					}
					for (int neighbour : corners)
					{
						int group = owner[neighbour];
						if (group >= 0 && group != NO_BONE)
						{
							if (tally[corner] == null)
							{
								tally[corner] = new int[groups.length];
							}
							tally[corner][group]++;
						}
					}
				}
			}

			changed = false;
			for (int vertex = 0; vertex < vertexCount; vertex++)
			{
				if (tally[vertex] == null)
				{
					continue;
				}
				int best = 0;
				for (int group = 1; group < tally[vertex].length; group++)
				{
					if (tally[vertex][group] > tally[vertex][best])
					{
						best = group;
					}
				}
				owner[vertex] = best;
				reboundVertices.add(vertex);
				changed = true;
			}
		}

		if (reboundVertices.isEmpty())
		{
			return groups;
		}

		// Keep every untouched group's member order, so meshes without strays convert identically
		int[][] rebound = new int[groups.length][];
		for (int group = 0; group < groups.length; group++)
		{
			List<Integer> members = new ArrayList<>();
			for (int vertex : groups[group] == null ? new int[0] : groups[group])
			{
				if (owner[vertex] == group)
				{
					members.add(vertex);
				}
			}
			for (int vertex : reboundVertices)
			{
				if (owner[vertex] == group)
				{
					members.add(vertex);
				}
			}
			rebound[group] = members.stream().mapToInt(Integer::intValue).toArray();
		}

        log.info("  mesh {}: bound {} vertices off the no-bone group 255, {} stay static",
				meshId, reboundVertices.size(), rebound[NO_BONE].length);
		return rebound;
	}
}
