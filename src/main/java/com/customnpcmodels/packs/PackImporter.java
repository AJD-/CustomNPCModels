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

import com.customnpcmodels.inject.AssetCodec;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.util.Locale;
import lombok.Value;
import net.runelite.client.util.Filepath;

/**
 * Copies a pack folder the user picked into the local packs folder, where it is loaded from then on.
 *
 * <p>The pack is read before anything is copied, so a folder that would only ever load as a broken
 * pack is refused with the reason instead. Disk IO throughout, so never on the client thread or
 * the EDT.
 */
public final class PackImporter
{
	private PackImporter()
	{
	}

	/** What an import did, for the panel to say. */
	@Value
	public static class Result
	{
		boolean imported;
		String message;
	}

	/**
	 * Copies {@code source} - a folder holding a {@code bundle.dat}, and perhaps a {@code pack.json}
	 * - into a new folder under {@code localPacks}, named after the pack's id or else the folder's.
	 * Never overwrites a pack already there.
	 */
	public static Result importFolder(Filepath source, Filepath localPacks, Gson gson)
	{
		Filepath bundle = source.joinSegment(DirectoryPackSource.BUNDLE_FILE);
		Filepath info = source.joinSegment(DirectoryPackSource.INFO_FILE);
		if (!bundle.isFile())
		{
			return new Result(false, "That folder has no " + DirectoryPackSource.BUNDLE_FILE
				+ ". Choose the folder generateAssets -PpackOut wrote.");
		}

		String name;
		try
		{
			if (bundle.size() > AssetCodec.MAX_FILE_BYTES)
			{
				return new Result(false, DirectoryPackSource.BUNDLE_FILE + " is past the "
					+ AssetCodec.MAX_FILE_BYTES / (1024 * 1024) + " MiB limit.");
			}
			try (InputStream in = bundle.openInputStream())
			{
				AssetCodec.read(in);
			}
			name = folderName(info.isFile() ? idIn(info, gson) : null, source.getFileName());
		}
		catch (IOException | RuntimeException ex)
		{
			return new Result(false, "That pack can't be read: " + LoadedPack.describe(ex));
		}

		Filepath target;
		try
		{
			target = localPacks.joinSegment(name);
		}
		catch (IllegalArgumentException ex)
		{
			return new Result(false, "That pack's name can't be used as a folder name.");
		}
		if (target.exists())
		{
			return new Result(false, "A local pack called '" + name + "' is already installed. "
				+ "Remove it first to replace it.");
		}

		try
		{
			target.createDirectories();
			bundle.copyTo(target.joinSegment(DirectoryPackSource.BUNDLE_FILE));
			if (info.isFile())
			{
				info.copyTo(target.joinSegment(DirectoryPackSource.INFO_FILE));
			}
		}
		catch (IOException | RuntimeException ex)
		{
			// Half a pack would load as a broken one, so none is left behind
			try
			{
				target.deleteRecursively();
			}
			catch (IOException | RuntimeException ignored)
			{
				// The copy's own failure is the one worth reporting
			}
			return new Result(false, "The pack could not be copied: " + LoadedPack.describe(ex));
		}

		return new Result(true, "Imported '" + name + "'.");
	}

	/**
	 * Deletes a local pack's folder. Nothing happens when it is not there.
	 *
	 * @throws IllegalArgumentException when {@code folder} could never name a pack, so a name that
	 *                                  reaches outside {@code localPacks} deletes nothing
	 */
	public static void remove(Filepath localPacks, String folder) throws IOException
	{
		if (!DirectoryPackSource.isValidFolderName(folder))
		{
			throw new IllegalArgumentException("'" + folder + "' is not a pack folder name");
		}
		Filepath pack = localPacks.joinSegment(folder);
		if (pack.exists())
		{
			pack.deleteRecursively();
		}
	}

	/** The id {@code pack.json} gives, or null. Only used to name the folder; see {@link DirectoryPackSource}. */
	private static String idIn(Filepath info, Gson gson) throws IOException
	{
		try (Reader reader = info.openReader())
		{
			JsonObject json = gson.fromJson(reader, JsonObject.class);
			if (json == null)
			{
				return null;
			}
			// Read as the loader will, so a pack.json it would refuse - tags that aren't a list, say -
			// is refused now rather than imported as a pack that won't load
			gson.fromJson(json, DirectoryPackSource.PackJson.class);
			return json.has("id") && json.get("id").isJsonPrimitive() ? json.get("id").getAsString() : null;
		}
		catch (JsonParseException | IllegalStateException ex)
		{
			throw new IOException(DirectoryPackSource.INFO_FILE + " isn't in the expected form: " + LoadedPack.describe(ex), ex);
		}
	}

	/**
	 * A folder name for the pack: its id when it has one, else the folder it came from, cut down to
	 * lowercase letters, digits, '-' and '_' - always a name {@link DirectoryPackSource} will load.
	 */
	static String folderName(String id, String folder)
	{
		String raw = id != null && !id.trim().isEmpty() ? id : folder;
		String name = (raw == null ? "" : raw).toLowerCase(Locale.ROOT)
			.replaceAll("[^a-z0-9_-]+", "-")
			.replaceAll("^-+|-+$", "");
		if (name.length() > 64)
		{
			name = name.substring(0, 64).replaceAll("-+$", "");
		}
		if (name.isEmpty())
		{
			name = "pack";
		}
		if (!DirectoryPackSource.isValidFolderName(name))
		{
			// Only a name Windows reserves, such as "con", gets this far
			name = name + "-pack";
		}
		return name;
	}
}
