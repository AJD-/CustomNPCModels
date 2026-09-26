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
import org.junit.Test;

public class PlaybackTest
{
	// Frames held for 3, 1 and 2 cycles: 6 in all
	private static final SequenceTiming TIMING = new SequenceTiming(1, new int[]{3, 1, 2});

	@Test
	public void eachFrameIsHeldForItsLength()
	{
		int[] expected = {0, 0, 0, 1, 2, 2};
		for (int cycle = 0; cycle < expected.length; cycle++)
		{
			assertEquals("cycle " + cycle, expected[cycle], Playback.frameAt(TIMING, cycle, true));
		}
	}

	@Test
	public void loopingWrapsToTheFirstFrame()
	{
		assertEquals(0, Playback.frameAt(TIMING, 6, true));
		assertEquals(1, Playback.frameAt(TIMING, 9, true));
		assertEquals(2, Playback.frameAt(TIMING, 6 * 100 + 5, true));
	}

	@Test
	public void withoutLoopingTheLastFrameIsHeld()
	{
		assertEquals(2, Playback.frameAt(TIMING, 6, false));
		assertEquals(2, Playback.frameAt(TIMING, 1000, false));
	}

	@Test
	public void aZeroLengthFrameStillShowsForOneCycle()
	{
		SequenceTiming timing = new SequenceTiming(1, new int[]{0, 2});
		assertEquals(0, Playback.frameAt(timing, 0, true));
		assertEquals(1, Playback.frameAt(timing, 1, true));
		assertEquals(1, Playback.frameAt(timing, 2, true));
		assertEquals(0, Playback.frameAt(timing, 3, true));
		assertEquals(3, Playback.cycles(timing));
	}

	@Test
	public void startCycleIsWhereAFrameBegins()
	{
		assertEquals(0, Playback.startCycle(TIMING, 0));
		assertEquals(3, Playback.startCycle(TIMING, 1));
		assertEquals(4, Playback.startCycle(TIMING, 2));
	}
}
