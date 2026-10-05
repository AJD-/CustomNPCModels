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

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;

public class GltfRetargeterTest
{
	private static final double[] POSITIONS = {0, 0, 0, 1, 0, 0, 1, 0, 1};

	/** Four frames of five cycles: 0.4 s, twice the source's 0.2 s. */
	private static final int STRETCH_TARGET = 100;

	/** Three frames of ten cycles: 0.6 s, three of the source's cycles. */
	private static final int LOOP_TARGET = 101;

	/** One frame of seven cycles: 0.14 s, shorter than the source. */
	private static final int SHORT_TARGET = 102;

	@Test
	public void testStretchSpreadsTheKeysOverTheTarget() throws Exception
	{
		Glb out = retarget(mapping(STRETCH_TARGET, new GltfRetargeter.Target("step", GltfRetargeter.Fit.STRETCH)));

		Gltf.Animation animation = only(out);
		assertEquals(String.valueOf(STRETCH_TARGET), animation.name);
		assertArrayEquals(new double[]{0, 0.2, 0.4}, out.readDoubles(animation.samplers.get(0).input), 1e-6);
		assertArrayEquals(new double[]{0, 0, 0, 1, 0, 0, 2, 0, 0}, out.readDoubles(animation.samplers.get(0).output), 0);
		assertEquals("STEP", animation.samplers.get(0).interpolation);
	}

	/** Each later cycle's first key replaces the key the cycle before ended on. */
	@Test
	public void testLoopRepeatsTheCycleAtItsOwnPace() throws Exception
	{
		Glb out = retarget(mapping(LOOP_TARGET, new GltfRetargeter.Target("step", GltfRetargeter.Fit.LOOP)));

		Gltf.Animation animation = only(out);
		assertArrayEquals(new double[]{0, 0.1, 0.2, 0.3, 0.4, 0.5, 0.6},
			out.readDoubles(animation.samplers.get(0).input), 1e-6);
		double[] x = new double[7];
		double[] values = out.readDoubles(animation.samplers.get(0).output);
		for (int key = 0; key < x.length; key++)
		{
			x[key] = values[key * 3];
		}
		assertArrayEquals(new double[]{0, 1, 0, 1, 0, 1, 2}, x, 0);
	}

	/** The cycle count that changes the pace least, as a ratio: 1.5x slower is worse than 0.75x. */
	@Test
	public void testLoopCyclesPickTheLeastChangeInPace()
	{
		assertEquals(1, GltfRetargeter.loopCycles(0.3));
		assertEquals(1, GltfRetargeter.loopCycles(1.3));
		assertEquals(2, GltfRetargeter.loopCycles(1.4999999));
		assertEquals(2, GltfRetargeter.loopCycles(1.5));
		assertEquals(5, GltfRetargeter.loopCycles(5.0));
		assertEquals(3, GltfRetargeter.loopCycles(2.5));
	}

	@Test
	public void testOneSourceFeedsTwoTargets() throws Exception
	{
		Map<Integer, GltfRetargeter.Target> targets = new LinkedHashMap<>();
		targets.put(STRETCH_TARGET, new GltfRetargeter.Target("step", GltfRetargeter.Fit.STRETCH));
		targets.put(LOOP_TARGET, new GltfRetargeter.Target("step", GltfRetargeter.Fit.STRETCH));
		Glb out = retarget(targets);

		assertEquals(2, out.gltf.animations.size());
		assertEquals(String.valueOf(STRETCH_TARGET), out.gltf.animations.get(0).name);
		assertEquals(String.valueOf(LOOP_TARGET), out.gltf.animations.get(1).name);
		assertEquals(0.6, max(out.readDoubles(out.gltf.animations.get(1).samplers.get(0).input)), 1e-6);
	}

	@Test
	public void testAnUnknownSourceIsRefused() throws Exception
	{
		try
		{
			retarget(mapping(STRETCH_TARGET, new GltfRetargeter.Target("missing", GltfRetargeter.Fit.STRETCH)));
			fail("an unknown source animation was accepted");
		}
		catch (GltfException e)
		{
			assertTrue(e.getMessage(), e.getMessage().contains("'missing'"));
		}
	}

	@Test
	public void testTheMeshAndNodesAreUntouched() throws Exception
	{
		Glb before = Glb.read(source());
		Glb out = retarget(mapping(STRETCH_TARGET, new GltfRetargeter.Target("linear", GltfRetargeter.Fit.STRETCH)));

		int position = out.gltf.meshes.get(0).primitives.get(0).attributes.get("POSITION");
		assertArrayEquals(POSITIONS, out.readDoubles(position), 0);
		assertEquals(before.gltf.nodes.size(), out.gltf.nodes.size());
		assertEquals("the source animations are dropped", 1, out.gltf.animations.size());
		assertEquals("LINEAR", only(out).samplers.get(0).interpolation);
		assertEquals(0, only(out).channels.get(0).target.node.intValue());
	}

	/** Hold plays the source at its own pace, and cuts the keys that start after the target ends. */
	@Test
	public void testHoldKeepsThePaceAndCutsTheRest() throws Exception
	{
		Glb out = retarget(mapping(SHORT_TARGET, new GltfRetargeter.Target("step", GltfRetargeter.Fit.HOLD)));

		Gltf.Animation animation = only(out);
		assertArrayEquals(new double[]{0, 0.1}, out.readDoubles(animation.samplers.get(0).input), 1e-6);
		assertArrayEquals(new double[]{0, 0, 0, 1, 0, 0}, out.readDoubles(animation.samplers.get(0).output), 0);
	}

	@Test
	public void testTheMappingReadsBothForms()
	{
		Map<Integer, GltfRetargeter.Target> targets = GltfRetargeter.readMapping(
			"{\"5326\": {\"from\": \"3424\", \"fit\": \"loop\"}, \"5327\": \"3428\", "
				+ "\"5329\": {\"from\": \"3430\", \"fit\": \"hold\"}}");

		assertEquals("3424", targets.get(5326).from);
		assertEquals(GltfRetargeter.Fit.LOOP, targets.get(5326).fit);
		assertEquals("3428", targets.get(5327).from);
		assertEquals(GltfRetargeter.Fit.STRETCH, targets.get(5327).fit);
		assertEquals(GltfRetargeter.Fit.HOLD, targets.get(5329).fit);
	}

	@Test
	public void testAnUnknownFitIsRefused()
	{
		try
		{
			GltfRetargeter.readMapping("{\"5326\": {\"from\": \"3424\", \"fit\": \"squash\"}}");
			fail("an unknown fit was accepted");
		}
		catch (GltfException e)
		{
			assertTrue(e.getMessage(), e.getMessage().contains("'squash'"));
		}
	}

	private static Glb retarget(Map<Integer, GltfRetargeter.Target> targets) throws Exception
	{
		List<String> report = new ArrayList<>();
		return Glb.read(GltfRetargeter.retarget(source(), targets, GltfRetargeterTest::timing, report));
	}

	private static SequenceTiming timing(int sequenceId)
	{
		switch (sequenceId)
		{
			case STRETCH_TARGET:
				return new SequenceTiming(sequenceId, new int[]{5, 5, 5, 5});
			case LOOP_TARGET:
				return new SequenceTiming(sequenceId, new int[]{10, 10, 10});
			case SHORT_TARGET:
				return new SequenceTiming(sequenceId, new int[]{7});
			default:
				return null;
		}
	}

	private static Map<Integer, GltfRetargeter.Target> mapping(int sequenceId, GltfRetargeter.Target target)
	{
		Map<Integer, GltfRetargeter.Target> targets = new LinkedHashMap<>();
		targets.put(sequenceId, target);
		return targets;
	}

	/**
	 * A mesh on one node, with a 0.2 s step animation moving that node along x (0, 1, then 2 on its
	 * last key) and a linear one.
	 */
	private static byte[] source()
	{
		Gltf gltf = new Gltf();
		Glb.BinBuilder bin = new Glb.BinBuilder(gltf);
		int position = bin.floats(POSITIONS, "VEC3", Gltf.ARRAY_BUFFER, true);
		Gltf.Primitive primitive = new Gltf.Primitive();
		primitive.attributes = new LinkedHashMap<>();
		primitive.attributes.put("POSITION", position);
		Gltf.MeshDef meshDef = new Gltf.MeshDef();
		meshDef.primitives.add(primitive);
		gltf.meshes.add(meshDef);
		Gltf.Node node = new Gltf.Node();
		node.mesh = 0;
		gltf.nodes.add(node);

		gltf.animations = new ArrayList<>();
		gltf.animations.add(animation(bin, "step", "STEP", new double[]{0, 0.1, 0.2}, new double[]{0, 0, 0, 1, 0, 0, 2, 0, 0}));
		gltf.animations.add(animation(bin, "linear", "LINEAR", new double[]{0, 0.2}, new double[]{0, 0, 0, 0, 3, 0}));
		return Glb.write(gltf, bin.finish());
	}

	private static Gltf.Animation animation(Glb.BinBuilder bin, String name, String interpolation, double[] times,
		double[] translations)
	{
		Gltf.Sampler sampler = new Gltf.Sampler();
		sampler.input = bin.floats(times, "SCALAR", null, true);
		sampler.output = bin.floats(translations, "VEC3", null, false);
		sampler.interpolation = interpolation;
		Gltf.Channel channel = new Gltf.Channel();
		channel.sampler = 0;
		channel.target = new Gltf.Target();
		channel.target.node = 0;
		channel.target.path = "translation";
		Gltf.Animation animation = new Gltf.Animation();
		animation.name = name;
		animation.samplers.add(sampler);
		animation.channels.add(channel);
		return animation;
	}

	private static Gltf.Animation only(Glb glb)
	{
		assertEquals(1, glb.gltf.animations.size());
		return glb.gltf.animations.get(0);
	}

	private static double max(double[] values)
	{
		double max = Double.NEGATIVE_INFINITY;
		for (double value : values)
		{
			max = Math.max(max, value);
		}
		return max;
	}
}
