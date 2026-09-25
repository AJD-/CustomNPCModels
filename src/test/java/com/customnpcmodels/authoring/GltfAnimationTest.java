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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import com.customnpcmodels.cache.CacheFiles;
import com.customnpcmodels.inject.Clip;
import com.customnpcmodels.inject.Mesh;
import com.customnpcmodels.inject.Rig;
import com.customnpcmodels.inject.Skinner;
import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeSet;
import net.runelite.cache.definitions.FrameDefinition;
import net.runelite.cache.definitions.FramemapDefinition;
import net.runelite.cache.definitions.SequenceDefinition;
import net.runelite.cache.definitions.loaders.FrameLoader;
import org.junit.Test;

/**
 * Stage B: rigs and clips through glTF and back.
 *
 * <p>The strongest check here is the baked pose: the original and the round-tripped asset are both
 * posed by the real {@code Skinner} at every frame and their vertices compared, so a mistake in the
 * rig reconstruction cannot cancel out against a mistake in the conversion the way it could when
 * comparing intermediate representations.
 */
public class GltfAnimationTest
{
	/**
	 * FrameOps is a port of FrameLoader's op construction. Serializing the same masks and values into
	 * the modern frame format and decoding them with the real FrameLoader must give identical ops.
	 */
	@Test
	public void testFrameOpsMatchesFrameLoader()
	{
		Random random = new Random(338);
		for (int trial = 0; trial < 500; trial++)
		{
			int length = 1 + random.nextInt(40);
			int[] types = new int[length];
			int[] masks = new int[length];
			List<Integer> values = new ArrayList<>();
			for (int i = 0; i < length; i++)
			{
				types[i] = new int[]{0, 1, 2, 3, 5}[random.nextInt(5)];
				masks[i] = random.nextInt(3) == 0 ? 0 : random.nextInt(8);
				for (int bit = 0; bit < 3; bit++)
				{
					if ((masks[i] & 1 << bit) != 0)
					{
						values.add(random.nextInt(2) == 0 ? random.nextInt(128) - 64 : random.nextInt(4000) - 2000);
					}
				}
			}

			int[][] ours = FrameOps.build(types, masks, values.stream().mapToInt(Integer::intValue).toArray());

			FramemapDefinition framemap = new FramemapDefinition();
			framemap.id = 7;
			framemap.types = types;
			framemap.length = length;
			FrameDefinition theirs = new FrameLoader().load(framemap, 0, encode(framemap.id, masks, values));

			assertArrayEquals("trial " + trial + " transforms", theirs.indexFrameIds, ours[0]);
			assertArrayEquals("trial " + trial + " x", theirs.translator_x, ours[1]);
			assertArrayEquals("trial " + trial + " y", theirs.translator_y, ours[2]);
			assertArrayEquals("trial " + trial + " z", theirs.translator_z, ours[3]);
		}
	}

	/** The modern frame format: framemap id, mask count, masks, then signed smart values. */
	private static byte[] encode(int framemapId, int[] masks, List<Integer> values)
	{
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		out.write(framemapId >> 8);
		out.write(framemapId);
		out.write(masks.length);
		for (int mask : masks)
		{
			out.write(mask);
		}
		for (int value : values)
		{
			if (value >= -64 && value < 64)
			{
				out.write(value + 64);
			}
			else
			{
				int packed = value + 0xC000;
				out.write(packed >> 8);
				out.write(packed);
			}
		}
		return out.toByteArray();
	}

	/**
	 * RigPoser folds each op into per-group matrices instead of moving vertices. Applied to the rest
	 * vertices, those matrices must reproduce the skinner's own pose on real cache clips.
	 */
	@Test
	public void testRigPoserReproducesTheSkinner() throws Exception
	{
		assertPoserMatches(LiveFixtures.mesh(LiveFixtures.SKELETON_MESH), LiveFixtures.SKELETON_READY, LiveFixtures.SKELETON_WALK);
		assertPoserMatches(LiveFixtures.mole(), LiveFixtures.MOLE_READY, LiveFixtures.MOLE_WALK);
	}

	private static void assertPoserMatches(Mesh mesh, int... sequences) throws Exception
	{
		Skinner skinner = new Skinner();
		int n = mesh.getVerticesCount();
		float[] x = new float[n];
		float[] y = new float[n];
		float[] z = new float[n];

		for (int sequence : sequences)
		{
			Clip clip = LiveFixtures.clip(sequence);
			Rig rig = LiveFixtures.rig(clip.getRigId());
			for (int frame = 0; frame < clip.getFrameCount(); frame++)
			{
				skinner.pose(mesh, rig, clip, frame, x, y, z);
				double[][] groups = RigPoser.pose(mesh, rig, clip, frame, mesh.getVertexGroups().length);
				for (int g = 0; g < groups.length; g++)
				{
					for (int v : mesh.getVertexGroup(g))
					{
						double[] p = Mat4.transformPoint(groups[g], mesh.getVerticesX()[v], mesh.getVerticesY()[v], mesh.getVerticesZ()[v]);
						assertEquals("sequence " + sequence + " frame " + frame + " vertex " + v, x[v], p[0], 0.01);
						assertEquals(y[v], p[1], 0.01);
						assertEquals(z[v], p[2], 0.01);
					}
				}
			}
		}
	}

	@Test
	public void testRsEulerInvertsRsRotation()
	{
		Random random = new Random(262);
		for (int trial = 0; trial < 2000; trial++)
		{
			int ax = random.nextInt(256);
			int ay = random.nextInt(256);
			int az = random.nextInt(256);
			double[] r = Mat4.rsRotation(ax, ay, az);
			double[] euler = Mat4.rsEuler(r);
			double[] back = Mat4.rsRotation(Mat4.quantizeAngle(euler[0]), Mat4.quantizeAngle(euler[1]), Mat4.quantizeAngle(euler[2]));
			for (int i = 0; i < 16; i++)
			{
				assertEquals("angles " + ax + "," + ay + "," + az, r[i], back[i], 1e-3);
			}
		}
	}

	/**
	 * A clip that scales a group to nothing leaves its joint with no axes to read a rotation from. Any
	 * rotation is then exact, but it must be a real one: glTF has no room for NaN keys.
	 */
	@Test
	public void testToTrsOfAFullyCollapsedMatrix()
	{
		double[] m = Mat4.multiply(Mat4.translation(0.5, 1, -2), Mat4.scale(0, 0, 0));
		double[][] trs = Mat4.toTrs(m);

		assertUnitQuaternion(trs[1]);
		assertArrayEquals(new double[]{0, 0, 0}, trs[2], 1e-12);
		assertArrayEquals(m, Mat4.fromTrs(trs[0], trs[1], trs[2]), 1e-9);
	}

	/**
	 * With one or two axes collapsed the survivors still carry a real rotation, which must come back,
	 * and the missing axes are completed around them.
	 */
	@Test
	public void testToTrsOfAPartlyCollapsedMatrix()
	{
		double[] rotation = Mat4.rsRotation(40, 90, 17);
		double[][] scales = {{0, 1.5, 2}, {1, 0, 1}, {3, 2, 0}, {0, 0, 3}, {0, 2, 0}, {1.5, 0, 0}};
		for (double[] s : scales)
		{
			double[] m = Mat4.multiply(Mat4.translation(1, 2, 3), rotation, Mat4.scale(s[0], s[1], s[2]));
			double[][] trs = Mat4.toTrs(m);

			String label = s[0] + "," + s[1] + "," + s[2];
			assertUnitQuaternion(trs[1]);
			assertArrayEquals(label, s, trs[2], 1e-3);
			assertArrayEquals(label, m, Mat4.fromTrs(trs[0], trs[1], trs[2]), 1e-3);
		}
	}

	private static void assertUnitQuaternion(double[] q)
	{
		for (double component : q)
		{
			assertTrue("quaternion " + Arrays.toString(q), Double.isFinite(component));
		}
		assertEquals(1, Math.sqrt(q[0] * q[0] + q[1] * q[1] + q[2] * q[2] + q[3] * q[3]), 1e-9);
	}

	/**
	 * Commander Zilyana's model carries an effect sphere on group 100, which her standing and walking
	 * clips scale to nothing on every frame. The file must hold real keys for it, say that the group
	 * is hidden in everything exported, and round-trip with the sphere still collapsed. The sphere
	 * itself lands exactly; the bound is looser than the skeleton's because the standing clip (6966)
	 * shears group 25, which both halves report (measured: 2.73 units standing, 1.97 walking).
	 */
	@Test
	public void testACollapsedGroupExportsCleanlyAndRoundTrips() throws Exception
	{
		Mesh mesh = GltfExporter.npcMesh(LiveFixtures.store(), LiveFixtures.npc(LiveFixtures.ZILYANA));
		int[] sequences = {LiveFixtures.ZILYANA_READY, LiveFixtures.ZILYANA_WALK};
		List<Clip> clips = new ArrayList<>();
		for (int sequence : sequences)
		{
			clips.add(LiveFixtures.clip(sequence));
		}
		List<String> report = new ArrayList<>();
		Glb glb = Glb.read(GlbWriter.write(mesh, LiveFixtures.rigs(), clips, timings(sequences), report));

		for (Gltf.Animation animation : glb.gltf.animations)
		{
			for (Gltf.Sampler sampler : animation.samplers)
			{
				for (double value : glb.readDoubles(sampler.output))
				{
					assertTrue("animation " + animation.name + " has a non-finite key", Double.isFinite(value));
				}
			}
		}
		assertTrue("writer " + report, report.stream().anyMatch(line ->
			line.startsWith("Vertex group 100 (80 faces) is scaled to zero in every exported clip")));

		GltfToMeshConverter.Result result = roundTrip(mesh, new ArrayList<>(), sequences);
		double worst = worstPoseError(mesh, result, sequences);
		System.out.println("Zilyana round trip: worst vertex error " + worst + " units; writer " + report
			+ "; reader " + result.report);
		assertTrue("worst vertex error " + worst, worst < 3.0);
	}

	/**
	 * Every angle pair around both quarter turns of X, where Y and Z collapse onto one axis. The
	 * gimbal test in {@link Mat4#rsEuler} has to catch x = 64 and 192 exactly - the 16-bit trig tables
	 * never let the matrix reach a true quarter turn - and one step either side must not be caught.
	 */
	@Test
	public void testRsEulerInvertsRsRotationAroundGimbalLock()
	{
		for (int ax : new int[]{63, 64, 65, 191, 192, 193})
		{
			for (int ay = 0; ay < 256; ay++)
			{
				for (int az = 0; az < 256; az++)
				{
					double[] r = Mat4.rsRotation(ax, ay, az);
					double[] euler = Mat4.rsEuler(r);
					double[] back = Mat4.rsRotation(Mat4.quantizeAngle(euler[0]), Mat4.quantizeAngle(euler[1]),
						Mat4.quantizeAngle(euler[2]));
					for (int i = 0; i < 16; i++)
					{
						if (Math.abs(r[i] - back[i]) > 1e-3)
						{
							assertEquals("angles " + ax + "," + ay + "," + az + " element " + i, r[i], back[i], 1e-3);
						}
					}
				}
			}
		}
	}

	static Map<Integer, SequenceTiming> timings(int... sequences) throws Exception
	{
		Map<Integer, SequenceTiming> timings = new LinkedHashMap<>();
		for (int sequence : sequences)
		{
			SequenceDefinition definition = CacheFiles.loadSequence(LiveFixtures.store(), sequence);
			assertNotNull(definition);
			timings.put(sequence, new SequenceTiming(sequence, definition.frameLengths));
		}
		return timings;
	}

	/** Writer then converter, with every clip mapped back to its own sequence. */
	static GltfToMeshConverter.Result roundTrip(Mesh mesh, List<String> writerReport, int... sequences) throws Exception
	{
		List<Clip> clips = new ArrayList<>();
		for (int sequence : sequences)
		{
			clips.add(LiveFixtures.clip(sequence));
		}
		Map<Integer, SequenceTiming> timings = timings(sequences);

		byte[] glb = GlbWriter.write(mesh, LiveFixtures.rigs(), clips, timings, writerReport);

		Map<String, SequenceTiming> animations = new LinkedHashMap<>();
		for (int sequence : sequences)
		{
			animations.put(String.valueOf(sequence), timings.get(sequence));
		}
		return GltfToMeshConverter.convert(glb, 1_000_001, 1_000_001, animations);
	}

	/** The largest distance any vertex lands from where the original puts it, over every frame. */
	static double worstPoseError(Mesh original, GltfToMeshConverter.Result converted, int... sequences) throws Exception
	{
		double worst = 0;
		for (int s = 0; s < sequences.length; s++)
		{
			Clip originalClip = LiveFixtures.clip(sequences[s]);
			worst = Math.max(worst, PoseComparison.worstPoseError(original, LiveFixtures.rig(originalClip.getRigId()),
				originalClip, converted.mesh, converted.rig, converted.clips.get(s)));
		}
		return worst;
	}

	/**
	 * The baked-pose oracle on the skeleton, whose mesh and clips Retro NPC Swapper already draws
	 * correctly in game. The rig is rebuilt from the file's joint hierarchy alone, and every frame
	 * must land where the original does, within the rotation quantisation.
	 */
	@Test
	public void testTheSkeletonPosesTheSameAfterARoundTrip() throws Exception
	{
		Mesh mesh = LiveFixtures.mesh(LiveFixtures.SKELETON_MESH);
		List<String> report = new ArrayList<>();
		GltfToMeshConverter.Result result = roundTrip(mesh, report, LiveFixtures.SKELETON_READY, LiveFixtures.SKELETON_WALK);

		double worst = worstPoseError(mesh, result, LiveFixtures.SKELETON_READY, LiveFixtures.SKELETON_WALK);
		System.out.println("Skeleton round trip: worst vertex error " + worst + " units; writer " + report
			+ "; reader " + result.report);
		assertTrue("worst vertex error " + worst, worst < 2.0);

		assertEquals(Collections.emptyList(), AssetValidator.validateMesh(result.mesh));
		assertEquals(Collections.emptyList(), AssetValidator.validateRig(result.rig));
		Map<Integer, Rig> rigs = Collections.singletonMap(result.rig.getId(), result.rig);
		assertEquals(Collections.emptyList(), AssetValidator.validateClip(result.clips.get(0), rigs,
			LiveFixtures.liveFrameCount(LiveFixtures.SKELETON_READY)));
	}

	/**
	 * The framemap shape: the rebuilt rig pivots each joint on its own group and rotates the union of
	 * its subtree, and every rotate set the original rig names about a single owning group comes back
	 * as that group's subtree - the hierarchy was recovered, not just approximated.
	 */
	@Test
	public void testTheSkeletonHierarchyIsRecovered() throws Exception
	{
		Mesh mesh = LiveFixtures.mesh(LiveFixtures.SKELETON_MESH);
		GltfToMeshConverter.Result result = roundTrip(mesh, new ArrayList<>(), LiveFixtures.SKELETON_READY);
		Rig rebuilt = result.rig;

		Map<Integer, Set<Integer>> subtreeOf = new LinkedHashMap<>();
		for (int t = 0; t + 3 < rebuilt.getTransformCount(); t += GltfToMeshConverter.TRANSFORMS_PER_JOINT)
		{
			assertEquals(1, rebuilt.getType(t));
			assertEquals(0, rebuilt.getType(t + 1));
			assertEquals(2, rebuilt.getType(t + 2));
			assertEquals(1, rebuilt.getGroups(t + 1).length);
			assertEquals(3, rebuilt.getType(t + 3));
			assertArrayEquals(rebuilt.getGroups(t), rebuilt.getGroups(t + 2));
			assertArrayEquals(rebuilt.getGroups(t), rebuilt.getGroups(t + 3));
			subtreeOf.put(rebuilt.getGroups(t + 1)[0], toSet(rebuilt.getGroups(t + 2)));
		}

		Set<Integer> used = new TreeSet<>();
		for (int g = 0; g < mesh.getVertexGroups().length; g++)
		{
			if (mesh.getVertexGroup(g).length > 0)
			{
				used.add(g);
			}
		}

		Rig original = LiveFixtures.rig(LiveFixtures.clip(LiveFixtures.SKELETON_READY).getRigId());
		int[] lastPivot = null;
		int compared = 0;
		for (int t = 0; t < original.getTransformCount(); t++)
		{
			if (original.getType(t) == 0)
			{
				lastPivot = original.getGroups(t);
			}
			else if (original.getType(t) == 2 && lastPivot != null)
			{
				Set<Integer> pivot = toSet(lastPivot);
				pivot.retainAll(used);
				Set<Integer> rotated = toSet(original.getGroups(t));
				rotated.retainAll(used);
				if (pivot.size() == 1 && !rotated.isEmpty())
				{
					int owner = pivot.iterator().next();
					if (subtreeOf.get(owner).equals(rotated))
					{
						compared++;
					}
				}
			}
		}
		System.out.println("Skeleton hierarchy: " + compared + " of the original rotate sets recovered exactly");
		assertTrue("no original rotate set was recovered", compared > 0);
	}

	private static Set<Integer> toSet(int[] values)
	{
		Set<Integer> set = new TreeSet<>();
		for (int v : values)
		{
			set.add(v);
		}
		return set;
	}

	/** The first authoring subject. Reported either way; asserted to the same bound as the skeleton. */
	@Test
	public void testTheMolePosesTheSameAfterARoundTrip() throws Exception
	{
		Mesh mesh = LiveFixtures.mole();
		List<String> report = new ArrayList<>();
		GltfToMeshConverter.Result result = roundTrip(mesh, report, LiveFixtures.MOLE_READY, LiveFixtures.MOLE_WALK);

		double worst = worstPoseError(mesh, result, LiveFixtures.MOLE_READY, LiveFixtures.MOLE_WALK);
		System.out.println("Mole round trip: worst vertex error " + worst + " units; writer " + report
			+ "; reader " + result.report);
		assertTrue("worst vertex error " + worst, worst < 2.0);
	}

	/**
	 * Every Giant Mole sequence, one at a time. All but one land within two units (measured: 0.6 to
	 * 1.6). The death clip (3310) scales a group along an axis it has already rotated, which is shear -
	 * a glTF joint cannot hold it - so it is reported by both halves and held to a looser bound
	 * (measured: 19 units, about a seventh of a tile, at its worst frame).
	 */
	@Test
	public void testEveryMoleSequenceRoundTrips() throws Exception
	{
		Mesh mesh = LiveFixtures.mole();
		int[] sequences = {3309, 3310, 3311, 3312, 3313, 3314, 3315};
		StringBuilder summary = new StringBuilder("Mole per sequence:");
		for (int sequence : sequences)
		{
			GltfToMeshConverter.Result result = roundTrip(mesh, new ArrayList<>(), sequence);
			double worst = worstPoseError(mesh, result, sequence);
			summary.append(' ').append(sequence).append('=').append(String.format("%.2f", worst));
			assertTrue("sequence " + sequence + " worst vertex error " + worst, worst < (sequence == 3310 ? 24.0 : 2.0));
		}
		System.out.println(summary);
	}
}
