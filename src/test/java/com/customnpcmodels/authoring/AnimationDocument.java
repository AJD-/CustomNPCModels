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

import com.customnpcmodels.inject.Clip;
import com.customnpcmodels.inject.Mesh;
import com.customnpcmodels.inject.MeshMerger;
import com.customnpcmodels.inject.NpcAppearance;
import com.customnpcmodels.inject.Rig;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * A {@code .glb} converted the way {@code generateAssets} converts it and built the way the plugin
 * builds it, so its animations can be played exactly as the game will draw them.
 *
 * <h2>Which sequence an animation plays against</h2>
 *
 * The manifest entry's {@code animations} map when it has one; otherwise every animation named with
 * digits alone stands in for that sequence, which is how {@code exportGltf} names them. Each is
 * sampled at the live sequence's frames, as the bundle's clip would be. There is no fallback to the
 * file's own key times: that would not be the timing the game plays, so an animation with no live
 * sequence is listed as unplayable, with the reason.
 *
 * <h2>What the plugin does to it</h2>
 *
 * As {@code ModelCache.build}: the mesh goes through {@link MeshMerger}, the manifest's recolors are
 * applied, and its lighting bytes and scale are carried for the viewport - which lights at rest and
 * scales after posing, as the plugin does.
 */
final class AnimationDocument
{
	/** One animation in the file, and what it plays as. */
	static final class Animation
	{
		/** The glTF animation's name. */
		final String name;

		/** The live sequence it stands in for, or -1 when nothing maps it to one. */
		final int sequenceId;

		/** How that sequence plays, or null when unplayable. */
		final SequenceTiming timing;

		/** The clip the bundle would carry, or null when unplayable. */
		final Clip clip;

		/** Why it cannot be played, or null when it can. */
		final String unplayableReason;

		private Animation(String name, int sequenceId, SequenceTiming timing, Clip clip, String unplayableReason)
		{
			this.name = name;
			this.sequenceId = sequenceId;
			this.timing = timing;
			this.clip = clip;
			this.unplayableReason = unplayableReason;
		}

		boolean isPlayable()
		{
			return clip != null;
		}
	}

	private final Mesh mesh;
	private final Rig rig;
	private final short[] faceColors;
	private final boolean recolored;
	private final float scaleXZ;
	private final float scaleY;
	private final int ambient;
	private final int contrast;
	private final List<Animation> animations = new ArrayList<>();
	private final List<String> report = new ArrayList<>();

	/**
	 * @param entry   the manifest entry naming the file, or null
	 * @param timings how live sequences play, or null when there is no cache to ask
	 */
	static AnimationDocument load(byte[] glb, Manifest.Model entry, AssetGenerator.Timings timings)
	{
		return new AnimationDocument(glb, entry, timings);
	}

	private AnimationDocument(byte[] glb, Manifest.Model entry, AssetGenerator.Timings timings)
	{
		Gltf gltf = Glb.read(glb).gltf;
		List<String> names = new ArrayList<>();
		if (gltf.animations != null)
		{
			for (Gltf.Animation animation : gltf.animations)
			{
				names.add(animation.name);
			}
		}

		boolean mappedByManifest = entry != null && entry.animations != null && !entry.animations.isEmpty();
		if (mappedByManifest)
		{
			for (String mapped : entry.animations.keySet())
			{
				if (!names.contains(mapped))
				{
					report.add("models.json maps animation '" + mapped + "', which the file does not have");
				}
			}
		}

		// Name to sequence and timing, for those that can be played; reasons for those that cannot
		Map<String, Integer> sequences = new LinkedHashMap<>();
		Map<String, SequenceTiming> playable = new LinkedHashMap<>();
		Map<String, String> reasons = new LinkedHashMap<>();
		boolean skinned = gltf.skins != null && !gltf.skins.isEmpty();
		for (String name : names)
		{
			Integer sequenceId = mappedByManifest ? entry.animations.get(name)
				: name != null && name.matches("\\d+") ? Integer.valueOf(name) : null;
			if (sequenceId == null)
			{
				reasons.put(name, mappedByManifest ? "models.json does not map it to a live sequence"
					: "Its name is not a sequence id, and no models.json entry maps it");
				report.add("Animation '" + name + "' is not mapped to a live sequence, so it cannot be played");
				continue;
			}
			sequences.put(name, sequenceId);
			if (!skinned)
			{
				reasons.put(name, "The file has no skin, so nothing can move");
				continue;
			}
			if (timings == null)
			{
				reasons.put(name, "There is no live cache to read sequence " + sequenceId
					+ "'s timing from; pass -PcacheDir=<path>");
				continue;
			}
			SequenceTiming timing;
			try
			{
				timing = timings.get(sequenceId);
			}
			catch (IOException ex)
			{
				timing = null;
				report.add("Could not read sequence " + sequenceId + ": " + ex.getMessage());
			}
			if (timing == null)
			{
				reasons.put(name, "Sequence " + sequenceId + " is not a frame-based live sequence");
				continue;
			}
			playable.put(name, timing);
		}

		GltfToMeshConverter.Result result = GltfToMeshConverter.convert(glb, 0, 0, playable);
		// Unmapped animations are reported above already, in the viewer's terms
		Set<String> unmapped = result.unmapped.stream()
			.map(GltfToMeshConverter::unmappedMessage)
			.collect(Collectors.toSet());
		result.report.stream().filter(line -> !unmapped.contains(line)).forEach(report::add);

		// No clips at all when the converter found no skin on the meshes, whatever skins the file defines
		Map<String, Clip> clips = result.clipsByAnimation;
		if (result.rig == null)
		{
			for (String name : playable.keySet())
			{
				reasons.put(name, "The file's meshes are not bound to its skin, so nothing can move");
			}
		}

		for (String name : names)
		{
			int sequenceId = sequences.getOrDefault(name, -1);
			Clip clip = clips.get(name);
			animations.add(clip != null
				? new Animation(name, sequenceId, playable.get(name), clip, null)
				: new Animation(name, sequenceId, null, null, reasons.get(name)));
		}

		mesh = MeshMerger.merge(result.mesh.getId(), Collections.singletonList(result.mesh));
		rig = result.rig;

		List<Manifest.Recolor> recolors = entry == null || entry.recolors == null
			? Collections.emptyList() : entry.recolors;
		short[] find = new short[recolors.size()];
		short[] replace = new short[recolors.size()];
		for (int pair = 0; pair < recolors.size(); pair++)
		{
			find[pair] = (short) recolors.get(pair).find;
			replace[pair] = (short) recolors.get(pair).replace;
		}
		faceColors = NpcAppearance.recolor(mesh.getFaceColors(), find, replace);
		recolored = !Arrays.equals(faceColors, mesh.getFaceColors());

		scaleXZ = NpcAppearance.scale(entry == null ? Rig.SCALE_UNIT : entry.scaleXZ());
		scaleY = NpcAppearance.scale(entry == null ? Rig.SCALE_UNIT : entry.scaleY());
		ambient = entry == null || entry.ambient == null ? 0 : entry.ambient;
		contrast = entry == null || entry.contrast == null ? 0 : entry.contrast;
	}

	/** The rest mesh, as the plugin builds it; its face colors are the file's, before recoloring. */
	Mesh mesh()
	{
		return mesh;
	}

	/** The rig every clip is expressed against, or null for an unskinned file. */
	Rig rig()
	{
		return rig;
	}

	/** Per face, the color the plugin lights: the file's, with the manifest's recolors applied. */
	short[] faceColors()
	{
		return faceColors;
	}

	/** Whether the manifest's recolors changed any face. */
	boolean isRecolored()
	{
		return recolored;
	}

	/** The manifest's horizontal scale as a factor, applied after posing. */
	float scaleXZ()
	{
		return scaleXZ;
	}

	/** The manifest's vertical scale as a factor, applied after posing. */
	float scaleY()
	{
		return scaleY;
	}

	int ambient()
	{
		return ambient;
	}

	int contrast()
	{
		return contrast;
	}

	/** Every animation in the file, in file order. */
	List<Animation> animations()
	{
		return Collections.unmodifiableList(animations);
	}

	List<Animation> playable()
	{
		return animations.stream().filter(Animation::isPlayable).collect(Collectors.toList());
	}

	/** What the conversion found worth saying, as {@code generateAssets} would print it. */
	List<String> report()
	{
		return Collections.unmodifiableList(report);
	}
}
