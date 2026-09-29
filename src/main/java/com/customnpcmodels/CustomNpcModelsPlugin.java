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
import com.customnpcmodels.packs.ClasspathPackSource;
import com.customnpcmodels.packs.DirectoryPackSource;
import com.customnpcmodels.packs.HubClient;
import com.customnpcmodels.packs.HubEntry;
import com.customnpcmodels.packs.HubInstaller;
import com.customnpcmodels.packs.LoadedPack;
import com.customnpcmodels.packs.ModelCatalog;
import com.customnpcmodels.packs.PackComposer;
import com.customnpcmodels.packs.PackImporter;
import com.customnpcmodels.packs.PackKind;
import com.customnpcmodels.packs.PackSelection;
import com.customnpcmodels.packs.PackSettings;
import com.customnpcmodels.packs.PackView;
import com.customnpcmodels.ui.PacksPanel;
import com.google.gson.Gson;
import com.google.inject.Provides;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import javax.inject.Inject;
import javax.inject.Named;
import javax.swing.SwingUtilities;
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
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.ui.overlay.OverlayManager;
import net.runelite.client.util.Filepath;
import net.runelite.client.util.ImageUtil;
import okhttp3.OkHttpClient;

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
	/** The renderer 117 HD registers by default; its legacy renderer lives in a sibling package. */
	private static final String HD_ZONE_RENDERER_PACKAGE = "rs117.hd.renderer.zone.";

	private static final String HD_PLUGIN_CLASS = "rs117.hd.HdPlugin";

	/** Another Hub plugin, so recognized by class name alone like 117 HD. */
	private static final String RETRO_PLUGIN_CLASS = "com.retronpcswapper.RetroNpcSwapperPlugin";

	/** Folders in the data directory: packs installed from the hub, and packs the user added. */
	static final String HUB_PACKS = "hub";
	static final String LOCAL_PACKS = "local";

	/** Points the hub at a test server, in developer mode only; see {@link #hubUrl}. */
	private static final String HUB_URL_PROPERTY = "customnpcmodels.hubUrl";

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

	@Inject
	private Gson gson;

	@Inject
	private ClientToolbar clientToolbar;

	@Inject
	private PackSettings packSettings;

	@Inject
	private OkHttpClient okHttpClient;

	@Inject
	@Named("developerMode")
	private boolean developerMode;

	/** Made in startUp; only ever asked anything while the hub is switched on in config. */
	private HubClient hubClient;

	/**
	 * Hub pack icons as fetched, by commit, so an install carries the icon without fetching it again.
	 * Written on OkHttp threads, read on the EDT, so concurrent.
	 */
	private final Map<String, byte[]> hubIcons = new ConcurrentHashMap<>();

	/**
	 * Bumped whenever the hub is switched on or off, and on stop. A hub answer carries the value it
	 * was asked under, so one landing after the hub was switched off is dropped - without disturbing
	 * pack reads, which {@link #generation} guards.
	 */
	private final AtomicInteger hubGeneration = new AtomicInteger();

	/** The side panel, and the toolbar button that opens it. Made in startUp, on the EDT. */
	private volatile PacksPanel panel;
	private NavigationButton navButton;

	/**
	 * Whether a recompose is already queued. A profile switch changes every selection key at once,
	 * each posting its own ConfigChanged, and one recompose covers them all.
	 */
	private final AtomicBoolean recomposeQueued = new AtomicBoolean();

	/** The packs inside the jar: the shipped one, and the development bundle under ./gradlew run. */
	private final ClasspathPackSource classpathPacks = new ClasspathPackSource();

	/**
	 * This plugin's data directory, resolved once off the client thread. Null until then, and for
	 * good when it cannot be made - the classpath packs still load without it.
	 */
	private volatile Filepath dataDirectory;

	/** Every pack as last read, whether or not it loaded. Client thread only. */
	private List<LoadedPack> loadedPacks = Collections.emptyList();

	/**
	 * Bumped on every start and stop. Work queued off the client thread carries the value it was
	 * queued under and is dropped when that is stale, so a read queued before a quick restart cannot
	 * land in the plugin that replaced it - which {@link #active} alone cannot tell apart.
	 */
	private final AtomicInteger generation = new AtomicInteger();

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
	 * The in-flight pack read, so shutDown can cancel it.
	 *
	 * <p>Volatile because startUp and shutDown are not guaranteed to be the same thread.
	 */
	private volatile Future<?> packLoad;

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
		generation.incrementAndGet();

		// startUp runs on the EDT, which is where Swing has to be built
		panel = new PacksPanel(new PanelActions());
		navButton = NavigationButton.builder()
			.tooltip("Custom NPC Models")
			.icon(ImageUtil.loadImageResource(getClass(), "panel_icon.png"))
			.priority(8)
			.panel(panel)
			.build();
		clientToolbar.addNavigation(navButton);

		hubClient = new HubClient(okHttpClient, gson, hubUrl());
		// Only records the switch; nothing is fetched until the panel is opened
		panel.setHubEnabled(config.hubEnabled());

		loadPacks();
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
		generation.incrementAndGet();

		// The executor is RuneLite's own and is not ours to shut down, but the read we put on it is
		Future<?> load = packLoad;
		if (load != null)
		{
			load.cancel(false);
			packLoad = null;
		}

		if (navButton != null)
		{
			clientToolbar.removeNavigation(navButton);
			navButton = null;
		}
		// The panel goes with its button; nothing holds it, so its snapshot of packs goes too
		panel = null;
		hubGeneration.incrementAndGet();
		hubIcons.clear();

		// Straight away rather than on the client thread, so Retro NPC Swapper takes these NPCs back
		// even if the queued work below never runs. Nothing is computed, so any thread will do.
		eventBus.post(ModelSwapProtocol.claimsMessage(Collections.emptySet()));

		clientThread.invoke(() ->
		{
			// detach() stands the Interact Highlight takeover down as part of dropping the wrapper
			detach();
			modelCache.clear();
			loadedPacks = Collections.emptyList();
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

		if (CustomNpcModelsConfig.HUB_ENABLED.equals(event.getKey()))
		{
			// Whatever the hub was asked before this, its answer no longer applies
			hubGeneration.incrementAndGet();
			boolean enabled = config.hubEnabled();
			SwingUtilities.invokeLater(() ->
			{
				PacksPanel shown = panel;
				if (shown != null)
				{
					shown.setHubEnabled(enabled);
				}
			});
			return;
		}

		if (SELECTION_KEYS.contains(event.getKey()))
		{
			// Which packs and models are on: decide again which model each NPC is drawn with. That
			// sweeps the scene itself, so the generic recheck below is not needed on top.
			requestRecompose();
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

		// Building here, on the client thread, keeps the draw callback to a lookup. The packs load
		// off-thread, so NPCs already on screen at startup usually come through before they land;
		// recheckLoadedNpcs picks them up once they do.
		if (wrapper == null || !config.enabled() || isSafetyDisabled() || !modelCache.ensureBuilt(npc.getId()))
		{
			modelCache.clearSubstituted(npc.getId());
			return;
		}

		modelCache.setSubstituted(npc.getId());
	}

	/**
	 * Reads every pack off the client thread and publishes them back onto it.
	 *
	 * <p>Decompressing packs is quick, but it is still IO, and startUp must not block on it - so this
	 * is fire-and-forget. Everything downstream treats "no packs yet" as "no custom models", which is
	 * why nothing has to wait for this to finish.
	 */
	private void loadPacks()
	{
		loadPacks(null);
	}

	/**
	 * {@link #loadPacks()}, then {@code whenShown} on the client thread once the panel has been handed
	 * the packs as read - so it runs after the panel shows them.
	 */
	private void loadPacks(Runnable whenShown)
	{
		int queuedUnder = generation.get();
		packLoad = executor.submit(() ->
		{
			if (!active || queuedUnder != generation.get())
			{
				return;
			}

			List<LoadedPack> packs = new ArrayList<>(classpathPacks.load());
			Filepath data = dataDirectory();
			if (data != null)
			{
				try
				{
					HubInstaller.clearLeftovers(data.joinSegment(HUB_PACKS));
				}
				catch (IOException | RuntimeException ex)
				{
					log.debug("Could not clear an interrupted hub install", ex);
				}
				packs.addAll(readPacks(data.joinSegment(HUB_PACKS), PackKind.HUB));
				packs.addAll(readPacks(data.joinSegment(LOCAL_PACKS), PackKind.LOCAL));
			}

			for (LoadedPack pack : packs)
			{
				if (!pack.isLoaded())
				{
					// A pack that exists but will not read is worth saying out loud, unlike one that
					// is simply absent - it means a stale, truncated or foreign file
					log.warn("Custom NPC model pack '{}' could not be read: {}", pack.getId(), pack.getError());
				}
			}

			clientThread.invoke(() ->
			{
				// A read that finishes after shutDown must not put packs back into a cache that was
				// just cleared, nor sweep the scene on behalf of a plugin that is no longer running
				if (!active || queuedUnder != generation.get())
				{
					log.debug("Custom NPC model packs finished reading after shutdown; dropping them");
					return;
				}

				loadedPacks = packs;
				recompose();
				if (whenShown != null)
				{
					whenShown.run();
				}
			});
		});
	}

	/**
	 * This plugin's data directory, with the pack folders inside it, made on first use. Null when it
	 * cannot be. Disk IO, so on the executor only.
	 */
	private Filepath dataDirectory()
	{
		if (dataDirectory == null)
		{
			try
			{
				Filepath data = getPluginDirectory();
				data.joinSegment(HUB_PACKS).createDirectories();
				data.joinSegment(LOCAL_PACKS).createDirectories();
				dataDirectory = data;
			}
			catch (IOException | RuntimeException ex)
			{
				log.warn("Could not make the custom NPC model data directory; only the packs inside the plugin will load", ex);
			}
		}
		return dataDirectory;
	}

	private List<LoadedPack> readPacks(Filepath folder, PackKind kind)
	{
		try
		{
			return new DirectoryPackSource(folder, kind, gson).load();
		}
		catch (IOException | RuntimeException ex)
		{
			// Caught wide: anything escaping here would end the load without a word, and take the
			// packs inside the plugin down with it
			log.warn("Could not list the custom NPC model packs in {}", folder, ex);
			return Collections.emptyList();
		}
	}

	/**
	 * Decides again which model every NPC is drawn with, from the packs already read. Cheap: nothing
	 * is read, and only NPCs whose model changed are rebuilt. Client thread only.
	 */
	private void recompose()
	{
		PackSelection selection = packSettings.read();
		ModelCatalog catalog = PackComposer.compose(loadedPacks, selection);
		modelCache.setCatalog(catalog);

		// NPCs are usually already on screen by the time packs land, and setCatalog only drops what
		// changed, it does not rebuild
		recheckLoadedNpcs();

		List<PackView> views = PackView.of(loadedPacks, catalog, selection);
		SwingUtilities.invokeLater(() ->
		{
			PacksPanel shown = panel;
			if (shown != null)
			{
				shown.setPacks(views);
			}
		});
	}

	/** Queues one recompose on the client thread, however many changes ask for it before it runs. */
	private void requestRecompose()
	{
		if (recomposeQueued.compareAndSet(false, true))
		{
			clientThread.invoke(() ->
			{
				recomposeQueued.set(false);
				if (active)
				{
					recompose();
				}
			});
		}
	}

	/** Shows a message in the panel, in red when it is an error, from any thread. */
	private void showStatus(String message, boolean error)
	{
		SwingUtilities.invokeLater(() ->
		{
			PacksPanel shown = panel;
			if (shown != null)
			{
				shown.showStatus(message, error);
			}
		});
	}

	/**
	 * What the side panel asks for. Called on the EDT: selection changes are config writes, which come
	 * back through {@link #onConfigChanged}; anything touching disk goes to the executor.
	 */
	private class PanelActions implements PacksPanel.Actions
	{
		@Override
		public void setPackEnabled(String packId, boolean enabled)
		{
			packSettings.setPackEnabled(packId, enabled);
		}

		@Override
		public void setModelEnabled(String modelKey, boolean enabled)
		{
			packSettings.setModelEnabled(modelKey, enabled);
		}

		@Override
		public void setOrder(List<String> packIds)
		{
			packSettings.setOrder(packIds);
		}

		@Override
		public void refresh()
		{
			showStatus(null, false);
			loadPacks();
		}

		@Override
		public void importPack()
		{
			PacksPanel shown = panel;
			if (shown == null)
			{
				return;
			}

			// The chooser has to be shown on the EDT, and blocks until it closes
			List<Filepath> chosen = new Filepath.Chooser()
				.setIsOpen()
				.setAcceptsDirectories()
				.setDialogTitle("Choose a pack folder (one holding bundle.dat)")
				.showDialog(shown);
			if (chosen == null || chosen.isEmpty())
			{
				return;
			}

			Filepath source = chosen.get(0);
			int queuedUnder = generation.get();
			executor.submit(() ->
			{
				if (!active || queuedUnder != generation.get())
				{
					return;
				}

				// Resolved here rather than read from the field: an import straight after startup can
				// come before the first load has made the folder
				Filepath data = dataDirectory();
				if (data == null)
				{
					showStatus("The plugin's data folder isn't available, so packs can't be imported. See the log.", true);
					return;
				}
				PackImporter.Result result = PackImporter.importFolder(source, data.joinSegment(LOCAL_PACKS), gson);
				log.debug("Pack import from {}: {}", source, result.getMessage());
				showStatus(result.getMessage(), !result.isImported());
				if (result.isImported())
				{
					loadPacks();
				}
			});
		}

		@Override
		public void loadHub()
		{
			// Checked again here rather than trusting the panel: while this is off, nothing is fetched
			if (!config.hubEnabled())
			{
				return;
			}

			int queuedUnder = generation.get();
			int hubQueuedUnder = hubGeneration.get();
			hubClient.fetchManifest(entries ->
			{
				if (!isCurrentHub(queuedUnder, hubQueuedUnder))
				{
					return;
				}
				onPanel(shown -> shown.setHubEntries(entries));
				for (HubEntry entry : entries)
				{
					if (!hubIcons.containsKey(entry.getCommit()))
					{
						hubClient.fetchIcon(entry, icon ->
						{
							// Nor are icons fetched for a list that arrived as the hub was switched off
							if (!isCurrentHub(queuedUnder, hubQueuedUnder))
							{
								return;
							}
							hubIcons.put(entry.getCommit(), icon);
							BufferedImage image = HubClient.readIcon(icon);
							if (image != null)
							{
								onPanel(shown -> shown.setHubIcon(entry.getCommit(), image));
							}
						});
					}
				}
			}, error ->
			{
				if (!isCurrentHub(queuedUnder, hubQueuedUnder))
				{
					return;
				}
				log.debug("Custom Model Hub list failed: {}", error);
				onPanel(shown -> shown.setHubError(error));
			});
		}

		@Override
		public void installHubPack(HubEntry entry)
		{
			if (!config.hubEnabled() || !entry.isCompatible())
			{
				return;
			}

			PacksPanel shown = panel;
			if (shown != null)
			{
				shown.setBusy(entry.getId(), true);
			}
			int queuedUnder = generation.get();
			hubClient.download(entry, bundle ->
				// Downloaded and verified on the OkHttp thread; written to disk on the executor, where
				// every other change to the pack folders happens
				executor.submit(() ->
				{
					if (!active || queuedUnder != generation.get())
					{
						return;
					}
					boolean installed = false;
					try
					{
						Filepath data = dataDirectory();
						if (data == null)
						{
							showStatus("The plugin's data folder isn't available, so packs can't be installed. "
								+ "See the log.", true);
							return;
						}
						HubInstaller.install(data.joinSegment(HUB_PACKS), entry, bundle, hubIcons.get(entry.getCommit()), gson);
						log.debug("Installed hub pack {} at {}", entry.getId(), entry.getCommit());
						showStatus("Installed '" + entry.getName() + "'.", false);
						// Busy until the panel shows it installed, or its button would offer the install again
						loadPacks(() -> onPanel(done -> done.setBusy(entry.getId(), false)));
						installed = true;
					}
					catch (IOException | RuntimeException ex)
					{
						log.warn("Could not install hub pack {}", entry.getId(), ex);
						showStatus("'" + entry.getName() + "' couldn't be installed: " + ex.getMessage(), true);
					}
					finally
					{
						if (!installed)
						{
							onPanel(done -> done.setBusy(entry.getId(), false));
						}
					}
				}), error ->
			{
				if (!active || queuedUnder != generation.get())
				{
					return;
				}
				showStatus(error, true);
				onPanel(done -> done.setBusy(entry.getId(), false));
			});
		}

		@Override
		public void removeHubPack(String folder)
		{
			PacksPanel shown = panel;
			if (shown != null)
			{
				shown.setBusy(folder, true);
			}
			int queuedUnder = generation.get();
			executor.submit(() ->
			{
				if (!active || queuedUnder != generation.get())
				{
					return;
				}
				boolean removed = false;
				try
				{
					Filepath data = dataDirectory();
					if (data != null)
					{
						HubInstaller.remove(data.joinSegment(HUB_PACKS), folder);
						showStatus("Removed the pack.", false);
						loadPacks(() -> onPanel(done -> done.setBusy(folder, false)));
						removed = true;
					}
				}
				catch (IOException | RuntimeException ex)
				{
					log.warn("Could not remove hub pack {}", folder, ex);
					showStatus("The pack couldn't be removed: " + ex.getMessage(), true);
				}
				finally
				{
					if (!removed)
					{
						onPanel(done -> done.setBusy(folder, false));
					}
				}
			});
		}
	}

	/**
	 * Whether a hub answer still belongs to this session and this spell of the hub being switched on.
	 * One that arrives after the hub is switched off is dropped - and asks for nothing more, icons
	 * included.
	 */
	private boolean isCurrentHub(int queuedUnder, int hubQueuedUnder)
	{
		return active && queuedUnder == generation.get() && hubQueuedUnder == hubGeneration.get()
			&& config.hubEnabled();
	}

	/**
	 * Where the hub is. Always the real hub, except in RuneLite's developer mode, where
	 * {@code ./gradlew run -PhubUrl=...} can point it at a local test hub; a Plugin Hub install never
	 * runs in developer mode.
	 */
	private String hubUrl()
	{
		String override = developerMode ? System.getProperty(HUB_URL_PROPERTY) : null;
		if (override != null && !override.trim().isEmpty())
		{
			log.info("Using the test Custom Model Hub at {}", override);
			return override.endsWith("/") ? override : override + "/";
		}
		return HubClient.BASE_URL;
	}

	/** Runs {@code update} on the panel, on the EDT, if the panel is still there by then. */
	private void onPanel(Consumer<PacksPanel> update)
	{
		SwingUtilities.invokeLater(() ->
		{
			PacksPanel shown = panel;
			if (shown != null)
			{
				update.accept(shown);
			}
		});
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
