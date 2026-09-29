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

import com.customnpcmodels.inject.AssetBundle;
import java.util.ArrayList;
import java.util.List;

/** Packs built from bundles in memory, for tests that need a catalog rather than disk. */
public final class TestPacks
{
	private TestPacks()
	{
	}

	/** A local pack called {@code folder}, as if read from disk. */
	public static LoadedPack pack(String folder, AssetBundle bundle)
	{
		return LoadedPack.loaded(PackInfo.named(PackKind.LOCAL.packId(folder), PackKind.LOCAL, folder), bundle);
	}

	/** The catalog of the given bundles as local packs, the first taking priority, nothing switched off. */
	public static ModelCatalog catalogOf(AssetBundle... bundles)
	{
		List<LoadedPack> packs = new ArrayList<>();
		for (int i = 0; i < bundles.length; i++)
		{
			packs.add(pack("pack" + i, bundles[i]));
		}
		return PackComposer.compose(packs, PackSelection.DEFAULT);
	}
}
