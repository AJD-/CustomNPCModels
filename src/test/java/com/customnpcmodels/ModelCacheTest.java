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
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import com.customnpcmodels.inject.AssetBundle;
import com.customnpcmodels.inject.Clip;
import com.customnpcmodels.inject.Lighter;
import com.customnpcmodels.inject.Mesh;
import com.customnpcmodels.inject.NpcBinding;
import com.customnpcmodels.inject.Rig;
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
		return new Mesh(1, 0,
			new float[]{0, 128, 0, 0, 200, 328, 200, 200},
			new float[]{0, 0, -128, 0, 0, 0, -128, 0},
			new float[]{0, 0, 0, 128, 0, 0, 0, 128},
			new int[]{0, 0, 0, 1, 4, 4, 4, 5},
			new int[]{2, 1, 3, 2, 6, 5, 7, 6},
			new int[]{1, 3, 2, 3, 5, 7, 6, 7},
			new short[]{(short) 0x3A40, (short) 0x3A40, (short) 0x1240, (short) 0x1240,
				(short) 0x3A40, (short) 0x3A40, (short) 0x1240, (short) 0x1240},
			null, null, null, null, null, null, null, null,
			new int[][]{{0, 1, 2, 3}, {4, 5, 6, 7}});
	}

	/** Translate group 1 along X by a tile, then turn it a quarter about its own centroid. */
	private static AssetBundle bundle(NpcBinding binding)
	{
		Map<Integer, Mesh> meshes = new LinkedHashMap<>();
		meshes.put(1, mesh());
		Map<Integer, Rig> rigs = new LinkedHashMap<>();
		rigs.put(7, new Rig(7, new int[]{1, 0, 2}, new int[][]{{1}, {1}, {1}}));
		Map<Integer, Clip> clips = new LinkedHashMap<>();
		clips.put(SEQUENCE, new Clip(SEQUENCE, 7,
			new int[][]{{0, 1, 2}},
			new int[][]{{128, 0, 0}},
			new int[][]{{0, 0, 64}},
			new int[][]{{0, 0, 0}}));
		return new AssetBundle(meshes, rigs, clips, Collections.singletonList(binding));
	}

	private static Model pose(NpcBinding binding)
	{
		ModelCache cache = new ModelCache();
		cache.setBundle(bundle(binding));
		assertTrue(cache.ensureBuilt(NPC_ID));
		cache.setSubstituted(NPC_ID);

		NPC npc = mock(NPC.class);
		when(npc.getId()).thenReturn(NPC_ID);
		when(npc.getAnimation()).thenReturn(-1);
		when(npc.getPoseAnimation()).thenReturn(SEQUENCE);
		when(npc.getPoseAnimationFrame()).thenReturn(0);
		return cache.pose(npc);
	}

	/**
	 * The client resizes an NPC after posing it, so a translation in a clip is scaled along with
	 * everything else. Resizing the rest mesh first and posing it after would leave every translated
	 * limb short of - or past - the body it is attached to: the gaps seen on the Giant Mole.
	 */
	@Test
	public void testTheResizeIsAppliedAfterThePose()
	{
		NpcBinding unscaled = new NpcBinding("unscaled", new int[]{NPC_ID}, new int[]{1}, 128, 128, null, null);
		NpcBinding scaled = new NpcBinding("scaled", new int[]{NPC_ID}, new int[]{1}, 64, 96, null, null);

		Model reference = pose(unscaled);
		float[] rx = reference.getVerticesX().clone();
		float[] ry = reference.getVerticesY().clone();
		float[] rz = reference.getVerticesZ().clone();

		Model model = pose(scaled);
		for (int v = 0; v < 8; v++)
		{
			assertEquals("x of vertex " + v, rx[v] * 64 / 128f, model.getVerticesX()[v], 1e-3f);
			assertEquals("y of vertex " + v, ry[v] * 96 / 128f, model.getVerticesY()[v], 1e-3f);
			assertEquals("z of vertex " + v, rz[v] * 64 / 128f, model.getVerticesZ()[v], 1e-3f);
		}
	}

	/**
	 * A blacklisted NPC is refused wherever the bundle came from: never built, so never drawn, and
	 * never among the ids claimed from Retro NPC Swapper.
	 */
	@Test
	public void testABlacklistedNpcIsNeverBuiltOrClaimed()
	{
		ModelCache cache = new ModelCache();
		cache.setBundle(bundle(new NpcBinding("zuk", new int[]{NpcID.INFERNO_TZKALZUK_PLACEHOLDER},
			new int[]{1}, 128, 128, null, null)));

		assertFalse(cache.ensureBuilt(NpcID.INFERNO_TZKALZUK_PLACEHOLDER));
		assertTrue(cache.boundNpcIds().isEmpty());

		cache.setSubstituted(NpcID.INFERNO_TZKALZUK_PLACEHOLDER);
		NPC zuk = mock(NPC.class);
		when(zuk.getId()).thenReturn(NpcID.INFERNO_TZKALZUK_PLACEHOLDER);
		assertNull(cache.pose(zuk));
	}

	/** Only the blacklisted id is taken out; the binding still dresses every other NPC it names. */
	@Test
	public void testABindingKeepsItsAllowedNpcs()
	{
		ModelCache cache = new ModelCache();
		cache.setBundle(bundle(new NpcBinding("mixed", new int[]{NpcID.INFERNO_JAD, NPC_ID},
			new int[]{1}, 128, 128, null, null)));

		assertFalse(cache.ensureBuilt(NpcID.INFERNO_JAD));
		assertTrue(cache.ensureBuilt(NPC_ID));
		assertEquals(Collections.singleton(NPC_ID), cache.boundNpcIds());
	}

	/**
	 * NPCs are lit with their own formula - {@code light(64 + ambient, 850 + 5 * contrast, -30, -50,
	 * -30)}, read out of the client's NPCComposition - not the item and scenery defaults of
	 * {@code ModelData.light()}. The definition's contrast byte is multiplied by 5 as it is decoded.
	 */
	@Test
	public void testLightingUsesTheNpcFormula()
	{
		NpcBinding binding = new NpcBinding("lit", new int[]{NPC_ID}, new int[]{1}, 118, 118, null, null, 10, -3);
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
