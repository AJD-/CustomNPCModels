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
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import com.customnpcmodels.inject.AssetBundle;
import com.customnpcmodels.inject.AssetCodec;
import com.customnpcmodels.inject.Mesh;
import com.customnpcmodels.inject.NpcBinding;
import com.google.gson.Gson;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
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
 * Importing a pack folder. {@link Filepath.Unchecked} stands in for the chooser and the plugin's data
 * folder, which only a test may do.
 */
public class PackImporterTest
{
	@Rule
	public TemporaryFolder folder = new TemporaryFolder();

	private static byte[] bundleBytes() throws IOException
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

	private Filepath source(String name, byte[] bundle, String packJson) throws IOException
	{
		Path dir = folder.getRoot().toPath().resolve("source").resolve(name);
		Files.createDirectories(dir);
		if (bundle != null)
		{
			Files.write(dir.resolve("bundle.dat"), bundle);
		}
		if (packJson != null)
		{
			Files.write(dir.resolve("pack.json"), packJson.getBytes(StandardCharsets.UTF_8));
		}
		return Filepath.Unchecked.getRooted(dir);
	}

	private Filepath local() throws IOException
	{
		Path dir = folder.getRoot().toPath().resolve("local");
		Files.createDirectories(dir);
		return Filepath.Unchecked.getRooted(dir);
	}

	@Test
	public void testAPackIsCopiedUnderItsId() throws IOException
	{
		Filepath local = local();
		// The source folder's name differs, so the folder can only have come from the id
		PackImporter.Result result = PackImporter.importFolder(
			source("exported", bundleBytes(), "{\"id\": \"Mole Test\", \"name\": \"Mole test\"}"), local, new Gson());

		assertTrue(result.getMessage(), result.isImported());
		List<LoadedPack> packs = new DirectoryPackSource(local, PackKind.LOCAL, new Gson()).load();
		assertEquals(1, packs.size());
		assertEquals("local:mole-test", packs.get(0).getId());
		assertEquals("Mole test", packs.get(0).getInfo().getName());
		assertTrue(packs.get(0).isLoaded());
	}

	@Test
	public void testAnIdThatIsNotAStringFallsBackToTheFolderName() throws IOException
	{
		Filepath local = local();
		PackImporter.Result result = PackImporter.importFolder(
			source("exported", bundleBytes(), "{\"id\": {\"not\": \"a string\"}, \"name\": \"Mole\"}"), local, new Gson());

		assertTrue(result.getMessage(), result.isImported());
		List<LoadedPack> packs = new DirectoryPackSource(local, PackKind.LOCAL, new Gson()).load();
		assertEquals("local:exported", packs.get(0).getId());
		assertTrue("the loader ignores the id the same way", packs.get(0).isLoaded());
	}

	@Test
	public void testAnExistingPackIsNeverOverwritten() throws IOException
	{
		Filepath local = local();
		assertTrue(PackImporter.importFolder(source("mole", bundleBytes(), null), local, new Gson()).isImported());

		PackImporter.Result again = PackImporter.importFolder(source("mole", bundleBytes(), null), local, new Gson());

		assertFalse(again.isImported());
		assertTrue(again.getMessage(), again.getMessage().contains("already installed"));
	}

	@Test
	public void testARemovedPackIsGone() throws IOException
	{
		Filepath local = local();
		assertTrue(PackImporter.importFolder(source("mole", bundleBytes(), null), local, new Gson()).isImported());
		assertTrue(PackImporter.importFolder(source("goblins", bundleBytes(), null), local, new Gson()).isImported());

		PackImporter.remove(local, "mole");
		PackImporter.remove(local, "never-imported");

		List<LoadedPack> packs = new DirectoryPackSource(local, PackKind.LOCAL, new Gson()).load();
		assertEquals(1, packs.size());
		assertEquals("local:goblins", packs.get(0).getId());
	}

	/** A name no pack folder could have is refused outright, so it can never reach past local/. */
	@Test
	public void testRemovingAnInvalidNameDeletesNothing() throws IOException
	{
		Filepath local = local();
		assertTrue(PackImporter.importFolder(source("mole", bundleBytes(), null), local, new Gson()).isImported());

		for (String name : new String[]{"..", ".", "con", "", "mole/..", " mole"})
		{
			try
			{
				PackImporter.remove(local, name);
				fail("'" + name + "' was accepted");
			}
			catch (IllegalArgumentException expected)
			{
				// Refused before any path was built
			}
		}

		assertTrue(Files.isDirectory(folder.getRoot().toPath().resolve("local").resolve("mole")));
		assertTrue(Files.isDirectory(folder.getRoot().toPath().resolve("source")));
	}

	/** Refused before anything is copied, so a broken pack never lands in the local folder. */
	@Test
	public void testAFolderThatIsNotAPackIsRefused() throws IOException
	{
		Filepath local = local();

		PackImporter.Result missing = PackImporter.importFolder(source("empty", null, null), local, new Gson());
		PackImporter.Result corrupt = PackImporter.importFolder(source("junk", new byte[]{1, 2, 3}, null), local, new Gson());
		PackImporter.Result badInfo = PackImporter.importFolder(
			source("tagged", bundleBytes(), "{\"name\": \"Tagged\", \"tags\": \"not a list\"}"), local, new Gson());

		assertFalse(missing.isImported());
		assertTrue(missing.getMessage(), missing.getMessage().contains("no bundle.dat"));
		assertFalse(corrupt.isImported());
		assertTrue(corrupt.getMessage(), corrupt.getMessage().contains("can't be read"));
		assertFalse("a pack.json the loader would refuse is refused here", badInfo.isImported());
		assertTrue(badInfo.getMessage(), badInfo.getMessage().contains("pack.json"));
		assertTrue(new DirectoryPackSource(local, PackKind.LOCAL, new Gson()).load().isEmpty());
	}

	@Test
	public void testFolderNamesAreAlwaysLoadable()
	{
		assertEquals("my-pack", PackImporter.folderName("My Pack!", "ignored"));
		assertEquals("from-folder", PackImporter.folderName(null, "From Folder"));
		assertEquals("con-pack", PackImporter.folderName("con", null));
		assertEquals("pack", PackImporter.folderName("...", null));
		assertTrue(DirectoryPackSource.isValidFolderName(PackImporter.folderName(null, "a.b.")));
	}
}
