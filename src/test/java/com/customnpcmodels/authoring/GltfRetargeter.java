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
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.runelite.cache.fs.Store;

/**
 * Puts a model's animations on another NPC's sequences, so one creature can wear another's model
 * and still move to what the first one does. Each output animation copies a source animation, is
 * named after the live sequence it now stands in for, and has its keys spread over exactly that
 * sequence's length - which is all {@code generateAssets} needs to sample it frame for frame.
 * <p>
 * A mapping fits the source to the target one of three ways, its {@link Fit}: stretch it once over
 * the target, which suits an attack; loop it, repeating it the whole number of times that fits
 * best and stretching that, so an idle or a walk keeps its own pace; or hold it, playing it at its
 * own pace and cutting what runs past the target. A death wants the last: it usually ends on one
 * long held frame, which stretching would count as part of the fall.
 * <p>
 * The output keeps everything else in the file as it was - mesh, skin, colors and extras - and
 * only the retargeted animations. Their keys are appended to the BIN chunk; the source keys stay
 * behind, unreferenced, so no byte of the mesh moves.
 * <p>
 * Run with {@code ./gradlew retargetGltf -Pglb=<file> -Pmap=<mapping.json> -Pout=<file>}. The
 * mapping names each target sequence and the animation it copies:
 * <pre>
 * {
 *   "5326": {"from": "3424", "fit": "loop"},
 *   "5327": "3428",
 *   "5329": {"from": "3430", "fit": "hold"}
 * }
 * </pre>
 * A bare name stretches.
 */
public final class GltfRetargeter
{
	private GltfRetargeter()
	{
	}

	/** How a source animation's keys are laid over its target sequence. */
	enum Fit
	{
		STRETCH,
		LOOP,
		HOLD
	}

	/** One output animation: the source animation it copies, and how it fits the target. */
	static final class Target
	{
		final String from;
		final Fit fit;

		Target(String from, Fit fit)
		{
			this.from = from;
			this.fit = fit;
		}
	}

	public static void main(String[] args) throws IOException
	{
		String glbArg = System.getProperty("customnpcmodels.glb");
		String mapArg = System.getProperty("customnpcmodels.map");
		String outArg = System.getProperty("customnpcmodels.out");
		if (glbArg == null || glbArg.isEmpty() || mapArg == null || mapArg.isEmpty()
			|| outArg == null || outArg.isEmpty())
		{
			System.err.println("Usage: ./gradlew retargetGltf -Pglb=<file> -Pmap=<mapping.json> -Pout=<file>");
			System.exit(1);
			return;
		}

		Path in = Paths.get(glbArg);
		Path out = Paths.get(outArg);
		Map<Integer, Target> targets = readMapping(new String(Files.readAllBytes(Paths.get(mapArg)), StandardCharsets.UTF_8));

		try (Store store = CacheFiles.openLiveCache())
		{
			if (store == null)
			{
				System.err.println("Retargeting reads the target sequences' lengths from the live cache, but there "
					+ "is none; pass one with -PcacheDir=<path>");
				System.exit(1);
				return;
			}

			List<String> report = new ArrayList<>();
			byte[] result = retarget(Files.readAllBytes(in), targets, sequenceId -> AssetGenerator.timing(store, sequenceId),
				report);
			for (String line : report)
			{
				System.out.println("  " + line);
			}
			if (out.toAbsolutePath().getParent() != null)
			{
				Files.createDirectories(out.toAbsolutePath().getParent());
			}
			Files.write(out, result);
			System.out.println("Wrote " + out.toAbsolutePath());
		}
	}

	/** Reads a mapping document: each target sequence id to a source name, or to {from, fit}. */
	static Map<Integer, Target> readMapping(String text)
	{
		JsonObject json = Glb.GSON.fromJson(text, JsonObject.class);
		if (json == null)
		{
			throw new GltfException("The mapping is empty");
		}

		Map<Integer, Target> targets = new LinkedHashMap<>();
		for (Map.Entry<String, JsonElement> entry : json.entrySet())
		{
			int sequenceId;
			try
			{
				sequenceId = Integer.parseInt(entry.getKey());
			}
			catch (NumberFormatException e)
			{
				throw new GltfException("Mapping key '" + entry.getKey() + "' is not a sequence id");
			}

			JsonElement value = entry.getValue();
			if (value.isJsonPrimitive())
			{
				targets.put(sequenceId, new Target(value.getAsString(), Fit.STRETCH));
			}
			else if (value.isJsonObject() && value.getAsJsonObject().has("from"))
			{
				JsonObject target = value.getAsJsonObject();
				Fit fit = Fit.STRETCH;
				if (target.has("fit"))
				{
					try
					{
						fit = Fit.valueOf(target.get("fit").getAsString().toUpperCase(Locale.ROOT));
					}
					catch (IllegalArgumentException e)
					{
						throw new GltfException("Mapping for sequence " + sequenceId + " has fit '"
							+ target.get("fit").getAsString() + "'; use stretch, loop or hold");
					}
				}
				targets.put(sequenceId, new Target(target.get("from").getAsString(), fit));
			}
			else
			{
				throw new GltfException("Mapping for sequence " + sequenceId + " must be an animation name or "
					+ "{\"from\": name, \"fit\": \"stretch\"|\"loop\"|\"hold\"}");
			}
		}
		return targets;
	}

	/**
	 * The file with its animations replaced by one per target, each named after its sequence.
	 *
	 * @throws GltfException naming every target that could not be built, when any could not
	 */
	static byte[] retarget(byte[] data, Map<Integer, Target> targets, AssetGenerator.Timings timings,
		List<String> report) throws IOException
	{
		Glb glb = Glb.read(data);
		JsonObject json = Glb.readJson(data);
		if (glb.gltf.buffers == null || glb.gltf.buffers.isEmpty())
		{
			throw new GltfException("The file has no binary buffer, so no animations to copy");
		}

		Map<String, Integer> byName = new HashMap<>();
		List<Gltf.Animation> animations = glb.gltf.animations == null ? new ArrayList<>() : glb.gltf.animations;
		for (int i = 0; i < animations.size(); i++)
		{
			if (animations.get(i).name != null)
			{
				byName.put(animations.get(i).name, i);
			}
		}

		Appender appender = new Appender(json, glb.binBytes());
		JsonArray sourceJson = json.has("animations") ? json.getAsJsonArray("animations") : new JsonArray();
		JsonArray retargeted = new JsonArray();
		List<String> problems = new ArrayList<>();

		for (Map.Entry<Integer, Target> entry : targets.entrySet())
		{
			int sequenceId = entry.getKey();
			Target target = entry.getValue();
			Integer index = byName.get(target.from);
			if (index == null)
			{
				problems.add("Sequence " + sequenceId + " copies animation '" + target.from
					+ "', which the file does not have");
				continue;
			}
			SequenceTiming timing = timings.get(sequenceId);
			if (timing == null)
			{
				problems.add("Sequence " + sequenceId + " is not a frame-based live sequence");
				continue;
			}

			Gltf.Animation source = animations.get(index);
			double sourceLength = length(glb, source);
			if (sourceLength <= 0)
			{
				problems.add("Animation '" + target.from + "' has no length to spread over sequence " + sequenceId);
				continue;
			}
			if (source.samplers.stream().anyMatch(s -> "CUBICSPLINE".equals(s.interpolation)))
			{
				problems.add("Animation '" + target.from + "' uses cubic spline keys, whose tangents this does "
					+ "not rescale; export it with step or linear keys");
				continue;
			}

			int cycles = target.fit == Fit.LOOP ? loopCycles(timing.duration() / sourceLength) : 1;
			double stretch = target.fit == Fit.HOLD ? 1 : timing.duration() / (cycles * sourceLength);
			double end = target.fit == Fit.HOLD ? timing.duration() : Double.POSITIVE_INFINITY;

			JsonObject animation = new JsonObject();
			animation.addProperty("name", String.valueOf(sequenceId));
			animation.add("channels", sourceJson.get(index).getAsJsonObject().get("channels").deepCopy());
			JsonArray samplers = new JsonArray();
			for (Gltf.Sampler sampler : source.samplers)
			{
				samplers.add(cycledSampler(glb, appender, sampler, sourceLength, cycles, stretch, end));
			}
			animation.add("samplers", samplers);
			retargeted.add(animation);

			String fitted = target.fit == Fit.HOLD
				? (sourceLength > timing.duration() ? String.format(", held, cut at %.2fs", end) : ", held")
				: String.format(", stretched %.2fx", stretch);
			report.add("Sequence " + sequenceId + " <- '" + target.from + "'" + (cycles > 1 ? " x" + cycles : "") + fitted);
		}

		if (!problems.isEmpty())
		{
			throw new GltfException(String.join("\n", problems));
		}

		json.add("animations", retargeted);
		byte[] result = Glb.write(json, appender.finish());
		Glb.read(result);
		return result;
	}

	/**
	 * How many times a loop repeats to fill {@code ratio} of its own length: whichever whole count
	 * changes its pace least. Measured as a ratio rather than a difference, so 1.5 goes to 2 cycles
	 * at 0.75x rather than 1 at 1.5x.
	 */
	static int loopCycles(double ratio)
	{
		int fewer = Math.max(1, (int) Math.floor(ratio));
		int more = fewer + 1;
		return Math.abs(Math.log(ratio / fewer)) < Math.abs(Math.log(ratio / more)) ? fewer : more;
	}

	/** How long an animation runs: its last key, over every sampler. */
	private static double length(Glb glb, Gltf.Animation animation)
	{
		double length = 0;
		for (Gltf.Sampler sampler : animation.samplers)
		{
			for (double time : glb.readDoubles(sampler.input))
			{
				length = Math.max(length, time);
			}
		}
		return length;
	}

	/**
	 * A sampler's keys repeated {@code cycles} times back to back, then scaled in time, dropping any
	 * that start after {@code end}. Where one cycle's last key lands on the next cycle's first, the
	 * next cycle's wins: glTF keys must strictly increase, and a cycle starts from its own first pose.
	 */
	private static JsonObject cycledSampler(Glb glb, Appender appender, Gltf.Sampler sampler, double sourceLength,
		int cycles, double stretch, double end)
	{
		double[] times = glb.readDoubles(sampler.input);
		double[] values = glb.readDoubles(sampler.output);
		int width = values.length / times.length;
		String type = glb.gltf.accessors.get(sampler.output).type;

		List<Double> outTimes = new ArrayList<>();
		List<double[]> outValues = new ArrayList<>();
		for (int cycle = 0; cycle < cycles; cycle++)
		{
			for (int key = 0; key < times.length; key++)
			{
				double time = cycle * sourceLength + times[key];
				if (!outTimes.isEmpty() && time <= outTimes.get(outTimes.size() - 1))
				{
					outTimes.remove(outTimes.size() - 1);
					outValues.remove(outValues.size() - 1);
				}
				outTimes.add(time);
				double[] value = new double[width];
				System.arraycopy(values, key * width, value, 0, width);
				outValues.add(value);
			}
		}

		// The first key always stays, so a sampler never ends up empty
		int kept = 1;
		while (kept < outTimes.size() && outTimes.get(kept) * stretch <= end)
		{
			kept++;
		}
		double[] scaledTimes = new double[kept];
		double[] flatValues = new double[kept * width];
		for (int key = 0; key < kept; key++)
		{
			scaledTimes[key] = outTimes.get(key) * stretch;
			System.arraycopy(outValues.get(key), 0, flatValues, key * width, width);
		}

		JsonObject out = new JsonObject();
		out.addProperty("input", appender.floats(scaledTimes, "SCALAR", true));
		out.addProperty("output", appender.floats(flatValues, type, false));
		if (sampler.interpolation != null)
		{
			out.addProperty("interpolation", sampler.interpolation);
		}
		return out;
	}

	/** Adds float accessors to a document edited as JSON, after the BIN chunk's existing bytes. */
	private static final class Appender
	{
		private final JsonObject json;
		private final ByteArrayOutputStream bin = new ByteArrayOutputStream();

		Appender(JsonObject json, byte[] existing)
		{
			this.json = json;
			bin.write(existing, 0, existing.length);
			if (!json.has("bufferViews"))
			{
				json.add("bufferViews", new JsonArray());
			}
			if (!json.has("accessors"))
			{
				json.add("accessors", new JsonArray());
			}
		}

		int floats(double[] values, String type, boolean bounds)
		{
			while (bin.size() % 4 != 0)
			{
				bin.write(0);
			}
			ByteBuffer buffer = ByteBuffer.allocate(values.length * 4).order(ByteOrder.LITTLE_ENDIAN);
			for (double value : values)
			{
				buffer.putFloat((float) value);
			}

			JsonArray views = json.getAsJsonArray("bufferViews");
			JsonObject view = new JsonObject();
			view.addProperty("buffer", 0);
			view.addProperty("byteOffset", bin.size());
			view.addProperty("byteLength", values.length * 4);
			views.add(view);
			bin.write(buffer.array(), 0, values.length * 4);

			int components = Gltf.components(type);
			JsonObject accessor = new JsonObject();
			accessor.addProperty("bufferView", views.size() - 1);
			accessor.addProperty("componentType", Gltf.FLOAT);
			accessor.addProperty("count", values.length / components);
			accessor.addProperty("type", type);
			if (bounds)
			{
				JsonArray min = new JsonArray();
				JsonArray max = new JsonArray();
				for (int c = 0; c < components; c++)
				{
					double lo = Double.POSITIVE_INFINITY;
					double hi = Double.NEGATIVE_INFINITY;
					for (int i = c; i < values.length; i += components)
					{
						// Bounds of the float actually stored, not the double it came from
						lo = Math.min(lo, (float) values[i]);
						hi = Math.max(hi, (float) values[i]);
					}
					min.add(lo);
					max.add(hi);
				}
				accessor.add("min", min);
				accessor.add("max", max);
			}
			JsonArray accessors = json.getAsJsonArray("accessors");
			accessors.add(accessor);
			return accessors.size() - 1;
		}

		byte[] finish()
		{
			while (bin.size() % 4 != 0)
			{
				bin.write(0);
			}
			json.getAsJsonArray("buffers").get(0).getAsJsonObject().addProperty("byteLength", bin.size());
			return bin.toByteArray();
		}
	}
}
