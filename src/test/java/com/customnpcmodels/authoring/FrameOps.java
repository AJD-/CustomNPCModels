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

import com.customnpcmodels.inject.Rig;
import java.util.Arrays;

/**
 * Turns one frame's per-transform axis masks and values into the op list a {@code Clip} carries.
 * <p>
 * A port of the body of {@code FrameLoader.load} from {@code net.runelite:cache}, reading plain
 * arrays instead of a byte stream: the same mask semantics, the same pivot back-fill - a non-pivot
 * op re-emits the nearest preceding pivot transform with a zero delta, so it turns about its own
 * pivot rather than whichever the last op left behind - and the same default of 128 for a scale
 * axis the mask leaves unset. Authored clips are built through here rather than as hand-rolled op
 * lists, so they take exactly the shape cache clips do by construction.
 */
final class FrameOps
{
	private FrameOps()
	{
	}

	/**
	 * @param types  the rig's transform types
	 * @param masks  per transform: bit 0 x, bit 1 y, bit 2 z; 0 skips the transform
	 * @param values one value per set mask bit, in transform then x, y, z order
	 * @return {transforms, dx, dy, dz}, the frame's op columns
	 */
	static int[][] build(int[] types, int[] masks, int[] values)
	{
		int transformCount = masks.length;

		// The pivot back-fill can emit one extra op per transform, so twice is the ceiling
		int[] transforms = new int[transformCount * 2];
		int[] dx = new int[transformCount * 2];
		int[] dy = new int[transformCount * 2];
		int[] dz = new int[transformCount * 2];

		int cursor = 0;
		int lastI = -1;
		int index = 0;

		for (int i = 0; i < transformCount; i++)
		{
			int mask = masks[i];
			if (mask <= 0)
			{
				continue;
			}

			if (types[i] != 0)
			{
				for (int j = i - 1; j > lastI; j--)
				{
					if (types[j] == 0)
					{
						transforms[index] = j;
						dx[index] = 0;
						dy[index] = 0;
						dz[index] = 0;
						index++;
						break;
					}
				}
			}

			transforms[index] = i;
			int unset = types[i] == Rig.TYPE_SCALE ? Rig.SCALE_UNIT : 0;
			dx[index] = (mask & 1) != 0 ? values[cursor++] : unset;
			dy[index] = (mask & 2) != 0 ? values[cursor++] : unset;
			dz[index] = (mask & 4) != 0 ? values[cursor++] : unset;

			lastI = i;
			index++;
		}

		if (cursor != values.length)
		{
			throw new IllegalArgumentException("The masks consume " + cursor + " values but "
				+ values.length + " were given");
		}

		return new int[][]{
			Arrays.copyOf(transforms, index),
			Arrays.copyOf(dx, index),
			Arrays.copyOf(dy, index),
			Arrays.copyOf(dz, index)};
	}
}
