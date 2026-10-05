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
package com.customnpcmodels.chathead;

import static org.junit.Assert.assertEquals;
import org.junit.Test;

public class EmoteClockTest
{
	@Test
	public void testEachFrameHoldsForItsLength()
	{
		int[] lengths = {3, 2};

		assertEquals(0, EmoteClock.frameAt(lengths, 0));
		assertEquals(0, EmoteClock.frameAt(lengths, 2));
		assertEquals(1, EmoteClock.frameAt(lengths, 3));
		assertEquals(1, EmoteClock.frameAt(lengths, 4));
	}

	/** A chathead talks until the page turns, so the emote loops. */
	@Test
	public void testLoops()
	{
		assertEquals(0, EmoteClock.frameAt(new int[]{3, 2}, 5));
		assertEquals(1, EmoteClock.frameAt(new int[]{3, 2}, 8));
	}

	/** A zero length still shows its frame for a cycle, as the client plays it. */
	@Test
	public void testAZeroLengthFrameShowsForOneCycle()
	{
		assertEquals(1, EmoteClock.frameAt(new int[]{1, 0, 1}, 1));
		assertEquals(2, EmoteClock.frameAt(new int[]{1, 0, 1}, 2));
	}

	/** No frames (a skeletal emote, or none loaded): no frame, and the head shows at rest. */
	@Test
	public void testNoFramesIsNoFrame()
	{
		assertEquals(-1, EmoteClock.frameAt(new int[0], 10));
		assertEquals(-1, EmoteClock.frameAt(null, 10));
	}

	@Test
	public void testNegativeElapsedIsTheFirstFrame()
	{
		assertEquals(0, EmoteClock.frameAt(new int[]{3, 2}, -4));
	}
}
