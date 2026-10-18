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

import static org.junit.Assert.assertTrue;
import com.customnpcmodels.cache.CacheFiles;
import com.customnpcmodels.cache.skeletal.SkeletalAnimation;
import com.customnpcmodels.cache.skeletal.SkeletalPoser;
import com.customnpcmodels.cache.skeletal.Skeleton;
import com.customnpcmodels.inject.Clip;
import com.customnpcmodels.inject.Skinner;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.stream.Stream;
import net.runelite.api.gameval.AnimationID;
import net.runelite.api.gameval.NpcID;
import net.runelite.cache.definitions.ModelDefinition;
import net.runelite.cache.definitions.NpcDefinition;
import net.runelite.cache.fs.Store;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * A skeletal NPC through the whole authoring pipeline: Yami exported from the live cache, converted
 * as the asset generator converts it, then posed by the plugin's own skinner - against the poses
 * the skeletal decoders give, which match the client's.
 */
public class SkeletalRoundTripTest
{
	/**
	 * In engine units. The engine's rotation steps alone cost Yami's limbs 4 to 5; keeping only a
	 * vertex's heaviest bone cost up to 36 until vertices weighted to several began to blend.
	 */
	private static final double WORST = 6;

	@Rule
	public TemporaryFolder folder = new TemporaryFolder();

	@Test
	public void testYamiAnimatesAsTheClientDoes() throws Exception
	{
		Store store = LiveFixtures.store();
		NpcDefinition yami = LiveFixtures.npc(NpcID.YAMA_PET);
		int[] sequences = {AnimationID.NPC_YAMA01_IDLE01, AnimationID.NPC_YAMA01_WALK03};

		Path out = folder.newFolder().toPath();
		GltfExporter.export(store, yami, new LinkedHashSet<>(Arrays.asList(sequences[0], sequences[1])), out);
		Path glb;
		try (Stream<Path> files = Files.list(out))
		{
			glb = files.filter(file -> file.toString().endsWith(".glb")).findFirst().orElseThrow(AssertionError::new);
		}

		Map<String, SequenceTiming> timings = new LinkedHashMap<>();
		for (int sequence : sequences)
		{
			timings.put(String.valueOf(sequence), AssetGenerator.timing(store, sequence));
		}
		GltfToMeshConverter.Result result = GltfToMeshConverter.convert(Files.readAllBytes(glb), 1, 1, timings);
		assertTrue("vertex numbering kept", result.keptVertexNumbering);

		ModelDefinition model = CacheFiles.loadModel(store, yami.models[0]);
		Skinner skinner = new Skinner();
		StringBuilder report = new StringBuilder(String.join("\n", result.report)).append('\n');
		double worst = 0;
		for (int sequence : sequences)
		{
			Clip clip = result.clipsByAnimation.get(String.valueOf(sequence));
			SkeletalAnimation animation = CacheFiles.loadSkeletalAnimation(store, CacheFiles.loadSequence(store, sequence));
			Skeleton skeleton = CacheFiles.loadSkeleton(store, animation.getSkeletonId());
			double sequenceWorst = 0;
			long ops = 0;
			int vertices = result.mesh.getVerticesCount();
			for (int frame = 0; frame < clip.getFrameCount(); frame++)
			{
				ops += clip.getOpCount(frame);
				float[] x = new float[vertices];
				float[] y = new float[vertices];
				float[] z = new float[vertices];
				skinner.pose(result.mesh, result.rig, clip, frame, x, y, z);
				float[][] truth = SkeletalPoser.skin(model.vertexX, model.vertexY, model.vertexZ, model.animayaGroups,
					model.animayaScales, SkeletalPoser.finalMatrices(skeleton, animation, frame));
				for (int v = 0; v < vertices; v++)
				{
					sequenceWorst = Math.max(sequenceWorst, Math.max(Math.abs(x[v] - truth[0][v]),
						Math.max(Math.abs(y[v] - truth[1][v]), Math.abs(z[v] - truth[2][v]))));
				}
			}
			report.append(String.format("sequence %d: %d frames, %d ops, worst %.1f units%n", sequence,
				clip.getFrameCount(), ops, sequenceWorst));
			worst = Math.max(worst, sequenceWorst);
		}
		assertTrue(report.toString(), worst <= WORST);
	}
}
