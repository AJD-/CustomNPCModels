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
import org.junit.Test;

public class RsColorTest
{
	private static final int GREY = RsColor.pack(0, 0, 90);

	@Test
	public void testFullBrightnessIsThePlainPalette()
	{
		assertEquals(RsColor.hslToRgb(GREY), RsColor.hslToRgb(GREY, 1.0));
	}

	/** The client's palette raises each channel to its brightness, so a setting below 1 lightens. */
	@Test
	public void testBrightnessRaisesEachChannelAsTheClientsPaletteDoes()
	{
		int plain = RsColor.hslToRgb(GREY);
		int bright = RsColor.hslToRgb(GREY, 0.6);

		for (int shift : new int[]{16, 8, 0})
		{
			int channel = plain >> shift & 255;
			assertEquals((int) (Math.pow(channel / 256.0, 0.6) * 256.0), bright >> shift & 255);
		}
	}
}
