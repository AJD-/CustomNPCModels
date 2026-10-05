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
import com.customnpcmodels.inject.Clip;
import com.customnpcmodels.inject.Mesh;
import com.customnpcmodels.inject.Rig;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.runelite.cache.definitions.NpcDefinition;
import net.runelite.cache.fs.Store;

/**
 * Reports how far a {@code .glb} - typically one that has been through Blender - has moved from the
 * NPC it was exported from, without starting the client: what the converter reported, which vertex
 * groups moved at rest, which faces were rewired or recolored, and the worst vertex error over every
 * frame of every sequence.
 *
 * <p>An unedited Blender round trip should show nothing moved and nothing recolored, and pose errors
 * within the direct round trip's bounds (under 2 units; the mole's death clip, which shears, under 24).
 * An edit should show exactly the groups and faces that were edited.
 *
 * <p>Run with {@code ./gradlew compareGltf -Pnpc=<id> -Pglb=<file> [-Pseqs=a,b,...]}. Without
 * {@code -Pseqs} every animation in the file named after a sequence id is compared.
 */
public class GltfComparer
{
	/** Vertices moving less than this at rest are float noise, not an edit. */
	private static final double MOVED = 0.5;
	private static final int SAMPLES = 5;

	public static void main(String[] args) throws IOException
	{
		String usage = "./gradlew compareGltf -Pnpc=<id> -Pglb=<file> [-Pseqs=a,b,...]";
		String npcArg = ToolCli.required("npc", usage);
		Path glbPath = Paths.get(ToolCli.required("glb", usage));
		byte[] glb = Files.readAllBytes(glbPath);

		try (Store store = ToolCli.liveCache("Could not find an OSRS cache"))
		{
			NpcDefinition npc = ToolCli.npc(store, npcArg);
			compare(store, npc, glb, sequences(glb, ToolCli.option("seqs")), glbPath.toAbsolutePath().toString());
		}
	}

	/** The sequences named, or else every animation in the file named after one. */
	private static Set<Integer> sequences(byte[] glb, String seqArg)
	{
		Set<Integer> sequences = ToolCli.sequenceIds(seqArg);
		if (!sequences.isEmpty())
		{
			return sequences;
		}

		Gltf gltf = Glb.read(glb).gltf;
		if (gltf.animations != null)
		{
			for (Gltf.Animation animation : gltf.animations)
			{
				if (animation.name != null && animation.name.matches("\\d+"))
				{
					sequences.add(Integer.parseInt(animation.name));
				}
			}
		}
		return sequences;
	}

	static void compare(Store store, NpcDefinition npc, byte[] glb, Set<Integer> sequences, String label)
		throws IOException
	{
		System.out.println("Comparing " + label);
		System.out.println("  against " + (npc.name == null ? "npc" : npc.name) + " (id " + npc.id + ") from the live cache");

		Mesh original = GltfExporter.npcMesh(store, npc);
		Map<Integer, Rig> rigs = new LinkedHashMap<>();
		Map<Integer, Clip> originalClips = new LinkedHashMap<>();
		Map<String, SequenceTiming> animations = new LinkedHashMap<>();
		for (int sequenceId : sequences)
		{
			Clip clip = CacheFiles.buildClip(store, sequenceId, rigs);
			SequenceTiming timing = AssetGenerator.timing(store, sequenceId);
			if (clip == null || timing == null)
			{
				System.out.println("  sequence " + sequenceId + " skipped: not a frame-based live sequence");
				continue;
			}
			originalClips.put(sequenceId, clip);
			animations.put(String.valueOf(sequenceId), timing);
		}

		GltfToMeshConverter.Result result = GltfToMeshConverter.convert(glb, original.getId(), original.getId(), animations);
		Mesh converted = result.mesh;

		System.out.println();
		System.out.println("Converter report" + (result.report.isEmpty() ? ": nothing to report" : ":"));
		for (String line : result.report)
		{
			System.out.println("  " + line);
		}

		System.out.println();
		System.out.println("Geometry");
		System.out.println("  vertices  cache " + original.getVerticesCount() + ", file " + converted.getVerticesCount());
		System.out.println("  faces     cache " + original.getFaceCount() + ", file " + converted.getFaceCount());
		System.out.println("  priority  cache " + original.getPriority() + ", file " + converted.getPriority());

		// Vertices only line up one for one when they were welded by the original numbering
		boolean sameVertices = result.keptVertexNumbering && original.getVerticesCount() == converted.getVerticesCount();
		if (sameVertices)
		{
			compareRest(original, converted);
		}
		else
		{
			System.out.println("  vertices were renumbered, so they cannot be compared one for one");
		}

		if (original.getFaceCount() == converted.getFaceCount())
		{
			compareFaces(original, converted, sameVertices);
		}
		else
		{
			System.out.println("  face counts differ, so faces cannot be compared one for one");
		}

		System.out.println();
		System.out.println("Poses (worst vertex error over every frame, engine units; 128 = one tile)");
		if (!sameVertices)
		{
			System.out.println("  skipped: vertices were renumbered");
			return;
		}
		for (Map.Entry<Integer, Clip> entry : originalClips.entrySet())
		{
			Clip convertedClip = null;
			for (Clip clip : result.clips)
			{
				if (clip.getSequenceId() == entry.getKey())
				{
					convertedClip = clip;
				}
			}
			if (convertedClip == null)
			{
				System.out.println("  " + entry.getKey() + "  missing from the file");
				continue;
			}
			double worst = PoseComparison.worstPoseError(original, rigs.get(entry.getValue().getRigId()), entry.getValue(),
				converted, result.rig, convertedClip);
			System.out.println(String.format("  %d  %.2f", entry.getKey(), worst));
		}
	}

	/** Which vertex groups moved at rest, and how far - an edit shows up here and nowhere else. */
	private static void compareRest(Mesh original, Mesh converted)
	{
		int n = original.getVerticesCount();
		double[] moved = new double[n];
		double worst = 0;
		for (int v = 0; v < n; v++)
		{
			double dx = original.getVerticesX()[v] - converted.getVerticesX()[v];
			double dy = original.getVerticesY()[v] - converted.getVerticesY()[v];
			double dz = original.getVerticesZ()[v] - converted.getVerticesZ()[v];
			moved[v] = Math.sqrt(dx * dx + dy * dy + dz * dz);
			worst = Math.max(worst, moved[v]);
		}
		System.out.println(String.format("  rest pose: worst vertex moved %.2f units", worst));

		int[][] groups = original.getVertexGroups();
		if (groups == null)
		{
			return;
		}
		for (int group = 0; group < groups.length; group++)
		{
			int count = 0;
			double furthest = 0;
			for (int v : groups[group])
			{
				if (moved[v] >= MOVED)
				{
					count++;
					furthest = Math.max(furthest, moved[v]);
				}
			}
			if (count > 0)
			{
				System.out.println(String.format("    group %d: %d of %d vertices moved, up to %.1f units",
					group, count, groups[group].length, furthest));
			}
		}
	}

	private static void compareFaces(Mesh original, Mesh converted, boolean sameVertices)
	{
		int faces = original.getFaceCount();
		int rewired = 0;
		List<String> recolored = new ArrayList<>();
		int renderTypes = 0;
		int transparencies = 0;
		int renderPriorities = 0;
		for (int face = 0; face < faces; face++)
		{
			if (original.getFaceIndices1()[face] != converted.getFaceIndices1()[face]
				|| original.getFaceIndices2()[face] != converted.getFaceIndices2()[face]
				|| original.getFaceIndices3()[face] != converted.getFaceIndices3()[face])
			{
				rewired++;
			}
			int before = original.getFaceColors()[face] & 0xFFFF;
			int after = converted.getFaceColors()[face] & 0xFFFF;
			if (before != after)
			{
				recolored.add(String.format("face %d %06X -> %06X", face, RsColor.hslToRgb(before), RsColor.hslToRgb(after)));
			}
			renderTypes += faceByte(original.getFaceRenderTypes(), face, 0) != faceByte(converted.getFaceRenderTypes(), face, 0) ? 1 : 0;
			transparencies += faceByte(original.getFaceTransparencies(), face, 0) != faceByte(converted.getFaceTransparencies(), face, 0) ? 1 : 0;
			renderPriorities += faceByte(original.getFaceRenderPriorities(), face, original.getPriority())
				!= faceByte(converted.getFaceRenderPriorities(), face, converted.getPriority()) ? 1 : 0;
		}

		System.out.println("  faces rewired " + (sameVertices ? String.valueOf(rewired) : "n/a") + ", recolored " + recolored.size() + ", render type changed "
			+ renderTypes + ", transparency changed " + transparencies + ", render priority changed " + renderPriorities);
		for (int i = 0; i < Math.min(SAMPLES, recolored.size()); i++)
		{
			System.out.println("    " + recolored.get(i));
		}
		if (recolored.size() > SAMPLES)
		{
			System.out.println("    ... and " + (recolored.size() - SAMPLES) + " more");
		}
	}

	/** A face column's value, with an absent column read as what the engine draws in its place. */
	private static int faceByte(byte[] column, int face, int absent)
	{
		return column == null ? absent : column[face] & 0xFF;
	}
}
