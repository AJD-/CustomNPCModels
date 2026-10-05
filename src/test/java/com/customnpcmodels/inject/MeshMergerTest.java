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

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import java.util.Arrays;
import java.util.Collections;
import org.junit.Test;

/**
 * Covers the merge that lets the bundle store one part per model id.
 *
 * <p>The offsets are the whole point: a part's face indices and vertex-group members address its
 * own vertices, so both have to be shifted by the running vertex base or the merged mesh draws
 * garbage and animates against the wrong bones.
 */
public class MeshMergerTest
{
	/** Two vertices, one face, one vertex group, no optional arrays. */
	private static Mesh part(int id, int priority, float x, int group)
	{
		return new TestMesh()
			.id(id)
			.priority(priority)
			.vx(new float[]{x, x + 1f})
			.vy(new float[]{0f, 1f})
			.vz(new float[]{0f, 2f})
			.i1(new int[]{0})
			.i2(new int[]{1})
			.i3(new int[]{0})
			.colors(new short[]{(short) id})
			.groups(group < 0 ? null : groups(group))
			.build();
	}

	private static int[][] groups(int group)
	{
		int[][] vertexGroups = new int[group + 1][];
		for (int i = 0; i <= group; i++)
		{
			vertexGroups[i] = new int[0];
		}
		vertexGroups[group] = new int[]{0, 1};
		return vertexGroups;
	}

	@Test
	public void testSinglePartIsReturnedUntouched()
	{
		Mesh only = part(2887, 0, 0f, 0);

		// Returned rather than copied - Mesh is immutable and every consumer that needs to
		// change one derives a copy first, so this makes the merge a provable no-op for the
		// categories that were never multi-part
		assertSame(only, MeshMerger.merge(2887, Collections.singletonList(only)));
	}

	@Test
	public void testFaceIndicesAndVertexGroupsAreOffsetByTheRunningVertexBase()
	{
		Mesh body = part(2870, 0, 0f, 1);
		Mesh head = part(2862, 0, 100f, 3);

		Mesh merged = MeshMerger.merge(2870, Arrays.asList(body, head));

		assertEquals("merged mesh keeps the first part's id", 2870, merged.getId());
		assertEquals(4, merged.getVerticesCount());
		assertEquals(2, merged.getFaceCount());

		// The head's vertices follow the body's, and its single face addresses them there
		assertArrayEquals(new float[]{0f, 1f, 100f, 101f}, merged.getVerticesX(), 0f);
		assertArrayEquals(new int[]{0, 2}, merged.getFaceIndices1());
		assertArrayEquals(new int[]{1, 3}, merged.getFaceIndices2());
		assertArrayEquals(new int[]{0, 2}, merged.getFaceIndices3());
		assertArrayEquals(new short[]{2870, 2862}, merged.getFaceColors());

		// Group membership is unioned by group index, with the same offset applied
		assertArrayEquals(new int[]{0, 1}, merged.getVertexGroup(1));
		assertArrayEquals(new int[]{2, 3}, merged.getVertexGroup(3));
		assertEquals(0, merged.getVertexGroup(0).length);
	}

	@Test
	public void testPartsSharingAGroupMoveTogether()
	{
		// A prop rides a single bone the body already has - a held item bound to group 32 and nothing
		// else has to end up in the body's group 32 to animate with the arm
		Mesh body = part(2870, 0, 0f, 2);
		Mesh prop = part(4991, 0, 50f, 2);

		Mesh merged = MeshMerger.merge(2870, Arrays.asList(body, prop));

		assertArrayEquals(new int[]{0, 1, 2, 3}, merged.getVertexGroup(2));
	}

	@Test
	public void testAllNullOptionalArraysStayNull()
	{
		Mesh merged = MeshMerger.merge(2870,
			Arrays.asList(part(2870, 0, 0f, 0), part(2862, 0, 10f, 0)));

		// Null is not the same as empty: a null transparency array is what keeps a model on the
		// opaque upload path, so filling these in would silently move every merged model off it
		assertNull(merged.getFaceRenderTypes());
		assertNull(merged.getFaceTransparencies());
		assertNull(merged.getFaceRenderPriorities());
		assertNull(merged.getFaceTextures());
	}

	@Test
	public void testAPartWithoutPrioritiesIsFilledWithItsModelPriority()
	{
		Mesh withPriorities = new TestMesh()
			.id(2870)
			.vx(new float[]{0f, 1f})
			.vy(new float[]{0f, 0f})
			.vz(new float[]{0f, 0f})
			.i1(new int[]{0})
			.i2(new int[]{1})
			.i3(new int[]{0})
			.colors(new short[]{0})
			.priorities(new byte[]{7})
			.groups(groups(0))
			.build();

		// This part carries no per-face array, so the merge has to fall back to its model-level
		// priority - which is what the client's own mergeModels does. Filling zero here would
		// change draw order on exactly the multi-part NPCs merging exists for.
		Mesh withoutPriorities = part(2862, 4, 10f, 0);

		Mesh merged = MeshMerger.merge(2870,
			Arrays.asList(withPriorities, withoutPriorities));

		assertArrayEquals(new byte[]{7, 4}, merged.getFaceRenderPriorities());

		// Textures default to -1 rather than 0, which is a real texture id
		assertNull(merged.getFaceTextures());
	}

	/**
	 * A part whose triangles are not the first has its per-face indices shifted by everything ahead
	 * of it.
	 */
	@Test
	public void testTriangleIndicesShiftByTheTrianglesAhead()
	{
		Mesh first = textured(1, 3, new byte[]{0, 1, -1}, 2);
		Mesh second = textured(2, 2, new byte[]{0, 0}, 1);
		Mesh untextured = part(3, 0, 50f, 0);

		Mesh merged = MeshMerger.merge(1, Arrays.asList(first, second, untextured));

		assertArrayEquals(new byte[]{0, 1, -1, 2, 2, -1}, merged.getTextureCoords());
		assertEquals(3, merged.getTextureTriangleCount());

		// first's triangles address vertices 0..2 and stay put; second's address its own 0..2 and
		// shift by first's three vertices
		assertArrayEquals(new int[]{0, 0, 3}, merged.getTexIndices1());
		assertArrayEquals(new int[]{1, 1, 4}, merged.getTexIndices2());
		assertArrayEquals(new int[]{2, 2, 5}, merged.getTexIndices3());
	}

	/**
	 * The renderer reads a per-face triangle index as {@code textureFaces[face] & 0xff}, so 255 is
	 * indistinguishable from the -1 that means "no triangle" and only 0..254 are addressable. A
	 * merge that pushes a face past that must drop it to the face-as-UV projection rather than let
	 * the byte wrap onto some unrelated triangle.
	 *
	 * <p>Three indices are needed to pin this, because two of them cannot tell the behaviours apart:
	 * 254 is the last index that still works, so it separates a correct boundary from one off by
	 * one; 255 narrows to -1 whether it wrapped or fell back, so it proves nothing on its own; 256
	 * is the one that matters, because wrapping it yields 0 and silently maps the face onto the
	 * first part's own first triangle - a corruption with nothing on screen to announce it.
	 */
	@Test
	public void testAFaceMappedPastTheAddressableTrianglesFallsBackRatherThanWrapping()
	{
		Mesh first = textured(1, 1, new byte[]{0}, 254);
		Mesh second = textured(2, 3, new byte[]{0, 1, 2}, 3);

		Mesh merged = MeshMerger.merge(1, Arrays.asList(first, second));

		// second's triangles shift by first's 254 to 254, 255 and 256. Only the first is
		// addressable; the other two must read as "no triangle", never as 255 & 0xff or 0
		assertArrayEquals(new byte[]{0, (byte) 254, -1, -1}, merged.getTextureCoords());

		// The table itself still concatenates in full - it is the per-face index that cannot reach
		// the tail, not the triangles that go missing
		assertEquals(257, merged.getTextureTriangleCount());
	}

	/** Three vertices, {@code coords.length} faces and {@code triangles} texture triangles. */
	/** A triangle on one group, at the given corner positions. */
	private static Mesh triangle(int id, int group, float[] x, float[] y, float[] z)
	{
		int[][] groups = new int[group + 1][];
		for (int g = 0; g < group; g++)
		{
			groups[g] = new int[0];
		}
		groups[group] = new int[]{0, 1, 2};
		return new TestMesh()
			.id(id)
			.vx(x)
			.vy(y)
			.vz(z)
			.i1(new int[]{0})
			.i2(new int[]{1})
			.i3(new int[]{2})
			.colors(new short[]{(short) id})
			.groups(groups)
			.build();
	}

	/**
	 * The client welds a merge by position: a limb corner sitting exactly on a body corner lands on
	 * the body's vertex, bone and all, so the limb's faces stretch back to the body when it moves
	 * instead of parting from it. That is what keeps a multi-part NPC's seams closed.
	 */
	@Test
	public void testCoincidentVerticesWeldToTheFirstPartsVertex()
	{
		Mesh body = triangle(1, 0, new float[]{0, 10, 0}, new float[]{0, 0, -10}, new float[]{0, 0, 0});
		// Shares its first corner with the body's second, and binds it to a different bone
		Mesh limb = triangle(2, 1, new float[]{10, 20, 10}, new float[]{0, 0, -10}, new float[]{0, 0, 0});

		Mesh merged = MeshMerger.merge(1, Arrays.asList(body, limb));

		assertEquals("five distinct positions, not six copies", 5, merged.getVerticesCount());
		assertEquals("the limb's seam corner is the body's vertex", merged.getFaceIndices2()[0], merged.getFaceIndices1()[1]);

		// The shared vertex keeps the body's bone - it was merged first - so only two vertices move
		// with the limb and the face between them stretches
		assertArrayEquals(new int[]{0, 1, 2}, merged.getVertexGroup(0));
		assertArrayEquals(new int[]{3, 4}, merged.getVertexGroup(1));
	}

	@Test
	public void testDuplicatesWithinAPartWeldAndUnusedVerticesAreDropped()
	{
		Mesh a = new TestMesh()
			.id(1)
			.vx(new float[]{0, 10, 0, 0, 99})
			.vy(new float[]{0, 0, -10, 0, 99})
			.vz(new float[]{0, 0, 0, 0, 99})
			.i1(new int[]{0})
			.i2(new int[]{1})
			.i3(new int[]{3})
			.colors(new short[]{1})
			.groups(new int[][]{{0, 1, 2, 3, 4}})
			.build();
		Mesh b = part(2, 0, 50f, 0);

		Mesh merged = MeshMerger.merge(1, Arrays.asList(a, b));

		// Vertex 3 sits on vertex 0 and welds to it; vertices 2 and 4 are named by no face and go
		assertEquals(0, merged.getFaceIndices1()[0]);
		assertEquals(0, merged.getFaceIndices3()[0]);
		assertEquals(4, merged.getVerticesCount());
	}

	private static Mesh textured(int id, int faceCount, byte[] coords, int triangles)
	{
		int[] i1 = new int[faceCount];
		int[] i2 = new int[faceCount];
		int[] i3 = new int[faceCount];
		short[] colors = new short[faceCount];
		short[] textures = new short[faceCount];
		for (int f = 0; f < faceCount; f++)
		{
			i1[f] = 0;
			i2[f] = 1;
			i3[f] = 2;
			textures[f] = 37;
		}

		int[] t1 = new int[triangles];
		int[] t2 = new int[triangles];
		int[] t3 = new int[triangles];
		for (int t = 0; t < triangles; t++)
		{
			t1[t] = 0;
			t2[t] = 1;
			t3[t] = 2;
		}

		return new Mesh(id, 0,
			// Each part sits at its own offset, so nothing welds and the shift itself is what is tested
			new float[]{id * 10f, id * 10f + 1f, id * 10f + 2f}, new float[]{0f, 1f, 2f}, new float[]{0f, 1f, 2f},
			i1, i2, i3, colors, null, null, null, textures,
			coords, t1, t2, t3, groups(0));
	}
}
