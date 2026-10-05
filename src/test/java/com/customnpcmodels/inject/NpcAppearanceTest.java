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

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import org.junit.Test;

public class NpcAppearanceTest
{
	@Test
	public void testTheFirstMatchingRecolorWinsAndTheInputIsLeftAlone()
	{
		short[] colors = {1, 2, 3};
		short[] recolored = NpcAppearance.recolor(colors, new short[]{2, 2, 3}, new short[]{20, 99, 30});

		assertArrayEquals(new short[]{1, 20, 30}, recolored);
		assertArrayEquals("the bundle's colors are never changed", new short[]{1, 2, 3}, colors);
	}

	@Test
	public void testNoRecolorsIsACopy()
	{
		short[] colors = {1, 2};
		assertArrayEquals(colors, NpcAppearance.recolor(colors, null, null));
	}

	@Test
	public void testResizeScalesTheFootprintAndHeightSeparately()
	{
		float[] x = {10, 1};
		float[] y = {10, 1};
		float[] z = {10, 1};
		NpcAppearance.resize(x, y, z, 1, NpcAppearance.scale(256), NpcAppearance.scale(64));

		assertEquals(20f, x[0], 0f);
		assertEquals(5f, y[0], 0f);
		assertEquals(20f, z[0], 0f);
		assertEquals("only the first count vertices", 1f, x[1], 0f);
	}
}
