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

import com.customnpcmodels.cache.CacheFiles;
import com.customnpcmodels.inject.AssetBundle;
import com.customnpcmodels.inject.AssetCodec;
import com.customnpcmodels.inject.Clip;
import com.customnpcmodels.inject.Mesh;
import com.customnpcmodels.inject.NpcBinding;
import com.customnpcmodels.inject.Rig;
import com.customnpcmodels.inject.SwapBlacklist;
import com.customnpcmodels.packs.DirectoryPackSource;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import net.runelite.cache.NpcManager;
import net.runelite.cache.definitions.NpcDefinition;
import net.runelite.cache.definitions.SequenceDefinition;
import net.runelite.cache.fs.Store;

/**
 * Builds the asset bundle from the authoring manifest and the {@code .glb} files beside it.
 * <p>
 * Reads no geometry from any cache. The live cache is opened only for the frame counts and
 * lengths of the sequences authored clips stand in for, which is what they are sampled against, and
 * to check that a chathead NPC a model names has a chathead, and only when some model needs either.
 * <p>
 * Every conversion's report is printed, and the result goes through {@link AssetValidator}
 * before anything is written, so a bundle that would draw wrongly is never produced. With
 * {@code -Pdev} the bundle is written to the gitignored development resource on the test classpath
 * rather than the shipped one. With {@code -PpackOut} it is written as a pack instead - a
 * {@code bundle.dat} and a {@code pack.json} - for the plugin's local packs folder or the hub.
 * <p>
 * Run with {@code ./gradlew generateAssets [-PassetsDir=dir] [-Pdev | -PpackOut[=dir]]}.
 */
@Slf4j
public class AssetGenerator
{
	private static final String ASSETS_DIR_PROPERTY = "customnpcmodels.assetsDir";
	private static final String DEV_PROPERTY = "customnpcmodels.dev";
	private static final String PACK_OUT_PROPERTY = "customnpcmodels.packOut";

	/** Where {@code -PpackOut} with no directory writes, under a folder named for the pack. */
	private static final Path PACKS = Paths.get("build/packs");

	/** What a pack id may be: it names the pack's folder, and its branch on the hub. */
	private static final Pattern PACK_ID = Pattern.compile("[a-z0-9-]{1,64}");

	private static final Path SHIPPED = Paths.get("src/main/resources/com/customnpcmodels/custom-assets.dat");
	private static final Path DEV = Paths.get("src/test/resources/com/customnpcmodels/custom-assets-dev.dat");

	/** Supplies how a live sequence plays, or null when it does not exist. */
	interface Timings
	{
		SequenceTiming get(int sequenceId) throws IOException;
	}

	/** Whether an NPC exists and has a chathead, so a model may name it as its own. */
	interface Chatheads
	{
		boolean has(int npcId) throws IOException;
	}

	/** For a build with no cache: knows no NPC, so any chathead a model names is refused. */
	private static final Chatheads NO_CHATHEADS = npcId -> false;

	public static void main(String[] args) throws IOException
	{
		Path assetsDir = Paths.get(System.getProperty(ASSETS_DIR_PROPERTY, "assets"));
		boolean dev = Boolean.parseBoolean(System.getProperty(DEV_PROPERTY, "false"));
		String packOut = System.getProperty(PACK_OUT_PROPERTY);

		Manifest manifest = Manifest.read(assetsDir);
		log.info("Manifest {}: {} model(s)",
			assetsDir.resolve(Manifest.FILE_NAME).toAbsolutePath(), manifest.models.size());

		// Before the cache is opened, so a manifest that can never build says so without needing one
		List<String> problems = checkManifest(manifest, assetsDir);
		if (packOut != null)
		{
			problems.addAll(checkPack(manifest, dev));
		}
		refuseIfAny(problems);

		boolean animated = manifest.models.stream().anyMatch(m -> m.animations != null && !m.animations.isEmpty());
		boolean chatheads = manifest.models.stream().anyMatch(m -> m.chathead != null);
		Store store = animated || chatheads ? CacheFiles.openLiveCache() : null;

		try (store)
		{
			if ((animated || chatheads) && store == null)
			{
				System.err.println("Animations are sampled against live sequences and chatheads checked against live NPCs, "
					+ "but there is no live cache; pass one with -PcacheDir=<path>");
				System.exit(1);
				return;
			}
			AssetBundle bundle = build(manifest, assetsDir, sequenceId -> timing(store, sequenceId),
				chatheads ? chatheads(store) : NO_CHATHEADS);
			if (packOut != null)
			{
				Path dir = packOut.isEmpty() ? PACKS.resolve(manifest.pack.id) : Paths.get(packOut);
				write(bundle, dir.resolve(DirectoryPackSource.BUNDLE_FILE));
				writePackInfo(manifest.pack, bundle, dir.resolve(DirectoryPackSource.INFO_FILE));
			}
			else
			{
				write(bundle, dev ? DEV : SHIPPED);
			}
		}
	}

	static SequenceTiming timing(Store store, int sequenceId) throws IOException
	{
		if (store == null)
		{
			return null;
		}
		SequenceDefinition sequence = CacheFiles.loadSequence(store, sequenceId);
		if (sequence == null || sequence.frameIDs == null || sequence.frameLengths == null)
		{
			return null;
		}
		return new SequenceTiming(sequenceId, sequence.frameLengths);
	}

	/** The live cache's NPCs, loaded once, as a {@link Chatheads}. */
	static Chatheads chatheads(Store store) throws IOException
	{
		NpcManager npcs = new NpcManager(store);
		npcs.load();
		return npcId ->
		{
			NpcDefinition npc = npcs.get(npcId);
			return npc != null && npc.chatheadModels != null && npc.chatheadModels.length > 0;
		};
	}

	/** {@link #build(Manifest, Path, Timings, Chatheads)} for a manifest that names no chatheads. */
	static AssetBundle build(Manifest manifest, Path assetsDir, Timings timings) throws IOException
	{
		return build(manifest, assetsDir, timings, NO_CHATHEADS);
	}

	/**
	 * Converts and validates every model in the manifest into one bundle.
	 *
	 * @throws IllegalStateException naming every problem, when the result would not be valid
	 */
	static AssetBundle build(Manifest manifest, Path assetsDir, Timings timings, Chatheads chatheads)
		throws IOException
	{
		refuseIfAny(checkManifest(manifest, assetsDir));

		Map<Integer, Mesh> meshes = new LinkedHashMap<>();
		Map<Integer, Rig> rigs = new LinkedHashMap<>();
		List<Clip> clips = new ArrayList<>();
		Set<Long> clipKeys = new HashSet<>();
		List<NpcBinding> bindings = new ArrayList<>();
		Map<Integer, Integer> frameCounts = new HashMap<>();
		List<String> problems = new ArrayList<>();

		for (Manifest.Model model : manifest.models)
		{
			String name = model.name == null ? model.glb : model.name;
			System.out.println(name);

			Map<String, SequenceTiming> animations = new LinkedHashMap<>();
			for (Map.Entry<String, Integer> entry : model.animations == null
				? new LinkedHashMap<String, Integer>().entrySet() : model.animations.entrySet())
			{
				// Two models may both answer for one sequence: clips are keyed by rig as well, and every
				// model has a rig of its own (reusing one is refused below)
				int sequenceId = entry.getValue();
				SequenceTiming timing = timings.get(sequenceId);
				if (timing == null)
				{
					problems.add(name + " maps animation '" + entry.getKey() + "' to sequence " + sequenceId
						+ ", which is not a frame-based live sequence");
					continue;
				}
				animations.put(entry.getKey(), timing);
				frameCounts.put(sequenceId, timing.frameCount());
			}

			byte[] glb = Files.readAllBytes(assetsDir.resolve(model.glb));
			GltfToMeshConverter.Result result;
			try
			{
				result = GltfToMeshConverter.convert(glb, model.meshId, model.rigId, animations);
			}
			catch (GltfException ex)
			{
				problems.add(name + ": " + ex.getMessage());
				continue;
			}

			for (String line : result.report)
			{
				System.out.println("  " + line);
			}
			log.info("  mesh {}  verts={} faces={} rigged={}",
				model.meshId, result.mesh.getVerticesCount(), result.mesh.getFaceCount(), result.mesh.isRigged());

			if (meshes.putIfAbsent(model.meshId, result.mesh) != null)
			{
				problems.add(name + " reuses mesh id " + model.meshId);
			}
			if (result.rig != null && model.rigId < GltfExporter.ID_BASE)
			{
				problems.add(name + " is rigged but has no rig id; set \"rigId\" to one at or above "
					+ GltfExporter.ID_BASE);
			}
			else if (result.rig != null && rigs.putIfAbsent(model.rigId, result.rig) != null)
			{
				problems.add(name + " reuses rig id " + model.rigId);
			}
			for (Clip clip : result.clips)
			{
				// Two animations mapped to one sequence would each become a clip under the same key,
				// and the bundle would keep only the last
				if (!clipKeys.add(AssetBundle.clipKey(clip.getRigId(), clip.getSequenceId())))
				{
					problems.add(name + " maps two animations to sequence " + clip.getSequenceId());
					continue;
				}
				clips.add(clip);
				System.out.println("  clip " + clip.getSequenceId() + "  frames=" + clip.getFrameCount());
			}

			short[] find = null;
			short[] replace = null;
			if (model.recolors != null && !model.recolors.isEmpty())
			{
				find = new short[model.recolors.size()];
				replace = new short[model.recolors.size()];
				for (int i = 0; i < find.length; i++)
				{
					find[i] = (short) model.recolors.get(i).find;
					replace[i] = (short) model.recolors.get(i).replace;
				}
			}
			// A dialogue head draws from the cache, so a chathead with nothing there would show none at all
			int chathead = model.chathead == null ? NpcBinding.NO_CHATHEAD : model.chathead;
			if (model.chathead != null && (chathead < 0 || !chatheads.has(chathead)))
			{
				problems.add(name + " names chathead NPC " + chathead + ", which has no chathead in the cache");
			}

			// A glb with no skin converts to no rig, and so draws at rest; its binding names none
			int rigId = result.rig == null ? NpcBinding.STATIC : model.rigId;
			bindings.add(new NpcBinding(name, model.npcIds, new int[]{model.meshId}, rigId,
				model.scaleXZ(), model.scaleY(), find, replace,
				model.ambient == null ? 0 : model.ambient, model.contrast == null ? 0 : model.contrast,
				chathead, NpcBinding.NO_CHATHEAD, NpcBinding.STATIC));
		}

		AssetBundle bundle = new AssetBundle(meshes, rigs, clips, bindings);
		problems.addAll(AssetValidator.validate(bundle, sequenceId -> frameCounts.getOrDefault(sequenceId, -1)));
		refuseIfAny(problems);
		return bundle;
	}

	/**
	 * What is wrong with the manifest itself, found before any {@code .glb} is read or any sequence
	 * looked up.
	 * <p>
	 * These would otherwise surface as a bare exception from deep inside the build - a missing
	 * {@code npcIds} as a NullPointerException, a missing file as a NoSuchFileException - or not at
	 * all: a missing {@code meshId} quietly becomes 0. A blacklisted NPC is refused here too, so a
	 * bundle binding one is never produced.
	 */
	static List<String> checkManifest(Manifest manifest, Path assetsDir)
	{
		List<String> problems = new ArrayList<>();
		for (Manifest.Model model : manifest.models)
		{
			String name = model.name == null ? model.glb : model.name;

			if (model.npcIds == null || model.npcIds.length == 0)
			{
				problems.add(name + " names no NPCs; add \"npcIds\"");
			}
			else
			{
				for (int npcId : model.npcIds)
				{
					if (SwapBlacklist.isBlocked(npcId))
					{
						problems.add(name + ": NPC " + npcId + " is in " + SwapBlacklist.contentOf(npcId)
							+ " and can never be swapped");
					}
				}
			}

			if (model.meshId < GltfExporter.ID_BASE)
			{
				problems.add(name + " has mesh id " + model.meshId + "; synthetic ids start at " + GltfExporter.ID_BASE
					+ ", so set \"meshId\" to one at or above it");
			}
			// A static model may leave its rig id out, but one that is set has to be synthetic too
			if (model.rigId != 0 && model.rigId < GltfExporter.ID_BASE)
			{
				problems.add(name + " has rig id " + model.rigId + "; synthetic ids start at " + GltfExporter.ID_BASE
					+ ", so set \"rigId\" to one at or above it");
			}

			if (model.glb == null)
			{
				problems.add(name + " names no .glb; add \"glb\"");
			}
			else if (!Files.isRegularFile(assetsDir.resolve(model.glb)))
			{
				problems.add(name + ": " + assetsDir.resolve(model.glb).toAbsolutePath() + " does not exist");
			}
		}
		return problems;
	}

	/** What stops the manifest being built into a pack with {@code -PpackOut}. */
	static List<String> checkPack(Manifest manifest, boolean dev)
	{
		List<String> problems = new ArrayList<>();
		if (dev)
		{
			problems.add("-PpackOut and -Pdev write to different places; use one or the other");
		}
		if (manifest.pack == null)
		{
			problems.add("The manifest has no \"pack\" block, which -PpackOut needs for the pack's id and name");
		}
		else
		{
			if (manifest.pack.id == null || !PACK_ID.matcher(manifest.pack.id).matches())
			{
				problems.add("The pack id '" + manifest.pack.id + "' may only use lowercase letters, digits and "
					+ "hyphens, up to 64 of them");
			}
			if (manifest.pack.name == null || manifest.pack.name.isBlank())
			{
				problems.add("The pack has no \"name\"");
			}
		}
		return problems;
	}

	/**
	 * The pack's {@code pack.json}: the {@code pack} block as written, plus the models it carries -
	 * taken from the bundle, so the list can never drift from what is actually in it.
	 */
	static void writePackInfo(Manifest.Pack pack, AssetBundle bundle, Path output) throws IOException
	{
		JsonObject json = Glb.GSON.toJsonTree(pack).getAsJsonObject();
		JsonArray models = new JsonArray();
		for (NpcBinding binding : bundle.getBindings())
		{
			JsonObject model = new JsonObject();
			model.addProperty("key", binding.getMeshIds()[0]);
			model.addProperty("name", binding.getName());
			model.add("npcIds", Glb.GSON.toJsonTree(binding.getNpcIds()));
			models.add(model);
		}
		json.add("models", models);

		Files.createDirectories(output.getParent());
		Files.write(output, Glb.GSON.newBuilder().setPrettyPrinting().create().toJson(json)
			.getBytes(StandardCharsets.UTF_8));
		System.out.println("Wrote " + output.toAbsolutePath());
	}

	private static void refuseIfAny(List<String> problems)
	{
		if (!problems.isEmpty())
		{
			throw new IllegalStateException("Not writing the bundle; " + problems.size() + " problem(s):\n  "
				+ String.join("\n  ", problems));
		}
	}

	private static void write(AssetBundle bundle, Path output) throws IOException
	{
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		AssetCodec.write(bundle, bytes);

		// Read it back through the plugin's own loader, so a bundle the plugin would refuse is never written
		AssetCodec.read(new ByteArrayInputStream(bytes.toByteArray()));
		if (bytes.size() > AssetCodec.MAX_FILE_BYTES)
		{
			throw new IllegalStateException("Not writing the bundle; it is " + AssetCodec.mebibytes(bytes.size())
				+ ", and the plugin refuses a pack past " + AssetCodec.mebibytes(AssetCodec.MAX_FILE_BYTES));
		}

		Files.createDirectories(output.getParent());
		Files.write(output, bytes.toByteArray());
		System.out.println();
		log.info("Wrote {}  ({} KB, {})", output.toAbsolutePath(), Files.size(output) / 1024, bundle);
	}
}
