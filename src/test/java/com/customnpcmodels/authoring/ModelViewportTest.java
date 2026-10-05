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

import com.customnpcmodels.inject.Mesh;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import com.customnpcmodels.inject.TestMesh;
import org.junit.Test;

public class ModelViewportTest
{
	private static final int SIZE = 101;
	private static final int CENTER = SIZE / 2;
	private static final int FRONT = 0;
	private static final int BACK = 1;

	/**
	 * Two triangles covering the view's center, the front one nearer the camera once the view is
	 * turned to Blender's front view, which looks down -Z.
	 */
	private static ModelViewport viewport(int frontTransparency)
	{
		return viewport(new TestMesh()
			.i1(new int[]{0, 3})
			.i2(new int[]{1, 4})
			.i3(new int[]{2, 5})
			.colors(new short[]{(short) (10 << 10 | 7 << 7 | 64), (short) (40 << 10 | 7 << 7 | 64)})
			.transparencies(new byte[]{(byte) frontTransparency, 0}));
	}

	/** The same vertices, so the same framing, with no front face at all. */
	private static ModelViewport backOnly()
	{
		return viewport(new TestMesh()
			.i1(new int[]{3})
			.i2(new int[]{4})
			.i3(new int[]{5})
			.colors(new short[]{(short) (40 << 10 | 7 << 7 | 64)}));
	}

	private static ModelViewport viewport(TestMesh faces)
	{
		Mesh mesh = faces
			.vx(new float[]{-100, 100, 0, -100, 100, 0})
			.vy(new float[]{100, 100, -100, 100, 100, -100})
			.vz(new float[]{10, 10, 10, -10, -10, -10})
			.groups(null)
			.build();
		ModelViewport viewport = new ModelViewport(mesh, 0, 0)
		{
			@Override
			protected short faceColor(int face)
			{
				return mesh.getFaceColors()[face];
			}
		};
		viewport.relight();
		viewport.frame();
		viewport.orbit(-Math.PI * 3 / 4, -0.35);
		return viewport;
	}

	private static int centerPixel(ModelViewport viewport)
	{
		return viewport.render(SIZE, SIZE).getRGB(CENTER, CENTER) & 0xFFFFFF;
	}

	@Test
	public void anOpaqueFaceHidesWhatIsBehindIt()
	{
		ModelViewport viewport = viewport(0);
		viewport.render(SIZE, SIZE);
		assertEquals(FRONT, viewport.faceAt(CENTER, CENTER));
	}

	@Test
	public void aFullyTransparentFaceIsNotDrawnOrPicked()
	{
		ModelViewport viewport = viewport(255);
		int pixel = centerPixel(viewport);
		assertEquals(BACK, viewport.faceAt(CENTER, CENTER));
		assertEquals(centerPixel(backOnly()), pixel);
	}

	@Test
	public void aPartlyTransparentFaceIsBlendedOverWhatIsBehindIt()
	{
		int opaque = centerPixel(viewport(0));
		int hidden = centerPixel(backOnly());
		int blended = centerPixel(viewport(128));

		assertNotEquals(opaque, blended);
		assertNotEquals(hidden, blended);
		for (int shift = 0; shift <= 16; shift += 8)
		{
			int a = opaque >> shift & 255;
			int b = hidden >> shift & 255;
			int c = blended >> shift & 255;
			assertTrue("channel " + shift + ": " + c + " not between " + a + " and " + b,
				c >= Math.min(a, b) - 1 && c <= Math.max(a, b) + 1);
		}
	}
}
