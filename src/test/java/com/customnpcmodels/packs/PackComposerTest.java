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
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import com.customnpcmodels.inject.AssetBundle;
import com.customnpcmodels.inject.NpcBinding;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import net.runelite.api.gameval.NpcID;
import org.junit.Test;

public class PackComposerTest
{
	private static final int MOLE = NpcID.MOLE_GIANT;
	private static final int BABY = NpcID.MOLE_BABY_01;

	/** A bundle binding one model, on mesh {@code meshId}, to {@code npcIds}. The composer reads no geometry. */
	private static AssetBundle bundle(int meshId, int... npcIds)
	{
		NpcBinding binding = new NpcBinding("model " + meshId, npcIds, new int[]{meshId}, NpcBinding.STATIC,
			128, 128, null, null, 0, 0);
		return new AssetBundle(Collections.emptyMap(), Collections.emptyMap(), Collections.emptyList(),
			Collections.singletonList(binding));
	}

	private static LoadedPack pack(PackKind kind, String folder, AssetBundle bundle)
	{
		return LoadedPack.loaded(PackInfo.named(kind.packId(folder), kind, folder), bundle);
	}

	private static PackSelection selection(List<String> disabledPacks, List<String> disabledModels, List<String> order)
	{
		return new PackSelection(new java.util.HashSet<>(disabledPacks), new java.util.HashSet<>(disabledModels), order);
	}

	@Test
	public void testTheFirstPackToBindAnNpcDrawsIt()
	{
		LoadedPack first = pack(PackKind.LOCAL, "first", bundle(1, MOLE));
		LoadedPack second = pack(PackKind.LOCAL, "second", bundle(1, MOLE, BABY));

		ModelCatalog catalog = PackComposer.compose(Arrays.asList(first, second), PackSelection.DEFAULT);

		assertEquals("local:first", catalog.get(MOLE).getPackId());
		assertEquals("an id only the second binds is still drawn from it", "local:second", catalog.get(BABY).getPackId());
		assertEquals(1, catalog.getConflicts().size());
		ModelCatalog.Conflict conflict = catalog.getConflicts().get(0);
		assertEquals(MOLE, conflict.getNpcId());
		assertEquals("local:second", conflict.getPackId());
		assertEquals("local:first", conflict.getWinnerPackId());
	}

	@Test
	public void testTheUsersOrderDecidesWhichPackWins()
	{
		LoadedPack first = pack(PackKind.LOCAL, "first", bundle(1, MOLE));
		LoadedPack second = pack(PackKind.LOCAL, "second", bundle(1, MOLE));

		ModelCatalog catalog = PackComposer.compose(Arrays.asList(first, second),
			selection(Collections.emptyList(), Collections.emptyList(), Collections.singletonList("local:second")));

		assertEquals("local:second", catalog.get(MOLE).getPackId());
	}

	/** Dev first, so a model being worked on shows over the release; the shipped pack last. */
	@Test
	public void testByDefaultDevWinsAndBuiltInLoses()
	{
		LoadedPack builtin = pack(PackKind.BUILTIN, null, bundle(1, MOLE));
		LoadedPack local = pack(PackKind.LOCAL, "local", bundle(1, MOLE));
		LoadedPack dev = pack(PackKind.DEV, null, bundle(1, MOLE));

		assertEquals("dev", PackComposer.compose(Arrays.asList(builtin, local, dev), PackSelection.DEFAULT)
			.get(MOLE).getPackId());
		assertEquals("local:local", PackComposer.compose(Arrays.asList(builtin, local), PackSelection.DEFAULT)
			.get(MOLE).getPackId());
	}

	/**
	 * Reordering writes every pack's id, built-in included. A pack added after that is not named, and
	 * must still land by kind - a new local pack above the built-in one - not below everything.
	 */
	@Test
	public void testAPackAddedAfterReorderingKeepsItsKindsPlace()
	{
		LoadedPack builtin = pack(PackKind.BUILTIN, null, bundle(1, MOLE));
		LoadedPack first = pack(PackKind.LOCAL, "first", bundle(1, MOLE));
		LoadedPack added = pack(PackKind.LOCAL, "added", bundle(1, MOLE));
		PackSelection reordered = selection(Collections.emptyList(), Collections.emptyList(),
			Arrays.asList("local:first", "builtin"));

		List<LoadedPack> ordered = reordered.ordered(Arrays.asList(builtin, first, added));

		assertEquals(Arrays.asList("local:first", "local:added", "builtin"),
			Arrays.asList(ordered.get(0).getId(), ordered.get(1).getId(), ordered.get(2).getId()));

		// And a user who put built-in first keeps it there, with the new pack after their other one
		PackSelection builtinFirst = selection(Collections.emptyList(), Collections.emptyList(),
			Arrays.asList("builtin", "local:first"));
		List<LoadedPack> kept = builtinFirst.ordered(Arrays.asList(builtin, first, added));
		assertEquals(Arrays.asList("builtin", "local:first", "local:added"),
			Arrays.asList(kept.get(0).getId(), kept.get(1).getId(), kept.get(2).getId()));

		// With none of its kind or earlier named, it goes to the top
		PackSelection onlyBuiltin = selection(Collections.emptyList(), Collections.emptyList(),
			Collections.singletonList("builtin"));
		assertEquals("local:added", onlyBuiltin.ordered(Arrays.asList(builtin, added)).get(0).getId());
	}

	@Test
	public void testSwitchedOffPacksAndModelsAreSkipped()
	{
		LoadedPack first = pack(PackKind.LOCAL, "first", bundle(1, MOLE));
		LoadedPack second = pack(PackKind.LOCAL, "second", bundle(2, MOLE));
		List<LoadedPack> packs = Arrays.asList(first, second);

		assertEquals("local:second", PackComposer.compose(packs,
			selection(Collections.singletonList("local:first"), Collections.emptyList(), Collections.emptyList()))
			.get(MOLE).getPackId());

		ModelCatalog none = PackComposer.compose(packs, selection(Collections.singletonList("local:first"),
			Collections.singletonList("local:second|2"), Collections.emptyList()));
		assertNull(none.get(MOLE));
		assertTrue("a model switched off is not a conflict", none.getConflicts().isEmpty());
	}

	@Test
	public void testBlacklistedNpcsAreRecordedButNeverResolved()
	{
		LoadedPack pack = pack(PackKind.LOCAL, "zuk", bundle(1, NpcID.INFERNO_TZKALZUK_PLACEHOLDER, MOLE));

		ModelCatalog catalog = PackComposer.compose(Collections.singletonList(pack), PackSelection.DEFAULT);

		assertNull(catalog.get(NpcID.INFERNO_TZKALZUK_PLACEHOLDER));
		assertEquals("local:zuk", catalog.get(MOLE).getPackId());
		assertEquals(1, catalog.getBlocked().size());
		assertEquals("the Inferno", catalog.getBlocked().get(0).getContent());
	}

	@Test
	public void testAPackThatFailedToReadIsSkipped()
	{
		LoadedPack failed = LoadedPack.failed(PackInfo.named("local:broken", PackKind.LOCAL, "broken"), "corrupt");
		LoadedPack good = pack(PackKind.LOCAL, "good", bundle(1, MOLE));

		ModelCatalog catalog = PackComposer.compose(Arrays.asList(failed, good), PackSelection.DEFAULT);

		assertEquals("local:good", catalog.get(MOLE).getPackId());
	}

	/** A local pack named like a hub pack must not share its switch or its priority. */
	@Test
	public void testPacksOfDifferentKindsNeverShareAnId()
	{
		assertNotEquals(PackKind.HUB.packId("goblins"), PackKind.LOCAL.packId("goblins"));
		assertNotEquals(PackKind.LOCAL.packId("builtin"), PackKind.BUILTIN.packId(null));
		assertEquals("local:goblins|1005779", PackComposer.modelKey(PackKind.LOCAL.packId("goblins"),
			bundle(1_005_779, MOLE).getBindings().get(0)));
	}
}
