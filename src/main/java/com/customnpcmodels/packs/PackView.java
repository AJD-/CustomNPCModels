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
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.Value;

/**
 * One pack as the side panel shows it: what it is, whether it is on, and what became of each of its
 * models. A snapshot, made on the client thread and handed to the panel whole, so the panel never
 * reads plugin state itself.
 */
@Value
public class PackView
{
	String id;
	String name;
	PackKind kind;

	/** Null when the pack does not say. */
	String author;
	String version;
	String description;

	/** Why the pack could not be read, or null when it was. */
	String error;

	boolean enabled;

	List<ModelView> models;

	/** One model in a pack. */
	@Value
	public static class ModelView
	{
		String key;
		String name;
		List<Integer> npcIds;
		boolean enabled;

		/**
		 * The content of every NPC this model binds that is on the {@link SwapBlacklist}, such as
		 * "the Inferno". Empty when none is.
		 */
		List<String> blocked;

		/** True when every NPC it binds is blacklisted, so it can never be drawn at all. */
		boolean neverDrawn;

		/** The name of the pack drawn in its place for some of its NPCs, or null. */
		String overriddenBy;
	}

	/**
	 * Every pack, highest priority first, as the panel shows it: switched on or off per the
	 * selection, and each model marked with what the catalog made of it.
	 */
	public static List<PackView> of(List<LoadedPack> packs, ModelCatalog catalog, PackSelection selection)
	{
		Map<String, String> names = new HashMap<>();
		for (LoadedPack pack : packs)
		{
			names.put(pack.getId(), pack.getInfo().getName());
		}

		Map<String, String> overriddenBy = new HashMap<>();
		for (ModelCatalog.Conflict conflict : catalog.getConflicts())
		{
			overriddenBy.putIfAbsent(conflict.getModelKey(),
				names.getOrDefault(conflict.getWinnerPackId(), conflict.getWinnerPackId()));
		}

		List<PackView> views = new ArrayList<>();
		for (LoadedPack pack : selection.ordered(packs))
		{
			PackInfo info = pack.getInfo();
			List<ModelView> models = new ArrayList<>();
			if (pack.isLoaded())
			{
				for (NpcBinding binding : pack.getBundle().getBindings())
				{
					models.add(model(pack.getId(), binding, selection, overriddenBy));
				}
			}

			views.add(new PackView(info.getId(), info.getName(), info.getKind(), info.getAuthor(), info.getVersion(),
				info.getDescription(), pack.getError(), selection.isPackEnabled(info.getId()),
				Collections.unmodifiableList(models)));
		}
		return Collections.unmodifiableList(views);
	}

	private static ModelView model(String packId, NpcBinding binding, PackSelection selection,
		Map<String, String> overriddenBy)
	{
		String key = PackComposer.modelKey(packId, binding);
		List<Integer> npcIds = new ArrayList<>();
		Map<String, Boolean> blocked = new LinkedHashMap<>();
		for (int npcId : binding.getNpcIds())
		{
			npcIds.add(npcId);
			if (SwapBlacklist.isBlocked(npcId))
			{
				blocked.put(SwapBlacklist.contentOf(npcId), true);
			}
		}

		boolean neverDrawn = npcIds.stream().allMatch(SwapBlacklist::isBlocked);
		return new ModelView(key, binding.getName(), Collections.unmodifiableList(npcIds),
			selection.isModelEnabled(key), Collections.unmodifiableList(new ArrayList<>(blocked.keySet())), neverDrawn,
			overriddenBy.get(key));
	}
}
