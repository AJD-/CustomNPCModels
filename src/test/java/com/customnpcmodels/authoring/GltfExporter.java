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
import com.customnpcmodels.cache.MeshFactory;
import com.customnpcmodels.inject.Clip;
import com.customnpcmodels.inject.Mesh;
import com.customnpcmodels.inject.MeshMerger;
import com.customnpcmodels.inject.Rig;
import com.customnpcmodels.inject.SwapBlacklist;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import javax.annotation.Nonnull;
import lombok.extern.slf4j.Slf4j;
import net.runelite.cache.definitions.ModelDefinition;
import net.runelite.cache.definitions.NpcDefinition;
import net.runelite.cache.fs.Store;

/**
 * Exports an NPC from the live cache as a {@code .glb}, plus a manifest entry that binds it back to
 * the same NPC - to seed Blender work from something that already animates correctly, or to
 * round-trip it through the pipeline and into the game for comparison with the original.
 * <p>
 * The NPC's model parts are merged into one mesh, exactly as the plugin merges them at spawn, and
 * each part is written as a glTF mesh of its own so it can be hidden in Blender or the painter. Its
 * recolors and scale go into the manifest entry rather than the mesh, the same split the plugin
 * applies them in, so the round-tripped model is dressed exactly as the original.
 * <p>
 * <b>The output is Jagex geometry.</b> It goes to a gitignored directory and must never be
 * committed or bundled for release; bundle it only with {@code generateAssets -Pdev}.
 * <p>
 * Run with {@code ./gradlew exportGltf -Pnpc=<id> [-Pseqs=a,b,...] [-Pout=dir]}. Without
 * {@code -Pseqs} every sequence on the NPC's rig is exported - see {@link #defaultSequences}.
 */
@Slf4j
public class GltfExporter
{
	/** Synthetic ids for an exported NPC: this plus the NPC id, clear of anything a cache uses. */
	static final int ID_BASE = 1_000_000;

	/**
	 * The most sequences a rig can animate and still be taken as one NPC's. Monster rigs measured 7 to
	 * 14 (Giant Mole, Zulrah, Commander Zilyana); the humanoid rig animates thousands.
	 */
	static final int MAX_RIG_SEQUENCES = 64;

	public static void main(String[] args) throws IOException
	{
		String npcArg = ToolCli.required("npc", "./gradlew exportGltf -Pnpc=<id> [-Pseqs=a,b,...] [-Pout=dir]");
		String outArg = ToolCli.option("out");
		Path out = Paths.get(outArg == null ? "build/gltf" : outArg);

		try (Store store = ToolCli.liveCache("Could not find an OSRS cache"))
		{
			NpcDefinition npc = ToolCli.npc(store, npcArg);

			Set<Integer> sequences = ToolCli.sequenceIds(ToolCli.option("seqs"));
			if (sequences.isEmpty())
			{
				sequences.addAll(defaultSequences(store, npc, System.out::println));
			}

			export(store, npc, sequences, out);
		}
	}

	/**
	 * The sequences an NPC is exported with when none are named: its definition's own - standing,
	 * walking, turning, running, crawling - then every other sequence animating the same rig, which
	 * is where attacks, blocks and deaths live, since the definition never names those.
	 * <p>
	 * A rig shared by more than {@link #MAX_RIG_SEQUENCES} sequences - the humanoid rig is shared by
	 * thousands - cannot say which are this NPC's, so only the definition's own are taken and the
	 * author is told to name the rest with {@code -Pseqs}.
	 */
	static Set<Integer> defaultSequences(Store store, NpcDefinition npc, Consumer<String> report) throws IOException
	{
		Set<Integer> own = createOwnSequences(npc);

		int rig = -1;
		for (int sequence : own)
		{
			rig = CacheFiles.framemapOf(store, sequence);
			if (rig != -1)
			{
				break;
			}
		}
		if (rig == -1)
		{
			return own;
		}

		List<Integer> shared = CacheFiles.sequencesByFramemap(store).getOrDefault(rig, new ArrayList<>());
		if (shared.size() > MAX_RIG_SEQUENCES)
		{
			report.accept("  rig " + rig + " animates " + shared.size() + " sequences, too many to tell which are this "
				+ "NPC's; exporting its definition's " + own.size() + ". Name any others with -Pseqs");
			return own;
		}

		Set<Integer> sequences = new LinkedHashSet<>(own);
		sequences.addAll(shared);
		report.accept("  exporting " + sequences.size() + " sequences: " + own.size() + " from the definition, "
			+ (sequences.size() - own.size()) + " more on rig " + rig);
		return sequences;
	}

	@Nonnull
	private static Set<Integer> createOwnSequences(NpcDefinition npc)
	{
		Set<Integer> ownSequences = new LinkedHashSet<>();
		for (int sequence : new int[]{
			npc.standingAnimation, npc.walkingAnimation,
			npc.idleRotateLeftAnimation, npc.idleRotateRightAnimation,
			npc.rotate180Animation, npc.rotateLeftAnimation, npc.rotateRightAnimation,
			npc.runAnimation, npc.runRotate180Animation, npc.runRotateLeftAnimation, npc.runRotateRightAnimation,
			npc.crawlAnimation, npc.crawlRotate180Animation, npc.crawlRotateLeftAnimation, npc.crawlRotateRightAnimation})
		{
			if (sequence != -1)
			{
				ownSequences.add(sequence);
			}
		}
		return ownSequences;
	}

	static void export(Store store, NpcDefinition npc, Set<Integer> sequences, Path out) throws IOException
	{
		int id = ID_BASE + npc.id;
		String name = npc.name == null ? "npc-" + npc.id : npc.name;
		System.out.println(name + " (id " + npc.id + ")");
		boolean blocked = SwapBlacklist.isBlocked(npc.id);
		if (blocked)
		{
			// Exporting is still useful for study, but an entry binding it would make generateAssets
			// refuse the whole manifest, so none is written
			System.out.println("  warning: NPC " + npc.id + " is in " + SwapBlacklist.contentOf(npc.id)
				+ " and can never be swapped; writing the .glb without a manifest entry");
		}

		List<Mesh> partMeshes = partMeshes(store, npc);
		Mesh mesh = merge(npc, partMeshes);
		List<MeshPart> parts = partRanges(npc, partMeshes);
		if (parts.size() > 1)
		{
			for (MeshPart part : parts)
			{
				System.out.println("  " + part);
			}
		}

		Map<Integer, Rig> rigs = new LinkedHashMap<>();
		List<Clip> clips = new ArrayList<>();
		Map<Integer, SequenceTiming> timings = new LinkedHashMap<>();
		Map<String, Integer> animations = new LinkedHashMap<>();
		for (int sequenceId : sequences)
		{
			Clip clip = CacheFiles.buildClip(store, sequenceId, rigs);
			SequenceTiming timing = AssetGenerator.timing(store, sequenceId);
			if (clip == null || timing == null)
			{
				log.info("  sequence {} skipped: not a frame-based live sequence", sequenceId);
				continue;
			}
			clips.add(clip);
			timings.put(sequenceId, timing);
			animations.put(String.valueOf(sequenceId), sequenceId);
		}

		List<String> report = new ArrayList<>();
		byte[] glb = GlbWriter.write(mesh, parts, rigs, clips, timings, report);
		for (String line : report)
		{
			System.out.println("  " + line);
		}

		String file = name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-") + ".glb";
		Files.createDirectories(out);
		Files.write(out.resolve(file), glb);

		Manifest.Model entry = new Manifest.Model();
		entry.name = name;
		entry.glb = file;
		entry.meshId = id;
		entry.rigId = id;
		entry.npcIds = new int[]{npc.id};
		entry.scaleXZ = npc.widthScale;
		entry.scaleY = npc.heightScale;
		entry.ambient = npc.ambient;
		entry.contrast = npc.contrast;
		if (npc.recolorToFind != null && npc.recolorToReplace != null)
		{
			entry.recolors = new ArrayList<>();
			for (int i = 0; i < npc.recolorToFind.length; i++)
			{
				entry.recolors.add(new Manifest.Recolor(npc.recolorToFind[i], npc.recolorToReplace[i]));
			}
		}
		entry.animations = animations;

		if (!blocked)
		{
			// Replace this NPC's entry in an existing manifest rather than piling up duplicates
			Manifest manifest = Manifest.read(out);
			manifest.models.removeIf(model -> model.meshId == id);
			manifest.models.add(entry);
			manifest.write(out);
		}

		log.info("  wrote {} ({} KB, {} verts, {} faces, {} clips)", out.resolve(file).toAbsolutePath()
				, glb.length / 1024, mesh.getVerticesCount(), mesh.getFaceCount(), clips.size());
		if (!blocked)
		{
			System.out.println("  manifest " + out.resolve(Manifest.FILE_NAME).toAbsolutePath());
		}
	}

	/** The NPC's model parts merged into one mesh, as the plugin merges them at spawn, under its synthetic id. */
	static Mesh npcMesh(Store store, NpcDefinition npc) throws IOException
	{
		return merge(npc, partMeshes(store, npc));
	}

	/** The NPC's models, decoded, in definition order. */
	static List<Mesh> partMeshes(Store store, NpcDefinition npc) throws IOException
	{
		List<Mesh> parts = new ArrayList<>();
		for (int modelId : npc.models)
		{
			ModelDefinition model = CacheFiles.decodeModel(store, modelId);
			if (model == null)
			{
				throw new IOException("Model " + modelId + " does not decode");
			}
			parts.add(MeshFactory.toMesh(modelId, model));
		}
		return parts;
	}

	/**
	 * Where each model's faces land in the merged mesh: the merge appends every part's faces after
	 * the last's, so each is one run. A single model is the whole mesh.
	 */
	static List<MeshPart> partRanges(NpcDefinition npc, List<Mesh> parts)
	{
		if (parts.size() == 1)
		{
			return MeshPart.whole(parts.get(0).getFaceCount());
		}

		List<MeshPart> ranges = new ArrayList<>();
		int firstFace = 0;
		for (int i = 0; i < parts.size(); i++)
		{
			int faces = parts.get(i).getFaceCount();
			ranges.add(new MeshPart(MeshPart.name(i, npc.models[i]), firstFace, faces));
			firstFace += faces;
		}
		return ranges;
	}

	/** The parts merged under the NPC's synthetic id. */
	static Mesh merge(NpcDefinition npc, List<Mesh> parts)
	{
		int id = ID_BASE + npc.id;
		// A lone part comes back from the merge under its own id
		return MeshMerger.merge(id, parts).withId(id);
	}
}
