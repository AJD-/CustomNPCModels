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
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonParseException;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import lombok.extern.slf4j.Slf4j;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * Talks to the Custom Model Hub: its manifest, and each pack's files at the commit the manifest
 * names. Everything is fetched from GitHub's raw file host.
 *
 * <p>Every call is {@code enqueue}d, so nothing here blocks, and every callback runs on an OkHttp
 * thread - never the client thread or the EDT. Nothing here touches disk; installing is
 * {@link HubInstaller}'s job, on the executor. Only ever used while the hub is switched on in config.
 */
@Slf4j
public class HubClient
{
	/** Where the hub is: the repo, on GitHub's raw file host. */
	public static final String BASE_URL = "https://raw.githubusercontent.com/AJD-/custom-model-hub/";

	/** The manifest, on its own branch. */
	static final String MANIFEST_PATH = "manifest/manifest.json";

	static final long MAX_MANIFEST_BYTES = 2L * 1024 * 1024;
	static final long MAX_ICON_BYTES = 256L * 1024;

	/** The largest icon decoded. A small PNG can declare an enormous image. */
	static final int MAX_ICON_SIDE = 512;

	private final OkHttpClient http;
	private final Gson gson;
	private final String baseUrl;

	/** Requests not yet answered, so {@link #cancelAll} can stop them. */
	private final Set<Call> inFlight = ConcurrentHashMap.newKeySet();

	/** @param baseUrl where the hub is, ending in a slash: {@link #BASE_URL}, but for testing */
	public HubClient(OkHttpClient http, Gson gson, String baseUrl)
	{
		this.http = http;
		this.gson = gson;
		this.baseUrl = baseUrl;
	}

	/** The packs the hub offers, or why they could not be fetched. */
	public void fetchManifest(Consumer<List<HubEntry>> done, Consumer<String> failed)
	{
		get(MANIFEST_PATH, MAX_MANIFEST_BYTES, bytes ->
		{
			List<HubEntry> entries;
			try
			{
				entries = parseManifest(gson, new String(bytes, StandardCharsets.UTF_8));
			}
			catch (JsonParseException | IllegalStateException ex)
			{
				failed.accept("The Custom Model Hub's list of packs couldn't be read: " + LoadedPack.describe(ex));
				return;
			}
			// Outside the try, so a failure in the caller's handling isn't blamed on the manifest
			done.accept(entries);
		}, failed);
	}

	/**
	 * A pack's {@code bundle.dat}, checked against its entry - size, SHA-256 and format - before it
	 * is handed over, or why it could not be.
	 */
	public void download(HubEntry entry, Consumer<byte[]> done, Consumer<String> failed)
	{
		get(entry.getCommit() + "/" + DirectoryPackSource.BUNDLE_FILE, entry.getSize(), bytes ->
		{
			String problem = verify(entry, bytes);
			if (problem != null)
			{
				failed.accept("'" + entry.getName() + "' wasn't installed: " + problem);
				return;
			}
			done.accept(bytes);
		}, failed);
	}

	/**
	 * A pack's icon file, once it is known to decode as a PNG no larger than {@link #MAX_ICON_SIDE}.
	 * Nothing when it has none or it fails.
	 */
	public void fetchIcon(HubEntry entry, Consumer<byte[]> done)
	{
		if (!entry.isIconAvailable())
		{
			return;
		}
		get(entry.getCommit() + "/icon.png", MAX_ICON_BYTES, bytes ->
		{
			if (readIcon(bytes) != null)
			{
				done.accept(bytes);
			}
		}, reason -> log.debug("No icon for hub pack {}: {}", entry.getId(), reason));
	}

	private void get(String path, long maxBytes, Consumer<byte[]> done, Consumer<String> failed)
	{
		Request request = new Request.Builder().url(baseUrl + path).build();
		Call queued = http.newCall(request);
		inFlight.add(queued);
		queued.enqueue(new Callback()
		{
			@Override
			public void onFailure(Call call, IOException ex)
			{
				inFlight.remove(call);
				deliver(path, () -> failed.accept("Couldn't reach the Custom Model Hub: " + LoadedPack.describe(ex)));
			}

			@Override
			public void onResponse(Call call, Response response)
			{
				inFlight.remove(call);
				byte[] bytes;
				try (ResponseBody body = response.body())
				{
					if (!response.isSuccessful() || body == null)
					{
						deliver(path, () -> failed.accept("The Custom Model Hub answered " + response.code() + " for " + path));
						return;
					}
					bytes = readCapped(body.byteStream(), maxBytes);
				}
				catch (IOException ex)
				{
					deliver(path, () -> failed.accept("Couldn't download from the Custom Model Hub: "
						+ LoadedPack.describe(ex)));
					return;
				}
				deliver(path, () -> done.accept(bytes));
			}
		});
	}

	/**
	 * Stops every request still waiting on the hub. Each one then fails with a cancellation, which
	 * callers have already stopped listening for by the time they call this. Any thread.
	 */
	public void cancelAll()
	{
		for (Iterator<Call> calls = inFlight.iterator(); calls.hasNext(); )
		{
			calls.next().cancel();
			calls.remove();
		}
	}

	/**
	 * Runs a caller's callback. A callback that throws is a bug in the caller rather than the hub, so
	 * it's logged here instead of escaping onto OkHttp's shared dispatcher thread.
	 */
	private static void deliver(String path, Runnable callback)
	{
		try
		{
			callback.run();
		}
		catch (RuntimeException ex)
		{
			log.warn("Handling the Custom Model Hub's answer for {} failed", path, ex);
		}
	}

	/**
	 * The manifest's valid entries. One that is malformed is skipped rather than failing the rest.
	 *
	 * @throws JsonParseException when the manifest is not a JSON array at all
	 */
	static List<HubEntry> parseManifest(Gson gson, String json)
	{
		JsonArray array = gson.fromJson(json, JsonArray.class);
		List<HubEntry> entries = new ArrayList<>();
		if (array == null)
		{
			return entries;
		}
		for (JsonElement element : array)
		{
			HubEntry entry;
			try
			{
				entry = gson.fromJson(element, HubEntry.class);
			}
			catch (JsonParseException ex)
			{
				log.debug("Skipping a malformed Custom Model Hub entry: {}", LoadedPack.describe(ex));
				continue;
			}

			String problem = entry == null ? "it is empty" : entry.problem();
			if (problem != null)
			{
				log.debug("Skipping a Custom Model Hub entry: {}", problem);
				continue;
			}
			if (entries.stream().anyMatch(other -> other.getId().equals(entry.getId())))
			{
				log.debug("Skipping a second Custom Model Hub entry for {}", entry.getId());
				continue;
			}
			entries.add(entry);
		}
		return entries;
	}

	/** Why a downloaded bundle does not match its entry, or null when it does. */
	static String verify(HubEntry entry, byte[] bytes)
	{
		if (bytes.length != entry.getSize())
		{
			return "the download is " + bytes.length + " bytes, not the " + entry.getSize() + " the hub listed";
		}
		if (!sha256(bytes).equals(entry.getSha256()))
		{
			return "the download doesn't match the hub's checksum";
		}
		try
		{
			AssetCodec.read(new ByteArrayInputStream(bytes));
		}
		catch (IOException | RuntimeException | OutOfMemoryError ex)
		{
			return "the pack can't be read: " + LoadedPack.describe(ex);
		}
		return null;
	}

	static String sha256(byte[] bytes)
	{
		try
		{
			StringBuilder hex = new StringBuilder();
			for (byte b : MessageDigest.getInstance("SHA-256").digest(bytes))
			{
				hex.append(String.format("%02x", b & 0xFF));
			}
			return hex.toString();
		}
		catch (NoSuchAlgorithmException ex)
		{
			// Every JVM must provide SHA-256
			throw new IllegalStateException(ex);
		}
	}

	/** All of {@code in}, refusing to read past {@code maxBytes}. */
	static byte[] readCapped(InputStream in, long maxBytes) throws IOException
	{
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		byte[] buffer = new byte[8192];
		long total = 0;
		int n;
		while ((n = in.read(buffer)) != -1)
		{
			total += n;
			if (total > maxBytes)
			{
				throw new IOException("the download is larger than the " + maxBytes + " bytes expected");
			}
			out.write(buffer, 0, n);
		}
		return out.toByteArray();
	}

	/** An icon's image, or null when it is not a PNG of at most {@link #MAX_ICON_SIDE} a side. */
	public static BufferedImage readIcon(byte[] bytes)
	{
		try (ImageInputStream in = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes)))
		{
			Iterator<ImageReader> readers = ImageIO.getImageReadersByFormatName("png");
			if (in == null || !readers.hasNext())
			{
				return null;
			}
			ImageReader reader = readers.next();
			try
			{
				reader.setInput(in);
				// Checked from the header, before anything is allocated for the pixels
				if (reader.getWidth(0) > MAX_ICON_SIDE || reader.getHeight(0) > MAX_ICON_SIDE)
				{
					return null;
				}
				return reader.read(0);
			}
			finally
			{
				reader.dispose();
			}
		}
		catch (IOException | RuntimeException ex)
		{
			return null;
		}
	}
}
