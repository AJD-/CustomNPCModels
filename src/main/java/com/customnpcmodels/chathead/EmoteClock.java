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

/**
 * Which frame of an emote shows, given how long it has played. The dialogue head widget does not say
 * which frame the client is on, so a drawn head keeps its own time, from the frame lengths the
 * client plays the emote with.
 */
public final class EmoteClock
{
	private EmoteClock()
	{
	}

	/**
	 * @param frameLengths  each frame's length in client cycles, as the client's animation gives them
	 * @param elapsedCycles client cycles since the emote started
	 * @return the frame, looping, or -1 when there are no frames
	 */
	public static int frameAt(int[] frameLengths, long elapsedCycles)
	{
		if (frameLengths == null || frameLengths.length == 0)
		{
			return -1;
		}
		long total = 0;
		for (int length : frameLengths)
		{
			total += Math.max(length, 1);
		}
		long cycle = Math.max(elapsedCycles, 0) % total;
		for (int frame = 0; frame < frameLengths.length; frame++)
		{
			cycle -= Math.max(frameLengths[frame], 1);
			if (cycle < 0)
			{
				return frame;
			}
		}
		return frameLengths.length - 1;
	}
}
