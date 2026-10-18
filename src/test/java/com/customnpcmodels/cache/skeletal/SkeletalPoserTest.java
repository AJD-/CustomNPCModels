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
package com.customnpcmodels.cache.skeletal;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;
import com.customnpcmodels.cache.CacheFiles;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import net.runelite.cache.definitions.ModelDefinition;
import net.runelite.cache.definitions.SequenceDefinition;
import net.runelite.cache.fs.Store;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * The skeletal decoders against the client itself: {@code client-poses.json.gz} holds models posed
 * by the live client's {@code applyTransformations}, captured with {@code SkeletalCapturePlugin} -
 * Yami (10339, Yama's own geometry) standing and walking, and the Yama on his throne (9434) in the
 * throne's idle, which the capture also matched against what the client drew for NPC 124.
 */
public class SkeletalPoserTest
{
	/** The client rounds nothing, but keeps floats; the fixture is rounded to a tenth. */
	private static final double TOLERANCE = 0.5;

	private static Store store;
	private static JsonObject capture;

	@BeforeClass
	public static void load() throws IOException
	{
		store = CacheFiles.openLiveCache();
		try (Reader reader = new InputStreamReader(new GZIPInputStream(
			SkeletalPoserTest.class.getResourceAsStream("client-poses.json.gz")), StandardCharsets.UTF_8))
		{
			capture = new JsonParser().parse(reader).getAsJsonObject();
		}
	}

	@AfterClass
	public static void close() throws IOException
	{
		if (store != null)
		{
			store.close();
		}
	}

	private static ModelDefinition model(int modelId) throws IOException
	{
		assumeTrue("no live OSRS cache on this machine", store != null);
		ModelDefinition model = CacheFiles.loadModel(store, modelId);
		assertNotNull(model);
		return model;
	}

	private static float[] coords(JsonObject pose, String axis)
	{
		JsonArray values = pose.getAsJsonArray(axis);
		float[] out = new float[values.size()];
		for (int i = 0; i < out.length; i++)
		{
			out[i] = values.get(i).getAsFloat();
		}
		return out;
	}

	@Test
	public void testTheCacheModelIsTheClientsRestPose() throws IOException
	{
		for (Map.Entry<String, JsonElement> entry : capture.getAsJsonObject("models").entrySet())
		{
			ModelDefinition model = model(Integer.parseInt(entry.getKey()));
			JsonObject rest = entry.getValue().getAsJsonObject().getAsJsonObject("rest");
			float[] x = coords(rest, "x");
			float[] y = coords(rest, "y");
			float[] z = coords(rest, "z");
			assertEquals(model.vertexCount, x.length);
			for (int v = 0; v < x.length; v++)
			{
				assertEquals(model.vertexX[v], x[v], 0);
				assertEquals(model.vertexY[v], y[v], 0);
				assertEquals(model.vertexZ[v], z[v], 0);
			}
		}
	}

	/**
	 * Every captured frame matches the poser at that frame, or at the frame before: at a few key
	 * changes the client moves to the next key a frame later (see {@link SkeletalPoser}).
	 */
	@Test
	public void testPosesMatchTheClient() throws IOException
	{
		StringBuilder report = new StringBuilder();
		boolean matched = true;
		for (Map.Entry<String, JsonElement> modelEntry : capture.getAsJsonObject("models").entrySet())
		{
			ModelDefinition model = model(Integer.parseInt(modelEntry.getKey()));
			JsonObject sequences = modelEntry.getValue().getAsJsonObject().getAsJsonObject("sequences");
			for (Map.Entry<String, JsonElement> sequenceEntry : sequences.entrySet())
			{
				SequenceDefinition sequence = CacheFiles.loadSequence(store, Integer.parseInt(sequenceEntry.getKey()));
				SkeletalAnimation animation = CacheFiles.loadSkeletalAnimation(store, sequence);
				assertNotNull("sequence " + sequenceEntry.getKey() + " has no skeletal animation", animation);
				Skeleton skeleton = CacheFiles.loadSkeleton(store, animation.getSkeletonId());

				int onFrame = 0;
				int late = 0;
				double worst = 0;
				JsonObject frames = sequenceEntry.getValue().getAsJsonObject().getAsJsonObject("frames");
				for (Map.Entry<String, JsonElement> frameEntry : frames.entrySet())
				{
					int frame = Integer.parseInt(frameEntry.getKey());
					JsonObject expected = frameEntry.getValue().getAsJsonObject();
					double error = error(model, skeleton, animation, frame, expected);
					if (error <= TOLERANCE)
					{
						onFrame++;
						continue;
					}
					double lateError = error(model, skeleton, animation, frame - 1, expected);
					if (lateError <= TOLERANCE)
					{
						late++;
						continue;
					}
					matched = false;
					worst = Math.max(worst, Math.min(error, lateError));
				}
				report.append(String.format("  model %s sequence %s: %d frames on time, %d a frame late, worst miss %.2f%n",
					modelEntry.getKey(), sequenceEntry.getKey(), onFrame, late, worst));
			}
		}
		assertTrue("posed vertices differ from the client's:\n" + report, matched);
	}

	private static double error(ModelDefinition model, Skeleton skeleton, SkeletalAnimation animation, int frame,
		JsonObject expected)
	{
		float[][] posed = SkeletalPoser.skin(model.vertexX, model.vertexY, model.vertexZ,
			model.animayaGroups, model.animayaScales, SkeletalPoser.finalMatrices(skeleton, animation, frame));
		String[] axes = {"x", "y", "z"};
		double worst = 0;
		for (int a = 0; a < 3; a++)
		{
			float[] want = coords(expected, axes[a]);
			for (int v = 0; v < want.length; v++)
			{
				worst = Math.max(worst, Math.abs(posed[a][v] - want[v]));
			}
		}
		return worst;
	}
}
