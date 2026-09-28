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
import com.google.gson.JsonParseException;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import net.runelite.client.util.Filepath;

/**
 * Reads every pack in one folder of the plugin's data directory, one pack per subfolder:
 *
 * <pre>
 * &lt;root&gt;/&lt;folder&gt;/bundle.dat   the models, as the generator writes them
 * &lt;root&gt;/&lt;folder&gt;/pack.json    optional: name, author, description, version, license, tags
 * </pre>
 *
 * <p>Every pack here is untrusted, so one that cannot be read comes back failed, saying why, rather
 * than stopping the rest. All of it is disk IO, so never on the client thread.
 */
public final class DirectoryPackSource
{
	public static final String BUNDLE_FILE = "bundle.dat";
	public static final String INFO_FILE = "pack.json";

	/**
	 * What a pack folder may be called. It becomes part of the pack's id, which is stored in config
	 * alongside others, so it is kept to characters that cannot be mistaken for a separator - and it
	 * may not start or end with a dot or a space, which {@link Filepath} refuses outright.
	 */
	private static final Pattern FOLDER_NAME = Pattern.compile("[A-Za-z0-9_-]([A-Za-z0-9 ._-]{0,62}[A-Za-z0-9_-])?");

	/** Names Windows reserves, which {@link Filepath} refuses on every platform. */
	private static final Pattern RESERVED_NAME = Pattern.compile("(?i)(con|prn|aux|nul|com[0-9]|lpt[0-9])(\\..*)?");

	/**
	 * Folders a hub install leaves behind when it is interrupted: the download being staged, and the
	 * pack it was replacing. Neither is a pack; the hub client clears them up.
	 */
	private static final Pattern HUB_STAGING = Pattern.compile("dl-.*|.*\\.old");

	private final Filepath root;
	private final PackKind kind;
	private final Gson gson;

	public DirectoryPackSource(Filepath root, PackKind kind, Gson gson)
	{
		this.root = root;
		this.kind = kind;
		this.gson = gson;
	}

	/** Every pack folder under the root, in name order. Nothing when the root does not exist. */
	public List<LoadedPack> load() throws IOException
	{
		if (!root.isDirectory())
		{
			return Collections.emptyList();
		}

		List<Filepath> folders;
		try (Stream<Filepath> entries = root.walk(1))
		{
			// walk() starts with the root itself
			folders = entries
				.filter(entry -> !entry.equals(root) && entry.isDirectory())
				.sorted()
				.collect(Collectors.toList());
		}

		List<LoadedPack> packs = new ArrayList<>();
		for (Filepath folder : folders)
		{
			String name = folder.getFileName();
			if (name.startsWith(".") || (kind == PackKind.HUB && HUB_STAGING.matcher(name).matches()))
			{
				continue;
			}

			try
			{
				packs.add(read(folder, name));
			}
			catch (RuntimeException ex)
			{
				// One folder must never stop the rest - nor the packs inside the plugin - from loading
				packs.add(LoadedPack.failed(PackInfo.named(kind.packId(name), kind, name), ex));
			}
		}
		return packs;
	}

	private LoadedPack read(Filepath folder, String name)
	{
		PackInfo info = PackInfo.named(kind.packId(name), kind, name);
		if (!isValidFolderName(name))
		{
			return LoadedPack.failed(info, "Pack folder names may only use letters, digits, spaces, '.', '-' and '_', "
				+ "may not start or end with a dot or a space, and may not be a name Windows reserves");
		}

		Filepath infoFile = folder.joinSegment(INFO_FILE);
		if (infoFile.isFile())
		{
			try (Reader reader = infoFile.openReader())
			{
				PackJson json = gson.fromJson(reader, PackJson.class);
				if (json != null)
				{
					info = json.toInfo(info);
				}
			}
			catch (IOException | JsonParseException ex)
			{
				return LoadedPack.failed(info, INFO_FILE + " could not be read: " + LoadedPack.describe(ex));
			}
		}

		Filepath bundleFile = folder.joinSegment(BUNDLE_FILE);
		if (!bundleFile.isFile())
		{
			return LoadedPack.failed(info, "The pack has no " + BUNDLE_FILE);
		}

		try
		{
			long size = bundleFile.size();
			if (size > AssetCodec.MAX_FILE_BYTES)
			{
				return LoadedPack.failed(info, BUNDLE_FILE + " is " + size / (1024 * 1024) + " MiB, past the "
					+ AssetCodec.MAX_FILE_BYTES / (1024 * 1024) + " MiB limit");
			}

			try (InputStream in = bundleFile.openInputStream())
			{
				return LoadedPack.loaded(info, AssetCodec.read(in));
			}
		}
		catch (IOException | RuntimeException ex)
		{
			// Anything a malformed file can throw names this pack, rather than stopping the others
			return LoadedPack.failed(info, ex);
		}
	}

	/** Whether a folder may hold a pack: see {@link #FOLDER_NAME} and {@link #RESERVED_NAME}. */
	static boolean isValidFolderName(String name)
	{
		return FOLDER_NAME.matcher(name).matches() && !RESERVED_NAME.matcher(name).matches();
	}

	/** {@code pack.json} as written. The id is never taken from it: a pack's folder decides that. */
	static final class PackJson
	{
		String name;
		String author;
		String description;
		String version;
		String license;
		List<String> tags;
		String commit;

		PackInfo toInfo(PackInfo folder)
		{
			return new PackInfo(folder.getId(), folder.getKind(),
				name == null || name.trim().isEmpty() ? folder.getName() : name.trim(),
				author, description, version, license,
				tags == null ? Collections.emptyList() : Collections.unmodifiableList(new ArrayList<>(tags)),
				commit);
		}
	}
}
