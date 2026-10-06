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

import com.customnpcmodels.compatibility.CustomInteractHighlightOverlay;
import com.customnpcmodels.compatibility.CustomNpcOutliner;
import com.customnpcmodels.compatibility.InteractHighlightCompat;
import com.customnpcmodels.compatibility.ModelSwapProtocol;
import com.customnpcmodels.compatibility.PartnerOutlines;
import java.util.function.Function;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Model;
import net.runelite.api.NPC;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.ui.overlay.OverlayManager;

/**
 * Draws Interact Highlight's NPC outlines in its place while custom models are drawn, so the outline
 * follows the model on screen, and hands them back otherwise. Client thread only, except where said.
 * <p>
 * Retro NPC Swapper can do the same, and is told over {@link ModelSwapProtocol} whether this plugin
 * has, along with how to pose the NPCs this plugin swaps so its outlines can follow them too.
 */
@Singleton
@Slf4j
class OutlineTakeover
{
	@Inject
	private CustomNpcModelsConfig config;

	@Inject
	private ConfigManager configManager;

	@Inject
	private ClientThread clientThread;

	@Inject
	private OverlayManager overlayManager;

	@Inject
	private InteractHighlightCompat interactHighlight;

	@Inject
	private CustomInteractHighlightOverlay interactHighlightOverlay;

	@Inject
	private CustomNpcOutliner outliner;

	@Inject
	private RendererAttachment attachment;

	@Inject
	private EventBus eventBus;

	@Inject
	private PartnerOutlines partnerOutlines;

	@Inject
	private Session session;

	@Inject
	private ModelCache modelCache;

	// Whether we are currently drawing Interact Highlight's NPC outlines in its place
	private boolean takenOver;

	// What Retro was last told about takenOver, or null when it has been told nothing this start
	private Boolean posted;

	// Handed to Retro, which calls it on the client thread while it draws the outlines. The session
	// check keeps a stopped plugin from posing for it before it hears the poser was withdrawn.
	private final Function<NPC, Model> poser = npc -> session.isActive() ? modelCache.pose(npc) : null;

	/**
	 * Takes over Interact Highlight's NPC outlines, or hands them back.
	 * <p>
	 * Only worth doing while geometry is actually being substituted - with no wrapper attached the
	 * vanilla model is what gets drawn, and that plugin's own outline already fits it.
	 */
	void sync()
	{
		boolean takeOver = config.overrideInteractHighlight()
			&& attachment.isAttached()
			&& interactHighlight.isInteractHighlightActive();

		if (takeOver == takenOver)
		{
			return;
		}

		if (takeOver)
		{
			interactHighlight.suppress();
			overlayManager.add(interactHighlightOverlay);
		}
		else
		{
			interactHighlight.restore();
			overlayManager.remove(interactHighlightOverlay);
			outliner.clear();
		}

		// Last, so a failure to write config does not leave us recorded as having taken over
		takenOver = takeOver;
		publish(false);
	}

	/**
	 * Tells Retro NPC Swapper whether we draw the outlines, and how to pose our NPCs. Posted only
	 * when that changes, unless {@code always}.
	 */
	void publish(boolean always)
	{
		if (!always && posted != null && posted == takenOver)
		{
			return;
		}

		posted = takenOver;
		eventBus.post(ModelSwapProtocol.outlinesMessage(ModelSwapProtocol.SOURCE_CUSTOM_NPC_MODELS, takenOver, poser));
	}

	/**
	 * Tells Retro NPC Swapper we have let go of the outlines and the poser, as the plugin stops.
	 * Called after {@link #sync} has handed the outlines back, so Retro stashes Interact
	 * Highlight's restored settings rather than our false ones if it takes them over.
	 */
	void withdraw()
	{
		posted = null;
		eventBus.post(ModelSwapProtocol.outlinesMessage(ModelSwapProtocol.SOURCE_CUSTOM_NPC_MODELS, false, null));
		partnerOutlines.reset();
	}

	/**
	 * Finishes the handshake: Retro has answered the sync request by now if it is running, so
	 * deciding whether to take the outlines over can no longer race its answer.
	 */
	void finishHandshake()
	{
		partnerOutlines.setHandshakeDone();
		publish(true);
		sync();
	}

	/**
	 * Takes in what Retro NPC Swapper says about the outlines. Recorded straight away, so a
	 * decision made after this sees it; acted on later, on the client thread. Any thread.
	 */
	void onPartnerOutlines(ModelSwapProtocol.Outlines outlines)
	{
		partnerOutlines.accept(outlines);
		clientThread.invokeLater(this::onPartnerChanged);
	}

	/** Forgets Retro NPC Swapper's state as it stops. Any thread. */
	void onPartnerStopped()
	{
		partnerOutlines.forget();
		clientThread.invokeLater(this::onPartnerChanged);
	}

	private void onPartnerChanged()
	{
		// The carrier may still hold a model Retro posed
		outliner.clear();
		sync();
	}

	/**
	 * A session that died while suppressing left Interact Highlight's NPC outlines off. Puts them
	 * back, before the next {@link #sync} decides whether to suppress again.
	 */
	void restoreStaleStash()
	{
		interactHighlight.restoreStaleStash();
	}

	/**
	 * Starts over under a new profile. Config is per profile, so the new profile has its own
	 * Interact Highlight settings and none of the stash written under the old one. The takeover is
	 * dropped outright rather than letting {@link #sync} see no change and leave the new profile
	 * unsuppressed.
	 */
	void restartForProfile()
	{
		interactHighlight.forget();
		overlayManager.remove(interactHighlightOverlay);
		outliner.clear();
		takenOver = false;

		interactHighlight.restoreStaleStash();
		sync();
		// sync() sees no change when the new profile is not taken over either, but Retro may still
		// think we draw the outlines
		publish(false);
	}

	/**
	 * Hands the outlines back for good when the user turned Interact Highlight's NPC outlines back on
	 * themselves, rather than fighting over the setting. Any thread.
	 *
	 * @return whether {@code event} was that, and has been dealt with
	 */
	boolean handleUserOverride(ConfigChanged event)
	{
		if (!interactHighlight.isUserOverride(event))
		{
			return false;
		}

		log.debug("Interact Highlight NPC outlines re-enabled by the user; turning the fix off");
		// Deferred rather than done here: standing down writes config, and doing that from
		// inside a ConfigChanged dispatch would post a nested one
		final String changedKey = event.getKey();
		clientThread.invoke(() ->
		{
			// optOut() has to clear the suppression before the write below, or the
			// ConfigChanged it posts comes back through sync() into restore(),
			// which would put the stash back over the value the user just chose.
			interactHighlight.optOut(changedKey);

			configManager.setConfiguration(CustomNpcModelsConfig.GROUP,
				CustomNpcModelsConfig.OVERRIDE_INTERACT_HIGHLIGHT, false);
			sync();
		});
		return true;
	}

	/** Whether {@code plugin} is Interact Highlight, whose restart undoes the takeover. Any thread. */
	boolean isInteractHighlight(Plugin plugin)
	{
		return interactHighlight.isInteractHighlightPlugin(plugin);
	}
}
