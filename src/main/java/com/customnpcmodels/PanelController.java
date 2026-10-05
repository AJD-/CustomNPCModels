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

import com.customnpcmodels.packs.HubEntry;
import com.customnpcmodels.packs.HubInstaller;
import com.customnpcmodels.packs.PackImporter;
import com.customnpcmodels.packs.PackKind;
import com.customnpcmodels.packs.PackSettings;
import com.customnpcmodels.ui.PacksPanel;
import com.google.gson.Gson;
import java.io.IOException;
import java.util.List;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.util.Filepath;

/**
 * What the side panel asks for. Called on the EDT: selection changes are config writes, which come
 * back through the plugin's onConfigChanged; anything touching disk goes to the executor.
 */
@Singleton
@Slf4j
class PanelController implements PacksPanel.Actions
{
	@Inject
	private PackSettings packSettings;

	@Inject
	private PackLoader packLoader;

	@Inject
	private HubController hub;

	@Inject
	private PanelBridge panel;

	@Inject
	private Session session;

	@Inject
	private Gson gson;

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
		panel.showStatus(null, false);
		packLoader.load();
	}

	@Override
	public void importPack()
	{
		PacksPanel shown = panel.get();
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
		int queuedUnder = session.token();
		session.submit(() ->
		{
			if (!session.isCurrent(queuedUnder))
			{
				return;
			}

			// Resolved here rather than read from the field: an import straight after startup can
			// come before the first load has made the folder
			Filepath data = packLoader.dataDirectory();
			if (data == null)
			{
				panel.showStatus("The plugin's data folder isn't available, so packs can't be imported. See the log.", true);
				return;
			}
			PackImporter.Result result = PackImporter.importFolder(source, data.joinSegment(PackLoader.LOCAL_PACKS), gson);
			log.debug("Pack import from {}: {}", source, result.getMessage());
			panel.showStatus(result.getMessage(), !result.isImported());
			if (result.isImported())
			{
				packLoader.load();
			}
		});
	}

	@Override
	public void loadHub()
	{
		hub.load();
	}

	@Override
	public void installHubPack(HubEntry entry)
	{
		hub.install(entry);
	}

	@Override
	public void removeHubPack(String folder)
	{
		removePack(folder, "hub pack " + folder, data -> HubInstaller.remove(data.joinSegment(PackLoader.HUB_PACKS), folder));
	}

	@Override
	public void removeLocalPack(String folder)
	{
		removePack(PackKind.LOCAL.packId(folder), "local pack " + folder,
			data -> PackImporter.remove(data.joinSegment(PackLoader.LOCAL_PACKS), folder));
	}

	/**
	 * Runs a removal on the executor, with the pack's button busy under {@code busyKey} until the
	 * panel has read the packs again.
	 */
	private void removePack(String busyKey, String what, PackRemoval removal)
	{
		PacksPanel shown = panel.get();
		if (shown != null)
		{
			shown.setBusy(busyKey, true);
		}
		int queuedUnder = session.token();
		session.submit(() ->
		{
			if (!session.isCurrent(queuedUnder))
			{
				return;
			}
			boolean removed = false;
			try
			{
				Filepath data = packLoader.dataDirectory();
				if (data != null)
				{
					removal.remove(data);
					panel.showStatus("Removed the pack.", false);
					packLoader.load(() -> panel.update(done -> done.setBusy(busyKey, false)));
					removed = true;
				}
			}
			catch (IOException | RuntimeException ex)
			{
				log.warn("Could not remove {}", what, ex);
				panel.showStatus("The pack couldn't be removed: " + ex.getMessage(), true);
			}
			finally
			{
				if (!removed)
				{
					panel.update(done -> done.setBusy(busyKey, false));
				}
			}
		});
	}

	/** Deletes one pack's folder from under the plugin's data folder. Disk IO, so executor only. */
	@FunctionalInterface
	private interface PackRemoval
	{
		void remove(Filepath data) throws IOException;
	}
}
