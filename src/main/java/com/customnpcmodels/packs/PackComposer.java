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

import com.customnpcmodels.inject.NpcBinding;
import com.customnpcmodels.inject.SwapBlacklist;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Decides which model every NPC id is drawn with, from every pack that loaded and what the user has
 * switched on.
 *
 * <p>Pure and cheap - it walks bindings, never geometry - so it runs again on any change of selection
 * without anything being read from disk.
 */
public final class PackComposer
{
	private PackComposer()
	{
	}

	/**
	 * Packs are taken in priority order, and the first to bind an NPC id draws it. A later pack's
	 * model for that id is recorded as a conflict rather than dropped silently, and a blacklisted id
	 * is recorded rather than resolved at all.
	 */
	public static ModelCatalog compose(List<LoadedPack> packs, PackSelection selection)
	{
		Map<Integer, ResolvedModel> models = new LinkedHashMap<>();
		List<ModelCatalog.Conflict> conflicts = new ArrayList<>();
		List<ModelCatalog.Blocked> blocked = new ArrayList<>();

		for (LoadedPack pack : selection.ordered(packs))
		{
			String packId = pack.getId();
			if (!pack.isLoaded() || !selection.isPackEnabled(packId))
			{
				continue;
			}

			for (NpcBinding binding : pack.getBundle().getBindings())
			{
				String key = modelKey(packId, binding);
				if (!selection.isModelEnabled(key))
				{
					continue;
				}

				for (int npcId : binding.getNpcIds())
				{
					if (SwapBlacklist.isBlocked(npcId))
					{
						blocked.add(new ModelCatalog.Blocked(npcId, packId, key, binding.getName(),
							SwapBlacklist.contentOf(npcId)));
						continue;
					}

					ResolvedModel winner = models.get(npcId);
					if (winner != null)
					{
						conflicts.add(new ModelCatalog.Conflict(npcId, packId, key, winner.getPackId()));
						continue;
					}

					models.put(npcId, new ResolvedModel(packId, key, binding, pack.getBundle()));
				}
			}
		}

		return new ModelCatalog(models, conflicts, blocked);
	}

	/**
	 * Identifies a model across every pack: its pack, and the first mesh it is built from. The codec
	 * refuses two bindings in one bundle starting with the same mesh, and the manifest keeps mesh ids
	 * stable across regenerations, so a model keeps its key - and whether it is switched off - through
	 * an update of its pack.
	 */
	public static String modelKey(String packId, NpcBinding binding)
	{
		return packId + "|" + binding.getMeshIds()[0];
	}
}
