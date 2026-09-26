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

import com.customnpcmodels.inject.Clip;
import com.customnpcmodels.inject.Mesh;
import com.customnpcmodels.inject.Rig;
import com.customnpcmodels.inject.Skinner;

/**
 * The baked-pose oracle: two assets are posed by the real {@link Skinner} at every frame and their
 * vertices compared, so a mistake in a rig reconstruction cannot cancel out against a mistake in a
 * conversion the way it could when comparing intermediate representations.
 */
final class PoseComparison
{
	private PoseComparison()
	{
	}

	/**
	 * The largest distance any vertex of {@code b} lands from the same-numbered vertex of {@code a},
	 * over every frame of the two clips.
	 *
	 * @throws IllegalArgumentException when the meshes differ in vertex count or the clips in frame count
	 */
	static double worstPoseError(Mesh a, Rig aRig, Clip aClip, Mesh b, Rig bRig, Clip bClip)
	{
		int n = a.getVerticesCount();
		if (b.getVerticesCount() != n)
		{
			throw new IllegalArgumentException("vertex counts differ: " + n + " and " + b.getVerticesCount());
		}
		if (aClip.getFrameCount() != bClip.getFrameCount())
		{
			throw new IllegalArgumentException("frame counts differ: " + aClip.getFrameCount() + " and " + bClip.getFrameCount());
		}

		Skinner skinner = new Skinner();
		float[] ax = new float[n], ay = new float[n], az = new float[n];
		float[] bx = new float[n], by = new float[n], bz = new float[n];
		double worst = 0;
		for (int frame = 0; frame < aClip.getFrameCount(); frame++)
		{
			skinner.pose(a, aRig, aClip, frame, ax, ay, az);
			skinner.pose(b, bRig, bClip, frame, bx, by, bz);
			for (int v = 0; v < n; v++)
			{
				double dx = ax[v] - bx[v];
				double dy = ay[v] - by[v];
				double dz = az[v] - bz[v];
				worst = Math.max(worst, Math.sqrt(dx * dx + dy * dy + dz * dz));
			}
		}
		return worst;
	}
}
