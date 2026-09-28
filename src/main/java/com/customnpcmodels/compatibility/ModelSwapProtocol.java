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
package com.customnpcmodels.compatibility;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import net.runelite.client.events.PluginMessage;

/**
 * The messages this plugin and Retro NPC Swapper exchange over the event bus so both can run at once.
 * <p>
 * This plugin posts a {@code claims} message naming every NPC id it is currently drawing a custom
 * model for, and Retro NPC Swapper leaves those NPCs alone entirely - no retro model and no retro
 * animations, since this plugin's clips are keyed to the live sequences. Retro posts {@code hello}
 * when it starts, which is answered by posting the claims again, so start order does not matter.
 * <p>
 * The two plugins are loaded by different classloaders, so everything in a message is a JDK type.
 * A copy of this class lives in each plugin and the two must stay in step; {@link #VERSION} is
 * bumped on any incompatible change, and a message of another version is ignored.
 */
public final class ModelSwapProtocol
{
	public static final String NAMESPACE = "npc-model-swap";
	public static final int VERSION = 1;

	/** Posted by this plugin: the NPC ids it draws, which the partner must leave alone. */
	public static final String CLAIMS = "claims";

	/** Posted by a partner on start, asking for the current claims. */
	public static final String SYN = "modelSyncReq";

	public static final String SOURCE_CUSTOM_NPC_MODELS = "custom-npc-models";
	public static final String SOURCE_RETRO_NPC_SWAPPER = "retro-npc-swapper";

	static final String KEY_VERSION = "version";
	static final String KEY_SOURCE = "source";
	static final String KEY_NPC_IDS = "npcIds";

	private ModelSwapProtocol()
	{
	}

	/**
	 * The claims message naming {@code npcIds}. The set in it is a copy and cannot be modified.
	 */
	public static PluginMessage claimsMessage(Set<Integer> npcIds)
	{
		Map<String, Object> data = new HashMap<>();
		data.put(KEY_VERSION, VERSION);
		data.put(KEY_SOURCE, SOURCE_CUSTOM_NPC_MODELS);
		data.put(KEY_NPC_IDS, Set.copyOf(npcIds));
		return new PluginMessage(NAMESPACE, CLAIMS, data);
	}

	/**
	 * The hello message a plugin posts on start, from {@code source}.
	 */
	public static PluginMessage synMessage(String source)
	{
		Map<String, Object> data = new HashMap<>();
		data.put(KEY_VERSION, VERSION);
		data.put(KEY_SOURCE, source);
		return new PluginMessage(NAMESPACE, SYN, data);
	}

	/**
	 * Whether {@code message} is a partner's {@code hello} in a version this plugin speaks.
	 */
	public static boolean isSyncReq(PluginMessage message)
	{
		return isOurs(message, SYN);
	}

	/**
	 * The NPC ids in a claims message from this plugin, or null when {@code message} is anything else.
	 */
	public static Set<Integer> readClaims(PluginMessage message)
	{
		if (!isOurs(message, CLAIMS) || !SOURCE_CUSTOM_NPC_MODELS.equals(message.getData().get(KEY_SOURCE)))
		{
			return null;
		}

		Object ids = message.getData().get(KEY_NPC_IDS);
		if (!(ids instanceof Collection))
		{
			return null;
		}

		Set<Integer> claims = new HashSet<>();
		for (Object id : (Collection<?>) ids)
		{
			if (id instanceof Integer)
			{
				claims.add((Integer) id);
			}
		}
		return claims;
	}

	private static boolean isOurs(PluginMessage message, String name)
	{
		return message != null
			&& NAMESPACE.equals(message.getNamespace())
			&& name.equals(message.getName())
			&& message.getData() != null
			&& Integer.valueOf(VERSION).equals(message.getData().get(KEY_VERSION));
	}
}
