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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The authoring manifest, {@code models.json}: which glTF files become which bundle assets, and which
 * NPCs wear them. It sits beside the {@code .glb} files it names.
 *
 * <pre>
 * {
 *   "models": [
 *     {
 *       "name": "Giant Mole",
 *       "glb": "giant-mole.glb",
 *       "meshId": 1005779,
 *       "rigId": 1005779,
 *       "npcIds": [5779],
 *       "scaleXZ": 118,
 *       "scaleY": 118,
 *       "recolors": [{"find": 5169, "replace": 21662}],
 *       "animations": {"3309": 3309, "3313": 3313}
 *     }
 *   ]
 * }
 * </pre>
 * <p>
 * {@code meshId} and {@code rigId} are synthetic and must stay stable across regenerations; keep
 * them at 1 000 000 and up, clear of anything a cache would use. {@code animations} maps a glTF
 * animation's name to the live sequence it stands in for - the NPC keeps playing that sequence, and
 * the clip is sampled at its frame count. Scale is in 1/128ths and defaults to 128;
 * {@code ambient} and {@code contrast} are the NPC definition's lighting bytes and default to 0.
 */
final class Manifest
{
	static final String FILE_NAME = "models.json";

	/**
	 * What the models are published as, when they are built into a pack with {@code -PpackOut}.
	 * Optional otherwise. A real field rather than left to Gson to ignore, so the exporter keeps it
	 * when it rewrites this file.
	 */
	Pack pack;

	List<Model> models = new ArrayList<>();

	/** Written to the pack's {@code pack.json}. The plugin reads everything here but the id. */
	static final class Pack
	{
		/** Lowercase letters, digits and hyphens: the pack's folder, and its hub branch. */
		String id;
		String name;
		String author;
		String description;
		String version;
		String license;
		List<String> tags;
	}

	static final class Model
	{
		String name;
		String glb;
		int meshId;
		int rigId;
		int[] npcIds;
		Integer scaleXZ;
		Integer scaleY;

		/** The NPC definition's lighting bytes (opcodes 100 and 101), 0 when absent. */
		Integer ambient;
		Integer contrast;
		List<Recolor> recolors;
		Map<String, Integer> animations = new LinkedHashMap<>();

		int scaleXZ()
		{
			return scaleXZ == null ? 128 : scaleXZ;
		}

		int scaleY()
		{
			return scaleY == null ? 128 : scaleY;
		}
	}

	static final class Recolor
	{
		int find;
		int replace;

		Recolor()
		{
		}

		Recolor(int find, int replace)
		{
			this.find = find;
			this.replace = replace;
		}
	}

	/** The manifest in {@code dir}, or an empty one when there is none. */
	static Manifest read(Path dir) throws IOException
	{
		Path file = dir.resolve(FILE_NAME);
		if (!Files.exists(file))
		{
			return new Manifest();
		}

		Manifest manifest = Glb.GSON.fromJson(new String(Files.readAllBytes(file), StandardCharsets.UTF_8), Manifest.class);
		if (manifest == null || manifest.models == null)
		{
			return new Manifest();
		}
		return manifest;
	}

	/**
	 * The entry in the manifest beside a {@code .glb} that names it, or null when there is none or the
	 * manifest cannot be read - the authoring tools only take lighting, scale and recolors from it.
	 */
	static Model entryFor(Path glb)
	{
		try
		{
			for (Model model : read(glb.toAbsolutePath().getParent()).models)
			{
				if (glb.getFileName().toString().equals(model.glb))
				{
					return model;
				}
			}
		}
		catch (IOException | RuntimeException ex)
		{
			// Treated as no entry
		}
		return null;
	}

	void write(Path dir) throws IOException
	{
		Files.createDirectories(dir);
		String json = Glb.GSON.newBuilder().setPrettyPrinting().create().toJson(this);
		Files.write(dir.resolve(FILE_NAME), json.getBytes(StandardCharsets.UTF_8));
	}
}
