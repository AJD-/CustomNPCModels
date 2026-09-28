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
package com.customnpcmodels.packs;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import com.customnpcmodels.inject.AssetBundle;
import com.customnpcmodels.inject.NpcBinding;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import net.runelite.api.gameval.NpcID;
import org.junit.Test;

/** What the side panel is told about each pack and model. */
public class PackViewTest
{
	private static NpcBinding binding(String name, int meshId, int... npcIds)
	{
		return new NpcBinding(name, npcIds, new int[]{meshId}, NpcBinding.STATIC, 128, 128, null, null, 0, 0);
	}

	private static LoadedPack pack(String folder, NpcBinding... bindings)
	{
		return TestPacks.pack(folder, new AssetBundle(Collections.emptyMap(), Collections.emptyMap(),
			Collections.emptyList(), Arrays.asList(bindings)));
	}

	private static List<PackView> views(List<LoadedPack> packs, PackSelection selection)
	{
		return PackView.of(packs, PackComposer.compose(packs, selection), selection);
	}

	@Test
	public void testPacksAreShownInPriorityOrderWithTheirSwitches()
	{
		List<LoadedPack> packs = Arrays.asList(pack("first", binding("a", 1, NpcID.MOLE_GIANT)),
			pack("second", binding("b", 1, NpcID.MOLE_BABY_01)));
		PackSelection selection = new PackSelection(Collections.singleton("local:first"),
			Collections.singleton("local:second|1"), Collections.singletonList("local:second"));

		List<PackView> views = views(packs, selection);

		assertEquals("local:second", views.get(0).getId());
		assertTrue(views.get(0).isEnabled());
		assertFalse("switched off, yet still listed so it can be switched back on", views.get(1).isEnabled());
		assertFalse(views.get(0).getModels().get(0).isEnabled());
	}

	@Test
	public void testAModelAnotherPackOverridesSaysWhich()
	{
		List<LoadedPack> packs = Arrays.asList(pack("first", binding("a", 1, NpcID.MOLE_GIANT)),
			pack("second", binding("b", 1, NpcID.MOLE_GIANT)));

		List<PackView> views = views(packs, PackSelection.DEFAULT);

		assertNull(views.get(0).getModels().get(0).getOverriddenBy());
		assertEquals("first", views.get(1).getModels().get(0).getOverriddenBy());

		// Switching the winner off leaves nothing overriding the other
		PackSelection firstOff = new PackSelection(Collections.singleton("local:first"), Collections.emptySet(),
			Collections.emptyList());
		assertNull(views(packs, firstOff).get(1).getModels().get(0).getOverriddenBy());
	}

	@Test
	public void testABlacklistedModelSaysWhy()
	{
		List<LoadedPack> packs = Collections.singletonList(pack("mixed",
			binding("zuk", 1, NpcID.INFERNO_TZKALZUK_PLACEHOLDER),
			binding("jad and mole", 2, NpcID.INFERNO_JAD, NpcID.MOLE_GIANT)));

		List<PackView.ModelView> models = views(packs, PackSelection.DEFAULT).get(0).getModels();

		assertTrue(models.get(0).isNeverDrawn());
		assertEquals(Collections.singletonList("the Inferno"), models.get(0).getBlocked());
		assertFalse("its other NPC is still drawn", models.get(1).isNeverDrawn());
		assertEquals(Collections.singletonList("the Inferno"), models.get(1).getBlocked());
	}

	@Test
	public void testAPackThatFailedShowsItsError()
	{
		LoadedPack broken = LoadedPack.failed(PackInfo.named("local:broken", PackKind.LOCAL, "broken"), "Not in GZIP format");

		PackView view = views(Collections.singletonList(broken), PackSelection.DEFAULT).get(0);

		assertEquals("Not in GZIP format", view.getError());
		assertTrue(view.getModels().isEmpty());
	}
}
