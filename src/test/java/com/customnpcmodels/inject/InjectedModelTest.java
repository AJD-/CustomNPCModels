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
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

/**
 * Covers the copy that stands between the client's shared posed model and the renderer.
 *
 * <p>The source is a {@link InjectedModel} subclass rather than a mock: {@code Model} has around sixty
 * methods, and the handful that carry data are exactly the ones {@code InjectedModel} already
 * implements, so subclassing gives a real source in a few lines.
 */
public class InjectedModelTest
{
	/** A unit cube-ish source with known bounds: radius 3, tallest vertex 4. */
	private static Source source(int vertices, int faces)
	{
		Source source = new Source();
		source.verticesCount = vertices;
		source.faceCount = faces;
		source.x = new float[vertices];
		source.y = new float[vertices];
		source.z = new float[vertices];
		source.i1 = new int[faces];
		source.i2 = new int[faces];
		source.i3 = new int[faces];
		source.c1 = new int[faces];
		source.c2 = new int[faces];
		source.c3 = new int[faces];
		return source;
	}

	@Test
	public void testCopiesGeometry()
	{
		Source src = source(3, 1);
		src.x[0] = 3;
		src.y[0] = -4;
		src.z[0] = 0;
		src.i1[0] = 0;
		src.i2[0] = 1;
		src.i3[0] = 2;
		src.c3[0] = -1;

		InjectedModel model = new InjectedModel();
		model.copyFrom(src);

		assertEquals(3, model.getVerticesCount());
		assertEquals(1, model.getFaceCount());
		assertEquals(3f, model.getVerticesX()[0], 0f);
		assertEquals(-4f, model.getVerticesY()[0], 0f);
		assertEquals(2, model.getFaceIndices3()[0]);

		// -1 means flat shaded and -2 means hidden; the renderer acts on both, so they have to
		// survive the copy rather than being normalised away
		assertEquals(-1, model.getFaceColors3()[0]);
	}

	@Test
	public void testDoesNotAliasTheSourceArrays()
	{
		Source src = source(3, 1);
		InjectedModel model = new InjectedModel();
		model.copyFrom(src);

		assertNotSame("aliasing the client's shared model is a use-after-free waiting to happen",
			src.getVerticesX(), model.getVerticesX());
		assertNotSame(src.getFaceIndices1(), model.getFaceIndices1());
	}

	/**
	 * A smaller second model must not inherit the first one's counts. The buffers are deliberately
	 * reused and so stay oversized; correctness rests entirely on the counts, which is exactly the
	 * kind of thing that rots silently.
	 */
	@Test
	public void testShrinkingCopyReportsTheNewCounts()
	{
		InjectedModel model = new InjectedModel();
		model.copyFrom(source(64, 32));

		float[] bigBuffer = model.getVerticesX();

		model.copyFrom(source(4, 2));

		assertEquals(4, model.getVerticesCount());
		assertEquals(2, model.getFaceCount());
		assertSame("buffers should be reused rather than reallocated when they already fit",
			bigBuffer, model.getVerticesX());
		assertTrue("a reused buffer stays oversized, which is fine as long as the count is right",
			model.getVerticesX().length >= model.getVerticesCount());
	}

	/**
	 * A count that overruns its own arrays must be believed no further than the arrays go.
	 *
	 * <p>This is the reused-buffer case, which is why it is worth a test rather than a shrug: the
	 * previous model's vertices are still sitting in the buffer past the new one's end, so trusting
	 * the larger count would not read zeroes - it would read the last NPC's geometry and size the
	 * bounding cylinder around it. {@code ModelUploader.uploadSortedModel} buckets faces into an
	 * array of {@code diameter} slots and asserts the index lands inside it, so bounds taken from
	 * the wrong geometry are an {@code AssertionError} in the renderer.
	 */
	@Test
	public void testACountThatOverrunsItsArraysIsClampedToThem()
	{
		InjectedModel model = new InjectedModel();

		Source big = source(64, 32);
		big.y[0] = -400f;                       // Y is negative upward, so this is a tall model
		model.copyFrom(big);
		assertEquals(400, model.getModelHeight());

		// Claims 64 vertices and 32 faces but carries four and two. The buffers still hold the
		// tall model, so a clamp that did not happen would show up as its height coming back.
		Source lying = source(4, 2);
		lying.verticesCount = 64;
		lying.faceCount = 32;
		model.copyFrom(lying);

		assertEquals(4, model.getVerticesCount());
		assertEquals(2, model.getFaceCount());
		assertEquals("bounds must come from the geometry that arrived, not the buffer's tail",
			0, model.getModelHeight());
	}

	/**
	 * A null column is the degenerate form of the same thing - {@code copy} hands back an empty
	 * array for one, so the count has to follow it down to zero rather than describe it.
	 */
	@Test
	public void testANullColumnTakesTheCountToZero()
	{
		InjectedModel model = new InjectedModel();
		model.copyFrom(source(64, 32));

		Source missing = source(8, 4);
		missing.z = null;
		model.copyFrom(missing);

		assertEquals(0, model.getVerticesCount());

		// The faces are intact, so their count stands; only the vertex side collapsed
		assertEquals(4, model.getFaceCount());
	}

	@Test
	public void testBoundsFollowTheGeometry()
	{
		Source src = source(2, 1);
		// 3-4-5 in the XZ plane, so xzRadius is exactly 5; y is negative upward, giving 6 above the
		// origin and 10 below it
		src.x[0] = 3;
		src.z[0] = 4;
		src.y[0] = 10;
		src.y[1] = -6;

		InjectedModel model = new InjectedModel();
		model.copyFrom(src);

		// radius = ceil(sqrt(5^2 + 6^2)) = 8, diameter = radius + ceil(sqrt(5^2 + 10^2)) = 8 + 12
		assertEquals(8, model.getRadius());
		assertEquals(20, model.getDiameter());
		assertEquals("bottomY is the extent below the origin", 10, model.getBottomY());
	}

	/**
	 * Regression guard for the bug that crashed the renderer.
	 *
	 * <p>{@code ModelUploader.uploadSortedModel} buckets each face by {@code radius + meanDepth}
	 * into an array of {@code diameter} slots and asserts the index lands in {@code [0, diameter)}.
	 * Depth runs along the view axis, so a radius derived from X and Z alone sends the index
	 * negative as soon as the camera looks down at something tall - which is what an
	 * {@code AssertionError} inside the GPU plugin turned out to mean.
	 *
	 * <p>A skeleton is roughly this shape: a couple of hundred units tall and a few wide.
	 */
	@Test
	public void testRadiusAccountsForHeightNotJustFootprint()
	{
		Source src = source(4, 1);
		src.x[0] = 2;
		src.z[0] = 2;
		src.y[0] = -240;
		src.y[1] = 40;
		src.x[2] = -2;
		src.z[2] = -2;

		InjectedModel model = new InjectedModel();
		model.copyFrom(src);

		// The footprint is about 3 units across; the model is 240 tall. A radius anywhere near the
		// footprint means the vertical extent was dropped.
		assertTrue("radius must grow with height, not just footprint - got " + model.getRadius(),
			model.getRadius() >= 240);
		assertTrue("diameter has to leave room beyond radius for the extent below the origin",
			model.getDiameter() > model.getRadius());
	}

	@Test
	public void testNullArraysStayNull()
	{
		InjectedModel model = new InjectedModel();
		model.copyFrom(source(3, 1));

		// A null transparency array is what puts a model on the opaque upload path, so replacing it
		// with an empty array would silently move every injected model onto the sorted path
		assertNull(model.getFaceTransparencies());
		assertNull(model.getFaceTextures());
		assertNull(model.getFaceRenderPriorities());
	}

	/** Minimal stand-in for a posed client model. */
	private static final class Source extends InjectedModel
	{
		private int verticesCount;
		private int faceCount;
		private float[] x;
		private float[] y;
		private float[] z;
		private int[] i1;
		private int[] i2;
		private int[] i3;
		private int[] c1;
		private int[] c2;
		private int[] c3;

		@Override
		public int getVerticesCount()
		{
			return verticesCount;
		}

		@Override
		public float[] getVerticesX()
		{
			return x;
		}

		@Override
		public float[] getVerticesY()
		{
			return y;
		}

		@Override
		public float[] getVerticesZ()
		{
			return z;
		}

		@Override
		public int getFaceCount()
		{
			return faceCount;
		}

		@Override
		public int[] getFaceIndices1()
		{
			return i1;
		}

		@Override
		public int[] getFaceIndices2()
		{
			return i2;
		}

		@Override
		public int[] getFaceIndices3()
		{
			return i3;
		}

		@Override
		public int[] getFaceColors1()
		{
			return c1;
		}

		@Override
		public int[] getFaceColors2()
		{
			return c2;
		}

		@Override
		public int[] getFaceColors3()
		{
			return c3;
		}
	}
}
