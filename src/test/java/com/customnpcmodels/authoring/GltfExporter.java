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
import com.customnpcmodels.cache.skeletal.SkeletalAnimation;
import com.customnpcmodels.cache.skeletal.Skeleton;
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
		String kind = "rig";
		Map<Integer, List<Integer>> byRig;
		if (rig != -1)
		{
			byRig = CacheFiles.sequencesByFramemap(store);
		}
		else
		{
			// A skeletal NPC's sequences share a skeleton instead
			for (int sequence : own)
			{
				rig = CacheFiles.skeletonOf(store, sequence);
				if (rig != -1)
				{
					break;
				}
			}
			if (rig == -1)
			{
				return own;
			}
			kind = "skeleton";
			byRig = CacheFiles.sequencesBySkeleton(store);
		}

		List<Integer> shared = byRig.getOrDefault(rig, new ArrayList<>());
		if (shared.size() > MAX_RIG_SEQUENCES)
		{
			report.accept("  " + kind + " " + rig + " animates " + shared.size() + " sequences, too many to tell which are this "
				+ "NPC's; exporting its definition's " + own.size() + ". Name any others with -Pseqs");
			return own;
		}

		Set<Integer> sequences = new LinkedHashSet<>(own);
		sequences.addAll(shared);
		report.accept("  exporting " + sequences.size() + " sequences: " + own.size() + " from the definition, "
			+ (sequences.size() - own.size()) + " more on " + kind + " " + rig);
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

		List<String> report = new ArrayList<>();
		Map<String, Integer> animations = new LinkedHashMap<>();
		byte[] glb = exportSkeletal(store, npc.models, mesh, parts, sequences, animations, report);
		int clipCount = animations.size();
		if (glb == null)
		{
			Map<Integer, Rig> rigs = new LinkedHashMap<>();
			List<Clip> clips = new ArrayList<>();
			Map<Integer, SequenceTiming> timings = new LinkedHashMap<>();
			animations = clips(store, sequences, rigs, clips, timings);
			glb = GlbWriter.write(mesh, parts, rigs, clips, timings, report);
			clipCount = clips.size();
		}
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
				, glb.length / 1024, mesh.getVerticesCount(), mesh.getFaceCount(), clipCount);
		if (!blocked)
		{
			System.out.println("  manifest " + out.resolve(Manifest.FILE_NAME).toAbsolutePath());
		}
	}

	/**
	 * The NPC as a skeletal glb, when its models are weighted to bones and some of the sequences are
	 * skeletal; null otherwise, for the frame-based export. The skeleton is the one most of the
	 * skeletal sequences animate, and the rest are reported and left out. Fills {@code animations}
	 * with the manifest's animation map.
	 */
	private static byte[] exportSkeletal(Store store, int[] modelIds, Mesh mesh, List<MeshPart> parts,
		Set<Integer> sequences, Map<String, Integer> animations, List<String> report) throws IOException
	{
		List<ModelDefinition> models = new ArrayList<>();
		boolean weighted = false;
		for (int modelId : modelIds)
		{
			ModelDefinition model = CacheFiles.decodeModel(store, modelId);
			if (model == null)
			{
				throw new IOException("Model " + modelId + " does not decode");
			}
			models.add(model);
			weighted |= model.animayaGroups != null;
		}
		if (!weighted)
		{
			return null;
		}

		Map<Integer, List<GlbWriter.SkeletalClip>> bySkeleton = new LinkedHashMap<>();
		for (int sequenceId : sequences)
		{
			SkeletalAnimation animation = CacheFiles.loadSkeletalAnimation(store, CacheFiles.loadSequence(store, sequenceId));
			SequenceTiming timing = AssetGenerator.timing(store, sequenceId);
			if (animation == null || timing == null)
			{
				report.add("Sequence " + sequenceId + " skipped: not a skeletal live sequence");
				continue;
			}
			bySkeleton.computeIfAbsent(animation.getSkeletonId(), id -> new ArrayList<>())
				.add(new GlbWriter.SkeletalClip(sequenceId, animation, timing));
		}
		if (bySkeleton.isEmpty())
		{
			report.add("The models are weighted to bones, but no skeletal sequence was exported; written without "
				+ "a rig (name its sequences with -Pseqs)");
			return null;
		}

		int skeletonId = -1;
		for (Map.Entry<Integer, List<GlbWriter.SkeletalClip>> entry : bySkeleton.entrySet())
		{
			if (skeletonId == -1 || entry.getValue().size() > bySkeleton.get(skeletonId).size())
			{
				skeletonId = entry.getKey();
			}
		}
		for (Map.Entry<Integer, List<GlbWriter.SkeletalClip>> entry : bySkeleton.entrySet())
		{
			if (entry.getKey() != skeletonId)
			{
				for (GlbWriter.SkeletalClip clip : entry.getValue())
				{
					report.add("Sequence " + clip.sequenceId + " skipped: it animates skeleton " + entry.getKey()
						+ ", not " + skeletonId);
				}
			}
		}
		List<GlbWriter.SkeletalClip> clips = bySkeleton.get(skeletonId);
		Skeleton skeleton = CacheFiles.loadSkeleton(store, skeletonId);
		for (GlbWriter.SkeletalClip clip : clips)
		{
			animations.put(String.valueOf(clip.sequenceId), clip.sequenceId);
		}

		// The merge welds and renumbers vertices, keeping each where a face first reaches it, so each
		// merged vertex takes the bones of the part vertex that first reached it
		int[][] bones = new int[mesh.getVerticesCount()][];
		double[][] weights = new double[mesh.getVerticesCount()][];
		boolean[] seen = new boolean[mesh.getVerticesCount()];
		int faceBase = 0;
		for (ModelDefinition model : models)
		{
			int[][] corners = {model.faceIndices1, model.faceIndices2, model.faceIndices3};
			int[][] merged = {mesh.getFaceIndices1(), mesh.getFaceIndices2(), mesh.getFaceIndices3()};
			for (int face = 0; face < model.faceCount; face++)
			{
				for (int corner = 0; corner < 3; corner++)
				{
					int vertex = merged[corner][faceBase + face];
					int source = corners[corner][face];
					if (seen[vertex])
					{
						continue;
					}
					seen[vertex] = true;
					int[] groups = model.animayaGroups == null ? null : model.animayaGroups[source];
					if (groups != null && groups.length > 0)
					{
						bones[vertex] = groups.clone();
						weights[vertex] = new double[groups.length];
						for (int i = 0; i < groups.length; i++)
						{
							weights[vertex][i] = model.animayaScales[source][i] / 255.0;
						}
					}
				}
			}
			faceBase += model.faceCount;
		}
		if (faceBase != mesh.getFaceCount())
		{
			throw new IOException("The models have " + faceBase + " faces, the merged mesh " + mesh.getFaceCount());
		}

		report.add("Skeletal: " + skeleton.getBoneCount() + " bones on skeleton " + skeletonId + ", "
			+ clips.size() + " sequences");
		return GlbWriter.writeSkeletal(mesh, parts, skeleton, 0, bones, weights, clips, report);
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
