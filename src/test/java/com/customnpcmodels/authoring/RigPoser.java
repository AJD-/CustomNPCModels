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

/**
 * Replays a clip frame as one affine transform per vertex group, rather than as moved vertices.
 * <p>
 * The same ops, in the same order, with the same pivot rule and trig tables as {@code Skinner} -
 * but each op is folded into the matrices of the groups it names instead of being applied to their
 * vertices. That turns "where did every vertex go" into "how did every group move", which is what a
 * glTF joint animates, and it does so exactly: no fitting, and no trouble with a group too small to
 * fit a rotation to. Applying a group's matrix to its rest vertices reproduces the skinner's pose,
 * which the tests check.
 * <p>
 * A scale op makes a group's matrix non-rigid. That is reported by the writer rather than
 * handled here.
 */
final class RigPoser
{
	private static final int TYPE_PIVOT = 0;
	private static final int TYPE_TRANSLATE = 1;
	private static final int TYPE_ROTATE = 2;
	private static final int TYPE_SCALE = 3;

	private RigPoser()
	{
	}

	/**
	 * Per vertex group, the transform from the rest pose to this frame, in engine units. Groups the
	 * frame does not touch come back as the identity.
	 */
	static double[][] pose(Mesh mesh, Rig rig, Clip clip, int frame, int groupCount)
	{
		double[][] groups = new double[groupCount][];
		for (int g = 0; g < groupCount; g++)
		{
			groups[g] = Mat4.identity();
		}

		double pivotX = 0;
		double pivotY = 0;
		double pivotZ = 0;

		for (int op = 0; op < clip.getOpCount(frame); op++)
		{
			int transform = clip.getTransform(frame, op);
			if (transform < 0 || transform >= rig.getTransformCount())
			{
				continue;
			}

			int[] named = rig.getGroups(transform);
			if (named == null)
			{
				continue;
			}

			int dx = clip.getDx(frame, op);
			int dy = clip.getDy(frame, op);
			int dz = clip.getDz(frame, op);

			switch (rig.getType(transform))
			{
				case TYPE_PIVOT:
				{
					double sumX = 0;
					double sumY = 0;
					double sumZ = 0;
					int counted = 0;
					for (int group : named)
					{
						for (int vertex : mesh.getVertexGroup(group))
						{
							double[] p = Mat4.transformPoint(groups[group],
								mesh.getVerticesX()[vertex], mesh.getVerticesY()[vertex], mesh.getVerticesZ()[vertex]);
							sumX += p[0];
							sumY += p[1];
							sumZ += p[2];
							counted++;
						}
					}
					pivotX = dx + (counted > 0 ? sumX / counted : 0);
					pivotY = dy + (counted > 0 ? sumY / counted : 0);
					pivotZ = dz + (counted > 0 ? sumZ / counted : 0);
					break;
				}
				case TYPE_TRANSLATE:
					apply(groups, named, Mat4.translation(dx, dy, dz));
					break;
				case TYPE_ROTATE:
					apply(groups, named, Mat4.multiply(
						Mat4.translation(pivotX, pivotY, pivotZ),
						Mat4.rsRotation(dx, dy, dz),
						Mat4.translation(-pivotX, -pivotY, -pivotZ)));
					break;
				case TYPE_SCALE:
					apply(groups, named, Mat4.multiply(
						Mat4.translation(pivotX, pivotY, pivotZ),
						Mat4.scale(dx / 128.0, dy / 128.0, dz / 128.0),
						Mat4.translation(-pivotX, -pivotY, -pivotZ)));
					break;
				default:
					// Alpha, or unknown: the skinner leaves vertices alone, and so does this
					break;
			}
		}

		return groups;
	}

	/**
	 * Folds an op into each named group. A group named twice is moved twice, exactly as the skinner
	 * moves its vertices twice; a group the mesh does not have is moved to no effect.
	 */
	private static void apply(double[][] groups, int[] named, double[] op)
	{
		for (int group : named)
		{
			if (group >= 0 && group < groups.length)
			{
				groups[group] = Mat4.multiply(op, groups[group]);
			}
		}
	}
}
