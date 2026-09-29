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

import com.customnpcmodels.inject.SwapBlacklist;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.Value;

/**
 * Which model every NPC id is drawn with, across every enabled pack, plus what explains the rest:
 * models another pack overrode, and models never drawn because their NPC is on the
 * {@link SwapBlacklist}.
 */
public final class ModelCatalog
{
	private static final ModelCatalog EMPTY = new ModelCatalog(
		Collections.emptyMap(), Collections.emptyList(), Collections.emptyList());

	private final Map<Integer, ResolvedModel> models;
	private final List<Conflict> conflicts;
	private final List<Blocked> blocked;

	public ModelCatalog(Map<Integer, ResolvedModel> models, List<Conflict> conflicts, List<Blocked> blocked)
	{
		this.models = Collections.unmodifiableMap(new LinkedHashMap<>(models));
		this.conflicts = Collections.unmodifiableList(new ArrayList<>(conflicts));
		this.blocked = Collections.unmodifiableList(new ArrayList<>(blocked));
	}

	public static ModelCatalog empty()
	{
		return EMPTY;
	}

	/** The model an NPC id is drawn with, or null when it has none. */
	public ResolvedModel get(int npcId)
	{
		return models.get(npcId);
	}

	public Set<Integer> npcIds()
	{
		return models.keySet();
	}

	public List<Conflict> getConflicts()
	{
		return conflicts;
	}

	public List<Blocked> getBlocked()
	{
		return blocked;
	}

	/**
	 * This catalog with every blacklisted NPC moved from its models to {@link #getBlocked()}, or this
	 * catalog itself when it has none.
	 */
	public ModelCatalog withoutBlacklisted()
	{
		if (models.keySet().stream().noneMatch(SwapBlacklist::isBlocked))
		{
			return this;
		}

		Map<Integer, ResolvedModel> kept = new LinkedHashMap<>();
		List<Blocked> dropped = new ArrayList<>(blocked);
		for (Map.Entry<Integer, ResolvedModel> entry : models.entrySet())
		{
			int npcId = entry.getKey();
			ResolvedModel model = entry.getValue();
			if (SwapBlacklist.isBlocked(npcId))
			{
				dropped.add(new Blocked(npcId, model.getPackId(), model.getModelKey(),
					model.getBinding().getName(), SwapBlacklist.contentOf(npcId)));
			}
			else
			{
				kept.put(npcId, model);
			}
		}
		return new ModelCatalog(kept, conflicts, dropped);
	}

	@Override
	public String toString()
	{
		return "ModelCatalog{npcs=" + models.size() + ", conflicts=" + conflicts.size()
			+ ", blocked=" + blocked.size() + "}";
	}

	/** A model that would have drawn an NPC id, had a pack ahead of it not bound that id first. */
	@Value
	public static class Conflict
	{
		int npcId;
		String packId;
		String modelKey;

		/** The pack whose model is drawn instead. */
		String winnerPackId;
	}

	/** A model binding an NPC on the {@link SwapBlacklist}, which is therefore never drawn with it. */
	@Value
	public static class Blocked
	{
		int npcId;
		String packId;
		String modelKey;
		String modelName;

		/** The content the NPC belongs to, such as "the Inferno". */
		String content;
	}
}
