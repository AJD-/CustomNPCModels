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
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import net.runelite.api.ModelData;
import org.junit.Test;

/**
 * Structural cover for the lighting port. Exact agreement with the client is not something a unit
 * test can assert - that comparison happens against a real lit model in
 * {@code ModelCache.verifyLighting} - so this pins the parts that are easy to break silently:
 * the sentinels, and the luminance clamp.
 */
public class LighterTest
{
	/** Two triangles sharing an edge, lying in the XZ plane. */
	private static final float[] X = {0, 100, 100, 0};
	private static final float[] Y = {0, 0, 0, 0};
	private static final float[] Z = {0, 0, 100, 100};
	private static final int[] I1 = {0, 0};
	private static final int[] I2 = {1, 2};
	private static final int[] I3 = {2, 3};

	private static int[][] light(short[] faceColors, byte[] renderTypes, short[] textures)
	{
		int faceCount = I1.length;
		int[] c1 = new int[faceCount];
		int[] c2 = new int[faceCount];
		int[] c3 = new int[faceCount];

		Lighter.light(
			X.length, X, Y, Z,
			faceCount, I1, I2, I3,
			faceColors, renderTypes, textures,
			ModelData.DEFAULT_AMBIENT, ModelData.DEFAULT_CONTRAST,
			ModelData.DEFAULT_X, ModelData.DEFAULT_Y, ModelData.DEFAULT_Z,
			c1, c2, c3);

		return new int[][]{c1, c2, c3};
	}

	@Test
	public void testGouraudFacesGetPerCornerColors()
	{
		short color = (short) 0x3A05; // arbitrary packed HSL with a mid luminance
		int[][] out = light(new short[]{color, color}, null, null);

		for (int face = 0; face < I1.length; face++)
		{
			// A gouraud face must not land on either sentinel, or the renderer will flatten or
			// skip it
			assertNotEquals("gouraud face must not read as flat shaded", -1, out[2][face]);
			assertNotEquals("gouraud face must not read as hidden", -2, out[2][face]);
		}
	}

	@Test
	public void testFlatFacesSetTheFlatSentinel()
	{
		int[][] out = light(new short[]{100, 100}, new byte[]{1, 1}, null);

		assertEquals(-1, out[2][0]);
		assertEquals(-1, out[2][1]);
	}

	@Test
	public void testUnknownRenderTypeHidesTheFace()
	{
		// Type 2 is the textured-with-its-own-shading case this port does not handle; it must fall
		// through to hidden rather than being drawn with a bogus color
		int[][] out = light(new short[]{100, 100}, new byte[]{2, 2}, null);

		assertEquals(-2, out[2][0]);
		assertEquals(-2, out[2][1]);
	}

	@Test
	public void testUnshadedFacesUseTheFixedLevel()
	{
		int[][] out = light(new short[]{100, 100}, new byte[]{3, 3}, null);

		assertEquals(128, out[0][0]);
		assertEquals(-1, out[2][0]);
	}

	/**
	 * Luminance occupies the low 7 bits and is clamped to 2..126 - 0 and 127 are reserved, and
	 * letting one through changes the color rather than the brightness.
	 */
	@Test
	public void testLuminanceStaysInsideTheReservedBounds()
	{
		// Maximum luminance in the low 7 bits, which without a clamp would light past 126
		short bright = (short) 0x7F7F;
		int[][] out = light(new short[]{bright, bright}, null, null);

		for (int face = 0; face < I1.length; face++)
		{
			for (int corner = 0; corner < 3; corner++)
			{
				int luminance = out[corner][face] & 127;
				assertTrue("luminance " + luminance + " escaped the clamp",
					luminance >= 2 && luminance <= 126);
			}
		}
	}

	@Test
	public void testHueAndSaturationSurviveLighting()
	{
		short color = (short) 0x3A05;
		int[][] out = light(new short[]{color, color}, null, null);

		assertEquals("lighting must replace luminance only",
			color & 0xFF80, out[0][0] & 0xFF80);
	}
}
