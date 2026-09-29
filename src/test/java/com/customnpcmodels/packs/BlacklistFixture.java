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
import com.customnpcmodels.inject.NpcBinding;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import net.runelite.api.gameval.NpcID;

/**
 * Writes a pack that binds a blacklisted NPC, for checking in game that the plugin refuses it - which
 * {@code generateAssets} never lets anyone build. It takes the development bundle's models and adds
 * TzKal-Zuk to their NPCs:
 *
 * <pre>
 * ./gradlew generateAssets -PassetsDir=assets-dev -Pdev
 * ./gradlew writeBlacklistFixture
 * </pre>
 *
 * <p>then import {@code build/fixtures/blacklisted-pack} with the side panel's Import pack button.
 */
public final class BlacklistFixture
{
	private static final String DEV_RESOURCE = "/com/customnpcmodels/custom-assets-dev.dat";
	private static final Path OUT = Paths.get("build/fixtures/blacklisted-pack");

	private BlacklistFixture()
	{
	}

	public static void main(String[] args) throws IOException
	{
		AssetBundle dev;
		try (InputStream in = BlacklistFixture.class.getResourceAsStream(DEV_RESOURCE))
		{
			if (in == null)
			{
				System.err.println("No development bundle; run ./gradlew generateAssets -PassetsDir=assets-dev -Pdev first");
				System.exit(1);
				return;
			}
			dev = AssetCodec.read(in);
		}

		// Zuk joins the first model only: an NPC may be bound once per bundle, or the plugin refuses it
		List<NpcBinding> bindings = new ArrayList<>(dev.getBindings());
		if (bindings.isEmpty())
		{
			System.err.println("The development bundle binds no NPCs");
			System.exit(1);
			return;
		}
		NpcBinding first = bindings.get(0);
		int[] npcIds = new int[first.getNpcIds().length + 1];
		npcIds[0] = NpcID.INFERNO_TZKALZUK_PLACEHOLDER;
		System.arraycopy(first.getNpcIds(), 0, npcIds, 1, first.getNpcIds().length);
		bindings.set(0, new NpcBinding(first.getName(), npcIds, first.getMeshIds(), first.getRigId(),
			first.getScaleXZ(), first.getScaleY(), first.getRecolorFind(), first.getRecolorReplace(),
			first.getAmbient(), first.getContrast()));

		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		AssetCodec.write(new AssetBundle(dev.getMeshes(), dev.getRigs(), dev.getClips(), bindings), bytes);
		// Read back as the plugin will, so the fixture fails here rather than as a broken pack in game
		AssetCodec.read(new ByteArrayInputStream(bytes.toByteArray()));

		Files.createDirectories(OUT);
		Files.write(OUT.resolve(DirectoryPackSource.BUNDLE_FILE), bytes.toByteArray());
		Files.write(OUT.resolve(DirectoryPackSource.INFO_FILE),
			("{\"id\": \"blacklisted-fixture\", \"name\": \"Blacklist fixture\", "
				+ "\"description\": \"Binds TzKal-Zuk, which the plugin must refuse.\"}").getBytes(StandardCharsets.UTF_8));
		System.out.println("Wrote " + OUT.toAbsolutePath() + " (" + bindings.size() + " model(s), the first also binding NPC "
			+ NpcID.INFERNO_TZKALZUK_PLACEHOLDER + ")");
	}
}
