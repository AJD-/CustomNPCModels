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
import java.util.function.Function;
import net.runelite.api.Model;
import net.runelite.api.NPC;
import net.runelite.client.events.PluginMessage;

/**
 * The messages this plugin and Retro NPC Swapper exchange over the event bus so both can run at once.
 * <p>
 * This plugin posts a {@code claims} message naming every NPC id it is currently drawing a custom
 * model for, and Retro NPC Swapper leaves those NPCs alone entirely - no retro model and no retro
 * animations, since this plugin's clips are keyed to the live sequences.
 * <p>
 * Both plugins can draw Interact Highlight's NPC outlines in its place, and only one may at a time:
 * two would each turn that plugin's settings off and stash the other's {@code false} as the user's
 * choice. Each posts an {@code outlines} message saying whether it has taken the outlines over, and
 * the first to do so keeps them until it posts that it has let go. The message also carries a
 * poser, so the one drawing can outline the NPCs the other swaps around the model on screen. The
 * one drawing posts {@code outlinesOptOut} when the user turns Interact Highlight's own outlines back
 * on, and the other turns its fix off too rather than taking the outlines over again.
 * <p>
 * Each plugin posts {@code modelSyncReq} once it is listening, which the other answers by posting
 * its claims and outlines again, so start order does not matter. A plugin hears its own messages, so
 * every read names the partner it expects them from.
 * <p>
 * The two plugins are loaded by different classloaders, so everything in a message is a JDK type or
 * a RuneLite API type, which both see through the client's classloader. A copy of this class lives in
 * each plugin and the two must stay in step; {@link #VERSION} is bumped on any incompatible change,
 * and a message of another version is ignored.
 */
public final class ModelSwapProtocol
{
	public static final String NAMESPACE = "npc-model-swap";
	public static final int VERSION = 1;

	/** Posted by Custom NPC Models: the NPC ids it draws, which Retro NPC Swapper must leave alone. */
	public static final String CLAIMS = "claims";

	/** Posted by either plugin once it is listening, asking for the other's current state. */
	public static final String SYN = "modelSyncReq";

	/** Posted by either plugin: whether it draws Interact Highlight's NPC outlines, and its poser. */
	public static final String OUTLINES = "outlines";

	/** Posted by the plugin drawing the outlines when the user turned Interact Highlight's back on. */
	public static final String OUTLINES_OPT_OUT = "outlinesOptOut";

	public static final String SOURCE_CUSTOM_NPC_MODELS = "custom-npc-models";
	public static final String SOURCE_RETRO_NPC_SWAPPER = "retro-npc-swapper";

	static final String KEY_VERSION = "version";
	static final String KEY_SOURCE = "source";
	static final String KEY_NPC_IDS = "npcIds";
	static final String KEY_OWNING = "owning";
	static final String KEY_POSER = "poser";

	private ModelSwapProtocol()
	{
	}

	/**
	 * A partner's outlines state: whether it draws Interact Highlight's NPC outlines, and how to pose
	 * the NPCs it swaps.
	 */
	public static final class Outlines
	{
		private final boolean owning;
		private final Function<NPC, Model> poser;

		Outlines(boolean owning, Function<NPC, Model> poser)
		{
			this.owning = owning;
			this.poser = poser;
		}

		/** Whether the partner has taken Interact Highlight's NPC outlines over. */
		public boolean isOwning()
		{
			return owning;
		}

		/**
		 * The model the partner draws an NPC with, or null when it draws none for it. Null when the
		 * partner has withdrawn it. Client thread only, and the model is overwritten by the partner's
		 * next pose, so it has to be used straight away.
		 */
		public Function<NPC, Model> getPoser()
		{
			return poser;
		}
	}

	/**
	 * The claims message naming {@code npcIds}. The set in it is a copy and cannot be modified.
	 */
	public static PluginMessage claimsMessage(Set<Integer> npcIds)
	{
		Map<String, Object> data = header(SOURCE_CUSTOM_NPC_MODELS);
		data.put(KEY_NPC_IDS, Set.copyOf(npcIds));
		return new PluginMessage(NAMESPACE, CLAIMS, data);
	}

	/**
	 * The message a plugin posts once it is listening, from {@code source}.
	 */
	public static PluginMessage synMessage(String source)
	{
		return new PluginMessage(NAMESPACE, SYN, header(source));
	}

	/**
	 * The outlines message from {@code source}. A null {@code poser} withdraws the one sent before.
	 */
	public static PluginMessage outlinesMessage(String source, boolean owning, Function<NPC, Model> poser)
	{
		Map<String, Object> data = header(source);
		data.put(KEY_OWNING, owning);
		if (poser != null)
		{
			data.put(KEY_POSER, poser);
		}
		return new PluginMessage(NAMESPACE, OUTLINES, data);
	}

	/**
	 * The opt-out message from {@code source}.
	 */
	public static PluginMessage optOutMessage(String source)
	{
		return new PluginMessage(NAMESPACE, OUTLINES_OPT_OUT, header(source));
	}

	/**
	 * Whether {@code message} is {@code fromSource} asking for this plugin's state, in a version
	 * this plugin speaks.
	 */
	public static boolean isSyncReq(PluginMessage message, String fromSource)
	{
		return isFrom(message, SYN, fromSource);
	}

	/**
	 * Whether {@code message} is {@code fromSource} saying the user turned Interact Highlight's NPC
	 * outlines back on.
	 */
	public static boolean isOptOut(PluginMessage message, String fromSource)
	{
		return isFrom(message, OUTLINES_OPT_OUT, fromSource);
	}

	/**
	 * The NPC ids in a claims message from Custom NPC Models, or null when {@code message} is
	 * anything else.
	 */
	public static Set<Integer> readClaims(PluginMessage message)
	{
		if (!isFrom(message, CLAIMS, SOURCE_CUSTOM_NPC_MODELS))
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

	/**
	 * The outlines state in an outlines message from {@code fromSource}, or null when
	 * {@code message} is anything else.
	 */
	@SuppressWarnings("unchecked")
	public static Outlines readOutlines(PluginMessage message, String fromSource)
	{
		if (!isFrom(message, OUTLINES, fromSource))
		{
			return null;
		}

		Object owning = message.getData().get(KEY_OWNING);
		if (!(owning instanceof Boolean))
		{
			return null;
		}

		// Generics are erased, so Function is as far as this can check; the partner is the only
		// sender and puts nothing else there
		Object poser = message.getData().get(KEY_POSER);
		return new Outlines((Boolean) owning, poser instanceof Function ? (Function<NPC, Model>) poser : null);
	}

	private static Map<String, Object> header(String source)
	{
		Map<String, Object> data = new HashMap<>();
		data.put(KEY_VERSION, VERSION);
		data.put(KEY_SOURCE, source);
		return data;
	}

	private static boolean isFrom(PluginMessage message, String name, String source)
	{
		return message != null
			&& NAMESPACE.equals(message.getNamespace())
			&& name.equals(message.getName())
			&& message.getData() != null
			&& Integer.valueOf(VERSION).equals(message.getData().get(KEY_VERSION))
			&& source.equals(message.getData().get(KEY_SOURCE));
	}
}
