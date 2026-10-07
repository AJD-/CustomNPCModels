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
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpServer;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import javax.imageio.ImageIO;

/**
 * A Custom Model Hub on localhost, for trying the plugin's hub client in game before the real one
 * exists. It serves, from memory, what the real hub serves from GitHub: a manifest, and each pack's
 * files at its commit. The packs are the development bundle's models:
 *
 * <ul>
 * <li>{@code mole-hub}, which installs;</li>
 * <li>{@code future-pack}, built for a newer bundle format, which the panel refuses to install;</li>
 * <li>{@code tampered}, whose download doesn't match its checksum, which is refused after download.</li>
 * </ul>
 *
 * <pre>
 * ./gradlew serveHubFixture [-Prevision=2] [-Pdelay=15]
 * ./gradlew run -PhubUrl=http://localhost:8765/
 * </pre>
 *
 * <p>A different {@code -Prevision} gives {@code mole-hub} a new commit, so an installed copy shows
 * an update. {@code -Pdelay} holds every {@code bundle.dat} back that many seconds, long enough to
 * switch the hub off in the plugin's settings while an install is downloading. Stop it with Ctrl+C.
 */
public final class HubFixture
{
	private static final int PORT = 8765;
	private static final String DEV_RESOURCE = "/com/customnpcmodels/custom-assets-dev.dat";

	private HubFixture()
	{
	}

	public static void main(String[] args) throws IOException
	{
		int revision = Integer.parseInt(System.getProperty("customnpcmodels.revision", "1"));
		int delay = Integer.parseInt(System.getProperty("customnpcmodels.delay", "0"));

		byte[] bundle;
		try (InputStream in = HubFixture.class.getResourceAsStream(DEV_RESOURCE))
		{
			if (in == null)
			{
				System.err.println("No development bundle; run ./gradlew generateAssets -PassetsDir=assets-dev -Pdev first");
				System.exit(1);
				return;
			}
			bundle = in.readAllBytes();
		}
		byte[] icon = icon();

		Map<String, byte[]> files = new HashMap<>();
		JsonArray manifest = new JsonArray();

		String moleCommit = commit("mole-hub", revision);
		manifest.add(entry("mole-hub", "Mole (test hub)", "A copy of the development bundle, served by the test hub. "
			+ "Revision " + revision + ".", "1." + revision, AssetCodec.VERSION, moleCommit, bundle, bundle, true));
		files.put(moleCommit + "/bundle.dat", bundle);
		files.put(moleCommit + "/icon.png", icon);

		String futureCommit = commit("future-pack", 1);
		manifest.add(entry("future-pack", "From the future", "Built for a newer bundle format than this plugin reads.",
			"1.0", AssetCodec.VERSION + 1, futureCommit, bundle, bundle, false));
		files.put(futureCommit + "/bundle.dat", bundle);

		String tamperedCommit = commit("tampered", 1);
		byte[] tampered = bundle.clone();
		tampered[tampered.length - 1] ^= 1;
		manifest.add(entry("tampered", "Tampered", "Its download doesn't match the checksum the manifest lists.",
			"1.0", AssetCodec.VERSION, tamperedCommit, bundle, tampered, false));
		files.put(tamperedCommit + "/bundle.dat", tampered);

		files.put(HubClient.MANIFEST_PATH, manifest.toString().getBytes(StandardCharsets.UTF_8));

		HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), PORT), 0);
		// Answers are sent from here, so a held-back bundle doesn't hold up the manifest and icons
		ScheduledExecutorService answers = Executors.newScheduledThreadPool(4);
		server.createContext("/", exchange ->
		{
			String path = exchange.getRequestURI().getPath().substring(1);
			byte[] body = files.get(path);
			int wait = body != null && path.endsWith("/bundle.dat") ? delay : 0;
			System.out.println(exchange.getRequestMethod() + " " + exchange.getRequestURI() + " -> "
				+ (body == null ? 404 : 200) + (wait > 0 ? " in " + wait + "s" : ""));
			answers.schedule(() ->
			{
				try
				{
					exchange.sendResponseHeaders(body == null ? 404 : 200, body == null ? -1 : body.length);
					if (body != null)
					{
						try (OutputStream out = exchange.getResponseBody())
						{
							out.write(body);
						}
					}
					if (wait > 0)
					{
						System.out.println("Sent " + exchange.getRequestURI());
					}
				}
				catch (IOException ex)
				{
					// The client hung up first, as a cancelled download does
					System.out.println("Not sent " + exchange.getRequestURI() + ": " + ex.getMessage());
				}
				finally
				{
					exchange.close();
				}
			}, wait, TimeUnit.SECONDS);
		});
		server.start();
		System.out.println("Test Custom Model Hub at http://localhost:" + PORT + "/ (revision " + revision
			+ (delay > 0 ? ", bundles held back " + delay + "s" : "") + "). Start the client with ./gradlew run -PhubUrl=http://localhost:" + PORT + "/ and stop this with Ctrl+C.");
	}

	/** A manifest entry listing {@code listed}'s size and checksum, whatever is actually served. */
	private static JsonObject entry(String id, String name, String description, String version, int format,
		String commit, byte[] listed, byte[] served, boolean hasIcon)
	{
		JsonObject entry = new JsonObject();
		entry.addProperty("id", id);
		entry.addProperty("name", name);
		entry.addProperty("author", "Test hub");
		entry.addProperty("description", description);
		entry.addProperty("version", version);
		entry.addProperty("formatVersion", format);
		entry.addProperty("commit", commit);
		entry.addProperty("size", served.length);
		entry.addProperty("sha256", HubClient.sha256(listed));
		entry.addProperty("hasIcon", hasIcon);
		entry.addProperty("repo", "https://github.com/AJD-/CustomNPCModels");
		JsonArray models = new JsonArray();
		JsonObject model = new JsonObject();
		model.addProperty("key", 1_005_779);
		model.addProperty("name", "Giant Mole");
		JsonArray npcIds = new JsonArray();
		npcIds.add(5779);
		model.add("npcIds", npcIds);
		models.add(model);
		entry.add("models", models);
		return entry;
	}

	/** A 40-character hex commit, fixed for a pack and revision. */
	private static String commit(String id, int revision)
	{
		return HubClient.sha256((id + "#" + revision).getBytes(StandardCharsets.UTF_8)).substring(0, 40);
	}

	private static byte[] icon() throws IOException
	{
		BufferedImage image = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = image.createGraphics();
		g.setColor(new Color(220, 138, 0));
		g.fillOval(8, 8, 48, 48);
		g.dispose();
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		ImageIO.write(image, "png", out);
		return out.toByteArray();
	}
}
