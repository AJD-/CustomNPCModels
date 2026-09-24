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
package com.customnpcmodels.inject;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

/**
 * Hand-checkable cases for the skinner. Agreement with the client over a real animation is measured
 * in game against {@code applyTransformations}; these pin the arithmetic that would otherwise only
 * be visible as a subtly wrong pose.
 */
public class SkinnerTest
{
	private static final float EPSILON = 0.01f;

	/** Two vertices on the X axis, both in group 0. */
	private static Mesh mesh()
	{
		return new Mesh(1, 0,
			new float[]{0f, 100f},
			new float[]{0f, 0f},
			new float[]{0f, 0f},
			new int[]{0}, new int[]{1}, new int[]{0},
			new short[]{0}, null, null, null, null,
			null, null, null, null,
			new int[][]{{0, 1}});
	}

	/** Transform 0 sets the pivot, transform 1 is the op under test. */
	private static Rig rig(int opType)
	{
		return new Rig(1, new int[]{0, opType}, new int[][]{{0}, {0}});
	}

	private static Clip clip(int dx, int dy, int dz)
	{
		return new Clip(1, 1,
			new int[][]{{0, 1}},
			new int[][]{{0, dx}},
			new int[][]{{0, dy}},
			new int[][]{{0, dz}});
	}

	private static float[][] pose(Rig rig, Clip clip)
	{
		Mesh mesh = mesh();
		float[] x = new float[mesh.getVerticesCount()];
		float[] y = new float[mesh.getVerticesCount()];
		float[] z = new float[mesh.getVerticesCount()];

		assertTrue(new Skinner().pose(mesh, rig, clip, 0, x, y, z));
		return new float[][]{x, y, z};
	}

	/**
	 * A quarter turn about Y. Rotations are stored in 8 bits and scaled by 8 into the 2048-entry
	 * table, so 64 is a quarter of a circle - not 512, which is the trap if you assume the stored
	 * value indexes the table directly.
	 */
	@Test
	public void testQuarterTurnAboutYSwingsIntoZ()
	{
		float[][] posed = pose(rig(2), clip(0, 64, 0));

		// The pivot is the mean of the group, so (50, 0, 0); the two vertices sit 50 either side of
		// it on X and swing to 50 either side on Z
		assertEquals(50f, posed[0][0], EPSILON);
		assertEquals(0f, posed[1][0], EPSILON);
		assertEquals(50f, posed[2][0], EPSILON);

		assertEquals(50f, posed[0][1], EPSILON);
		assertEquals(0f, posed[1][1], EPSILON);
		assertEquals(-50f, posed[2][1], EPSILON);
	}

	@Test
	public void testTranslateMovesEveryVertexInTheGroup()
	{
		float[][] posed = pose(rig(1), clip(5, -7, 11));

		assertEquals(5f, posed[0][0], EPSILON);
		assertEquals(-7f, posed[1][0], EPSILON);
		assertEquals(11f, posed[2][0], EPSILON);

		assertEquals(105f, posed[0][1], EPSILON);
		assertEquals(-7f, posed[1][1], EPSILON);
		assertEquals(11f, posed[2][1], EPSILON);
	}

	/** Scale is in 128ths and works about the pivot, so the group spreads around its own centre. */
	@Test
	public void testScaleWorksAboutThePivot()
	{
		float[][] posed = pose(rig(3), clip(256, 128, 128));

		// Doubling X about a pivot of 50 sends 0 to -50 and 100 to 150
		assertEquals(-50f, posed[0][0], EPSILON);
		assertEquals(150f, posed[0][1], EPSILON);
	}

	@Test
	public void testUnknownOpTypeLeavesTheMeshAlone()
	{
		// Type 5 animates transparency, which this does not carry - it must be a no-op rather than
		// falling through into a transform
		float[][] posed = pose(rig(5), clip(64, 64, 64));

		assertEquals(0f, posed[0][0], EPSILON);
		assertEquals(100f, posed[0][1], EPSILON);
	}

	@Test
	public void testPosingIsNotCumulativeAcrossCalls()
	{
		Mesh mesh = mesh();
		Skinner skinner = new Skinner();
		float[] x = new float[2];
		float[] y = new float[2];
		float[] z = new float[2];

		skinner.pose(mesh, rig(1), clip(10, 0, 0), 0, x, y, z);
		skinner.pose(mesh, rig(1), clip(10, 0, 0), 0, x, y, z);

		assertEquals("a second pose must start from the rest pose, not the last result",
			10f, x[0], EPSILON);
	}

	@Test
	public void testUnriggedMeshReportsFailureAndKeepsTheRestPose()
	{
		Mesh unrigged = new Mesh(1, 0,
			new float[]{7f}, new float[]{8f}, new float[]{9f},
			new int[]{0}, new int[]{0}, new int[]{0},
			new short[]{0}, null, null, null, null,
			null, null, null, null,
			null);

		float[] x = new float[1];
		float[] y = new float[1];
		float[] z = new float[1];

		assertFalse(new Skinner().pose(unrigged, rig(1), clip(50, 50, 50), 0, x, y, z));
		assertEquals("the buffers must still hold usable geometry after a refusal", 7f, x[0], EPSILON);
		assertEquals(8f, y[0], EPSILON);
		assertEquals(9f, z[0], EPSILON);
	}

	@Test
	public void testFrameOutOfRangeIsRefusedRatherThanThrowing()
	{
		Mesh mesh = mesh();
		float[] x = new float[2];
		float[] y = new float[2];
		float[] z = new float[2];

		// The frame index comes from the client, driven by the live sequence, so it can outrun a
		// clip that decoded short
		assertFalse(new Skinner().pose(mesh, rig(1), clip(5, 5, 5), 99, x, y, z));
	}
}
