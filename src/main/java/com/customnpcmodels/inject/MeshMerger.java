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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;

/**
 * Merges the parts of a multi-model NPC into one mesh the way the client merges them, so the parts
 * share one vertex list and one rig space.
 *
 * <p>The bundle stores each part under its own mesh id, so a family of NPCs that share a body and
 * differ by one part carries the shared body once; the merge happens here, once per NPC id at
 * spawn.
 *
 * <h2>Vertices are welded, not concatenated</h2>
 *
 * The client's {@code ModelData} merge runs every face corner and texture triangle corner through a
 * lookup that returns any vertex already merged at exactly the same position, and only appends a new
 * one when there is none. So where a head or limb part meets the body, both parts' faces end up on
 * one shared vertex, and that vertex keeps the bone of whichever part reached it first - the body,
 * since it is listed first. When the limb swings, its faces stretch back to the body's seam rather
 * than parting from it. Concatenating instead leaves each part its own copy of the seam, and the
 * pieces visibly separate as soon as they move; it also counts every seam vertex twice in the pivot
 * centroids the animation turns about.
 *
 * <p>Three more consequences of the same rule, all matching the client: vertices are numbered in the
 * order faces first reach them, duplicates within one part weld too, and a vertex no face or texture
 * triangle names is dropped. Positions are compared exactly - the client compares integer-cast
 * coordinates, which is the same thing for the integer geometry a cache model carries.
 */
@Slf4j
public final class MeshMerger
{
	/**
	 * The most texture triangles a merged mesh can carry. The renderer reads the per-face index as
	 * {@code textureFaces[face] & 0xff}, so 255 is indistinguishable from the -1 that means "no
	 * triangle" - which leaves 0..254 usable. The generator refuses to bundle a set that would
	 * exceed this, so reaching it here means the bundle and this code disagree.
	 */
	private static final int MAX_TEXTURE_TRIANGLES = 0xFF;

	private MeshMerger()
	{
	}

	/**
	 * Merges parts into a single mesh under {@code id}, conventionally the first part's model id so
	 * that logging still names something recognizable.
	 *
	 * <p>A single part is returned as-is rather than copied, exactly as the client uses a lone model
	 * without merging it. {@link Mesh} is immutable and every consumer that needs to change one
	 * builds a derived copy first, so sharing the instance is safe.
	 */
	public static Mesh merge(int id, List<Mesh> parts)
	{
		if (parts.size() == 1)
		{
			return parts.get(0);
		}

		int totalCorners = 0;
		int totalFaces = 0;
		int groupCount = 0;
		boolean anyRenderTypes = false;
		boolean anyTransparencies = false;
		boolean anyPriorities = false;
		boolean anyTextures = false;
		int totalTriangles = 0;

		for (Mesh part : parts)
		{
			totalCorners += part.getVerticesCount();
			totalFaces += part.getFaceCount();
			int[][] groups = part.getVertexGroups();
			if (groups != null)
			{
				groupCount = Math.max(groupCount, groups.length);
			}
			anyRenderTypes |= part.getFaceRenderTypes() != null;
			anyTransparencies |= part.getFaceTransparencies() != null;
			anyPriorities |= part.getFaceRenderPriorities() != null;
			anyTextures |= part.getFaceTextures() != null;
			totalTriangles += part.getTextureTriangleCount();
		}

		Welder welder = new Welder(totalCorners);
		int[] i1 = new int[totalFaces];
		int[] i2 = new int[totalFaces];
		int[] i3 = new int[totalFaces];
		short[] colors = new short[totalFaces];

		// Null is not the same as empty here: a null array is what keeps a model off the sorted
		// upload path, so an all-null column stays null rather than becoming all-zero.
		byte[] renderTypes = anyRenderTypes ? new byte[totalFaces] : null;
		byte[] transparencies = anyTransparencies ? new byte[totalFaces] : null;
		byte[] priorities = anyPriorities ? new byte[totalFaces] : null;
		short[] textures = anyTextures ? new short[totalFaces] : null;

		// A face with no triangle is -1 rather than 0, which is a real triangle. Allocated off the
		// triangle count rather than off any part's textureCoords, so a part whose faces all use
		// the face-as-UV projection contributes nothing but its -1s.
		byte[] textureCoords = totalTriangles > 0 ? new byte[totalFaces] : null;
		int[] texIndices1 = totalTriangles > 0 ? new int[totalTriangles] : null;
		int[] texIndices2 = totalTriangles > 0 ? new int[totalTriangles] : null;
		int[] texIndices3 = totalTriangles > 0 ? new int[totalTriangles] : null;

		// Counted rather than reported as they are found: this is a per-face condition, and one bad
		// merge would otherwise be hundreds of identical lines in a user's log
		int overflowedFaces = 0;
		int highestTriangle = -1;

		int faceBase = 0;
		int triangleBase = 0;
		for (Mesh part : parts)
		{
			int[] groupOf = groupOfVertex(part);

			int[] partI1 = part.getFaceIndices1();
			int[] partI2 = part.getFaceIndices2();
			int[] partI3 = part.getFaceIndices3();
			short[] partColors = part.getFaceColors();
			byte[] partRenderTypes = part.getFaceRenderTypes();
			byte[] partTransparencies = part.getFaceTransparencies();
			byte[] partPriorities = part.getFaceRenderPriorities();
			short[] partTextures = part.getFaceTextures();
			byte[] partCoords = part.getTextureCoords();

			for (int f = 0; f < part.getFaceCount(); f++)
			{
				int face = faceBase + f;
				i1[face] = welder.weld(part, groupOf, partI1[f]);
				i2[face] = welder.weld(part, groupOf, partI2[f]);
				i3[face] = welder.weld(part, groupOf, partI3[f]);
				colors[face] = partColors[f];

				if (renderTypes != null)
				{
					renderTypes[face] = partRenderTypes == null ? 0 : partRenderTypes[f];
				}
				if (transparencies != null)
				{
					transparencies[face] = partTransparencies == null ? 0 : partTransparencies[f];
				}
				if (priorities != null)
				{
					// The model-level priority is the fallback the client uses for a part with no
					// per-face array, which is why Mesh carries it at all
					priorities[face] = partPriorities == null
						? (byte) part.getPriority() : partPriorities[f];
				}
				if (textures != null)
				{
					textures[face] = partTextures == null ? -1 : partTextures[f];
				}
				if (textureCoords != null)
				{
					int merged = mergedCoord(partCoords, f, triangleBase);
					if (merged >= MAX_TEXTURE_TRIANGLES)
					{
						// Wrapping the byte would map this face onto some unrelated triangle, so
						// it falls back to the renderer's own projection instead
						overflowedFaces++;
						highestTriangle = Math.max(highestTriangle, merged);
						merged = -1;
					}
					textureCoords[face] = (byte) merged;
				}
			}

			// A texture triangle names vertices, so its corners weld exactly as a face's do. The
			// triangle table itself concatenates, which is what the per-face index above is shifted by.
			for (int t = 0; t < part.getTextureTriangleCount(); t++)
			{
				texIndices1[triangleBase + t] = welder.weld(part, groupOf, part.getTexIndices1()[t]);
				texIndices2[triangleBase + t] = welder.weld(part, groupOf, part.getTexIndices2()[t]);
				texIndices3[triangleBase + t] = welder.weld(part, groupOf, part.getTexIndices3()[t]);
			}

			faceBase += part.getFaceCount();
			triangleBase += part.getTextureTriangleCount();
		}

		if (overflowedFaces > 0)
		{
			// Unreachable from a bundle the generator produced - it refuses a set that would get
			// here - so say it once, loudly, rather than once per face
			log.warn("Merged mesh {} maps {} of its {} faces to texture triangles past the {} the "
				+ "renderer can address, the highest being {}; those faces fall back to the "
				+ "face-as-UV projection", id, overflowedFaces, totalFaces,
				MAX_TEXTURE_TRIANGLES, highestTriangle);
		}

		int vertices = welder.count;
		List<List<Integer>> groups = new ArrayList<>();
		for (int i = 0; i < groupCount; i++)
		{
			groups.add(new ArrayList<>());
		}
		for (int vertex = 0; vertex < vertices; vertex++)
		{
			int group = welder.groups[vertex];
			if (group >= 0)
			{
				groups.get(group).add(vertex);
			}
		}

		int[][] vertexGroups = new int[groupCount][];
		for (int group = 0; group < groupCount; group++)
		{
			vertexGroups[group] = groups.get(group).stream().mapToInt(Integer::intValue).toArray();
		}

		return new Mesh(id, parts.get(0).getPriority(),
			Arrays.copyOf(welder.x, vertices), Arrays.copyOf(welder.y, vertices), Arrays.copyOf(welder.z, vertices),
			i1, i2, i3, colors, renderTypes, transparencies, priorities, textures,
			textureCoords, texIndices1, texIndices2, texIndices3, vertexGroups);
	}

	/** Per vertex of a part, the group it is bound to, or -1 for none. */
	private static int[] groupOfVertex(Mesh part)
	{
		int[] groupOf = new int[part.getVerticesCount()];
		Arrays.fill(groupOf, -1);
		int[][] groups = part.getVertexGroups();
		if (groups != null)
		{
			for (int group = 0; group < groups.length; group++)
			{
				if (groups[group] == null)
				{
					continue;
				}
				for (int vertex : groups[group])
				{
					groupOf[vertex] = group;
				}
			}
		}
		return groupOf;
	}

	/**
	 * The merged vertex list, built the client's way: a corner lands on the first vertex already at
	 * its position, and only a new position is appended, carrying its own part's group.
	 */
	private static final class Welder
	{
		private final float[] x;
		private final float[] y;
		private final float[] z;
		private final int[] groups;
		private final Map<List<Float>, Integer> byPosition = new HashMap<>();
		private int count;

		private Welder(int capacity)
		{
			x = new float[capacity];
			y = new float[capacity];
			z = new float[capacity];
			groups = new int[capacity];
		}

		private int weld(Mesh part, int[] groupOf, int vertex)
		{
			float vx = part.getVerticesX()[vertex];
			float vy = part.getVerticesY()[vertex];
			float vz = part.getVerticesZ()[vertex];
			List<Float> key = Arrays.asList(vx, vy, vz);

			Integer existing = byPosition.get(key);
			if (existing != null)
			{
				return existing;
			}

			x[count] = vx;
			y[count] = vy;
			z[count] = vz;
			groups[count] = groupOf[vertex];
			byPosition.put(key, count);
			return count++;
		}
	}


	/**
	 * The merged per-face triangle index: -1 where the part named no triangle, and the part's own
	 * index shifted into the concatenated table where it did.
	 *
	 * <p>A part can decline a triangle two ways - a null array, meaning no face on it names one, or
	 * a -1 entry, meaning that one face uses the renderer's projection - and both mean -1 here.
	 *
	 * <p>Returned as an {@code int} rather than a {@code byte} so the caller can tell an index the
	 * renderer cannot address from one it can; narrowing here would wrap it into a valid-looking
	 * triangle and lose exactly the thing worth reporting.
	 */
	private static int mergedCoord(byte[] partCoords, int face, int triangleBase)
	{
		if (partCoords == null || partCoords[face] == -1)
		{
			return -1;
		}

		return (partCoords[face] & 0xFF) + triangleBase;
	}
}
