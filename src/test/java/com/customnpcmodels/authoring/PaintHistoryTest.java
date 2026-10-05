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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;

public class PaintHistoryTest
{
	@Test
	public void testUndoAndRedoReplayAStroke()
	{
		short[] colors = {1, 1, 1};
		PaintHistory history = new PaintHistory();
		colors[0] = 5;
		colors[2] = 5;
		history.record(Arrays.asList(new PaintHistory.Change(0, (short) 1, (short) 5),
			new PaintHistory.Change(2, (short) 1, (short) 5)));

		assertTrue(history.undo((face, color) -> colors[face] = color));
		assertArrayEquals(new short[]{1, 1, 1}, colors);
		assertFalse("one stroke, so one undo", history.undo((face, color) -> colors[face] = color));

		assertTrue(history.redo((face, color) -> colors[face] = color));
		assertArrayEquals(new short[]{5, 1, 5}, colors);
	}

	@Test
	public void testANewStrokeDropsWhatWasUndone()
	{
		PaintHistory history = new PaintHistory();
		history.record(Arrays.asList(new PaintHistory.Change(0, (short) 1, (short) 2)));
		history.undo((face, color) -> { });
		history.record(Arrays.asList(new PaintHistory.Change(1, (short) 1, (short) 3)));

		assertFalse(history.redo((face, color) -> { }));
	}

	@Test
	public void testFacesSharingAnEdgeAreNeighboursButNotThoseSharingACorner()
	{
		// Faces 0 and 1 share the edge 1-2; face 2 touches face 1 only at vertex 3
		List<int[]> adjacent = FaceAdjacency.of(new TestMesh()
			.vx(new float[6]).vy(new float[6]).vz(new float[6])
			.i1(new int[]{0, 1, 3}).i2(new int[]{1, 2, 4}).i3(new int[]{2, 3, 5})
			.colors(new short[3]).groups(new int[0][]).build());

		assertArrayEquals(new int[]{1}, adjacent.get(0));
		assertArrayEquals(new int[]{0}, adjacent.get(1));
		assertEquals(0, adjacent.get(2).length);
	}
}
