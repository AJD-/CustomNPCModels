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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import com.customnpcmodels.inject.Clip;
import com.customnpcmodels.inject.Mesh;
import com.customnpcmodels.inject.Rig;
import com.customnpcmodels.inject.Skinner;
import com.customnpcmodels.inject.TestMesh;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;

public class AnimationDocumentTest
{
	private static final int SEQUENCE = 100;
	private static final int RIG = 7;
	private static final short RED = (short) 0x3A05;
	private static final short BLUE = (short) 0x1234;

	/** Frame 0 at rest, frame 1 with group 1 (vertices 2 and 3) lifted a quarter tile. */
	private static final SequenceTiming TIMING = new SequenceTiming(SEQUENCE, new int[]{2, 3});

	private static byte[] animatedGlb()
	{
		Mesh mesh = new TestMesh().colors(new short[]{RED, BLUE}).build();
		Map<Integer, Rig> rigs = new LinkedHashMap<>();
		rigs.put(RIG, new Rig(RIG, new int[]{1}, new int[][]{{1}}));
		Clip clip = new Clip(SEQUENCE, RIG,
			new int[][]{{}, {0}},
			new int[][]{{}, {0}},
			new int[][]{{}, {-32}},
			new int[][]{{}, {0}});
		Map<Integer, SequenceTiming> timings = new LinkedHashMap<>();
		timings.put(SEQUENCE, TIMING);
		return GlbWriter.write(mesh, rigs, Collections.singletonList(clip), timings, new ArrayList<>());
	}

	private static AssetGenerator.Timings live()
	{
		return sequenceId -> sequenceId == SEQUENCE ? TIMING : null;
	}

	private static AnimationDocument.Animation only(AnimationDocument document)
	{
		assertEquals(1, document.animations().size());
		return document.animations().get(0);
	}

	@Test
	public void aNumericNameIsPlayedAgainstItsSequence()
	{
		AnimationDocument document = AnimationDocument.load(animatedGlb(), null, live());
		AnimationDocument.Animation animation = only(document);

		assertEquals(String.valueOf(SEQUENCE), animation.name);
		assertEquals(SEQUENCE, animation.sequenceId);
		assertTrue(animation.isPlayable());
		assertNull(animation.unplayableReason);
		assertEquals(2, animation.clip.getFrameCount());
		assertEquals(2, animation.timing.frameCount());
		assertNotNull(document.rig());
	}

	@Test
	public void posingTheClipMovesWhatTheGameWouldMove()
	{
		AnimationDocument document = AnimationDocument.load(animatedGlb(), null, live());
		AnimationDocument.Animation animation = only(document);
		Mesh mesh = document.mesh();
		int count = mesh.getVerticesCount();
		float[] x = new float[count];
		float[] y = new float[count];
		float[] z = new float[count];

		assertTrue(new Skinner().pose(mesh, document.rig(), animation.clip, 1, x, y, z));
		for (int v = 0; v < count; v++)
		{
			float expected = mesh.getVerticesY()[v] + (v >= 2 ? -32 : 0);
			assertEquals("vertex " + v, expected, y[v], 0.5f);
			assertEquals(mesh.getVerticesX()[v], x[v], 0.5f);
			assertEquals(mesh.getVerticesZ()[v], z[v], 0.5f);
		}
	}

	@Test
	public void theManifestMappingWinsOverTheName()
	{
		Manifest.Model entry = new Manifest.Model();
		entry.animations.put(String.valueOf(SEQUENCE), 555);
		SequenceTiming other = new SequenceTiming(555, new int[]{1, 1, 1});

		AnimationDocument document = AnimationDocument.load(animatedGlb(), entry,
			sequenceId -> sequenceId == 555 ? other : null);
		AnimationDocument.Animation animation = only(document);

		assertEquals(555, animation.sequenceId);
		assertEquals(3, animation.clip.getFrameCount());
	}

	@Test
	public void aNameTheManifestDoesNotMapIsUnplayable()
	{
		Manifest.Model entry = new Manifest.Model();
		entry.animations.put("walk", SEQUENCE);

		AnimationDocument document = AnimationDocument.load(animatedGlb(), entry, live());
		AnimationDocument.Animation animation = only(document);

		assertFalse(animation.isPlayable());
		assertEquals(-1, animation.sequenceId);
		assertTrue(animation.unplayableReason, animation.unplayableReason.contains("models.json"));
		assertTrue(document.report().stream().anyMatch(line -> line.contains("'walk'")));
	}

	@Test
	public void aSequenceTheCacheDoesNotHaveIsUnplayable()
	{
		AnimationDocument document = AnimationDocument.load(animatedGlb(), null, sequenceId -> null);
		AnimationDocument.Animation animation = only(document);

		assertFalse(animation.isPlayable());
		assertEquals(SEQUENCE, animation.sequenceId);
		assertTrue(animation.unplayableReason, animation.unplayableReason.contains("not a frame-based"));
	}

	@Test
	public void withoutACacheNothingIsPlayable()
	{
		AnimationDocument document = AnimationDocument.load(animatedGlb(), null, null);
		AnimationDocument.Animation animation = only(document);

		assertFalse(animation.isPlayable());
		assertTrue(animation.unplayableReason, animation.unplayableReason.contains("cache"));
	}

	@Test
	public void theManifestRecolorsScaleAndLightingAreApplied()
	{
		Manifest.Model entry = new Manifest.Model();
		entry.recolors = Collections.singletonList(new Manifest.Recolor(RED & 0xFFFF, 0x0101));
		entry.scaleXZ = 64;
		entry.scaleY = 256;
		entry.ambient = 20;
		entry.contrast = -10;

		AnimationDocument document = AnimationDocument.load(animatedGlb(), entry, live());

		assertEquals((short) 0x0101, document.faceColors()[0]);
		assertEquals(BLUE, document.faceColors()[1]);
		assertTrue(document.isRecolored());
		assertEquals(0.5f, document.scaleXZ(), 0f);
		assertEquals(2f, document.scaleY(), 0f);
		assertEquals(20, document.ambient());
		assertEquals(-10, document.contrast());
	}

	@Test
	public void aStaticFileHasNothingToPlay()
	{
		byte[] glb = GlbWriter.write(new TestMesh().build(), new ArrayList<>());
		AnimationDocument document = AnimationDocument.load(glb, null, live());

		assertTrue(document.animations().isEmpty());
		assertFalse(document.isRecolored());
		assertEquals(1f, document.scaleXZ(), 0f);
		List<AnimationDocument.Animation> playable = document.playable();
		assertTrue(playable.isEmpty());
	}
}
