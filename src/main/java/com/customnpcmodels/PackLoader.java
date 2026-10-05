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

import com.customnpcmodels.packs.ClasspathPackSource;
import com.customnpcmodels.packs.DirectoryPackSource;
import com.customnpcmodels.packs.HubInstaller;
import com.customnpcmodels.packs.LoadedPack;
import com.customnpcmodels.packs.ModelCatalog;
import com.customnpcmodels.packs.PackComposer;
import com.customnpcmodels.packs.PackKind;
import com.customnpcmodels.packs.PackSelection;
import com.customnpcmodels.packs.PackSettings;
import com.customnpcmodels.packs.PackView;
import com.google.gson.Gson;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.util.Filepath;

/**
 * Reads every pack - inside the plugin, from the hub and the user's own - and decides from them and
 * the side panel's selection which model each NPC is drawn with.
 */
@Singleton
@Slf4j
class PackLoader
{
	/** Folders in the data directory: packs installed from the hub, and packs the user added. */
	static final String HUB_PACKS = "hub";
	static final String LOCAL_PACKS = "local";

	/** Where the plugin's data directory is: {@code Plugin.getPluginDirectory}, which only the plugin can call. */
	@FunctionalInterface
	interface DataRoot
	{
		Filepath get() throws IOException;
	}

	@Inject
	private ClientThread clientThread;

	@Inject
	private Gson gson;

	@Inject
	private PackSettings packSettings;

	@Inject
	private ModelCache modelCache;

	@Inject
	private Session session;

	@Inject
	private PanelBridge panel;

	private DataRoot dataRoot;

	/** Re-evaluates the NPCs in the scene once the catalog has changed. */
	private Runnable recheckNpcs;

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

	/** Called from the plugin's startUp, before anything is loaded. */
	void start(DataRoot dataRoot, Runnable recheckNpcs)
	{
		this.dataRoot = dataRoot;
		this.recheckNpcs = recheckNpcs;
	}

	/** Forgets the packs as read. Client thread only. */
	void clear()
	{
		loadedPacks = Collections.emptyList();
	}

	/**
	 * Reads every pack off the client thread and publishes them back onto it.
	 *
	 * <p>Decompressing packs is quick, but it is still IO, and startUp must not block on it - so this
	 * is fire-and-forget. Everything downstream treats "no packs yet" as "no custom models", which is
	 * why nothing has to wait for this to finish.
	 */
	void load()
	{
		load(null);
	}

	/**
	 * {@link #load()}, then {@code whenShown} on the client thread once the panel has been handed
	 * the packs as read - so it runs after the panel shows them.
	 */
	void load(Runnable whenShown)
	{
		int queuedUnder = session.token();
		session.submit(() ->
		{
			if (!session.isCurrent(queuedUnder))
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
				if (!session.isCurrent(queuedUnder))
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
	Filepath dataDirectory()
	{
		if (dataDirectory == null)
		{
			try
			{
				Filepath data = dataRoot.get();
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
		recheckNpcs.run();

		List<PackView> views = PackView.of(loadedPacks, catalog, selection);
		panel.update(shown -> shown.setPacks(views));
	}

	/** Queues one recompose on the client thread, however many changes ask for it before it runs. */
	void requestRecompose()
	{
		if (recomposeQueued.compareAndSet(false, true))
		{
			clientThread.invoke(() ->
			{
				recomposeQueued.set(false);
				if (session.isActive())
				{
					recompose();
				}
			});
		}
	}
}
