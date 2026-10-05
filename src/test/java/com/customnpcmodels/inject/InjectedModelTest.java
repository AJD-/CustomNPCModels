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
import static org.junit.Assert.assertTrue;
import org.junit.Test;

/** Covers binding a mesh for the renderer, and the bounds the renderer reads off the result. */
public class InjectedModelTest
{
	/** A one-face mesh of these vertices, with nothing optional set. */
	private static Mesh mesh(float[] x, float[] y, float[] z)
	{
		return new TestMesh()
			.id(1)
			.vx(x)
			.vy(y)
			.vz(z)
			.i1(new int[]{0})
			.i2(new int[]{1})
			.i3(new int[]{2})
			.colors(new short[]{0})
			.groups(new int[0][])
			.build();
	}

	private static InjectedModel bind(Mesh mesh)
	{
		int[] lit = new int[mesh.getFaceCount()];
		InjectedModel model = new InjectedModel();
		model.bind(mesh, lit, lit.clone(), lit.clone());
		return model;
	}

	@Test
	public void testBoundsFollowTheGeometry()
	{
		// 3-4-5 in the XZ plane, so xzRadius is exactly 5; y is negative upward, giving 6 above the
		// origin and 10 below it
		InjectedModel model = bind(mesh(new float[]{3, 0, 0}, new float[]{10, -6, 0}, new float[]{4, 0, 0}));

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
		InjectedModel model = bind(mesh(new float[]{2, 0, -2, 0}, new float[]{-240, 40, 0, 0},
			new float[]{2, 0, -2, 0}));

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
		InjectedModel model = bind(mesh(new float[3], new float[3], new float[3]));

		// A null transparency array is what puts a model on the opaque upload path, so replacing it
		// with an empty array would silently move every injected model onto the sorted path
		assertNull(model.getFaceTransparencies());
		assertNull(model.getFaceTextures());
		assertNull(model.getFaceRenderPriorities());
	}

	@Test
	public void testVerticesAreCopiedSoPosingLeavesTheMeshAlone()
	{
		Mesh mesh = mesh(new float[]{1, 2, 3}, new float[3], new float[3]);
		InjectedModel model = bind(mesh);

		// The skinner writes the pose straight into these buffers every frame
		assertNotSame(mesh.getVerticesX(), model.getVerticesX());
		model.getVerticesX()[0] = 99;
		assertEquals(1f, mesh.getVerticesX()[0], 0f);
	}
}
