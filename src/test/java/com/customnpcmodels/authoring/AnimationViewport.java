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
import com.customnpcmodels.inject.NpcAppearance;
import com.customnpcmodels.inject.Skinner;
import java.util.ArrayList;
import java.util.List;

/**
 * Draws an {@link AnimationDocument} posed at one frame of one clip, the way {@code ModelCache.pose}
 * poses it: the plugin's own {@link Skinner}, then the manifest's scale. Lighting stays as it was
 * baked at rest, since the plugin never relights a pose.
 */
final class AnimationViewport extends ModelViewport
{
	private final AnimationDocument document;
	private final Skinner skinner = new Skinner();

	AnimationViewport(AnimationDocument document)
	{
		super(document.mesh(), document.ambient(), document.contrast());
		this.document = document;
		int count = mesh.getVerticesCount();
		x = new float[count];
		y = new float[count];
		z = new float[count];
		relight();
		show(null, 0);
	}

	@Override
	protected short faceColor(int face)
	{
		return document.faceColors()[face];
	}

	/** Shows a frame of a clip, or the rest pose for a null clip. */
	void show(Clip clip, int frame)
	{
		pose(clip, frame, x, y, z);
		repaint();
	}

	/** Frames the view over every frame of the given clips, and the rest pose. */
	void frameClips(List<Clip> clips)
	{
		List<float[][]> poses = new ArrayList<>();
		poses.add(posed(null, 0));
		for (Clip clip : clips)
		{
			for (int frame = 0; frame < clip.getFrameCount(); frame++)
			{
				poses.add(posed(clip, frame));
			}
		}
		frame(poses);
	}

	private float[][] posed(Clip clip, int frame)
	{
		int count = mesh.getVerticesCount();
		float[][] pose = {new float[count], new float[count], new float[count]};
		pose(clip, frame, pose[0], pose[1], pose[2]);
		return pose;
	}

	/** As {@code ModelCache.pose}: the resize comes after the pose, never before. */
	private void pose(Clip clip, int frame, float[] outX, float[] outY, float[] outZ)
	{
		skinner.pose(mesh, document.rig(), clip, frame, outX, outY, outZ);
		NpcAppearance.resize(outX, outY, outZ, mesh.getVerticesCount(), document.scaleXZ(), document.scaleY());
	}
}
