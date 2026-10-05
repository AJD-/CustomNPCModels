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
 * How a live sequence plays: how many frames it has and how long each is held.
 * <p>
 * An authored clip stands in for a live sequence the client keeps playing, and the client hands
 * over that sequence's frame index. So the clip has to have exactly this many frames, and sampling
 * the authored animation at these frame start times is what lines its poses up with the client's
 * playback.
 */
final class SequenceTiming
{
	/** A client cycle, the unit frame lengths are declared in. */
	static final double SECONDS_PER_CYCLE = 0.02;

	final int sequenceId;
	final int[] frameLengths;

	SequenceTiming(int sequenceId, int[] frameLengths)
	{
		this.sequenceId = sequenceId;
		this.frameLengths = frameLengths;
	}

	int frameCount()
	{
		return frameLengths.length;
	}

	/**
	 * The cycle frame {@code frame} starts on, counted from the start of the sequence. Each frame is
	 * held for its length, and at least one cycle.
	 */
	long startCycle(int frame)
	{
		long cycles = 0;
		for (int i = 0; i < frame; i++)
		{
			cycles += Math.max(frameLengths[i], 1);
		}
		return cycles;
	}

	/** How long one pass of the sequence takes, in client cycles. */
	long cycles()
	{
		return startCycle(frameLengths.length);
	}

	/** When frame {@code frame} starts, in seconds from the start of the sequence. */
	double startTime(int frame)
	{
		return startCycle(frame) * SECONDS_PER_CYCLE;
	}

	double duration()
	{
		return cycles() * SECONDS_PER_CYCLE;
	}
}
