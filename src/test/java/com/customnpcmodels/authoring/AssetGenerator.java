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
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import lombok.extern.slf4j.Slf4j;
import net.runelite.cache.definitions.SequenceDefinition;
import net.runelite.cache.fs.Store;

/**
 * Builds the asset bundle from the authoring manifest and the {@code .glb} files beside it.
 * <p>
 * Reads no geometry from any cache. The live cache is opened only for the frame counts and
 * lengths of the sequences authored clips stand in for, which is what they are sampled against, and
 * only when some model has animations at all.
 * <p>
 * Every conversion's report is printed, and the result goes through {@link AssetValidator}
 * before anything is written, so a bundle that would draw wrongly is never produced. With
 * {@code -Pdev} the bundle is written to the gitignored development resource on the test classpath
 * rather than the shipped one
 * <p>
 * Run with {@code ./gradlew generateAssets [-PassetsDir=dir] [-Pdev]}.
 */
@Slf4j
public class AssetGenerator
{
	private static final String ASSETS_DIR_PROPERTY = "customnpcmodels.assetsDir";
	private static final String DEV_PROPERTY = "customnpcmodels.dev";

	private static final Path SHIPPED = Paths.get("src/main/resources/com/customnpcmodels/custom-assets.dat");
	private static final Path DEV = Paths.get("src/test/resources/com/customnpcmodels/custom-assets-dev.dat");

	/** Supplies how a live sequence plays, or null when it does not exist. */
	interface Timings
	{
		SequenceTiming get(int sequenceId) throws IOException;
	}

	public static void main(String[] args) throws IOException
	{
		Path assetsDir = Paths.get(System.getProperty(ASSETS_DIR_PROPERTY, "assets"));
		boolean dev = Boolean.parseBoolean(System.getProperty(DEV_PROPERTY, "false"));

		Manifest manifest = Manifest.read(assetsDir);
        log.info("Manifest {}: {} model(s)",
				assetsDir.resolve(Manifest.FILE_NAME).toAbsolutePath(), manifest.models.size());

		// Before the cache is opened, so a manifest that can never build says so without needing one
		refuseIfAny(checkManifest(manifest, assetsDir));

		boolean needsCache = manifest.models.stream().anyMatch(m -> m.animations != null && !m.animations.isEmpty());
		Store store = needsCache ? CacheFiles.openLiveCache() : null;

        try (store) {
            if (needsCache && store == null) {
                System.err.println("Animated models are sampled against live sequences, but there is no live cache; "
                        + "pass one with -PcacheDir=<path>");
                System.exit(1);
                return;
            }
            AssetBundle bundle = build(manifest, assetsDir, sequenceId -> timing(store, sequenceId));
            write(bundle, dev ? DEV : SHIPPED);
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

	/**
	 * Converts and validates every model in the manifest into one bundle.
	 *
	 * @throws IllegalStateException naming every problem, when the result would not be valid
	 */
	static AssetBundle build(Manifest manifest, Path assetsDir, Timings timings) throws IOException
	{
		refuseIfAny(checkManifest(manifest, assetsDir));

		Map<Integer, Mesh> meshes = new LinkedHashMap<>();
		Map<Integer, Rig> rigs = new LinkedHashMap<>();
		Map<Integer, Clip> clips = new LinkedHashMap<>();
		List<NpcBinding> bindings = new ArrayList<>();
		Map<Integer, String> clipOwners = new HashMap<>();
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
				int sequenceId = entry.getValue();
				String owner = clipOwners.putIfAbsent(sequenceId, name);
				if (owner != null)
				{
					// Clips are keyed by sequence id alone, so two models cannot both answer for one
					problems.add(name + " and " + owner + " both map an animation to sequence " + sequenceId);
					continue;
				}

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
			if (result.rig != null && rigs.putIfAbsent(model.rigId, result.rig) != null)
			{
				problems.add(name + " reuses rig id " + model.rigId);
			}
			for (Clip clip : result.clips)
			{
				clips.put(clip.getSequenceId(), clip);
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
			bindings.add(new NpcBinding(name, model.npcIds, new int[]{model.meshId},
				model.scaleXZ(), model.scaleY(), find, replace,
				model.ambient == null ? 0 : model.ambient, model.contrast == null ? 0 : model.contrast));
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

		Files.createDirectories(output.getParent());
		Files.write(output, bytes.toByteArray());
		System.out.println();
        log.info("Wrote {}  ({} KB, {})", output.toAbsolutePath(), Files.size(output) / 1024, bundle);
	}
}
