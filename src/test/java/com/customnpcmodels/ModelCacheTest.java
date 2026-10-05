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
package com.customnpcmodels;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import com.customnpcmodels.inject.AssetBundle;
import com.customnpcmodels.inject.Clip;
import com.customnpcmodels.inject.Lighter;
import com.customnpcmodels.inject.Mesh;
import com.customnpcmodels.inject.NpcBinding;
import com.customnpcmodels.inject.Rig;
import com.customnpcmodels.inject.TestBinding;
import com.customnpcmodels.inject.TestMesh;
import com.customnpcmodels.packs.ModelCatalog;
import com.customnpcmodels.packs.ResolvedModel;
import com.customnpcmodels.packs.TestPacks;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import net.runelite.api.Model;
import net.runelite.api.NPC;
import net.runelite.api.gameval.NpcID;
import org.junit.Test;

/**
 * The spawn-time build and per-frame pose, checked against what the client does for an NPC it
 * decoded itself: pose first, then resize, and light with the NPC lighting formula.
 */
public class ModelCacheTest
{
	private static final int NPC_ID = 5779;
	private static final int SEQUENCE = 3309;

	/**
	 * Two tetrahedra, one per group. Group 1 is the "limb" the clip moves; group 0 stays put, so the
	 * distance between them is exactly what opens up as a gap when resize and pose are misordered.
	 */
	private static Mesh mesh()
	{
		return mesh(0);
	}

	/** The same, with the body - group 0, which no clip moves - shifted along X by {@code shift}. */
	private static Mesh mesh(float shift)
	{
		return new TestMesh()
			.id(1)
			.vx(new float[]{shift, 128 + shift, shift, shift, 200, 328, 200, 200})
			.vy(new float[]{0, 0, -128, 0, 0, 0, -128, 0})
			.vz(new float[]{0, 0, 0, 128, 0, 0, 0, 128})
			.i1(new int[]{0, 0, 0, 1, 4, 4, 4, 5})
			.i2(new int[]{2, 1, 3, 2, 6, 5, 7, 6})
			.i3(new int[]{1, 3, 2, 3, 5, 7, 6, 7})
			.colors(new short[]{(short) 0x3A40, (short) 0x3A40, (short) 0x1240, (short) 0x1240,
				(short) 0x3A40, (short) 0x3A40, (short) 0x1240, (short) 0x1240})
			.groups(new int[][]{{0, 1, 2, 3}, {4, 5, 6, 7}})
			.build();
	}

	/** Translate group 1 along X by a tile, then turn it a quarter about its own centroid. */
	private static AssetBundle bundle(NpcBinding binding)
	{
		return bundle(binding, 128);
	}

	/** The same, translating group 1 by {@code dx}, so bundles can be told apart by their pose. */
	private static AssetBundle bundle(NpcBinding binding, int dx)
	{
		return bundle(binding, dx, mesh());
	}

	private static AssetBundle bundle(NpcBinding binding, int dx, Mesh mesh)
	{
		Map<Integer, Mesh> meshes = new LinkedHashMap<>();
		meshes.put(1, mesh);
		Map<Integer, Rig> rigs = new LinkedHashMap<>();
		rigs.put(7, new Rig(7, new int[]{1, 0, 2}, new int[][]{{1}, {1}, {1}}));
		Clip clip = new Clip(SEQUENCE, 7,
			new int[][]{{0, 1, 2}},
			new int[][]{{dx, 0, 0}},
			new int[][]{{0, 0, 64}},
			new int[][]{{0, 0, 0}});
		return new AssetBundle(meshes, rigs, Collections.singletonList(clip), Collections.singletonList(binding));
	}

	private static NpcBinding binding(String name, int npcId)
	{
		return TestBinding.of(name, new int[]{npcId}, new int[]{1}).rig(7).build();
	}

	private static NPC npc(int npcId)
	{
		NPC npc = mock(NPC.class);
		when(npc.getId()).thenReturn(npcId);
		when(npc.getAnimation()).thenReturn(-1);
		when(npc.getPoseAnimation()).thenReturn(SEQUENCE);
		when(npc.getPoseAnimationFrame()).thenReturn(0);
		return npc;
	}

	private static Model pose(NpcBinding binding)
	{
		ModelCache cache = new ModelCache();
		cache.setCatalog(TestPacks.catalogOf(bundle(binding)));
		assertTrue(cache.ensureBuilt(NPC_ID));
		cache.setSubstituted(NPC_ID);
		return cache.pose(npc(NPC_ID));
	}

	/**
	 * The client resizes an NPC after posing it, so a translation in a clip is scaled along with
	 * everything else. Resizing the rest mesh first and posing it after would leave every translated
	 * limb short of - or past - the body it is attached to: the gaps seen on the Giant Mole.
	 */
	@Test
	public void testTheResizeIsAppliedAfterThePose()
	{
		NpcBinding unscaled = TestBinding.of("unscaled", new int[]{NPC_ID}, new int[]{1}).rig(7).build();
		NpcBinding scaled = TestBinding.of("scaled", new int[]{NPC_ID}, new int[]{1}).rig(7).scale(64, 96).build();

		Model reference = pose(unscaled);
		float[] rx = reference.getVerticesX().clone();
		float[] ry = reference.getVerticesY().clone();
		float[] rz = reference.getVerticesZ().clone();

		// Otherwise a model that never found its clip - drawn at rest - would pass this test too
		assertTrue("the clip should have moved the limb off its rest position",
			Math.abs(rx[4] - mesh().getVerticesX()[4]) > 1f);

		Model model = pose(scaled);
		for (int v = 0; v < 8; v++)
		{
			assertEquals("x of vertex " + v, rx[v] * 64 / 128f, model.getVerticesX()[v], 1e-3f);
			assertEquals("y of vertex " + v, ry[v] * 96 / 128f, model.getVerticesY()[v], 1e-3f);
			assertEquals("z of vertex " + v, rz[v] * 64 / 128f, model.getVerticesZ()[v], 1e-3f);
		}
	}

	/**
	 * A blacklisted NPC is refused even when a catalog names it - one not made by the composer, which
	 * would never resolve it: never built, so never drawn, and never among the ids claimed from Retro
	 * NPC Swapper.
	 */
	@Test
	public void testABlacklistedNpcIsNeverBuiltOrClaimed()
	{
		int zukId = NpcID.INFERNO_TZKALZUK_PLACEHOLDER;
		NpcBinding binding = binding("zuk", zukId);
		ModelCatalog catalog = new ModelCatalog(
			Collections.singletonMap(zukId, new ResolvedModel("local:zuk", "local:zuk|1", binding, bundle(binding))),
			Collections.emptyList(), Collections.emptyList());

		ModelCache cache = new ModelCache();
		cache.setCatalog(catalog);

		assertFalse(cache.ensureBuilt(zukId));
		assertTrue(cache.boundNpcIds().isEmpty());

		cache.setSubstituted(zukId);
		assertNull(cache.pose(npc(zukId)));
	}

	/** Only the blacklisted id is taken out; the binding still dresses every other NPC it names. */
	@Test
	public void testABindingKeepsItsAllowedNpcs()
	{
		ModelCache cache = new ModelCache();
		NpcBinding mixed = TestBinding.of("mixed", new int[]{NpcID.INFERNO_JAD, NPC_ID}, new int[]{1}).rig(7).build();
		cache.setCatalog(TestPacks.catalogOf(bundle(mixed)));

		assertFalse(cache.ensureBuilt(NpcID.INFERNO_JAD));
		assertTrue(cache.ensureBuilt(NPC_ID));
		assertEquals(Collections.singleton(NPC_ID), cache.boundNpcIds());
	}

	/**
	 * Two packs authored apart reuse the same mesh id, rig id and sequence - the exporter hands every
	 * author the same ids for the same NPC. Each NPC must still be built and posed from its own pack
	 * alone, or one pack's mesh would be drawn, or its clip drive the other's geometry.
	 */
	@Test
	public void testPacksReusingIdsEachPoseTheirOwnModel()
	{
		int otherId = NpcID.MOLE_BABY_01;
		ModelCache cache = new ModelCache();
		cache.setCatalog(TestPacks.catalogOf(
			bundle(binding("near", NPC_ID), 128),
			bundle(binding("far", otherId), 512, mesh(1000))));

		assertTrue(cache.ensureBuilt(NPC_ID));
		assertTrue(cache.ensureBuilt(otherId));
		cache.setSubstituted(NPC_ID);
		cache.setSubstituted(otherId);

		Model near = cache.pose(npc(NPC_ID));
		Model far = cache.pose(npc(otherId));
		assertEquals("each body is its own pack's mesh", 1000, far.getVerticesX()[0] - near.getVerticesX()[0], 1e-3f);
		assertEquals("each limb moves by its own pack's clip", 512 - 128,
			far.getVerticesX()[4] - near.getVerticesX()[4], 1e-3f);
	}

	/**
	 * A new catalog only rebuilds what changed. Switching one pack or model must not rebuild every NPC
	 * on screen, while a pack read again is new geometry and is rebuilt.
	 */
	@Test
	public void testANewCatalogKeepsModelsThatDidNotChange()
	{
		AssetBundle kept = bundle(binding("kept", NPC_ID));
		AssetBundle reread = bundle(binding("reread", NpcID.MOLE_BABY_01));
		ModelCache cache = new ModelCache();
		cache.setCatalog(TestPacks.catalogOf(kept, reread));
		cache.ensureBuilt(NPC_ID);
		cache.ensureBuilt(NpcID.MOLE_BABY_01);
		cache.setSubstituted(NPC_ID);
		cache.setSubstituted(NpcID.MOLE_BABY_01);
		Model keptModel = cache.pose(npc(NPC_ID));
		Model rereadModel = cache.pose(npc(NpcID.MOLE_BABY_01));

		cache.setCatalog(TestPacks.catalogOf(kept, bundle(binding("reread", NpcID.MOLE_BABY_01))));

		assertSame("an unchanged model is kept as built", keptModel, cache.pose(npc(NPC_ID)));
		assertNull("a changed one waits to be built again", cache.pose(npc(NpcID.MOLE_BABY_01)));
		assertTrue(cache.ensureBuilt(NpcID.MOLE_BABY_01));
		assertNotSame(rereadModel, cache.pose(npc(NpcID.MOLE_BABY_01)));
	}

	/**
	 * NPCs are lit with their own formula - {@code light(64 + ambient, 850 + 5 * contrast, -30, -50,
	 * -30)}, read out of the client's NPCComposition - not the item and scenery defaults of
	 * {@code ModelData.light()}. The definition's contrast byte is multiplied by 5 as it is decoded.
	 */
	@Test
	public void testLightingUsesTheNpcFormula()
	{
		NpcBinding binding = TestBinding.of("lit", new int[]{NPC_ID}, new int[]{1})
			.rig(7)
			.scale(118, 118)
			.lighting(10, -3)
			.build();
		Model model = pose(binding);

		Mesh mesh = mesh();
		int faces = mesh.getFaceCount();
		int[] c1 = new int[faces];
		int[] c2 = new int[faces];
		int[] c3 = new int[faces];
		Lighter.light(mesh.getVerticesCount(), mesh.getVerticesX(), mesh.getVerticesY(), mesh.getVerticesZ(),
			faces, mesh.getFaceIndices1(), mesh.getFaceIndices2(), mesh.getFaceIndices3(),
			mesh.getFaceColors(), null, null,
			64 + 10, 850 + 5 * -3, -30, -50, -30,
			c1, c2, c3);

		assertArrayEquals(c1, model.getFaceColors1());
		assertArrayEquals(c2, model.getFaceColors2());
		assertArrayEquals(c3, model.getFaceColors3());
	}
}
