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

import com.customnpcmodels.inject.Mesh;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Which faces of a mesh touch which, for flood fills. */
final class FaceAdjacency
{
	private FaceAdjacency()
	{
	}

	/** Per face, the faces sharing an edge with it. Faces that only share a corner do not count. */
	static List<int[]> of(Mesh mesh)
	{
		int faces = mesh.getFaceCount();
		Map<Long, List<Integer>> edges = new HashMap<>();
		for (int face = 0; face < faces; face++)
		{
			int[] v = {mesh.getFaceIndices1()[face], mesh.getFaceIndices2()[face], mesh.getFaceIndices3()[face]};
			for (int k = 0; k < 3; k++)
			{
				int a = Math.min(v[k], v[(k + 1) % 3]);
				int b = Math.max(v[k], v[(k + 1) % 3]);
				edges.computeIfAbsent((long) a << 32 | b, key -> new ArrayList<>()).add(face);
			}
		}

		List<List<Integer>> lists = new ArrayList<>();
		for (int face = 0; face < faces; face++)
		{
			lists.add(new ArrayList<>());
		}
		for (List<Integer> sharing : edges.values())
		{
			for (int a : sharing)
			{
				for (int b : sharing)
				{
					if (a != b)
					{
						lists.get(a).add(b);
					}
				}
			}
		}
		List<int[]> neighbours = new ArrayList<>();
		for (List<Integer> list : lists)
		{
			neighbours.add(list.stream().mapToInt(Integer::intValue).toArray());
		}
		return neighbours;
	}
}
