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

import com.customnpcmodels.packs.HubClient;
import com.customnpcmodels.packs.HubEntry;
import com.customnpcmodels.packs.HubInstaller;
import com.customnpcmodels.ui.PacksPanel;
import com.google.gson.Gson;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.util.Filepath;
import okhttp3.OkHttpClient;

/**
 * The Custom Model Hub as the side panel uses it: its list of packs, and installing from it. Asks the
 * hub for nothing while it is switched off in config.
 */
@Singleton
@Slf4j
class HubController
{
	/** Points the hub at a test server, in developer mode only; see {@link #hubUrl}. */
	private static final String HUB_URL_PROPERTY = "customnpcmodels.hubUrl";

	@Inject
	private CustomNpcModelsConfig config;

	@Inject
	private OkHttpClient okHttpClient;

	@Inject
	private Gson gson;

	@Inject
	@Named("developerMode")
	private boolean developerMode;

	@Inject
	private Session session;

	@Inject
	private PackLoader packLoader;

	@Inject
	private PanelBridge panel;

	/** Made in start; only ever asked anything while the hub is switched on in config. */
	private HubClient hubClient;

	/**
	 * Hub pack icons as fetched, by commit, so an install carries the icon without fetching it again.
	 * Written on OkHttp threads, read on the EDT, so concurrent.
	 */
	private final Map<String, byte[]> hubIcons = new ConcurrentHashMap<>();

	/**
	 * Bumped whenever the hub is switched on or off, and on stop. A hub answer carries the value it
	 * was asked under, so one landing after the hub was switched off is dropped - without disturbing
	 * pack reads, which {@link Session} guards.
	 */
	private final AtomicInteger hubGeneration = new AtomicInteger();

	void start()
	{
		hubClient = new HubClient(okHttpClient, gson, hubUrl());
	}

	void stop()
	{
		hubGeneration.incrementAndGet();
		hubIcons.clear();
	}

	/** The hub was switched on or off in config. Any thread. */
	void onToggled()
	{
		// Whatever the hub was asked before this, its answer no longer applies
		hubGeneration.incrementAndGet();
		boolean enabled = config.hubEnabled();
		panel.update(shown -> shown.setHubEnabled(enabled));
	}

	/** Fetches the hub's list of packs for the panel; see {@link PacksPanel.Actions#loadHub}. */
	void load()
	{
		// Checked again here rather than trusting the panel: while this is off, nothing is fetched
		if (!config.hubEnabled())
		{
			return;
		}

		int queuedUnder = session.token();
		int hubQueuedUnder = hubGeneration.get();
		hubClient.fetchManifest(entries ->
		{
			if (!isCurrentHub(queuedUnder, hubQueuedUnder))
			{
				return;
			}
			panel.update(shown -> shown.setHubEntries(entries));
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
							panel.update(shown -> shown.setHubIcon(entry.getCommit(), image));
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
			panel.update(shown -> shown.setHubError(error));
		});
	}

	/** Downloads and installs a hub pack, or updates it when it is installed. */
	void install(HubEntry entry)
	{
		if (!config.hubEnabled() || !entry.isCompatible())
		{
			return;
		}

		PacksPanel shown = panel.get();
		if (shown != null)
		{
			shown.setBusy(entry.getId(), true);
		}
		int queuedUnder = session.token();
		int hubQueuedUnder = hubGeneration.get();
		hubClient.download(entry, bundle ->
			// Downloaded and verified on the OkHttp thread; written to disk on the executor, where
			// every other change to the pack folders happens
			session.submit(() ->
			{
				if (!session.isCurrent(queuedUnder))
				{
					return;
				}
				boolean installed = false;
				try
				{
					Filepath data = packLoader.dataDirectory();
					if (data == null)
					{
						panel.showStatus("The plugin's data folder isn't available, so packs can't be installed. "
							+ "See the log.", true);
						return;
					}
					HubInstaller.install(data.joinSegment(PackLoader.HUB_PACKS), entry, bundle, hubIcons.get(entry.getCommit()), gson);
					log.debug("Installed hub pack {} at {}", entry.getId(), entry.getCommit());
					panel.showStatus("Installed '" + entry.getName() + "'.", false);
					// Busy until the panel shows it installed, or its button would offer the install again
					packLoader.load(() -> panel.update(done -> done.setBusy(entry.getId(), false)));
					installed = true;
				}
				catch (IOException | RuntimeException ex)
				{
					log.warn("Could not install hub pack {}", entry.getId(), ex);
					panel.showStatus("'" + entry.getName() + "' couldn't be installed: " + ex.getMessage(), true);
				}
				finally
				{
					if (!installed)
					{
						panel.update(done -> done.setBusy(entry.getId(), false));
					}
				}
			}), error ->
		{
			if (!session.isCurrent(queuedUnder))
			{
				return;
			}
			// Said only while the hub is still on, as with the list; the button is freed either way
			if (isCurrentHub(queuedUnder, hubQueuedUnder))
			{
				panel.showStatus(error, true);
			}
			panel.update(done -> done.setBusy(entry.getId(), false));
		});
	}

	/**
	 * Whether a hub answer still belongs to this session and this spell of the hub being switched on.
	 * One that arrives after the hub is switched off is dropped - and asks for nothing more, icons
	 * included.
	 */
	private boolean isCurrentHub(int queuedUnder, int hubQueuedUnder)
	{
		return session.isCurrent(queuedUnder) && hubQueuedUnder == hubGeneration.get()
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
		if (override != null && !override.isBlank())
		{
			log.info("Using the test Custom Model Hub at {}", override);
			return override.endsWith("/") ? override : override + "/";
		}
		return HubClient.BASE_URL;
	}
}
