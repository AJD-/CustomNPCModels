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
package com.customnpcmodels.authoring;

import com.customnpcmodels.cache.CacheFiles;
import java.io.IOException;
import java.util.LinkedHashSet;
import java.util.Set;
import net.runelite.cache.NpcManager;
import net.runelite.cache.definitions.NpcDefinition;
import net.runelite.cache.fs.Store;

/**
 * The command line side of the authoring tools: the {@code -P} options build.gradle passes on as
 * {@code customnpcmodels.*} system properties, and giving up with a message. For main methods only,
 * as giving up ends the JVM.
 */
final class ToolCli
{
	private static final String PREFIX = "customnpcmodels.";

	private ToolCli()
	{
	}

	/** A {@code -P} option, trimmed, or null when it was not given or is blank. */
	static String option(String name)
	{
		String value = System.getProperty(PREFIX + name);
		return value == null || value.isBlank() ? null : value.trim();
	}

	/** A {@code -P} option the tool cannot run without. Prints {@code usage} and exits when it is missing. */
	static String required(String name, String usage)
	{
		String value = option(name);
		if (value == null)
		{
			throw exit("Usage: " + usage);
		}
		return value;
	}

	/** Comma-separated sequence ids, in the order given; empty when {@code arg} is null. */
	static Set<Integer> sequenceIds(String arg)
	{
		Set<Integer> ids = new LinkedHashSet<>();
		if (arg != null)
		{
			for (String id : arg.split(","))
			{
				ids.add(Integer.parseInt(id.trim()));
			}
		}
		return ids;
	}

	/** The live cache. Exits when there is none, saying {@code why} the tool needs one. */
	static Store liveCache(String why) throws IOException
	{
		Store store = CacheFiles.openLiveCache();
		if (store == null)
		{
			throw exit(why + "; pass one with -PcacheDir=<path>");
		}
		return store;
	}

	/** The NPC with id {@code idArg}. Exits when there is none, or it has no models. */
	static NpcDefinition npc(Store store, String idArg) throws IOException
	{
		NpcManager npcs = new NpcManager(store);
		npcs.load();
		NpcDefinition npc = npcs.get(Integer.parseInt(idArg));
		if (npc == null || npc.models == null)
		{
			throw exit("No NPC with id " + idArg + ", or it has no models");
		}
		return npc;
	}

	/**
	 * Prints {@code message} and ends the tool with a failure. Never returns; it is declared to
	 * return an exception so callers can write {@code throw exit(...)} and the compiler knows.
	 */
	static RuntimeException exit(String message)
	{
		System.err.println(message);
		System.exit(1);
		return new IllegalStateException(message);
	}
}
