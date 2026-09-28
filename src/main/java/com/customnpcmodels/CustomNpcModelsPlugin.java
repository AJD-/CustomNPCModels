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
import com.customnpcmodels.compatibility.InteractTargetTracker;
import com.customnpcmodels.compatibility.ModelSwapProtocol;
import com.customnpcmodels.compatibility.RendererChain;
import com.customnpcmodels.inject.AssetBundle;
import com.customnpcmodels.inject.AssetSource;
import com.customnpcmodels.inject.ClasspathAssetSource;
import com.google.inject.Provides;
import java.io.IOException;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Model;
import net.runelite.api.NPC;
import net.runelite.api.WorldType;
import net.runelite.api.WorldView;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.InteractingChanged;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.api.events.NpcChanged;
import net.runelite.api.events.NpcDespawned;
import net.runelite.api.events.NpcSpawned;
import net.runelite.api.events.PlayerDespawned;
import net.runelite.api.events.VarbitChanged;
import net.runelite.api.events.WorldChanged;
import net.runelite.api.gameval.VarbitID;
import net.runelite.api.hooks.DrawCallbacks;
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
import net.runelite.client.plugins.PluginManager;
import net.runelite.client.plugins.gpu.GpuPlugin;
import net.runelite.client.ui.overlay.OverlayManager;

@Slf4j
@PluginDescriptor(
	name = "Custom NPC Models",
	description = "Replaces NPC models and animations with custom-authored ones, drawn from a bundle of original assets.",
	tags = {"npc", "model", "animation", "custom"}
)
public class CustomNpcModelsPlugin extends Plugin
{
	/** The renderer 117 HD registers by default; its legacy renderer lives in a sibling package. */
	private static final String HD_ZONE_RENDERER_PACKAGE = "rs117.hd.renderer.zone.";

	private static final String HD_PLUGIN_CLASS = "rs117.hd.HdPlugin";

	/** Another Hub plugin, so recognized by class name alone like 117 HD. */
	private static final String RETRO_PLUGIN_CLASS = "com.retronpcswapper.RetroNpcSwapperPlugin";

	@Inject
	private Client client;

	@Inject
	private ClientThread clientThread;

	@Inject
	private CustomNpcModelsConfig config;

	@Inject
	private ConfigManager configManager;

	@Inject
	private PluginManager pluginManager;

	@Inject
	private ModelCache modelCache;

	@Inject
	private ScheduledExecutorService executor;

	@Inject
	private OverlayManager overlayManager;

	@Inject
	private InteractHighlightCompat interactHighlight;

	@Inject
	private CustomInteractHighlightOverlay interactHighlightOverlay;

	@Inject
	private InteractTargetTracker targetTracker;

	@Inject
	private CustomNpcOutliner outliner;

	@Inject
	private EventBus eventBus;

	/** Where custom models come from. Swappable so the delivery mechanism can change later. */
	private final AssetSource assetSource = new ClasspathAssetSource();

	// Our decorator, while it is in the client's draw callbacks chain
	private CustomDrawCallbacks wrapper;

	// The NPC ids last claimed to Retro NPC Swapper, so an unchanged set is not posted again.
	// Client thread only.
	private Set<Integer> postedClaims = Collections.emptySet();

	// Class of the last renderer attach() declined, so the decline is logged once rather than per tick
	private String declinedHost;

	// Resolved once - the plugin list does not change identity, and attach() is polled per tick
	private Plugin gpuPlugin;

	// Whether we are currently drawing Interact Highlight's NPC outlines in its place
	private boolean outlineTakeover;

	/**
	 * The in-flight bundle read, so shutDown can cancel it.
	 *
	 * <p>Volatile because startUp and shutDown are not guaranteed to be the same thread.
	 */
	private volatile Future<?> bundleLoad;

	/**
	 * Whether this plugin is still running, read by anything coming back from another thread.
	 *
	 * <p>Cancelling a task cannot stop one already past its own read, nor a {@code clientThread}
	 * runnable it has already queued, so this flag is what actually closes the window - cancel only
	 * keeps a task that never started from starting.
	 */
	private volatile boolean active;

	@Override
	protected void startUp() throws Exception
	{
		log.info("Custom NPC Models started");
		active = true;
		loadAssetBundle();
		clientThread.invoke(() ->
		{
			// A session that died while suppressing left Interact Highlight's NPC outlines off.
			// Put them back before attach() decides whether to suppress again.
			interactHighlight.restoreStaleStash();
			recheckLoadedNpcs();
			attach();
		});
	}

	@Override
	protected void shutDown() throws Exception
	{
		log.info("Custom NPC Models stopped");
		active = false;

		// The executor is RuneLite's own and is not ours to shut down, but the read we put on it is
		Future<?> load = bundleLoad;
		if (load != null)
		{
			load.cancel(false);
			bundleLoad = null;
		}

		// Straight away rather than on the client thread, so Retro NPC Swapper takes these NPCs back
		// even if the queued work below never runs. Nothing is computed, so any thread will do.
		eventBus.post(ModelSwapProtocol.claimsMessage(Collections.emptySet()));

		clientThread.invoke(() ->
		{
			// detach() stands the Interact Highlight takeover down as part of dropping the wrapper
			detach();
			modelCache.clear();
			postedClaims = Collections.emptySet();
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
		if (interactHighlight.isUserOverride(event))
		{
			// The user turned Interact Highlight's NPC outlines back on themselves. Hand them back
			// rather than fighting over the setting.
			log.debug("Interact Highlight NPC outlines re-enabled by the user; turning the fix off");
			// Deferred rather than done here: standing down writes config, and doing that from
			// inside a ConfigChanged dispatch would post a nested one
			final String changedKey = event.getKey();
			clientThread.invoke(() ->
			{
				// optOut() has to clear the suppression before the write below, or the
				// ConfigChanged it posts comes back through syncInteractHighlight() into restore(),
				// which would put the stash back over the value the user just chose.
				interactHighlight.optOut(changedKey);

				configManager.setConfiguration(CustomNpcModelsConfig.GROUP,
					CustomNpcModelsConfig.OVERRIDE_INTERACT_HIGHLIGHT, false);
				syncInteractHighlight();
			});
			return;
		}

		if (!CustomNpcModelsConfig.GROUP.equals(event.getGroup()) || InteractHighlightCompat.isStashKey(event.getKey()))
		{
			// Stash keys are our own bookkeeping, not a setting the user changed
			return;
		}

		clientThread.invoke(() ->
		{
			recheckLoadedNpcs();
			syncInteractHighlight();
		});
	}

	@Subscribe
	public void onPluginMessage(PluginMessage event)
	{
		if (ModelSwapProtocol.isSyncReq(event))
		{
			// Retro NPC Swapper has just started and knows nothing of our claims yet. It posts from
			// its startUp, off the client thread, and working out the claims reads the client.
			clientThread.invoke(() -> publishClaims(true));
		}
	}

	@Subscribe
	public void onNpcSpawned(NpcSpawned event)
	{
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
		targetTracker.onGameTick();

		// Cheap guard: the GPU plugin and 117 HD set and clear the draw callbacks slot
		// unconditionally, so re-take it whenever we have lost it. Covers orderings PluginChanged
		// misses - including 117 HD restarting itself on a settings change, which installs a new
		// renderer without posting any plugin event. Retro NPC Swapper stacking on top leaves us
		// beneath it rather than out, which is why this looks through the chain.
		if (wrapper == null || !RendererChain.contains(client.getDrawCallbacks(), wrapper))
		{
			attach();
		}
	}

	@Subscribe
	public void onPluginChanged(PluginChanged event)
	{
		if (event.getPlugin() instanceof GpuPlugin || isHdPlugin(event.getPlugin()))
		{
			// attach() declines on its own when no supported renderer is holding the slot
			clientThread.invoke(this::attach);
		}
		else if (interactHighlight.isInteractHighlightPlugin(event.getPlugin()))
		{
			// Its startUp re-registers its overlay, so the suppression has to be re-applied
			clientThread.invoke(this::syncInteractHighlight);
		}
		else if (event.isLoaded() && event.getPlugin() != null
			&& RETRO_PLUGIN_CLASS.equals(event.getPlugin().getClass().getName()))
		{
			// Its hello goes out from inside its startUp, before the event bus has registered it,
			// so a reply to that alone can be posted before it is listening. This event comes after
			// the registration, so the claims sent from here are always heard.
			clientThread.invoke(() -> publishClaims(true));
		}
	}

	@Subscribe
	public void onProfileChanged(ProfileChanged event)
	{
		// Config is per profile, so the new profile has its own Interact Highlight settings and
		// none of the stash written under the old one. Drop the takeover outright rather than
		// letting syncInteractHighlight see no change and leave the new profile unsuppressed.
		clientThread.invoke(() ->
		{
			interactHighlight.forget();
			overlayManager.remove(interactHighlightOverlay);
			outliner.clear();
			outlineTakeover = false;

			interactHighlight.restoreStaleStash();
			syncInteractHighlight();
		});
	}

	@Subscribe
	public void onMenuOptionClicked(MenuOptionClicked event)
	{
		targetTracker.onMenuOptionClicked(event);
	}

	@Subscribe
	public void onInteractingChanged(InteractingChanged event)
	{
		targetTracker.onInteractingChanged(event);
	}

	@Subscribe
	public void onPlayerDespawned(PlayerDespawned event)
	{
		targetTracker.onActorDespawned(event.getPlayer());
	}

	@Subscribe
	public void onNpcDespawned(NpcDespawned event)
	{
		// The substitution memo is keyed by NPC id, not index, and is shared by every instance of
		// that type, so a despawn leaves it alone
		targetTracker.onActorDespawned(event.getNpc());
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged gameStateChanged)
	{
		if (gameStateChanged.getGameState() == GameState.LOGGING_IN
			|| gameStateChanged.getGameState() == GameState.HOPPING)
		{
			targetTracker.reset();
		}
		else if (gameStateChanged.getGameState() == GameState.LOGGED_IN)
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

		// Building here, on the client thread, keeps the draw callback to a lookup. The bundle loads
		// off-thread, so NPCs already on screen at startup usually come through before it lands;
		// recheckLoadedNpcs picks them up once it does.
		if (wrapper == null || !config.enabled() || isSafetyDisabled() || !modelCache.ensureBuilt(npc.getId()))
		{
			modelCache.clearSubstituted(npc.getId());
			return;
		}

		modelCache.setSubstituted(npc.getId());
	}

	/**
	 * Reads the asset bundle off the client thread and publishes it back onto it.
	 *
	 * <p>Decompressing the bundle is quick, but it is still IO, and startUp must not block on it - so
	 * this is fire-and-forget. Everything downstream treats an absent bundle as "no custom models",
	 * which is why nothing has to wait for this to finish.
	 */
	private void loadAssetBundle()
	{
		bundleLoad = executor.submit(() ->
		{
			if (!active)
			{
				return;
			}

			AssetBundle bundle;
			try
			{
				bundle = assetSource.load();
			}
			catch (IOException ex)
			{
				// A bundle that exists but will not read is worth saying out loud, unlike one that is
				// simply absent - it means a stale or truncated resource
				log.warn("Could not read the custom NPC model bundle; custom models are unavailable", ex);
				return;
			}

			if (bundle.isEmpty())
			{
				log.debug("No custom NPC model bundle present");
				return;
			}

			clientThread.invoke(() ->
			{
				// A read that finishes after shutDown must not put the bundle back into a cache that
				// was just cleared, nor sweep the scene on behalf of a plugin that is no longer running
				if (!active)
				{
					log.debug("Custom NPC model bundle finished reading after shutdown; dropping it");
					return;
				}

				modelCache.setBundle(bundle);

				// NPCs are usually already on screen by the time the bundle lands, and setBundle only
				// drops what was built, it does not rebuild
				recheckLoadedNpcs();
			});
		});
	}

	/**
	 * Re-evaluates all currently loaded scene NPCs against the configuration.
	 *
	 * <p>Everything that can change which NPCs are drawn custom - the bundle landing, attaching or
	 * detaching, config, the Wilderness and world changes - comes through here, which makes it the
	 * place to tell Retro NPC Swapper about it too.
	 */
	private void recheckLoadedNpcs()
	{
		publishClaims(false);

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
	 * Takes over the client's draw callbacks slot by wrapping whichever supported renderer holds it:
	 * the GPU plugin, or 117 HD's zone renderer - directly, or beneath Retro NPC Swapper's decorator.
	 * <p>
	 * Declines for anything else - no renderer at all, an unknown one, 117 HD's legacy renderer
	 * (which implements no {@code drawTemp}), or a decorator it cannot see through, including a Retro
	 * NPC Swapper too old to stack. Which of the two stacks on top does not matter: Retro leaves the
	 * NPCs this plugin claims alone, so each NPC is only ever swapped by one of them. Must be called
	 * on the client thread.
	 */
	private void attach()
	{
		DrawCallbacks current = client.getDrawCallbacks();
		if (RendererChain.contains(current, wrapper))
		{
			return;
		}

		// We are not in the chain anymore; drop the stale reference before deciding whether we can
		// retake the slot
		boolean wasAttached = wrapper != null;
		wrapper = null;

		// Left beneath Retro NPC Swapper by an earlier detach, which could not unlink it from under
		// another plugin's decorator. Picking it back up keeps a second one from stacking on top.
		CustomDrawCallbacks buried = RendererChain.find(current, CustomDrawCallbacks.class);
		if (buried != null)
		{
			wrapper = buried;
			declinedHost = null;
			log.debug("Re-adopted custom draw callbacks beneath {}", current.getClass().getName());
		}
		else if (isSupportedHost(current, findGpuPlugin()))
		{
			CustomDrawCallbacks callbacks = new CustomDrawCallbacks(current, this::substitute);
			client.setDrawCallbacks(callbacks);
			wrapper = callbacks;
			declinedHost = null;
			log.debug("Attached custom draw callbacks over {}", current.getClass().getName());
		}
		else if (current != null && !current.getClass().getName().equals(declinedHost))
		{
			// Polled every tick while detached, so only said once per renderer
			declinedHost = current.getClass().getName();
			log.debug("Draw callbacks held by unsupported renderer {}; skipping model swap", declinedHost);
		}

		// Only on a real transition - the per-tick guard calls this repeatedly while detached, and
		// re-processing the whole scene every tick would be wasteful
		if (wasAttached == (wrapper == null))
		{
			recheckLoadedNpcs();
			syncInteractHighlight();
		}
	}

	/**
	 * Hands the draw callbacks slot back to the renderer. Must be called on the client thread.
	 */
	private void detach()
	{
		if (wrapper != null && client.getDrawCallbacks() == wrapper)
		{
			// Restore the delegate, never null - nulling the slot would leave the renderer running
			// with no callbacks registered
			client.setDrawCallbacks(wrapper.getDelegate());
			log.debug("Detached custom draw callbacks");
		}
		else if (wrapper != null)
		{
			// Something else holds the slot. If it wrapped our wrapper - Retro NPC Swapper does - that
			// decorator stays in its chain, only forwarding once the cache's memo is cleared, and the
			// next attach() picks it back up.
			log.debug("Draw callbacks slot no longer ours at detach; leaving it untouched");
		}
		wrapper = null;

		// Nothing is being swapped anymore, so Interact Highlight's own outlines are correct again
		syncInteractHighlight();
	}

	/**
	 * Whether {@code current} is a renderer {@link CustomDrawCallbacks} can substitute models through,
	 * either itself or beneath Retro NPC Swapper's decorator.
	 * <p>
	 * 117 HD is a Plugin Hub plugin loaded in its own classloader, so it is recognized by class
	 * name alone - there is no compile or runtime dependency on it. The zone renderer package is
	 * allowlisted rather than the legacy one denylisted, so a renderer 117 HD adds or renames later
	 * is declined instead of wrapped blind.
	 */
	static boolean isSupportedHost(DrawCallbacks current, Plugin gpu)
	{
		DrawCallbacks renderer = RendererChain.base(current);
		return renderer != null && (renderer == gpu || isHdZoneRenderer(renderer.getClass().getName()));
	}

	static boolean isHdZoneRenderer(String className)
	{
		return className.startsWith(HD_ZONE_RENDERER_PACKAGE);
	}

	private static boolean isHdPlugin(Plugin plugin)
	{
		return plugin != null && HD_PLUGIN_CLASS.equals(plugin.getClass().getName());
	}

	private Plugin findGpuPlugin()
	{
		if (gpuPlugin == null)
		{
			for (Plugin plugin : pluginManager.getPlugins())
			{
				if (plugin instanceof GpuPlugin)
				{
					gpuPlugin = plugin;
					break;
				}
			}
		}
		return gpuPlugin;
	}

	/**
	 * Takes over Interact Highlight's NPC outlines, or hands them back.
	 * <p>
	 * Only worth doing while geometry is actually being substituted - with no wrapper attached the
	 * vanilla model is what gets drawn, and that plugin's own outline already fits it. Must be called
	 * on the client thread.
	 */
	private void syncInteractHighlight()
	{
		boolean takeOver = config.overrideInteractHighlight()
			&& wrapper != null
			&& interactHighlight.isInteractHighlightActive();

		if (takeOver == outlineTakeover)
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
		outlineTakeover = takeOver;
	}

	/**
	 * Tells Retro NPC Swapper which NPC ids this plugin is drawing, so it leaves them alone.
	 * <p>
	 * Every bound NPC is claimed while custom models can be drawn at all, and none while they
	 * cannot - off, detached, or stood down by a safety setting - so Retro can have them back. Posted
	 * only when the set changes, unless {@code always}. Must be called on the client thread.
	 */
	private void publishClaims(boolean always)
	{
		Set<Integer> claims = active && wrapper != null && config.enabled() && !isSafetyDisabled()
			? modelCache.boundNpcIds()
			: Collections.emptySet();

		if (!always && claims.equals(postedClaims))
		{
			return;
		}

		postedClaims = claims;
		log.debug("Claiming {} NPC id(s) for custom models", claims.size());
		eventBus.post(ModelSwapProtocol.claimsMessage(claims));
	}

	/**
	 * Supplies custom geometry for an NPC being drawn, or null to let the vanilla model through.
	 * <p>
	 * Runs per NPC per frame, so it does a lookup and a skin only - eligibility is decided in
	 * {@link #processNpc} and the geometry is built by {@link ModelCache} ahead of time.
	 */
	private Model substitute(NPC npc, Model vanilla)
	{
		return modelCache.pose(npc);
	}
}
