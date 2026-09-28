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

/**
 * Which frame of a live sequence the client shows after a number of client cycles.
 * <p>
 * The plugin only ever receives a frame index, so a clip plays in steps: frame {@code i} is held
 * for {@code frameLengths[i]} cycles - at least one - with nothing in between. A loop restarts at
 * frame 0. What the client does after the last frame of an action (its {@code frameStep} and
 * {@code maxLoops}) decides whether it repeats, not what the frames look like, so it is not modeled.
 */
final class Playback
{
	private Playback()
	{
	}

	/** How long one pass of the sequence takes, in client cycles. */
	static long cycles(SequenceTiming timing)
	{
		return startCycle(timing, timing.frameCount());
	}

	/** The cycle frame {@code frame} starts on, counted from the start of the sequence. */
	static long startCycle(SequenceTiming timing, int frame)
	{
		long cycles = 0;
		for (int i = 0; i < frame; i++)
		{
			cycles += Math.max(timing.frameLengths[i], 1);
		}
		return cycles;
	}

	/**
	 * @param elapsedCycles client cycles since the sequence started, 0 or more
	 * @param loop          wrap back to frame 0 after the last frame, rather than hold it
	 */
	static int frameAt(SequenceTiming timing, long elapsedCycles, boolean loop)
	{
		long total = cycles(timing);
		long cycle = loop ? elapsedCycles % total : Math.min(elapsedCycles, total - 1);
		for (int frame = 0; frame < timing.frameCount(); frame++)
		{
			cycle -= Math.max(timing.frameLengths[frame], 1);
			if (cycle < 0)
			{
				return frame;
			}
		}
		return timing.frameCount() - 1;
	}
}
