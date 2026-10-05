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
import com.customnpcmodels.inject.AssetCodec;
import com.customnpcmodels.inject.Mesh;
import com.customnpcmodels.inject.NpcBinding;
import com.customnpcmodels.inject.TestBinding;
import com.customnpcmodels.inject.TestMesh;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import net.runelite.api.gameval.NpcID;

/** Packs built from bundles in memory, for tests that need a catalog rather than disk. */
public final class TestPacks
{
	private TestPacks()
	{
	}

	/** A valid bundle file: one triangle, drawn for the Giant Mole. */
	public static byte[] bundleBytes() throws IOException
	{
		Mesh triangle = new TestMesh()
			.id(1_005_779)
			.vx(new float[]{0, 10, 0})
			.vy(new float[]{0, 0, 10})
			.vz(new float[]{0, 0, 0})
			.i1(new int[]{0})
			.i2(new int[]{1})
			.i3(new int[]{2})
			.colors(new short[]{(short) 0x3A05})
			.groups(null)
			.build();
		NpcBinding binding = TestBinding.of("Mole", new int[]{NpcID.MOLE_GIANT}, new int[]{1_005_779}).build();
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		AssetCodec.write(new AssetBundle(Collections.singletonMap(1_005_779, triangle), Collections.emptyMap(),
			Collections.emptyList(), Collections.singletonList(binding)), out);
		return out.toByteArray();
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
