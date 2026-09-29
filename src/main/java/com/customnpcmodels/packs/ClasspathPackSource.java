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

import com.customnpcmodels.inject.AssetCodec;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads the two packs that live on the classpath: the one shipped inside the plugin jar, and the
 * development bundle when one is present. Either is simply absent when its resource is.
 * <p>
 * The development bundle only ever exists on the test classpath - the generator writes it to
 * {@code src/test/resources}, which is gitignored - so {@code ./gradlew run} sees it and the Hub jar,
 * built from the main sourceSet alone, never can. It carries round-trip verification assets exported
 * from the live cache, which must not ship. It takes priority over every other pack by default.
 */
public final class ClasspathPackSource
{
	private static final String RESOURCE = "/com/customnpcmodels/custom-assets.dat";
	private static final String DEV_RESOURCE = "/com/customnpcmodels/custom-assets-dev.dat";

	/** Blocks on reading the jar, so never on the client thread. */
	public List<LoadedPack> load()
	{
		List<LoadedPack> packs = new ArrayList<>();
		read(DEV_RESOURCE, PackInfo.named(PackKind.DEV.packId(null), PackKind.DEV, "Development bundle"), packs);
		read(RESOURCE, PackInfo.named(PackKind.BUILTIN.packId(null), PackKind.BUILTIN, "Custom NPC Models"), packs);
		return packs;
	}

	private static void read(String resource, PackInfo info, List<LoadedPack> packs)
	{
		try (InputStream in = ClasspathPackSource.class.getResourceAsStream(resource))
		{
			if (in != null)
			{
				packs.add(LoadedPack.loaded(info, AssetCodec.read(in)));
			}
		}
		catch (IOException | RuntimeException ex)
		{
			packs.add(LoadedPack.failed(info, ex));
		}
	}
}
