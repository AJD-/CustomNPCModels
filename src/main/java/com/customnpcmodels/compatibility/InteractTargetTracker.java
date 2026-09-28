/*
 * Copyright (c) 2021, Adam <Adam@sigterm.info>
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

import javax.inject.Inject;
import javax.inject.Singleton;

import com.customnpcmodels.CustomNpcModelsPlugin;
import lombok.Getter;
import net.runelite.api.Actor;
import net.runelite.api.Client;
import net.runelite.api.MenuAction;
import net.runelite.api.NPC;
import net.runelite.api.Player;
import net.runelite.api.events.InteractingChanged;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.WidgetUtil;

/**
 * Tracks which actor the local player is interacting with, so an outline can be drawn around it.
 * <p>
 * This mirrors the actor half of RuneLite's Interact Highlight plugin, whose own state is
 * package private and cannot be read from here. Only the actor half is reproduced: while this
 * plugin draws NPC outlines, that plugin is still the one drawing objects, ground items and
 * players, with its own code and its own state.
 * <p>
 * Events are forwarded from {@link CustomNpcModelsPlugin} rather than subscribed to here - the
 * event bus registers the plugin, not the objects it injects.
 */
@Singleton
public class InteractTargetTracker
{
	@Inject
	private Client client;

	// May hold a Player. Kept rather than discarded so that clicking a player while already
	// fighting an NPC clears the NPC outline, which is what Interact Highlight does.
	private Actor interactedActor;

	@Getter
	private boolean attacked;

	private int clickTick;

	@Getter
	private int gameCycle;

	public void onMenuOptionClicked(MenuOptionClicked event)
	{
		switch (event.getMenuAction())
		{
			case WIDGET_TARGET_ON_NPC:
			case NPC_FIRST_OPTION:
			case NPC_SECOND_OPTION:
			case NPC_THIRD_OPTION:
			case NPC_FOURTH_OPTION:
			case NPC_FIFTH_OPTION:
			{
				interactedActor = event.getMenuEntry().getNpc();
				attacked = event.getMenuAction() == MenuAction.NPC_SECOND_OPTION
					|| event.getMenuAction() == MenuAction.WIDGET_TARGET_ON_NPC
						&& client.getSelectedWidget() != null
						&& WidgetUtil.componentToInterface(client.getSelectedWidget().getId()) == InterfaceID.MAGIC_SPELLBOOK;
				clickTick = client.getTickCount();
				gameCycle = client.getGameCycle();
				break;
			}
			case WIDGET_TARGET_ON_PLAYER:
			case PLAYER_FIRST_OPTION:
			case PLAYER_SECOND_OPTION:
			case PLAYER_THIRD_OPTION:
			case PLAYER_FOURTH_OPTION:
			case PLAYER_FIFTH_OPTION:
			case PLAYER_SIXTH_OPTION:
			case PLAYER_SEVENTH_OPTION:
			case PLAYER_EIGHTH_OPTION:
			{
				interactedActor = event.getMenuEntry().getPlayer();
				attacked = false;
				clickTick = client.getTickCount();
				gameCycle = client.getGameCycle();
				break;
			}
			// Any click that ends the interaction. Object and ground item clicks land here too:
			// they end ours, and Interact Highlight goes on tracking them for its own outlines.
			case WIDGET_TARGET_ON_GAME_OBJECT:
			case GAME_OBJECT_FIRST_OPTION:
			case GAME_OBJECT_SECOND_OPTION:
			case GAME_OBJECT_THIRD_OPTION:
			case GAME_OBJECT_FOURTH_OPTION:
			case GAME_OBJECT_FIFTH_OPTION:
			case WIDGET_TARGET_ON_GROUND_ITEM:
			case GROUND_ITEM_FIRST_OPTION:
			case GROUND_ITEM_SECOND_OPTION:
			case GROUND_ITEM_THIRD_OPTION:
			case GROUND_ITEM_FOURTH_OPTION:
			case GROUND_ITEM_FIFTH_OPTION:
			case WIDGET_TARGET_ON_WIDGET:
			case WALK:
				interactedActor = null;
				break;
			default:
				if (event.isItemOp())
				{
					interactedActor = null;
				}
		}
	}

	public void onGameTick()
	{
		if (client.getTickCount() > clickTick && client.getLocalDestinationLocation() == null)
		{
			// The destination has been reached, so the interaction we were tracking is over
			interactedActor = null;
		}
	}

	public void onInteractingChanged(InteractingChanged event)
	{
		if (event.getSource() == client.getLocalPlayer()
			&& client.getTickCount() > clickTick && event.getTarget() != interactedActor)
		{
			interactedActor = null;
			attacked = event.getTarget() != null && event.getTarget().getCombatLevel() > 0;
		}
	}

	public void onActorDespawned(Actor actor)
	{
		if (actor == interactedActor)
		{
			interactedActor = null;
		}
	}

	public void reset()
	{
		interactedActor = null;
		attacked = false;
	}

	/**
	 * The actor being interacted with, which may be an {@link NPC} or a {@link Player}, or null.
	 */
	Actor getInteractedTarget()
	{
		if (interactedActor != null)
		{
			return interactedActor;
		}

		Player local = client.getLocalPlayer();
		return local != null ? local.getInteracting() : null;
	}
}
