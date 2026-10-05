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
import com.google.gson.annotations.SerializedName;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import lombok.Getter;

/**
 * One pack the Custom Model Hub offers, as its manifest lists it.
 *
 * <p>Everything here comes from a server this plugin does not control, so an entry is only used
 * once {@link #problem()} has passed it: the id and commit go into file paths and URLs, and the
 * size and hash are what the download is checked against.
 */
@Getter
public final class HubEntry
{
	/** The pack's folder under {@code hub/}, and its branch on the hub. */
	private static final Pattern ID = Pattern.compile("[a-z0-9-]{1,64}");
	private static final Pattern COMMIT = Pattern.compile("[0-9a-f]{40}");
	private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");

	/** Only pages on GitHub are opened from the panel. */
	private static final String REPO_PREFIX = "https://github.com/";

	private String id;
	private String name;
	private String author;
	private String description;
	private String version;
	private String license;
	private List<String> tags;

	/** The bundle format the pack is built in; see {@link AssetCodec#VERSION}. */
	private int formatVersion;

	/** The hub commit the pack's files are fetched at, so an update is a new commit. */
	private String commit;

	/** The exact size and SHA-256 of its {@code bundle.dat}, checked before it is installed. */
	private long size;
	private String sha256;

	/** Whether the pack ships an {@code icon.png}. */
	@SerializedName("hasIcon")
	private boolean iconAvailable;

	/** The pack's page on GitHub. */
	private String repo;

	private List<Model> models;

	/** A model in the pack, for listing before it is downloaded. */
	@Getter
	public static final class Model
	{
		private int key;
		private String name;
		private int[] npcIds;
	}

	/** Why this entry can't be used, or null when it can. */
	String problem()
	{
		if (id == null || !ID.matcher(id).matches())
		{
			return "its id '" + id + "' isn't lowercase letters, digits and hyphens";
		}
		// The id is a folder under hub/, so it must be one that can hold a pack - not a name Windows
		// reserves, and not one the installer's own staging folders use, which are cleared on load
		if (!DirectoryPackSource.isValidFolderName(id) || DirectoryPackSource.isHubStaging(id))
		{
			return "its id '" + id + "' can't be a pack folder";
		}
		if (name == null || name.trim().isEmpty())
		{
			return "it has no name";
		}
		if (commit == null || !COMMIT.matcher(commit).matches())
		{
			return "its commit isn't a full commit hash";
		}
		if (sha256 == null || !SHA256.matcher(sha256).matches())
		{
			return "its sha256 isn't a SHA-256 in lowercase hex";
		}
		if (size <= 0 || size > AssetCodec.MAX_FILE_BYTES)
		{
			return "its size " + size + " is outside 1 byte to " + AssetCodec.MAX_FILE_BYTES / (1024 * 1024) + " MiB";
		}
		return null;
	}

	/** Whether this plugin reads the format the pack is built in. */
	public boolean isCompatible()
	{
		return formatVersion == AssetCodec.VERSION;
	}

	/** The id the pack has once installed; see {@link PackKind#packId}. */
	public String getPackId()
	{
		return PackKind.HUB.packId(id);
	}

	/** The pack's page, when it is one the panel may open: an https page on github.com. */
	public String getSafeRepo()
	{
		if (repo == null || !repo.startsWith(REPO_PREFIX))
		{
			return null;
		}
		try
		{
			URI uri = new URI(repo);
			return "https".equals(uri.getScheme()) && "github.com".equals(uri.getHost()) ? repo : null;
		}
		catch (URISyntaxException ex)
		{
			// Opening it would throw on the EDT
			return null;
		}
	}

	public List<String> getTags()
	{
		return tags == null ? Collections.emptyList() : tags;
	}

	/** The models the manifest lists, leaving out any it lists as null or without a name. */
	public List<Model> getModels()
	{
		return models == null ? Collections.emptyList() : models.stream()
			.filter(model -> model != null && model.getName() != null)
			.collect(Collectors.toList());
	}
}
