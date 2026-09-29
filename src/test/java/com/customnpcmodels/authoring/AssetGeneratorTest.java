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
package com.customnpcmodels.authoring;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import com.customnpcmodels.ModelCache;
import com.customnpcmodels.inject.AssetBundle;
import com.customnpcmodels.inject.AssetCodec;
import com.customnpcmodels.inject.Mesh;
import com.customnpcmodels.inject.NpcBinding;
import com.customnpcmodels.packs.TestPacks;
import com.google.gson.JsonObject;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.runelite.api.Model;
import net.runelite.api.NPC;
import net.runelite.api.gameval.NpcID;
import net.runelite.cache.NpcManager;
import net.runelite.cache.definitions.NpcDefinition;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * The whole pipeline, cache to plugin: export the Giant Mole, build a bundle from the manifest the
 * export wrote, push it through the codec, and hand it to the plugin's own {@link ModelCache}.
 */
public class AssetGeneratorTest
{
	@Rule
	public TemporaryFolder folder = new TemporaryFolder();

	@Test
	public void testAnEmptyManifestBuildsAnEmptyBundle() throws Exception
	{
		AssetBundle bundle = AssetGenerator.build(new Manifest(), folder.getRoot().toPath(), id -> null);
		assertTrue(bundle.isEmpty());

		AssetBundle read = codecRoundTrip(bundle);
		assertTrue(read.isEmpty());

		// With nothing bound, the plugin substitutes nothing
		ModelCache cache = new ModelCache();
		cache.setCatalog(TestPacks.catalogOf(read));
		assertFalse(cache.ensureBuilt(NpcID.MOLE_GIANT));
	}

	/** An entry that passes every manifest check, so each case below is one change away from it. */
	private Manifest.Model entry() throws Exception
	{
		folder.newFile("model.glb");
		Manifest.Model model = new Manifest.Model();
		model.name = "Model";
		model.glb = "model.glb";
		model.meshId = GltfExporter.ID_BASE + NpcID.MOLE_GIANT;
		model.rigId = model.meshId;
		model.npcIds = new int[]{NpcID.MOLE_GIANT};
		return model;
	}

	/** Refused by the manifest checks, before any .glb is read or cache opened - hence no timings. */
	private void assertManifestRefused(Manifest.Model model, String expected) throws Exception
	{
		Manifest manifest = new Manifest();
		manifest.models.add(model);
        assertEquals("the entry should differ from a valid one only by the change under test",
				1, AssetGenerator.checkManifest(manifest, folder.getRoot().toPath()).size());
		try
		{
			AssetGenerator.build(manifest, folder.getRoot().toPath(), id -> null);
			fail("expected a refusal naming " + expected);
		}
		catch (IllegalStateException ex)
		{
			assertTrue(ex.getMessage(), ex.getMessage().contains(expected));
		}
	}

	@Test
	public void testAValidEntryPassesTheManifestChecks() throws Exception
	{
		Manifest manifest = new Manifest();
		manifest.models.add(entry());
		assertTrue(AssetGenerator.checkManifest(manifest, folder.getRoot().toPath()).isEmpty());
	}

	@Test
	public void testABlacklistedNpcIsRefused() throws Exception
	{
		Manifest.Model model = entry();
		model.npcIds = new int[]{NpcID.INFERNO_TZKALZUK_PLACEHOLDER};
		assertManifestRefused(model, "NPC " + NpcID.INFERNO_TZKALZUK_PLACEHOLDER + " is in the Inferno");
	}

	@Test
	public void testMissingNpcIdsAreRefused() throws Exception
	{
		Manifest.Model model = entry();
		model.npcIds = null;
		assertManifestRefused(model, "names no NPCs");
	}

	@Test
	public void testAMissingMeshIdIsRefused() throws Exception
	{
		Manifest.Model model = entry();
		model.meshId = 0;
		assertManifestRefused(model, "has mesh id 0");
	}

	@Test
	public void testAMissingGlbIsRefused() throws Exception
	{
		Manifest.Model model = entry();
		model.glb = "absent.glb";
		assertManifestRefused(model, "absent.glb does not exist");
	}

	private static AssetBundle codecRoundTrip(AssetBundle bundle) throws Exception
	{
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		AssetCodec.write(bundle, out);
		return AssetCodec.read(new ByteArrayInputStream(out.toByteArray()));
	}

	private Path exportMole() throws Exception
	{
		NpcManager npcs = new NpcManager(LiveFixtures.store());
		npcs.load();
		NpcDefinition mole = npcs.get(NpcID.MOLE_GIANT);

		Set<Integer> sequences = new LinkedHashSet<>();
		sequences.add(LiveFixtures.MOLE_READY);
		sequences.add(LiveFixtures.MOLE_WALK);

		Path dir = folder.newFolder("assets").toPath();
		GltfExporter.export(LiveFixtures.store(), mole, sequences, dir);
		return dir;
	}

	@Test
	public void testTheExportedMoleBuildsIntoABundleThePluginDraws() throws Exception
	{
		Path dir = exportMole();
		Manifest manifest = Manifest.read(dir);
		assertEquals(1, manifest.models.size());
		assertEquals(118, manifest.models.get(0).scaleXZ());

		AssetBundle bundle = codecRoundTrip(AssetGenerator.build(manifest, dir,
			id -> AssetGenerator.timing(LiveFixtures.store(), id)));

		NpcBinding binding = bundle.getBinding(NpcID.MOLE_GIANT);
		assertNotNull(binding);
		assertEquals(118, binding.getScaleXZ());
		assertEquals(118, binding.getScaleY());
		assertEquals(LiveFixtures.mole().getVerticesCount(), bundle.getMesh(binding.getMeshIds()[0]).getVerticesCount());
		assertEquals(LiveFixtures.liveFrameCount(LiveFixtures.MOLE_READY), bundle.getClip(binding.getRigId(), LiveFixtures.MOLE_READY).getFrameCount());
		assertEquals(LiveFixtures.liveFrameCount(LiveFixtures.MOLE_WALK), bundle.getClip(binding.getRigId(), LiveFixtures.MOLE_WALK).getFrameCount());

		ModelCache cache = new ModelCache();
		cache.setCatalog(TestPacks.catalogOf(bundle));
		assertTrue(cache.ensureBuilt(NpcID.MOLE_GIANT));
		cache.setSubstituted(NpcID.MOLE_GIANT);

		NPC npc = mock(NPC.class);
		when(npc.getId()).thenReturn(NpcID.MOLE_GIANT);
		when(npc.getAnimation()).thenReturn(-1);
		when(npc.getPoseAnimation()).thenReturn(LiveFixtures.MOLE_WALK);
		when(npc.getPoseAnimationFrame()).thenReturn(3);

		Model posed = cache.pose(npc);
		assertNotNull(posed);
		assertEquals(LiveFixtures.mole().getVerticesCount(), posed.getVerticesCount());
		assertTrue(posed.getDiameter() > 0);

		NPC other = mock(NPC.class);
		when(other.getId()).thenReturn(NpcID.MOLE_BABY_01);
		assertFalse(cache.ensureBuilt(NpcID.MOLE_BABY_01));
		assertNull(cache.pose(other));
	}

	/**
	 * Clips are keyed by rig as well as sequence, so two models may both answer for one live sequence
	 * - as every humanoid must - and each is posed by its own.
	 */
	@Test
	public void testTwoModelsCanAnswerForOneSequence() throws Exception
	{
		Path dir = exportMole();
		Manifest manifest = Manifest.read(dir);
		Manifest.Model copy = Glb.GSON.fromJson(Glb.GSON.toJson(manifest.models.get(0)), Manifest.Model.class);
		copy.name = "Second mole";
		copy.meshId++;
		copy.rigId++;
		copy.npcIds = new int[]{NpcID.MOLE_BABY_01};
		manifest.models.add(copy);

		AssetBundle bundle = codecRoundTrip(AssetGenerator.build(manifest, dir,
			id -> AssetGenerator.timing(LiveFixtures.store(), id)));

		int first = bundle.getBinding(NpcID.MOLE_GIANT).getRigId();
		int second = bundle.getBinding(NpcID.MOLE_BABY_01).getRigId();
		assertEquals(copy.rigId, second);
		assertFalse(first == second);
		assertNotNull(bundle.getClip(first, LiveFixtures.MOLE_WALK));
		assertNotNull(bundle.getClip(second, LiveFixtures.MOLE_WALK));
		assertFalse(bundle.getClip(first, LiveFixtures.MOLE_WALK) == bundle.getClip(second, LiveFixtures.MOLE_WALK));
	}

	/**
	 * Clips are keyed by rig and sequence, so a second animation mapped to the same sequence would
	 * silently replace the first. It is refused instead.
	 */
	@Test
	public void testOneModelCannotMapTwoAnimationsToOneSequence() throws Exception
	{
		Path dir = exportMole();
		Manifest manifest = Manifest.read(dir);
		Map<String, Integer> animations = new LinkedHashMap<>();
		animations.put(String.valueOf(LiveFixtures.MOLE_READY), LiveFixtures.MOLE_WALK);
		animations.put(String.valueOf(LiveFixtures.MOLE_WALK), LiveFixtures.MOLE_WALK);
		manifest.models.get(0).animations = animations;

		assertRefused(manifest, dir, "maps two animations to sequence " + LiveFixtures.MOLE_WALK);
	}

	/** A glb with no skin converts to no rig, and its binding says so rather than naming one. */
	@Test
	public void testAnUnskinnedModelNamesNoRig() throws Exception
	{
		Mesh still = new TestMesh().groups(null).build();
		Files.write(folder.getRoot().toPath().resolve("still.glb"), GlbWriter.write(still, new ArrayList<>()));
		Manifest.Model model = new Manifest.Model();
		model.name = "Still";
		model.glb = "still.glb";
		model.meshId = GltfExporter.ID_BASE + NpcID.MOLE_GIANT;
		model.rigId = model.meshId;
		model.npcIds = new int[]{NpcID.MOLE_GIANT};
		Manifest manifest = new Manifest();
		manifest.models.add(model);

		AssetBundle bundle = codecRoundTrip(AssetGenerator.build(manifest, folder.getRoot().toPath(), id -> null));

		assertEquals(NpcBinding.STATIC, bundle.getBinding(NpcID.MOLE_GIANT).getRigId());
		assertTrue(bundle.getRigs().isEmpty());
	}

	@Test
	public void testAPackNeedsAnIdAndName()
	{
		Manifest manifest = new Manifest();
		assertTrue(AssetGenerator.checkPack(manifest, false).get(0).contains("no \"pack\" block"));

		manifest.pack = new Manifest.Pack();
		manifest.pack.id = "Not A Valid Id";
		List<String> problems = AssetGenerator.checkPack(manifest, true);
		assertEquals(problems.toString(), 3, problems.size());

		manifest.pack.id = "my-pack";
		manifest.pack.name = "My pack";
		assertTrue(AssetGenerator.checkPack(manifest, false).isEmpty());
	}

	/** The model list comes from the bundle, so pack.json can never disagree with what is in it. */
	@Test
	public void testPackInfoListsTheBundlesModels() throws Exception
	{
		Manifest.Pack pack = new Manifest.Pack();
		pack.id = "my-pack";
		pack.name = "My pack";
		pack.author = "Someone";
		NpcBinding binding = new NpcBinding("Mole", new int[]{NpcID.MOLE_GIANT}, new int[]{1_005_779},
			NpcBinding.STATIC, 128, 128, null, null, 0, 0);
		AssetBundle bundle = new AssetBundle(Collections.emptyMap(), Collections.emptyMap(),
			Collections.emptyList(), Collections.singletonList(binding));

		Path file = folder.getRoot().toPath().resolve("pack.json");
		AssetGenerator.writePackInfo(pack, bundle, file);

		JsonObject json = Glb.GSON.fromJson(new String(Files.readAllBytes(file), StandardCharsets.UTF_8), JsonObject.class);
		assertEquals("My pack", json.get("name").getAsString());
		assertEquals("Someone", json.get("author").getAsString());
		JsonObject model = json.getAsJsonArray("models").get(0).getAsJsonObject();
		assertEquals(1_005_779, model.get("key").getAsInt());
		assertEquals("Mole", model.get("name").getAsString());
		assertEquals(NpcID.MOLE_GIANT, model.getAsJsonArray("npcIds").get(0).getAsInt());
	}

	@Test
	public void testAnUnknownSequenceIsRefused() throws Exception
	{
		Path dir = exportMole();
		Manifest manifest = Manifest.read(dir);
		manifest.models.get(0).animations = Collections.singletonMap(String.valueOf(LiveFixtures.MOLE_READY), 99_999_999);

		assertRefused(manifest, dir, "not a frame-based live sequence");
	}

	private static void assertRefused(Manifest manifest, Path dir, String expected) throws Exception
	{
		try
		{
			AssetGenerator.build(manifest, dir, id -> AssetGenerator.timing(LiveFixtures.store(), id));
			fail("expected a refusal naming " + expected);
		}
		catch (IllegalStateException ex)
		{
			assertTrue(ex.getMessage(), ex.getMessage().contains(expected));
		}
	}
}
