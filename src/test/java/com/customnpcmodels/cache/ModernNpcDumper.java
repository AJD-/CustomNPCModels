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
package com.customnpcmodels.cache;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import net.runelite.cache.NpcManager;
import net.runelite.cache.definitions.ModelDefinition;
import net.runelite.cache.definitions.NpcDefinition;
import net.runelite.cache.definitions.SequenceDefinition;
import net.runelite.cache.fs.Store;

/**
 * Dev-only tool that prints NPC definitions from the live OSRS cache.
 *
 * <p>What an author needs before binding a custom model: the NPC ids to bind, the model parts and
 * scale the vanilla NPC is drawn with, and the sequences it plays - an authored clip has to be keyed
 * to one of those, and sampled at its frame count. For each model part it also says whether any
 * face is textured, which the glTF path cannot carry.
 *
 * <p>Run with {@code ./gradlew dumpNpcDefinitions -Pnpc=5779} (ids, or a name substring), and
 * optionally {@code -PcacheDir=...}. Test sourceSet only, never shipped, so console output and
 * reading files outside {@code .runelite} are fine here.
 */
public class ModernNpcDumper
{
	private static final String MAX_MATCHES_PROPERTY = "customnpcmodels.maxMatches";

	/** Guard against a broad name search printing thousands of definitions. Raise with -Pmax=N. */
	private static final int DEFAULT_MAX_NAME_MATCHES = 40;

	public static void main(String[] args) throws IOException
	{
		try (Store store = CacheFiles.openLiveCache())
		{
			if (store == null)
			{
				System.err.println("Could not find an OSRS cache; pass one with -PcacheDir=<path>");
				System.exit(1);
				return;
			}

			NpcManager npcManager = new NpcManager(store);
			npcManager.load();

			System.out.println("NPC definitions: " + npcManager.getNpcs().size());
			System.out.println();

			if (args.length == 0)
			{
				System.out.println("Usage: ./gradlew dumpNpcDefinitions -Pnpc=5779,5780");
				System.out.println("       ./gradlew dumpNpcDefinitions -Pnpc=\"giant mole\"");
				return;
			}

			int maxMatches = maxMatches();
			for (String arg : args)
			{
				dump(store, npcManager, arg.trim(), maxMatches);
			}
		}
	}

	private static int maxMatches()
	{
		String configured = System.getProperty(MAX_MATCHES_PROPERTY);
		if (configured == null || configured.isEmpty())
		{
			return DEFAULT_MAX_NAME_MATCHES;
		}

		try
		{
			return Math.max(1, Integer.parseInt(configured.trim()));
		}
		catch (NumberFormatException ex)
		{
			System.err.println("Ignoring non-numeric -Pmax=" + configured);
			return DEFAULT_MAX_NAME_MATCHES;
		}
	}

	/**
	 * Prints every definition matching a numeric id or a case-insensitive name substring.
	 */
	private static void dump(Store store, NpcManager npcManager, String query, int maxMatches)
		throws IOException
	{
		if (query.isEmpty())
		{
			return;
		}

		List<NpcDefinition> matches = new ArrayList<>();
		if (query.chars().allMatch(Character::isDigit))
		{
			NpcDefinition def = npcManager.get(Integer.parseInt(query));
			if (def == null)
			{
				System.out.println("No NPC with id " + query);
				return;
			}
			matches.add(def);
		}
		else
		{
			String needle = query.toLowerCase(Locale.ROOT);
			for (NpcDefinition def : npcManager.getNpcs())
			{
				if (def.name != null && def.name.toLowerCase(Locale.ROOT).contains(needle))
				{
					matches.add(def);
				}
			}

			if (matches.isEmpty())
			{
				System.out.println("No NPC name contains '" + query + "'");
				return;
			}
		}

		int shown = Math.min(matches.size(), maxMatches);
		for (int i = 0; i < shown; i++)
		{
			print(store, matches.get(i));
		}

		if (matches.size() > shown)
		{
			System.out.println("... and " + (matches.size() - shown) + " more matches for '" + query + "'");
			System.out.println();
		}
	}

	private static void print(Store store, NpcDefinition def) throws IOException
	{
		System.out.println(def.name + " (id " + def.id + ")");
		System.out.println("  models        " + Arrays.toString(def.models));
		System.out.println("  widthScale    " + def.widthScale);
		System.out.println("  heightScale   " + def.heightScale);
		System.out.println("  ambient       " + def.ambient);
		System.out.println("  contrast      " + def.contrast);
		System.out.println("  size          " + def.size);
		System.out.println("  combatLevel   " + def.combatLevel);
		System.out.println("  standingAnim  " + sequence(store, def.standingAnimation));
		System.out.println("  walkingAnim   " + sequence(store, def.walkingAnimation));
		if (def.recolorToFind != null)
		{
			System.out.println("  recolor       " + Arrays.toString(def.recolorToFind)
				+ " -> " + Arrays.toString(def.recolorToReplace));
		}
		if (def.configs != null)
		{
			System.out.println("  transforms    " + Arrays.toString(def.configs));
		}

		if (def.models != null)
		{
			for (int modelId : def.models)
			{
				System.out.println("  model " + modelId + "   " + describeModel(store, modelId));
			}
		}
		System.out.println();
	}

	private static String sequence(Store store, int sequenceId) throws IOException
	{
		if (sequenceId == -1)
		{
			return "-1";
		}

		SequenceDefinition sequence = CacheFiles.loadSequence(store, sequenceId);
		if (sequence == null)
		{
			return sequenceId + " (not in cache)";
		}
		if (sequence.frameIDs == null)
		{
			return sequenceId + " (skeletal, no frame list)";
		}
		return sequenceId + " (" + sequence.frameIDs.length + " frames)";
	}

	private static String describeModel(Store store, int modelId)
	{
		ModelDefinition model = CacheFiles.decodeModel(store, modelId);
		if (model == null)
		{
			return "does not decode";
		}

		int textured = 0;
		if (model.faceTextures != null)
		{
			for (short texture : model.faceTextures)
			{
				if (texture != -1)
				{
					textured++;
				}
			}
		}

		boolean complexMapping = false;
		if (model.textureRenderTypes != null)
		{
			for (byte type : model.textureRenderTypes)
			{
				complexMapping |= type != 0;
			}
		}

		return model.vertexCount + " verts, " + model.faceCount + " faces, "
			+ textured + " textured faces"
			+ (complexMapping ? ", non-simple texture mapping" : "")
			+ (model.faceTransparencies != null ? ", has transparencies" : "")
			+ (model.faceRenderTypes != null ? ", has render types" : "");
	}
}
