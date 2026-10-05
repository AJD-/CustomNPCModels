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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import com.customnpcmodels.inject.AssetBundle;
import com.customnpcmodels.inject.AssetCodec;
import com.customnpcmodels.inject.Mesh;
import com.customnpcmodels.inject.NpcBinding;
import com.google.gson.Gson;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.imageio.ImageIO;
import net.runelite.api.gameval.NpcID;
import okhttp3.Dispatcher;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.junit.Test;

/** The hub client against a hub faked in memory: nothing here goes over the network. */
public class HubClientTest
{
	private static final String BASE = "https://hub.test/";
	private static final String COMMIT = "0123456789abcdef0123456789abcdef01234567";
	private static final Gson GSON = new Gson();

	static byte[] bundleBytes() throws IOException
	{
		Mesh triangle = new Mesh(1_005_779, 0,
			new float[]{0, 10, 0}, new float[]{0, 0, 10}, new float[]{0, 0, 0},
			new int[]{0}, new int[]{1}, new int[]{2}, new short[]{(short) 0x3A05},
			null, null, null, null, null, null, null, null, null);
		NpcBinding binding = new NpcBinding("Mole", new int[]{NpcID.MOLE_GIANT}, new int[]{1_005_779},
			NpcBinding.STATIC, 128, 128, null, null, 0, 0);
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		AssetCodec.write(new AssetBundle(Collections.singletonMap(1_005_779, triangle), Collections.emptyMap(),
			Collections.emptyList(), Collections.singletonList(binding)), out);
		return out.toByteArray();
	}

	static String entryJson(String id, byte[] bundle)
	{
		return "{\"id\": \"" + id + "\", \"name\": \"Pack " + id + "\", \"author\": \"Someone\", \"formatVersion\": "
			+ AssetCodec.VERSION + ", \"commit\": \"" + COMMIT + "\", \"size\": " + bundle.length + ", \"sha256\": \""
			+ HubClient.sha256(bundle) + "\", \"repo\": \"https://github.com/AJD-/custom-model-hub/tree/pack-" + id
			+ "\", \"models\": [{\"key\": 1005779, \"name\": \"Mole\", \"npcIds\": [5779]}]}";
	}

	static HubEntry entry(String id, byte[] bundle)
	{
		return HubClient.parseManifest(GSON, "[" + entryJson(id, bundle) + "]").get(0);
	}

	/** A client whose every request is answered from {@code files}, by path under the base URL. */
	private static HubClient client(Map<String, byte[]> files)
	{
		return client(files, new OkHttpClient.Builder());
	}

	/** As {@link #client(Map)}, with every call run on {@code calls}. */
	private static HubClient client(Map<String, byte[]> files, ExecutorService calls)
	{
		return client(files, new OkHttpClient.Builder().dispatcher(new Dispatcher(calls)));
	}

	private static HubClient client(Map<String, byte[]> files, OkHttpClient.Builder builder)
	{
		OkHttpClient http = builder.addInterceptor(chain ->
		{
			String path = chain.request().url().toString().substring(BASE.length());
			byte[] body = files.get(path);
			return new Response.Builder()
				.request(chain.request())
				.protocol(Protocol.HTTP_1_1)
				.code(body == null ? 404 : 200)
				.message(body == null ? "Not Found" : "OK")
				.body(ResponseBody.create(MediaType.parse("application/octet-stream"), body == null ? new byte[0] : body))
				.build();
		}).build();
		return new HubClient(http, GSON, BASE);
	}

	@Test
	public void testTheManifestListsItsValidPacks() throws Exception
	{
		byte[] bundle = bundleBytes();
		Map<String, byte[]> files = new HashMap<>();
		files.put(HubClient.MANIFEST_PATH, ("[" + entryJson("goblins", bundle) + ", " + entryJson("Bad Id", bundle) + ", "
			+ entryJson("goblins", bundle) + ", {\"id\": \"no-commit\", \"name\": \"x\"}]").getBytes());

		CompletableFuture<List<HubEntry>> result = new CompletableFuture<>();
		client(files).fetchManifest(result::complete, reason -> result.completeExceptionally(new AssertionError(reason)));
		List<HubEntry> entries = result.get(5, TimeUnit.SECONDS);

		assertEquals("a bad id, a repeat and an entry with no commit are skipped", 1, entries.size());
		HubEntry goblins = entries.get(0);
		assertEquals("hub:goblins", goblins.getPackId());
		assertTrue(goblins.isCompatible());
		assertEquals("Mole", goblins.getModels().get(0).getName());
		assertNotNull(goblins.getSafeRepo());
	}

	@Test
	public void testACallerThatThrowsIsNotBlamedOnTheHub() throws Exception
	{
		Map<String, byte[]> files = new HashMap<>();
		files.put(HubClient.MANIFEST_PATH, ("[" + entryJson("goblins", bundleBytes()) + "]").getBytes());
		ExecutorService calls = Executors.newSingleThreadExecutor();
		try
		{
			List<String> failures = new CopyOnWriteArrayList<>();
			client(files, calls).fetchManifest(entries ->
			{
				throw new IllegalStateException("the caller's own bug");
			}, failures::add);

			// Every call runs on the one thread, so once a later task has run there the callback is done
			calls.submit(() ->
			{
			}).get(5, TimeUnit.SECONDS);
			assertTrue("the manifest was read fine, whatever the caller did with it: " + failures, failures.isEmpty());
		}
		finally
		{
			calls.shutdownNow();
		}
	}

	@Test
	public void testAnUnreachableHubSaysSo() throws Exception
	{
		CompletableFuture<String> failure = new CompletableFuture<>();
		client(new HashMap<>()).fetchManifest(entries -> failure.complete("no failure"), failure::complete);

		assertTrue(failure.get(5, TimeUnit.SECONDS).contains("404"));
	}

	@Test
	public void testADownloadIsCheckedBeforeItIsHandedOver() throws Exception
	{
		byte[] bundle = bundleBytes();
		HubEntry entry = entry("goblins", bundle);
		Map<String, byte[]> files = new HashMap<>();
		files.put(COMMIT + "/bundle.dat", bundle);

		CompletableFuture<byte[]> good = new CompletableFuture<>();
		client(files).download(entry, good::complete, reason -> good.completeExceptionally(new AssertionError(reason)));
		assertEquals(bundle.length, good.get(5, TimeUnit.SECONDS).length);

		// Tampered with: the same size, one byte different
		byte[] tampered = bundle.clone();
		tampered[tampered.length - 1] ^= 1;
		files.put(COMMIT + "/bundle.dat", tampered);
		CompletableFuture<String> refused = new CompletableFuture<>();
		client(files).download(entry, bytes -> refused.complete("installed"), refused::complete);
		assertTrue(refused.get(5, TimeUnit.SECONDS).contains("checksum"));
	}

	@Test
	public void testVerifyRefusesTheWrongSizeAndAnUnreadablePack() throws IOException
	{
		byte[] bundle = bundleBytes();
		HubEntry entry = entry("goblins", bundle);

		assertNull(HubClient.verify(entry, bundle));
		assertTrue(HubClient.verify(entry, new byte[3]).contains("bytes"));

		byte[] junk = new byte[bundle.length];
		HubEntry junkEntry = entry("junk", junk);
		assertTrue(HubClient.verify(junkEntry, junk), HubClient.verify(junkEntry, junk).contains("can't be read"));
	}

	@Test
	public void testAnOversizedBodyIsRefused()
	{
		try
		{
			HubClient.readCapped(new ByteArrayInputStream(new byte[100]), 99);
			fail("expected a body past its cap to be refused");
		}
		catch (IOException expected)
		{
			assertTrue(expected.getMessage().contains("larger"));
		}
	}

	/** A PNG can declare an enormous image in a few bytes; one past the limit is never decoded. */
	@Test
	public void testIconsAreDecodedOnlyWhenSmall() throws IOException
	{
		assertNotNull(HubClient.readIcon(png(64)));
		assertNull(HubClient.readIcon(png(HubClient.MAX_ICON_SIDE + 1)));
		assertNull(HubClient.readIcon(new byte[]{1, 2, 3}));
	}

	@Test
	public void testTheChecksumIsSha256()
	{
		assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", HubClient.sha256(new byte[0]));
	}

	@Test
	public void testAPackForAnotherFormatIsListedButNotCompatible() throws IOException
	{
		byte[] bundle = bundleBytes();
		HubEntry entry = HubClient.parseManifest(GSON, "[" + entryJson("future", bundle)
			.replace("\"formatVersion\": " + AssetCodec.VERSION, "\"formatVersion\": " + (AssetCodec.VERSION + 1)) + "]").get(0);

		assertFalse(entry.isCompatible());
	}

	@Test
	public void testOnlyGithubPagesAreOpened() throws IOException
	{
		HubEntry entry = HubClient.parseManifest(GSON, "[" + entryJson("elsewhere", bundleBytes())
			.replace("https://github.com/", "file:///") + "]").get(0);
		assertNull(entry.getSafeRepo());

		// Right prefix, but opening it would throw
		HubEntry malformed = HubClient.parseManifest(GSON, "[" + entryJson("malformed", bundleBytes())
			.replace("https://github.com/AJD-", "https://github.com/a b") + "]").get(0);
		assertNull(malformed.getSafeRepo());
	}

	/**
	 * The id becomes a folder under hub/: one the installer's own staging would clear on load, or one
	 * Windows reserves, could never stay installed, so neither is listed.
	 */
	@Test
	public void testIdsThatCantBeFoldersAreSkipped() throws IOException
	{
		byte[] bundle = bundleBytes();
		List<HubEntry> entries = HubClient.parseManifest(GSON, "[" + entryJson("dl-goblins", bundle) + ", "
			+ entryJson("con", bundle) + ", " + entryJson("goblins", bundle) + "]");

		assertEquals(1, entries.size());
		assertEquals("goblins", entries.get(0).getId());
	}

	/** A null in the model list would otherwise throw while the panel draws the card. */
	@Test
	public void testNullModelsAreLeftOut() throws IOException
	{
		HubEntry entry = HubClient.parseManifest(GSON, "[" + entryJson("goblins", bundleBytes())
			.replace("\"models\": [", "\"models\": [null, {\"key\": 1}, ") + "]").get(0);

		assertEquals(1, entry.getModels().size());
		assertEquals("Mole", entry.getModels().get(0).getName());
	}

	private static byte[] png(int side) throws IOException
	{
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		ImageIO.write(new BufferedImage(side, side, BufferedImage.TYPE_INT_ARGB), "png", out);
		return out.toByteArray();
	}
}
