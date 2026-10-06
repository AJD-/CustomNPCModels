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
import static org.junit.Assert.assertTrue;
import com.customnpcmodels.inject.AssetCodec;
import com.google.gson.Gson;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import net.runelite.api.gameval.NpcID;
import net.runelite.client.util.Filepath;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * Reading packs from a folder. {@link Filepath.Unchecked} roots the temporary folder, which only a
 * test may do - the plugin gets its folder from {@code getPluginDirectory()}.
 */
public class DirectoryPackSourceTest
{
	@Rule
	public TemporaryFolder folder = new TemporaryFolder();

	private Path pack(String name) throws IOException
	{
		Path dir = folder.getRoot().toPath().resolve(name);
		Files.createDirectories(dir);
		return dir;
	}

	private List<LoadedPack> load() throws IOException
	{
		Filepath root = Filepath.Unchecked.getRooted(folder.getRoot().toPath());
		return new DirectoryPackSource(root, PackKind.LOCAL, new Gson()).load();
	}

	@Test
	public void testEveryPackFolderIsRead() throws IOException
	{
		Files.write(pack("alpha").resolve("bundle.dat"), TestPacks.bundleBytes());
		Files.write(pack("alpha").resolve("pack.json"),
			"{\"id\": \"ignored\", \"name\": \"Alpha pack\", \"author\": \"Someone\", \"tags\": [\"moles\"]}"
				.getBytes(StandardCharsets.UTF_8));
		Files.write(pack("beta").resolve("bundle.dat"), TestPacks.bundleBytes());
		// Neither a loose file nor a hidden folder is a pack
		Files.write(folder.getRoot().toPath().resolve("notes.txt"), new byte[]{1});
		Files.write(pack(".hidden").resolve("bundle.dat"), TestPacks.bundleBytes());

		List<LoadedPack> packs = load();

		assertEquals(2, packs.size());
		LoadedPack alpha = packs.get(0);
		assertTrue(alpha.getError(), alpha.isLoaded());
		assertEquals("the id comes from the folder, never from pack.json", "local:alpha", alpha.getId());
		assertEquals("Alpha pack", alpha.getInfo().getName());
		assertEquals("Someone", alpha.getInfo().getAuthor());
		assertEquals(Collections.singletonList("moles"), alpha.getInfo().getTags());
		assertNotNull(alpha.getBundle().getBinding(NpcID.MOLE_GIANT));

		LoadedPack beta = packs.get(1);
		assertTrue(beta.isLoaded());
		assertEquals("a pack with no pack.json is named after its folder", "beta", beta.getInfo().getName());
	}

	/** Each broken pack says why, and none of them stops the good one. */
	@Test
	public void testABrokenPackSaysWhatIsWrong() throws IOException
	{
		Files.write(pack("corrupt").resolve("bundle.dat"), new byte[]{1, 2, 3});
		// Truncated: the exception it throws has no message of its own
		Files.write(pack("zero").resolve("bundle.dat"), new byte[0]);
		pack("empty");
		Files.write(pack("badjson").resolve("bundle.dat"), TestPacks.bundleBytes());
		Files.write(pack("badjson").resolve("pack.json"), "{not json".getBytes(StandardCharsets.UTF_8));
		Files.write(pack("bad,name").resolve("bundle.dat"), TestPacks.bundleBytes());
		Files.write(pack("good").resolve("bundle.dat"), TestPacks.bundleBytes());

		List<LoadedPack> packs = load();

		assertEquals(6, packs.size());
		for (LoadedPack pack : packs)
		{
			if (pack.getId().equals("local:good"))
			{
				assertTrue(pack.isLoaded());
			}
			else
			{
				assertFalse(pack.getId() + " should not load", pack.isLoaded());
				assertNotNull(pack.getId() + " should say why", pack.getError());
			}
		}
		assertTrue(find(packs, "local:empty").getError().contains("no bundle.dat"));
		assertTrue(find(packs, "local:badjson").getError().contains("pack.json"));
		assertTrue(find(packs, "local:bad,name").getError().contains("folder names"));
	}

	@Test
	public void testAnOversizedPackJsonIsRefusedUnread() throws IOException
	{
		Files.write(pack("huge").resolve("bundle.dat"), TestPacks.bundleBytes());
		Files.write(pack("huge").resolve("pack.json"), new byte[(int) DirectoryPackSource.MAX_INFO_BYTES + 1]);

		LoadedPack huge = find(load(), "local:huge");

		assertFalse(huge.isLoaded());
		assertTrue(huge.getError(), huge.getError().contains("KiB limit"));
	}

	/**
	 * Names {@link Filepath} would refuse are refused as a pack instead, rather than throwing out of
	 * the whole load. Checked on the rule directly: Windows cannot even create most of these folders.
	 */
	@Test
	public void testFolderNamesFilepathRefusesAreRefused()
	{
		for (String bad : new String[]{"mypack.", "mypack ", " mypack", ".pack", "aux", "CON", "com1", "nul.v2",
			"a,b", "a|b", "a:b"})
		{
			assertFalse(bad, DirectoryPackSource.isValidFolderName(bad));
		}
		for (String good : new String[]{"mypack", "My Pack", "pack.v2", "a", "goblins-1_2", "auxiliary"})
		{
			assertTrue(good, DirectoryPackSource.isValidFolderName(good));
		}
	}

	/** An interrupted hub install leaves its staging folders behind; neither is a pack. */
	@Test
	public void testHubStagingFoldersAreNotPacks() throws IOException
	{
		Files.write(pack("goblins").resolve("bundle.dat"), TestPacks.bundleBytes());
		Files.write(pack("dl-12345").resolve("bundle.dat"), TestPacks.bundleBytes());
		Files.write(pack("goblins.old").resolve("bundle.dat"), TestPacks.bundleBytes());

		List<LoadedPack> packs = new DirectoryPackSource(Filepath.Unchecked.getRooted(folder.getRoot().toPath()),
			PackKind.HUB, new Gson()).load();

		assertEquals(1, packs.size());
		assertEquals("hub:goblins", packs.get(0).getId());
	}

	@Test
	public void testAnOversizedBundleIsNotRead() throws IOException
	{
		try (RandomAccessFile file = new RandomAccessFile(pack("huge").resolve("bundle.dat").toFile(), "rw"))
		{
			file.setLength(AssetCodec.MAX_FILE_BYTES + 1);
		}

		LoadedPack pack = load().get(0);

		assertFalse(pack.isLoaded());
		assertTrue(pack.getError(), pack.getError().contains("limit"));
	}

	@Test
	public void testAMissingFolderHasNoPacks() throws IOException
	{
		Filepath missing = Filepath.Unchecked.getRooted(folder.getRoot().toPath()).joinSegment("absent");
		assertTrue(new DirectoryPackSource(missing, PackKind.HUB, new Gson()).load().isEmpty());
	}

	private static LoadedPack find(List<LoadedPack> packs, String id)
	{
		return packs.stream().filter(pack -> pack.getId().equals(id)).findFirst().orElseThrow(AssertionError::new);
	}
}
