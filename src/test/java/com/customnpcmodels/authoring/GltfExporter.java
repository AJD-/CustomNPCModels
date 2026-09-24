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
import net.runelite.cache.NpcManager;
import net.runelite.cache.definitions.ModelDefinition;
import net.runelite.cache.definitions.NpcDefinition;
import net.runelite.cache.fs.Store;

/**
 * Exports an NPC from the live cache as a {@code .glb}, plus a manifest entry that binds it back to
 * the same NPC - to seed Blender work from something that already animates correctly, or to
 * round-trip it through the pipeline and into the game for comparison with the original.
 *
 * <p>The NPC's model parts are merged into one mesh, exactly as the plugin merges them at spawn. Its
 * recolors and scale go into the manifest entry rather than the mesh, the same split the plugin
 * applies them in, so the round-tripped model is dressed exactly as the original.
 *
 * <p><b>The output is Jagex geometry.</b> It goes to a gitignored directory and must never be
 * committed or bundled for release; bundle it only with {@code generateAssets -Pdev}.
 *
 * <p>Run with {@code ./gradlew exportGltf -Pnpc=<id> [-Pseqs=a,b,...] [-Pout=dir]}. Without
 * {@code -Pseqs} the NPC's standing and walking sequences are exported.
 */
public class GltfExporter
{
	/** Synthetic ids for an exported NPC: this plus the NPC id, clear of anything a cache uses. */
	static final int ID_BASE = 1_000_000;

	public static void main(String[] args) throws IOException
	{
		String npcArg = System.getProperty("customnpcmodels.npc");
		if (npcArg == null || npcArg.isEmpty())
		{
			System.err.println("Usage: ./gradlew exportGltf -Pnpc=<id> [-Pseqs=a,b,...] [-Pout=dir]");
			System.exit(1);
			return;
		}
		Path out = Paths.get(System.getProperty("customnpcmodels.out", "build/gltf"));

		try (Store store = CacheFiles.openLiveCache())
		{
			if (store == null)
			{
				System.err.println("Could not find an OSRS cache; pass one with -PcacheDir=<path>");
				System.exit(1);
				return;
			}

			NpcManager npcs = new NpcManager(store);
			npcs.load();
			NpcDefinition npc = npcs.get(Integer.parseInt(npcArg.trim()));
			if (npc == null || npc.models == null)
			{
				System.err.println("No NPC with id " + npcArg + ", or it has no models");
				System.exit(1);
				return;
			}

			Set<Integer> sequences = new LinkedHashSet<>();
			String seqArg = System.getProperty("customnpcmodels.seqs");
			if (seqArg != null && !seqArg.isEmpty())
			{
				for (String seq : seqArg.split(","))
				{
					sequences.add(Integer.parseInt(seq.trim()));
				}
			}
			else
			{
				if (npc.standingAnimation != -1)
				{
					sequences.add(npc.standingAnimation);
				}
				if (npc.walkingAnimation != -1)
				{
					sequences.add(npc.walkingAnimation);
				}
			}

			export(store, npc, sequences, out);
		}
	}

	static void export(Store store, NpcDefinition npc, Set<Integer> sequences, Path out) throws IOException
	{
		int id = ID_BASE + npc.id;
		String name = npc.name == null ? "npc-" + npc.id : npc.name;
		System.out.println(name + " (id " + npc.id + ")");

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
		Mesh mesh = MeshMerger.merge(id, parts);
		mesh = new Mesh(id, mesh.getPriority(), mesh.getVerticesX(), mesh.getVerticesY(), mesh.getVerticesZ(),
			mesh.getFaceIndices1(), mesh.getFaceIndices2(), mesh.getFaceIndices3(), mesh.getFaceColors(),
			mesh.getFaceRenderTypes(), mesh.getFaceTransparencies(), mesh.getFaceRenderPriorities(),
			mesh.getFaceTextures(), mesh.getTextureCoords(), mesh.getTexIndices1(), mesh.getTexIndices2(),
			mesh.getTexIndices3(), mesh.getVertexGroups());

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
				System.out.println("  sequence " + sequenceId + " skipped: not a frame-based live sequence");
				continue;
			}
			clips.add(clip);
			timings.put(sequenceId, timing);
			animations.put(String.valueOf(sequenceId), sequenceId);
		}

		List<String> report = new ArrayList<>();
		byte[] glb = GlbWriter.write(mesh, rigs, clips, timings, report);
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

		// Replace this NPC's entry in an existing manifest rather than piling up duplicates
		Manifest manifest = Manifest.read(out);
		manifest.models.removeIf(model -> model.meshId == id);
		manifest.models.add(entry);
		manifest.write(out);

		System.out.println("  wrote " + out.resolve(file).toAbsolutePath() + " (" + glb.length / 1024 + " KB, "
			+ mesh.getVerticesCount() + " verts, " + mesh.getFaceCount() + " faces, " + clips.size() + " clips)");
		System.out.println("  manifest " + out.resolve(Manifest.FILE_NAME).toAbsolutePath());
	}
}
