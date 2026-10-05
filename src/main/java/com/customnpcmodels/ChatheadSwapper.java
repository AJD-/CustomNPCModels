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
package com.customnpcmodels;

import com.customnpcmodels.inject.NpcBinding;
import java.util.HashMap;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.IntUnaryOperator;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.Value;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.NPC;
import net.runelite.api.NPCComposition;
import net.runelite.api.WorldView;
import net.runelite.api.events.BeforeRender;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;
import net.runelite.api.widgets.WidgetModelType;
import net.runelite.client.eventbus.Subscribe;

/**
 * Shows the chathead a swapped NPC's pack picked for it in dialogue, in place of the NPC's own.
 * <p>
 * The dialogue head is a widget the client draws from the cache, so a pack's own geometry can't be
 * put in it. What a pack can do is name a cache NPC to borrow the head of, and the widget is pointed
 * at that NPC. It keeps its animation, the emote for the line, since humanoid chatheads share the
 * joints emotes move.
 * <p>
 * Checked every frame rather than on a script event, so a page that opens with the NPC's own head is
 * swapped before it is first drawn, and every new page, which sets the head again, is swapped too.
 * Client thread only.
 */
@Slf4j
@Singleton
class ChatheadSwapper
{
	@Inject
	private Client client;

	@Inject
	private ModelCache modelCache;

	@Inject
	private CustomNpcModelsConfig config;

	private BooleanSupplier canSubstitute = () -> false;

	/** The swap the head is showing, or null while it shows what the client gave it. */
	private Swap shown;

	/** Whether each chathead NPC named so far has a head the client can draw, looked up once. */
	private final Map<Integer, Boolean> drawable = new HashMap<>();

	/** What the head showed before a swap, and what it was swapped to. */
	@Value
	static class Swap
	{
		int type;
		int id;
		int speaker;
		int chathead;
	}

	/** What the head should show, and the swap that leaves it showing, if any. */
	@Value
	static class Head
	{
		int type;
		int id;
		Swap shown;
	}

	/** Swaps only while custom models are drawn at all, which the plugin decides. */
	void start(BooleanSupplier canSubstitute)
	{
		this.canSubstitute = canSubstitute;
	}

	/** Puts back the head a dialogue still open would have shown, and forgets everything. */
	void stop()
	{
		Widget head = client.getWidget(InterfaceID.ChatLeft.HEAD);
		if (head != null && shown != null && head.getModelType() == WidgetModelType.NPC_CHATHEAD
			&& head.getModelId() == shown.getChathead())
		{
			head.setModelType(shown.getType());
			head.setModelId(shown.getId());
		}
		shown = null;
		drawable.clear();
		canSubstitute = () -> false;
	}

	@Subscribe
	public void onBeforeRender(BeforeRender event)
	{
		Widget head = client.getWidget(InterfaceID.ChatLeft.HEAD);
		if (head == null || head.isHidden())
		{
			shown = null;
			return;
		}

		int type = head.getModelType();
		int id = head.getModelId();
		Head next = decide(type, id, shown, this::speakerAt, this::chatheadFor);
		shown = next.getShown();
		if (next.getType() != type || next.getId() != id)
		{
			head.setModelType(next.getType());
			head.setModelId(next.getId());
		}
	}

	/**
	 * What a dialogue head showing {@code type} and {@code id} should show instead.
	 * <p>
	 * A head still showing the last swap keeps it while the speaker's model still names that chathead,
	 * and goes back to what it was once it doesn't (the model switched off, the setting turned off,
	 * a safety setting stepping in). Anything else the head shows was set by the client, so it is
	 * looked at afresh: an NPC's own head, by id or by the index of the NPC in the scene, is swapped
	 * when its model names a chathead, and every other kind of head is left alone.
	 *
	 * @param speakerAt   the NPC id at a scene index, or -1 when there is none
	 * @param chatheadFor the chathead to show for an NPC id, or {@link NpcBinding#NO_CHATHEAD}
	 */
	static Head decide(int type, int id, Swap shown, IntUnaryOperator speakerAt, IntUnaryOperator chatheadFor)
	{
		if (shown != null && type == WidgetModelType.NPC_CHATHEAD && id == shown.getChathead())
		{
			int chathead = chatheadFor.applyAsInt(shown.getSpeaker());
			if (chathead == shown.getChathead())
			{
				return new Head(type, id, shown);
			}
			if (chathead == NpcBinding.NO_CHATHEAD)
			{
				return new Head(shown.getType(), shown.getId(), null);
			}
			return new Head(WidgetModelType.NPC_CHATHEAD, chathead,
				new Swap(shown.getType(), shown.getId(), shown.getSpeaker(), chathead));
		}

		int speaker = type == WidgetModelType.NPC_CHATHEAD ? id
			: type == WidgetModelType.NPC_INDEX_CHATHEAD ? speakerAt.applyAsInt(id)
			: -1;
		int chathead = speaker < 0 ? NpcBinding.NO_CHATHEAD : chatheadFor.applyAsInt(speaker);
		if (chathead == NpcBinding.NO_CHATHEAD || (type == WidgetModelType.NPC_CHATHEAD && id == chathead))
		{
			return new Head(type, id, null);
		}
		return new Head(WidgetModelType.NPC_CHATHEAD, chathead, new Swap(type, id, speaker, chathead));
	}

	private int speakerAt(int index)
	{
		WorldView worldView = client.getTopLevelWorldView();
		NPC npc = worldView == null ? null : worldView.npcs().byIndex(index);
		return npc == null ? -1 : npc.getId();
	}

	private int chatheadFor(int npcId)
	{
		if (!config.swapChatheads() || !canSubstitute.getAsBoolean())
		{
			return NpcBinding.NO_CHATHEAD;
		}
		int chathead = modelCache.chatheadFor(npcId);
		if (chathead == NpcBinding.NO_CHATHEAD)
		{
			return NpcBinding.NO_CHATHEAD;
		}
		return drawable.computeIfAbsent(chathead, this::hasChathead) ? chathead : NpcBinding.NO_CHATHEAD;
	}

	/**
	 * Whether the client has a head to draw for an NPC. The generator checks this against the cache it
	 * built with, but the game's cache can have moved on since.
	 */
	private boolean hasChathead(int npcId)
	{
		NPCComposition npc = client.getNpcDefinition(npcId);
		boolean has = npc != null && npc.getChatheadModels() != null && npc.getChatheadModels().length > 0;
		if (!has)
		{
			log.debug("Chathead NPC {} has no chathead in the game's cache; NPCs naming it keep their own", npcId);
		}
		return has;
	}
}
