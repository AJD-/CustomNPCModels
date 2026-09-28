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

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import lombok.Value;

/**
 * Which packs and models the user has switched off, and the order packs take priority in.
 *
 * <p>Held as what is switched off rather than on, so a pack or model the user has never seen is on.
 */
@Value
public class PackSelection
{
	public static final PackSelection DEFAULT = new PackSelection(
		Collections.emptySet(), Collections.emptySet(), Collections.emptyList());

	Set<String> disabledPacks;

	/** Model keys, as {@link PackComposer#modelKey} makes them. */
	Set<String> disabledModels;

	/** Pack ids, highest priority first. Packs not named here follow, in their default order. */
	List<String> order;

	/**
	 * A selection as stored: three comma-separated lists, any of them null or blank. No pack id or
	 * model key can hold a comma - folder names are refused one - so nothing needs escaping.
	 */
	public static PackSelection parse(String disabledPacks, String disabledModels, String order)
	{
		return new PackSelection(new LinkedHashSet<>(split(disabledPacks)), new LinkedHashSet<>(split(disabledModels)),
			split(order));
	}

	/** The entries of a stored list, blanks and repeats dropped. */
	public static List<String> split(String csv)
	{
		List<String> entries = new ArrayList<>();
		if (csv == null)
		{
			return entries;
		}
		for (String entry : csv.split(","))
		{
			String trimmed = entry.trim();
			if (!trimmed.isEmpty() && !entries.contains(trimmed))
			{
				entries.add(trimmed);
			}
		}
		return entries;
	}

	/** A list as stored, for {@link #split}. */
	public static String join(Collection<String> entries)
	{
		return String.join(",", entries);
	}

	public boolean isPackEnabled(String packId)
	{
		return !disabledPacks.contains(packId);
	}

	public boolean isModelEnabled(String modelKey)
	{
		return !disabledModels.contains(modelKey);
	}

	/**
	 * {@code packs}, highest priority first: those {@link #order} names, in that order, with any pack
	 * it does not name - one added since the user last reordered - slotted in by kind, just after the
	 * last named pack of its own kind or an earlier one. Kinds go dev, then hub and local, then
	 * built-in, so a new local pack still outranks the one inside the plugin unless the user has put
	 * that above their other packs.
	 */
	public List<LoadedPack> ordered(List<LoadedPack> packs)
	{
		// The default order: by kind, keeping the given order within a kind
		List<LoadedPack> byKind = new ArrayList<>(packs);
		byKind.sort(Comparator.comparingInt(pack -> kindRank(pack.getInfo().getKind())));

		List<LoadedPack> sorted = new ArrayList<>();
		for (String id : order)
		{
			byKind.stream().filter(pack -> pack.getId().equals(id)).findFirst().ifPresent(sorted::add);
		}

		for (LoadedPack pack : byKind)
		{
			if (sorted.contains(pack))
			{
				continue;
			}

			int rank = kindRank(pack.getInfo().getKind());
			int at = 0;
			for (int i = 0; i < sorted.size(); i++)
			{
				if (kindRank(sorted.get(i).getInfo().getKind()) <= rank)
				{
					at = i + 1;
				}
			}
			sorted.add(at, pack);
		}
		return sorted;
	}

	private static int kindRank(PackKind kind)
	{
		switch (kind)
		{
			case DEV:
				return 0;
			case BUILTIN:
				return 2;
			default:
				return 1;
		}
	}
}
