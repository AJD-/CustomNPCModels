/*
 * Copyright (c) 2026, AJD
 * Copyright (c) 2022-2023, dennisdev
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
package com.customnpcmodels.cache.skeletal;

import java.util.Arrays;

/**
 * Poses a skeletal animation's bones at a frame, and skins a model's vertices to them.
 * <p>
 * The frame is the one the client reports for the sequence ({@code Actor#getAnimationFrame}), which
 * counts client cycles from 0 up to the sequence's {@code animMayaEnd - animMayaStart}. The client
 * poses frame {@code f} with the curves at tick {@code f + 1}: measured against its own posed models,
 * every frame of Yama's stand and walk matches that tick, and the rest of the frames captured
 * either do or, at a few key changes, match tick {@code f} instead. The matrices work in the
 * engine's model space with y and z negated, so skinning negates them going in and coming back.
 * <p>
 * Ported from rs-map-viewer's {@code SkeletalBone} and {@code Model.transformSkeletal}
 * (https://github.com/dennisdev/rs-map-viewer, BSD-2-Clause). Unlike there, the vertices are not
 * rounded: the client's own posed models carry fractions.
 */
public final class SkeletalPoser
{
	private SkeletalPoser()
	{
	}

	/** Per bone, its matrix relative to its parent at a frame. */
	public static double[][] locals(Skeleton skeleton, SkeletalAnimation animation, int frame)
	{
		int tick = frame + 1;
		double[][] locals = new double[skeleton.getBoneCount()][];
		for (int bone = 0; bone < locals.length; bone++)
		{
			locals[bone] = animation.getLocal(skeleton, bone, tick);
		}
		return locals;
	}

	/**
	 * Per bone, its matrix at a frame in model space: its animated matrix composed down from its
	 * parents.
	 */
	public static double[][] animatedModel(Skeleton skeleton, SkeletalAnimation animation, int frame)
	{
		int tick = frame + 1;
		double[][] model = new double[skeleton.getBoneCount()][];
		for (int bone = 0; bone < model.length; bone++)
		{
			animatedModel(skeleton, animation, tick, bone, model);
		}
		return model;
	}

	private static double[] animatedModel(Skeleton skeleton, SkeletalAnimation animation, int tick, int bone,
		double[][] model)
	{
		if (model[bone] == null)
		{
			double[] local = animation.getLocal(skeleton, bone, tick);
			int parent = skeleton.getParent(bone);
			model[bone] = parent < 0 ? local
				: Matrices.multiply(animatedModel(skeleton, animation, tick, parent, model), local);
		}
		return model[bone];
	}

	/**
	 * Per bone, the matrix taking a vertex from the bind pose to a frame: its animated model matrix
	 * after the inverse of its bind-pose model matrix.
	 */
	public static double[][] finalMatrices(Skeleton skeleton, SkeletalAnimation animation, int frame)
	{
		double[][] model = animatedModel(skeleton, animation, frame);
		double[][] result = new double[model.length][];
		for (int bone = 0; bone < model.length; bone++)
		{
			result[bone] = Matrices.multiply(model[bone],
				Matrices.invert(skeleton.getModel(bone, animation.getPoseId())));
		}
		return result;
	}

	/**
	 * Skins vertices: each is moved by the weighted sum of its bones' final matrices, weights out of
	 * 255. A vertex with no bones stays where it is. Returns {x, y, z} in engine units.
	 */
	public static float[][] skin(int[] x, int[] y, int[] z, int[][] groups, int[][] scales, double[][] finals)
	{
		int count = x.length;
		float[][] out = new float[3][count];
		double[] m = new double[16];
		for (int v = 0; v < count; v++)
		{
			int[] bones = groups == null ? null : groups[v];
			if (bones == null || bones.length == 0)
			{
				out[0][v] = x[v];
				out[1][v] = y[v];
				out[2][v] = z[v];
				continue;
			}

			Arrays.fill(m, 0);
			for (int i = 0; i < bones.length; i++)
			{
				if (bones[i] < 0 || bones[i] >= finals.length)
				{
					continue;
				}
				double weight = scales[v][i] / 255.0;
				double[] f = finals[bones[i]];
				for (int k = 0; k < 16; k++)
				{
					m[k] += weight * f[k];
				}
			}

			double vx = x[v];
			double vy = -y[v];
			double vz = -z[v];
			out[0][v] = (float) (m[0] * vx + m[4] * vy + m[8] * vz + m[12]);
			out[1][v] = (float) -(m[1] * vx + m[5] * vy + m[9] * vz + m[13]);
			out[2][v] = (float) -(m[2] * vx + m[6] * vy + m[10] * vz + m[14]);
		}
		return out;
	}
}
