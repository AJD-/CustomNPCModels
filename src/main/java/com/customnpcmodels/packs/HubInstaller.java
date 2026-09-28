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
package com.customnpcmodels.packs;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import net.runelite.client.util.Filepath;

/**
 * Installs, updates and removes hub packs in the plugin's {@code hub/} folder, which is the plugin's
 * own: what is installed is whatever is there.
 *
 * <p>A pack is written to a staging folder first and moved into place whole, so an interrupted
 * install leaves the old pack - or nothing - rather than half a pack. Disk IO throughout, so on the
 * executor only; that it is one thread is also what keeps an install and a removal of the same pack
 * from running at once.
 */
public final class HubInstaller
{
	static final String ICON_FILE = "icon.png";

	/** Added to an installed pack's folder name while an update sets it aside. */
	private static final String OLD_SUFFIX = ".old";

	private HubInstaller()
	{
	}

	/**
	 * Installs {@code entry} from its verified bundle and, when it has one, its icon - replacing any
	 * copy already installed.
	 */
	public static void install(Filepath hubPacks, HubEntry entry, byte[] bundle, byte[] icon, Gson gson)
		throws IOException
	{
		hubPacks.createDirectories();
		Filepath target = hubPacks.joinSegment(entry.getId());
		Filepath old = hubPacks.joinSegment(entry.getId() + OLD_SUFFIX);
		Filepath staged = hubPacks.createTempDir("dl-");
		try
		{
			staged.joinSegment(DirectoryPackSource.BUNDLE_FILE).write(bundle);
			staged.joinSegment(DirectoryPackSource.INFO_FILE).write(gson.toJson(info(entry)));
			if (icon != null)
			{
				staged.joinSegment(ICON_FILE).write(icon);
			}

			// A folder cannot be moved over another, so the installed copy is moved aside first,
			// and put back if the new one cannot take its place
			if (old.exists())
			{
				old.deleteRecursively();
			}
			boolean replacing = target.exists();
			if (replacing)
			{
				target.moveTo(old);
			}
			try
			{
				staged.moveTo(target);
			}
			catch (IOException ex)
			{
				if (replacing)
				{
					try
					{
						old.moveTo(target);
					}
					catch (IOException rollback)
					{
						// The first failure is the one to report; clearLeftovers restores the copy later
						ex.addSuppressed(rollback);
					}
				}
				throw ex;
			}
			if (replacing)
			{
				old.deleteRecursively();
			}
		}
		finally
		{
			if (staged.exists())
			{
				staged.deleteRecursively();
			}
		}
	}

	/** Removes an installed hub pack. Nothing happens when it is not installed. */
	public static void remove(Filepath hubPacks, String id) throws IOException
	{
		Filepath pack = hubPacks.joinSegment(id);
		if (pack.exists())
		{
			pack.deleteRecursively();
		}
	}

	/**
	 * Tidies up after an interrupted install. Its staging folder is deleted. The copy it set aside is
	 * deleted too when the new one took its place - but moved back when it didn't, since then it is
	 * the only copy there is.
	 */
	public static void clearLeftovers(Filepath hubPacks) throws IOException
	{
		if (!hubPacks.isDirectory())
		{
			return;
		}

		List<Filepath> leftovers;
		try (Stream<Filepath> entries = hubPacks.walk(1))
		{
			leftovers = entries
				.filter(entry -> !entry.equals(hubPacks) && entry.isDirectory()
					&& DirectoryPackSource.isHubStaging(entry.getFileName()))
				.collect(Collectors.toList());
		}
		for (Filepath leftover : leftovers)
		{
			String name = leftover.getFileName();
			if (name.endsWith(OLD_SUFFIX))
			{
				Filepath pack = hubPacks.joinSegment(name.substring(0, name.length() - OLD_SUFFIX.length()));
				if (!pack.exists())
				{
					leftover.moveTo(pack);
					continue;
				}
			}
			leftover.deleteRecursively();
		}
	}

	/** The installed pack's {@code pack.json}: what the panel shows, and the commit updates are told by. */
	private static JsonObject info(HubEntry entry)
	{
		JsonObject json = new JsonObject();
		json.addProperty("id", entry.getId());
		json.addProperty("name", entry.getName());
		json.addProperty("author", entry.getAuthor());
		json.addProperty("description", entry.getDescription());
		json.addProperty("version", entry.getVersion());
		json.addProperty("license", entry.getLicense());
		JsonArray tags = new JsonArray();
		entry.getTags().forEach(tags::add);
		json.add("tags", tags);
		json.addProperty("commit", entry.getCommit());
		return json;
	}
}
