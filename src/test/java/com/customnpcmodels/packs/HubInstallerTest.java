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
import com.google.gson.Gson;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import net.runelite.client.util.Filepath;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * Installing hub packs to disk. {@link Filepath.Unchecked} stands in for the plugin's data folder,
 * which only a test may do.
 */
public class HubInstallerTest
{
	private static final Gson GSON = new Gson();

	@Rule
	public TemporaryFolder folder = new TemporaryFolder();

	private Filepath hub() throws IOException
	{
		Path dir = folder.getRoot().toPath().resolve("hub");
		Files.createDirectories(dir);
		return Filepath.Unchecked.getRooted(dir);
	}

	private List<LoadedPack> installed(Filepath hub) throws IOException
	{
		return new DirectoryPackSource(hub, PackKind.HUB, GSON).load();
	}

	@Test
	public void testAnInstalledPackLoadsWithItsCommit() throws IOException
	{
		Filepath hub = hub();
		byte[] bundle = HubClientTest.bundleBytes();
		HubEntry entry = HubClientTest.entry("goblins", bundle);

		HubInstaller.install(hub, entry, bundle, new byte[]{1}, GSON);

		List<LoadedPack> packs = installed(hub);
		assertEquals(1, packs.size());
		LoadedPack pack = packs.get(0);
		assertTrue(pack.getError(), pack.isLoaded());
		assertEquals("hub:goblins", pack.getId());
		assertEquals("Pack goblins", pack.getInfo().getName());
		assertEquals(entry.getCommit(), pack.getInfo().getCommit());
		assertTrue(Files.exists(folder.getRoot().toPath().resolve("hub/goblins/icon.png")));
		assertEquals("no staging folder is left behind", 1, folder.getRoot().toPath().resolve("hub").toFile().list().length);
	}

	/** A folder can't be moved over another, so an update has to set the old copy aside first. */
	@Test
	public void testAnUpdateReplacesTheInstalledCopy() throws IOException
	{
		Filepath hub = hub();
		byte[] bundle = HubClientTest.bundleBytes();
		HubInstaller.install(hub, HubClientTest.entry("goblins", bundle), bundle, null, GSON);
		HubEntry newer = HubClient.parseManifest(GSON, "[" + HubClientTest.entryJson("goblins", bundle)
			.replace("0123456789abcdef0123456789abcdef01234567", "fedcba9876543210fedcba9876543210fedcba98") + "]").get(0);

		HubInstaller.install(hub, newer, bundle, null, GSON);

		List<LoadedPack> packs = installed(hub);
		assertEquals(1, packs.size());
		assertEquals(newer.getCommit(), packs.get(0).getInfo().getCommit());
		assertFalse("the old copy is gone", Files.exists(folder.getRoot().toPath().resolve("hub/goblins.old")));
	}

	@Test
	public void testARemovedPackIsGone() throws IOException
	{
		Filepath hub = hub();
		byte[] bundle = HubClientTest.bundleBytes();
		HubInstaller.install(hub, HubClientTest.entry("goblins", bundle), bundle, null, GSON);

		HubInstaller.remove(hub, "goblins");
		HubInstaller.remove(hub, "never-installed");

		assertTrue(installed(hub).isEmpty());
	}

	/** An install interrupted by a crash leaves its staging folder and the copy it set aside. */
	@Test
	public void testLeftoversOfAnInterruptedInstallAreCleared() throws IOException
	{
		Filepath hub = hub();
		byte[] bundle = HubClientTest.bundleBytes();
		HubInstaller.install(hub, HubClientTest.entry("goblins", bundle), bundle, null, GSON);
		Path root = folder.getRoot().toPath().resolve("hub");
		Files.createDirectories(root.resolve("dl-12345"));
		Files.createDirectories(root.resolve("goblins.old"));
		Files.write(root.resolve("goblins.old").resolve("bundle.dat"), bundle);

		HubInstaller.clearLeftovers(hub);

		assertFalse(Files.exists(root.resolve("dl-12345")));
		assertFalse(Files.exists(root.resolve("goblins.old")));
		assertTrue("the installed pack is untouched", Files.exists(root.resolve("goblins/bundle.dat")));
	}

	/**
	 * Interrupted between setting the installed copy aside and moving the new one in, an update leaves
	 * only the old copy. That is put back, not deleted.
	 */
	@Test
	public void testTheOnlyCopyLeftByAnInterruptedUpdateIsRestored() throws IOException
	{
		Filepath hub = hub();
		byte[] bundle = HubClientTest.bundleBytes();
		HubInstaller.install(hub, HubClientTest.entry("goblins", bundle), bundle, null, GSON);
		Path root = folder.getRoot().toPath().resolve("hub");
		Files.move(root.resolve("goblins"), root.resolve("goblins.old"));

		HubInstaller.clearLeftovers(hub);

		assertFalse(Files.exists(root.resolve("goblins.old")));
		List<LoadedPack> packs = installed(hub);
		assertEquals(1, packs.size());
		assertTrue(packs.get(0).isLoaded());
	}
}
