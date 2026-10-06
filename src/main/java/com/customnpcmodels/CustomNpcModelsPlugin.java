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

import com.customnpcmodels.chathead.ChatheadOverlay;
import com.customnpcmodels.compatibility.InteractHighlightCompat;
import com.customnpcmodels.compatibility.InteractTargetTracker;
import com.customnpcmodels.compatibility.ModelSwapProtocol;
import com.customnpcmodels.ui.PacksPanel;
import com.google.inject.Provides;
import java.util.Set;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.NPC;
import net.runelite.api.WorldType;
import net.runelite.api.WorldView;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.NpcChanged;
import net.runelite.api.events.NpcSpawned;
import net.runelite.api.events.VarbitChanged;
import net.runelite.api.events.WorldChanged;
import net.runelite.api.gameval.VarbitID;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.events.PluginChanged;
import net.runelite.client.events.PluginMessage;
import net.runelite.client.events.ProfileChanged;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.ui.overlay.OverlayManager;
import net.runelite.client.util.ImageUtil;

@Slf4j
@PluginDescriptor(
	name = "Custom NPC Models",
	description = "Replaces NPC models and animations with custom-authored ones, drawn from a bundle of original assets.",
	tags = {"npc", "model", "animation", "custom"},
	// Must match the plugin-hub file name; it also names this plugin's data directory
	internalName = "custom-npc-models"
)
public class CustomNpcModelsPlugin extends Plugin
{
	/** The config keys the side panel writes, which change which model each NPC is drawn with. */
	private static final Set<String> SELECTION_KEYS = Set.of(CustomNpcModelsConfig.DISABLED_PACKS,
		CustomNpcModelsConfig.DISABLED_MODELS, CustomNpcModelsConfig.PACK_ORDER);

	@Inject
	private Client client;

	@Inject
	private ClientThread clientThread;

	@Inject
	private CustomNpcModelsConfig config;

	@Inject
	private ModelCache modelCache;

	@Inject
	private EventBus eventBus;

	@Inject
	private InteractTargetTracker targetTracker;

	@Inject
	private ChatheadSwapper chatheadSwapper;

	@Inject
	private ChatheadOverlay chatheadOverlay;

	@Inject
	private OverlayManager overlayManager;

	@Inject
	private RendererAttachment attachment;

	@Inject
	private OutlineTakeover outlineTakeover;

	@Inject
	private RetroClaims retroClaims;

	@Inject
	private ClientToolbar clientToolbar;

	@Inject
	private Session session;

	@Inject
	private PackLoader packLoader;

	@Inject
	private HubController hub;

	@Inject
	private PanelController panelController;

	@Inject
	private PanelBridge panelBridge;

	/** The toolbar button that opens the side panel. Made in startUp, on the EDT. */
	private NavigationButton navButton;

	@Override
	protected void startUp() throws Exception
	{
		log.info("Custom NPC Models started");
		session.start();

		// startUp runs on the EDT, which is where Swing has to be built
		PacksPanel panel = new PacksPanel(panelController);
		panelBridge.set(panel);
		navButton = NavigationButton.builder()
			.tooltip("Custom NPC Models")
			.icon(ImageUtil.loadImageResource(getClass(), "panel_icon.png"))
			.priority(8)
			.panel(panel)
			.build();
		clientToolbar.addNavigation(navButton);

		hub.start();
		// Only records the switch; nothing is fetched until the panel is opened
		panel.setHubEnabled(config.hubEnabled());

		packLoader.start(this::getPluginDirectory, this::recheckLoadedNpcs);
		chatheadSwapper.start(this::canSubstitute);
		packLoader.load();
		clientThread.invoke(() ->
		{
			outlineTakeover.restoreStaleStash();
			recheckLoadedNpcs();
			attach();
		});

		// Last, as RuneLite registers the plugin itself: the bus does not dedupe, so registering
		// before something that can throw would register it twice on the next start
		overlayManager.add(chatheadOverlay);
		eventBus.register(targetTracker);
		eventBus.register(chatheadSwapper);
	}

	@Override
	protected void shutDown() throws Exception
	{
		log.info("Custom NPC Models stopped");
		session.stop();
		eventBus.unregister(targetTracker);
		eventBus.unregister(chatheadSwapper);
		overlayManager.remove(chatheadOverlay);

		if (navButton != null)
		{
			clientToolbar.removeNavigation(navButton);
			navButton = null;
		}
		// The panel goes with its button; nothing holds it, so its snapshot of packs goes too
		panelBridge.set(null);
		hub.stop();

		// Straight away rather than on the client thread, so Retro NPC Swapper takes these NPCs back
		// even if the queued work below never runs. Nothing is computed, so any thread will do.
		retroClaims.withdraw();

		clientThread.invoke(() ->
		{
			// A dialogue left open shows the NPC's own head again
			chatheadSwapper.stop();
			attachment.detach();
			// Nothing is being swapped anymore, so Interact Highlight's own outlines are correct again
			outlineTakeover.sync();
			outlineTakeover.withdraw();
			modelCache.clear();
			packLoader.clear();
			retroClaims.clear();
		});
	}

	@Provides
	CustomNpcModelsConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(CustomNpcModelsConfig.class);
	}

	@Subscribe
	public void onConfigChanged(ConfigChanged event)
	{
		if (outlineTakeover.handleUserOverride(event))
		{
			return;
		}

		if (!CustomNpcModelsConfig.GROUP.equals(event.getGroup()) || InteractHighlightCompat.isStashKey(event.getKey()))
		{
			// Stash keys are our own bookkeeping, not a setting the user changed
			return;
		}

		if (CustomNpcModelsConfig.HUB_ENABLED.equals(event.getKey()))
		{
			hub.onToggled();
			return;
		}

		if (SELECTION_KEYS.contains(event.getKey()))
		{
			// Which packs and models are on: decide again which model each NPC is drawn with. That
			// sweeps the scene itself, so the generic recheck below is not needed on top.
			packLoader.requestRecompose();
			return;
		}

		clientThread.invoke(() ->
		{
			recheckLoadedNpcs();
			outlineTakeover.sync();
		});
	}

	@Subscribe
	public void onPluginMessage(PluginMessage event)
	{
		if (ModelSwapProtocol.isSyncReq(event, ModelSwapProtocol.SOURCE_RETRO_NPC_SWAPPER))
		{
			// Retro NPC Swapper has just started and knows nothing of our claims yet. It posts from
			// its startUp, off the client thread, and working out the claims reads the client.
			clientThread.invoke(() ->
			{
				retroClaims.publish(canSubstitute(), true);
				outlineTakeover.publish(true);
			});
			return;
		}

		ModelSwapProtocol.Outlines outlines = ModelSwapProtocol.readOutlines(event, ModelSwapProtocol.SOURCE_RETRO_NPC_SWAPPER);
		if (outlines != null)
		{
			outlineTakeover.onPartnerOutlines(outlines);
		}
	}

	@Subscribe
	public void onNpcSpawned(NpcSpawned event)
	{
		// Nothing happens on despawn: the substitution memo is keyed by NPC id, not index, and is
		// shared by every instance of that type
		processNpc(event.getNpc());
	}

	@Subscribe
	public void onNpcChanged(NpcChanged event)
	{
		// A transform gives the NPC a different id, which the spawn-time check never saw
		processNpc(event.getNpc());
	}

	@Subscribe
	public void onGameTick(GameTick event)
	{
		// Cheap guard: the GPU plugin and 117 HD set and clear the draw callbacks slot
		// unconditionally, so re-take it whenever we have lost it. Covers orderings PluginChanged
		// misses - including 117 HD restarting itself on a settings change, which installs a new
		// renderer without posting any plugin event. Retro NPC Swapper stacking on top leaves us
		// beneath it rather than out, which is why this looks through the chain.
		if (attachment.isLost())
		{
			attach();
		}
	}

	@Subscribe
	public void onPluginChanged(PluginChanged event)
	{
		if (event.getPlugin() == this && event.isLoaded())
		{
			// Posted from here rather than startUp, which runs before the event bus has registered
			// us, so Retro NPC Swapper's answer is heard. The handshake is finished behind that
			// answer on the client thread queue, so the takeover decision never races it.
			eventBus.post(ModelSwapProtocol.synMessage(ModelSwapProtocol.SOURCE_CUSTOM_NPC_MODELS));
			clientThread.invokeLater(outlineTakeover::finishHandshake);
		}
		else if (RendererAttachment.isRendererPlugin(event.getPlugin()))
		{
			// attach() declines on its own when no supported renderer is holding the slot
			clientThread.invoke(this::attach);
		}
		else if (outlineTakeover.isInteractHighlight(event.getPlugin()))
		{
			// Its startUp re-registers its overlay, so the suppression has to be re-applied
			clientThread.invoke(outlineTakeover::sync);
		}
		else if (RetroClaims.isRetroPlugin(event.getPlugin()))
		{
			if (event.isLoaded())
			{
				// Its hello goes out from inside its startUp, before the event bus has registered it,
				// so a reply to that alone can be posted before it is listening. This event comes after
				// the registration, so the claims sent from here are always heard.
				clientThread.invoke(() ->
				{
					retroClaims.publish(canSubstitute(), true);
					outlineTakeover.publish(true);
				});
			}
			else
			{
				outlineTakeover.onPartnerStopped();
			}
		}
	}

	@Subscribe
	public void onProfileChanged(ProfileChanged event)
	{
		clientThread.invoke(outlineTakeover::restartForProfile);
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged gameStateChanged)
	{
		if (gameStateChanged.getGameState() == GameState.LOGGED_IN)
		{
			clientThread.invoke(this::recheckLoadedNpcs);
		}
	}

	@Subscribe
	public void onVarbitChanged(VarbitChanged event)
	{
		if (event.getVarbitId() == VarbitID.INSIDE_WILDERNESS)
		{
			clientThread.invoke(this::recheckLoadedNpcs);
		}
	}

	@Subscribe
	public void onWorldChanged(WorldChanged event)
	{
		clientThread.invoke(this::recheckLoadedNpcs);
	}

	/**
	 * Whether NPCs can be drawn custom at all right now: running, attached to a renderer, switched on,
	 * and not stood down by a safety setting. Client thread only.
	 */
	private boolean canSubstitute()
	{
		return session.isActive() && attachment.isAttached() && config.enabled() && !isSafetyDisabled();
	}

	/**
	 * Whether custom models should stand down because of the safety settings (a PvP world, or
	 * inside the Wilderness).
	 */
	private boolean isSafetyDisabled()
	{
		if (config.disablePvpWorld() && client.getWorldType() != null && WorldType.isPvpWorld(client.getWorldType()))
		{
			return true;
		}
		return config.disableWilderness() && client.getVarbitValue(VarbitID.INSIDE_WILDERNESS) == 1;
	}

	/**
	 * Decides whether an NPC is drawn with its custom model, and builds that model if so.
	 * <p>
	 * No animation is touched either way. A custom model's clips are keyed by the live sequences
	 * the NPC already plays, so the client goes on driving its animations exactly as before and the
	 * substitution only changes what geometry those frames are applied to.
	 */
	private void processNpc(NPC npc)
	{
		if (npc == null)
		{
			return;
		}

		// Building here, on the client thread, keeps the draw callback to a lookup. The packs load
		// off-thread, so NPCs already on screen at startup usually come through before they land;
		// recheckLoadedNpcs picks them up once they do.
		if (!canSubstitute() || !modelCache.ensureBuilt(npc.getId()))
		{
			modelCache.clearSubstituted(npc.getId());
			return;
		}

		modelCache.setSubstituted(npc.getId());
	}

	/**
	 * Re-evaluates all currently loaded scene NPCs against the configuration.
	 *
	 * <p>Everything that can change which NPCs are drawn custom - packs landing, attaching or
	 * detaching, config, the Wilderness and world changes - comes through here, which makes it the
	 * place to tell Retro NPC Swapper about it too.
	 */
	private void recheckLoadedNpcs()
	{
		retroClaims.publish(canSubstitute(), false);

		if (client.getGameState() != GameState.LOGGED_IN)
		{
			return;
		}

		WorldView worldView = client.getTopLevelWorldView();
		if (worldView == null)
		{
			return;
		}

		for (NPC npc : worldView.npcs())
		{
			if (npc != null)
			{
				processNpc(npc);
			}
		}
	}

	/**
	 * Puts the custom draw callbacks in the renderer's slot if they are not there, and acts on a
	 * change. Must be called on the client thread.
	 */
	private void attach()
	{
		// Only on a real transition - the per-tick guard calls this repeatedly while detached, and
		// re-processing the whole scene every tick would be wasteful
		if (attachment.attach())
		{
			recheckLoadedNpcs();
			outlineTakeover.sync();
		}
	}
}
