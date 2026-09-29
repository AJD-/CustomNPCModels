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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import com.customnpcmodels.inject.AssetBundle;
import com.customnpcmodels.inject.Clip;
import com.customnpcmodels.inject.Mesh;
import com.customnpcmodels.inject.NpcBinding;
import com.customnpcmodels.inject.Rig;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.runelite.api.gameval.NpcID;
import org.junit.Test;

public class AssetValidatorTest
{
	/**
	 * A two-face quad at RS scale, every vertex in exactly one of two groups - the smallest mesh that
	 * passes every check, so each malformed case below is one change away from valid.
	 */
	private static TestMesh quad()
	{
		return new TestMesh();
	}

	private static Mesh valid()
	{
		return quad().build();
	}

	private static String only(List<String> problems)
	{
		assertEquals("expected exactly one problem, got " + problems, 1, problems.size());
		return problems.get(0);
	}

	@Test
	public void testAValidMeshPasses()
	{
		assertEquals(Collections.emptyList(), AssetValidator.validateMesh(valid()));
	}

	@Test
	public void testRejectsMismatchedVertexAxes()
	{
		String problem = only(AssetValidator.validateMesh(quad().vy(new float[]{0f, 0f, 0f}).build()));
		assertTrue(problem, problem.contains("4, 3 and 4 vertices"));
	}

	@Test
	public void testRejectsMismatchedFaceColumns()
	{
		String problem = only(AssetValidator.validateMesh(quad().i3(new int[]{2}).build()));
		assertTrue(problem, problem.contains("face corners"));
	}

	@Test
	public void testRejectsAFaceIndexPastTheVertices()
	{
		String problem = only(AssetValidator.validateMesh(quad().i2(new int[]{1, 4}).build()));
		assertTrue(problem, problem.contains("face 1 names a vertex outside 0..3"));
	}

	@Test
	public void testRejectsMissingFaceColors()
	{
		String problem = only(AssetValidator.validateMesh(quad().colors(null).build()));
		assertTrue(problem, problem.contains("no face colors"));
	}

	@Test
	public void testRejectsAnEmptyOptionalColumn()
	{
		String problem = only(AssetValidator.validateMesh(quad().transparencies(new byte[0]).build()));
		assertTrue(problem, problem.contains("empty transparencies array"));
	}

	@Test
	public void testRejectsAShortOptionalColumn()
	{
		String problem = only(AssetValidator.validateMesh(quad().priorities(new byte[]{1}).build()));
		assertTrue(problem, problem.contains("1 render priorities for 2 faces"));
	}

	@Test
	public void testRejectsAHiddenRenderType()
	{
		String problem = only(AssetValidator.validateMesh(quad().renderTypes(new byte[]{0, 2}).build()));
		assertTrue(problem, problem.contains("render type outside {0, 1, 3}"));
	}

	@Test
	public void testAcceptsEveryDrawnRenderType()
	{
		assertEquals(Collections.emptyList(),
			AssetValidator.validateMesh(quad().renderTypes(new byte[]{1, 3}).build()));
	}

	@Test
	public void testRejectsAVertexInTwoGroups()
	{
		String problem = only(AssetValidator.validateMesh(quad().groups(new int[][]{{0, 1}, {1, 2, 3}}).build()));
		assertTrue(problem, problem.contains("vertex 1 is in both group 0 and group 1"));
	}

	@Test
	public void testRejectsAVertexInNoGroup()
	{
		String problem = only(AssetValidator.validateMesh(quad().groups(new int[][]{{0, 1}, {2}}).build()));
		assertTrue(problem, problem.contains("1 vertices in no group"));
	}

	@Test
	public void testRejectsAGroupMemberPastTheVertices()
	{
		String problem = only(AssetValidator.validateMesh(quad().groups(new int[][]{{0, 1}, {2, 3, 9}}).build()));
		assertTrue(problem, problem.contains("names vertex 9"));
	}

	@Test
	public void testRejectsAnEmptyGroupTable()
	{
		String problem = only(AssetValidator.validateMesh(quad().groups(new int[0][]).build()));
		assertTrue(problem, problem.contains("empty vertex group table"));
	}

	@Test
	public void testAcceptsAnUnriggedMesh()
	{
		assertEquals(Collections.emptyList(), AssetValidator.validateMesh(quad().groups(null).build()));
	}

	@Test
	public void testRejectsAMeshPastTheDiameterCeiling()
	{
		float far = 5000f;
		String problem = only(AssetValidator.validateMesh(quad()
			.vx(new float[]{-far, far, far, -far}).vz(new float[]{-far, -far, far, far}).build()));
		assertTrue(problem, problem.contains("diameter"));
	}

	@Test
	public void testRejectsGeometryTooSmallToLight()
	{
		// The same quad in metres: every edge truncates to zero before the cross product
		String problem = only(AssetValidator.validateMesh(quad()
			.vx(new float[]{0f, 0.5f, 0.5f, 0f}).vy(new float[]{0f, 0f, 0f, 0f})
			.vz(new float[]{0f, 0f, 0.5f, 0.5f}).build()));
		assertTrue(problem, problem.contains("too small for the lighter"));
	}

	@Test
	public void testRejectsTooManyVertices()
	{
		int count = AssetValidator.MAX_VERTICES + 1;
		float[] v = new float[count];
		for (int i = 0; i < count; i++)
		{
			v[i] = i % 64;
		}
		List<String> problems = AssetValidator.validateMesh(quad().vx(v).vy(v.clone()).vz(v.clone()).groups(null).build());
		assertTrue(problems.toString(), problems.stream().anyMatch(p -> p.contains("past the 6500 ceiling")));
	}

	@Test
	public void testRejectsARigWhoseTablesDisagree()
	{
		String problem = only(AssetValidator.validateRig(new Rig(7, new int[]{0, 2}, new int[][]{{0}})));
		assertTrue(problem, problem.contains("fewer group sets"));
	}

	@Test
	public void testRejectsAnUnknownRigType()
	{
		String problem = only(AssetValidator.validateRig(new Rig(7, new int[]{0, 4}, new int[][]{{0}, {1}})));
		assertTrue(problem, problem.contains("unknown type 4"));
	}

	private static Map<Integer, Rig> rigs()
	{
		Map<Integer, Rig> rigs = new LinkedHashMap<>();
		rigs.put(7, new Rig(7, new int[]{0, 2}, new int[][]{{0}, {0, 1}}));
		return rigs;
	}

	private static Clip clip(int[][] transforms, int[][] dz)
	{
		return new Clip(3309, 7, transforms, new int[][]{{0, 0}, {0}}, new int[][]{{0, 0}, {0}}, dz);
	}

	@Test
	public void testAValidClipPasses()
	{
		assertEquals(Collections.emptyList(), AssetValidator.validateClip(
			clip(new int[][]{{0, 1}, {1}}, new int[][]{{0, 64}, {0}}), rigs(), 2));
	}

	@Test
	public void testRejectsUnequalClipRows()
	{
		String problem = only(AssetValidator.validateClip(
			clip(new int[][]{{0, 1}, {1}}, new int[][]{{0}, {0}}), rigs(), 2));
		assertTrue(problem, problem.contains("frame 0 has missing or unequal op rows"));
	}

	@Test
	public void testRejectsAClipOnAnUnknownRig()
	{
		String problem = only(AssetValidator.validateClip(
			clip(new int[][]{{0, 1}, {1}}, new int[][]{{0, 64}, {0}}), Collections.emptyMap(), 2));
		assertTrue(problem, problem.contains("names rig 7"));
	}

	@Test
	public void testRejectsAnOpPastTheRig()
	{
		String problem = only(AssetValidator.validateClip(
			clip(new int[][]{{0, 5}, {1}}, new int[][]{{0, 64}, {0}}), rigs(), 2));
		assertTrue(problem, problem.contains("names transform 5 of a 2-transform rig"));
	}

	@Test
	public void testRejectsAFrameCountDifferentFromTheLiveSequence()
	{
		String problem = only(AssetValidator.validateClip(
			clip(new int[][]{{0, 1}, {1}}, new int[][]{{0, 64}, {0}}), rigs(), 10));
		assertTrue(problem, problem.contains("has 2 frames, but live sequence 3309 has 10"));
	}

	private static AssetBundle bundleWith(NpcBinding... bindings)
	{
		Map<Integer, Mesh> meshes = new LinkedHashMap<>();
		meshes.put(1, valid());
		return new AssetBundle(meshes, Collections.emptyMap(), Collections.emptyList(), Arrays.asList(bindings));
	}

	@Test
	public void testRejectsBindingProblems()
	{
		List<String> problems = AssetValidator.validate(bundleWith(
			new NpcBinding("a", new int[]{5}, new int[]{1, 2}, NpcBinding.STATIC, 128, 128, null, null, 0, 0),
			new NpcBinding("b", new int[]{5}, new int[]{1}, NpcBinding.STATIC, 0, 128, new short[]{1}, null, 0, 0)), id -> -1);

		assertEquals(problems.toString(), 4, problems.size());
		assertTrue(problems.get(0), problems.get(0).contains("mesh 2"));
		assertTrue(problems.get(1), problems.get(1).contains("NPC 5"));
		assertTrue(problems.get(2), problems.get(2).contains("unpaired recolors"));
		assertTrue(problems.get(3), problems.get(3).contains("scale 0/128"));
	}

	@Test
	public void testRejectsABindingToAnUnknownRig()
	{
		String problem = only(AssetValidator.validate(bundleWith(
			new NpcBinding("a", new int[]{5}, new int[]{1}, 999, 128, 128, null, null, 0, 0)), id -> -1));

		assertTrue(problem, problem.contains("names rig 999"));
	}

	@Test
	public void testRejectsABlacklistedNpc()
	{
		String problem = only(AssetValidator.validate(bundleWith(
			new NpcBinding("zuk", new int[]{NpcID.INFERNO_TZKALZUK_PLACEHOLDER}, new int[]{1}, NpcBinding.STATIC, 128, 128, null, null, 0, 0)),
			id -> -1));

		assertTrue(problem, problem.contains("NPC " + NpcID.INFERNO_TZKALZUK_PLACEHOLDER));
		assertTrue(problem, problem.contains("the Inferno"));
	}

	@Test(expected = IllegalStateException.class)
	public void testRequireValidThrows()
	{
		AssetValidator.requireValid(bundleWith(
			new NpcBinding("a", new int[]{5}, new int[]{2}, NpcBinding.STATIC, 128, 128, null, null, 0, 0)), id -> -1);
	}

	/** The baseline: real cache geometry that animates correctly in game must pass. */
	@Test
	public void testTheLiveMolePartsPass() throws Exception
	{
		for (int part : LiveFixtures.MOLE_PARTS)
		{
			assertEquals("mole part " + part, Collections.emptyList(),
				AssetValidator.validateMesh(LiveFixtures.mesh(part)));
		}
		assertEquals(Collections.emptyList(), AssetValidator.validateMesh(LiveFixtures.mole()));
	}

	@Test
	public void testTheLiveSkeletonPasses() throws Exception
	{
		assertEquals(Collections.emptyList(),
			AssetValidator.validateMesh(LiveFixtures.mesh(LiveFixtures.SKELETON_MESH)));
	}

	@Test
	public void testTheLiveClipsPass() throws Exception
	{
		int[] sequences = {LiveFixtures.MOLE_READY, LiveFixtures.MOLE_WALK,
			LiveFixtures.SKELETON_READY, LiveFixtures.SKELETON_WALK};
		for (int sequence : sequences)
		{
			Clip clip = LiveFixtures.clip(sequence);
			assertEquals(Collections.emptyList(), AssetValidator.validateRig(LiveFixtures.rig(clip.getRigId())));
			assertEquals(Collections.emptyList(), AssetValidator.validateClip(
				clip, LiveFixtures.rigs(), LiveFixtures.liveFrameCount(sequence)));
		}
	}
}
