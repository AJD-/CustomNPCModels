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
import java.util.Collections;
import java.util.Comparator;
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

	public boolean isPackEnabled(String packId)
	{
		return !disabledPacks.contains(packId);
	}

	public boolean isModelEnabled(String modelKey)
	{
		return !disabledModels.contains(modelKey);
	}

	/**
	 * {@code packs}, highest priority first: those {@link #order} names, in that order, then the rest
	 * by kind - dev, then hub and local, then built-in - keeping their given order within a kind.
	 */
	public List<LoadedPack> ordered(List<LoadedPack> packs)
	{
		List<LoadedPack> sorted = new ArrayList<>(packs);
		sorted.sort(Comparator
			.comparingInt((LoadedPack pack) -> rank(pack.getId()))
			.thenComparingInt(pack -> kindRank(pack.getInfo().getKind())));
		return sorted;
	}

	private int rank(String packId)
	{
		int index = order.indexOf(packId);
		return index == -1 ? Integer.MAX_VALUE : index;
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
