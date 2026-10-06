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
import com.customnpcmodels.inject.NpcAppearance;
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
 * Run with {@code ./gradlew exportGltf -Pnpc=<id> [-Pchathead] [-Pseqs=a,b,...] [-Pout=dir]}. Without
 * {@code -Pseqs} every sequence on the NPC's rig is exported - see {@link #defaultSequences}. With
 * {@code -Pchathead} the NPC's chathead is exported instead, with every emote; see
 * {@link #exportChathead}.
 */
@Slf4j
public class GltfExporter
{
	/** Synthetic ids for an exported NPC: this plus the NPC id, clear of anything a cache uses. */
	static final int ID_BASE = 1_000_000;

	/** Synthetic ids for an exported chathead: this plus the NPC id, clear of the NPC's own export. */
	static final int HEAD_ID_BASE = 2_000_000;

	/** A talking emote. Every chathead emote animates the skeleton it does. */
	static final int CHATHEAD_EMOTE = 554;

	/**
	 * The most sequences a rig can animate and still be taken as one NPC's. Monster rigs measured 7 to
	 * 14 (Giant Mole, Zulrah, Commander Zilyana); the humanoid rig animates thousands.
	 */
	static final int MAX_RIG_SEQUENCES = 64;

	public static void main(String[] args) throws IOException
	{
		String npcArg = ToolCli.required("npc", "./gradlew exportGltf -Pnpc=<id> [-Pchathead] [-Pseqs=a,b,...] [-Pout=dir]");
		String outArg = ToolCli.option("out");
		Path out = Paths.get(outArg == null ? "build/gltf" : outArg);

		try (Store store = ToolCli.liveCache("Could not find an OSRS cache"))
		{
			NpcDefinition npc = ToolCli.npc(store, npcArg);
			boolean chathead = ToolCli.flag("chathead");

			Set<Integer> sequences = ToolCli.sequenceIds(ToolCli.option("seqs"));
			if (sequences.isEmpty())
			{
				sequences.addAll(chathead ? chatheadSequences(store) : defaultSequences(store, npc, System.out::println));
			}

			if (chathead)
			{
				exportChathead(store, npc, sequences, out);
			}
			else
			{
				export(store, npc, sequences, out);
			}
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
		Map<String, Integer> animations = clips(store, sequences, rigs, clips, timings);

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

	/** Each sequence's clip and timing, skipping any that isn't frame-based; returns the manifest's animation map. */
	private static Map<String, Integer> clips(Store store, Set<Integer> sequences, Map<Integer, Rig> rigs,
		List<Clip> clips, Map<Integer, SequenceTiming> timings) throws IOException
	{
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
		return animations;
	}

	/** Every frame-based sequence on the chathead skeleton, the one {@link #CHATHEAD_EMOTE} animates. */
	static Set<Integer> chatheadSequences(Store store) throws IOException
	{
		int skeleton = CacheFiles.framemapOf(store, CHATHEAD_EMOTE);
		return new LinkedHashSet<>(CacheFiles.sequencesByFramemap(store).getOrDefault(skeleton, new ArrayList<>()));
	}

	/**
	 * Writes an NPC's chathead as {@code <name>-chathead.glb}, with an animation per emote, and prints
	 * the manifest's {@code chathead} entry for it. The NPC's recolors are baked into the faces, since
	 * a head carries no recolors of its own, and no manifest entry is written: a head belongs to the
	 * models that name it.
	 *
	 * @return the written file
	 */
	static Path exportChathead(Store store, NpcDefinition npc, Set<Integer> sequences, Path out) throws IOException
	{
		if (npc.chatheadModels == null || npc.chatheadModels.length == 0)
		{
			throw new IOException("NPC " + npc.id + " has no chathead");
		}
		int id = HEAD_ID_BASE + npc.id;
		String name = npc.name == null ? "npc-" + npc.id : npc.name;
		System.out.println(name + " (id " + npc.id + "), chathead");

		List<Mesh> parts = partMeshes(store, npc.chatheadModels);
		Mesh merged = MeshMerger.merge(id, parts).withId(id);
		Mesh mesh = merged.withFaceColors(NpcAppearance.recolor(merged.getFaceColors(),
			npc.recolorToFind, npc.recolorToReplace));

		Map<Integer, Rig> rigs = new LinkedHashMap<>();
		List<Clip> clips = new ArrayList<>();
		Map<Integer, SequenceTiming> timings = new LinkedHashMap<>();
		clips(store, sequences, rigs, clips, timings);

		List<String> report = new ArrayList<>();
		byte[] glb = GlbWriter.write(mesh, partRanges(npc.chatheadModels, parts), rigs, clips, timings, report);
		for (String line : report)
		{
			System.out.println("  " + line);
		}

		String file = name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-") + "-chathead.glb";
		Files.createDirectories(out);
		Path written = out.resolve(file);
		Files.write(written, glb);
		log.info("  wrote {} ({} KB, {} verts, {} faces, {} clips)", written.toAbsolutePath(), glb.length / 1024,
			mesh.getVerticesCount(), mesh.getFaceCount(), clips.size());
		System.out.println("  in models.json: \"chathead\": {\"glb\": \"" + file + "\", \"meshId\": " + id
			+ ", \"rigId\": " + id + "}");
		return written;
	}

	/** The NPC's models, decoded, in definition order. */
	static List<Mesh> partMeshes(Store store, NpcDefinition npc) throws IOException
	{
		return partMeshes(store, npc.models);
	}

	/** Models, decoded, in the order given. */
	static List<Mesh> partMeshes(Store store, int[] modelIds) throws IOException
	{
		List<Mesh> parts = new ArrayList<>();
		for (int modelId : modelIds)
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

	/** {@link #partRanges(int[], List)} for the NPC's models. */
	static List<MeshPart> partRanges(NpcDefinition npc, List<Mesh> parts)
	{
		return partRanges(npc.models, parts);
	}

	/**
	 * Where each model's faces land in the merged mesh: the merge appends every part's faces after
	 * the last's, so each is one run. A single model is the whole mesh.
	 */
	static List<MeshPart> partRanges(int[] modelIds, List<Mesh> parts)
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
			ranges.add(new MeshPart(MeshPart.name(i, modelIds[i]), firstFace, faces));
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
