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
package com.customnpcmodels.inject;

import net.runelite.api.Perspective;

/**
 * Poses a {@link Mesh} for one animation frame.
 * <p>
 * Exists because {@code Client#applyTransformations} casts to the client's own concrete model
 * class, so injected geometry can never be animated by the client. That is the single constraint
 * that makes a skinner necessary - and, usefully, it is also what makes a custom rig format
 * possible at all.
 * <p>
 * A port of {@code ModelDefinition.animate} from the cache library, with one deliberate change:
 * vertices are floats here, as they are in the modern client, so the fixed-point shifts of the
 * original become float divisions.
 */
public final class Skinner
{
	/**
	 * Rotations are stored in 8 bits and scaled into the 2048-entry trig table, so the smallest
	 * step an animation can express is 1/256 of a turn. Anything authored against this rig has to
	 * be quantized to that.
	 */
	private static final int ROTATION_SCALE = 8;

	/** {@link Perspective#SINE} and {@code COSINE} are fixed point with 16 fractional bits. */
	private static final float TRIG_SCALE = 65536f;

	/** Pivot for rotate and scale ops, set by type 0 and carried across the ops that follow it. */
	private float pivotX;
	private float pivotY;
	private float pivotZ;

	/**
	 * Writes the mesh posed at one frame into the given buffers.
	 *
	 * <p>The buffers must be at least as long as the mesh's vertex count; they are overwritten from
	 * the rest pose first, so the caller need not clear them and posing never accumulates across
	 * frames.
	 *
	 * @return false when the frame cannot be applied, leaving the buffers holding the rest pose
	 */
	public boolean pose(Mesh mesh, Rig rig, Clip clip, int frame,
		float[] outX, float[] outY, float[] outZ)
	{
		int count = mesh.getVerticesCount();
		System.arraycopy(mesh.getVerticesX(), 0, outX, 0, count);
		System.arraycopy(mesh.getVerticesY(), 0, outY, 0, count);
		System.arraycopy(mesh.getVerticesZ(), 0, outZ, 0, count);

		if (rig == null || clip == null || !clip.hasFrame(frame) || !mesh.isRigged())
		{
			return false;
		}

		apply(mesh, rig, clip, frame, outX, outY, outZ);
		return true;
	}

	private void apply(Mesh mesh, Rig rig, Clip clip, int frame,
		float[] outX, float[] outY, float[] outZ)
	{
		pivotX = 0f;
		pivotY = 0f;
		pivotZ = 0f;

		int ops = clip.getOpCount(frame);
		for (int op = 0; op < ops; op++)
		{
			int transform = clip.getTransform(frame, op);
			if (transform < 0 || transform >= rig.getTransformCount())
			{
				continue;
			}

			int type = rig.getType(transform);
			int[] groups = rig.getGroups(transform);
			if (groups == null)
			{
				continue;
			}

			int dx = clip.getDx(frame, op);
			int dy = clip.getDy(frame, op);
			int dz = clip.getDz(frame, op);

			switch (type)
			{
				case Rig.TYPE_PIVOT:
					setPivot(mesh, groups, dx, dy, dz, outX, outY, outZ);
					break;
				case Rig.TYPE_TRANSLATE:
					translate(mesh, groups, dx, dy, dz, outX, outY, outZ);
					break;
				case Rig.TYPE_ROTATE:
					rotate(mesh, groups, dx, dy, dz, outX, outY, outZ);
					break;
				case Rig.TYPE_SCALE:
					scale(mesh, groups, dx, dy, dz, outX, outY, outZ);
					break;
				default:
					// Type 5 animates face transparency, which this does not carry; anything else is
					// unknown. Skipping leaves the vertices where they are, which is the safe result.
					break;
			}
		}
	}

	/**
	 * Sets the origin that following rotate and scale ops work about, as the mean of the affected
	 * vertices offset by the op's own delta.
	 *
	 * <p>Note it reads the working buffer rather than the rest pose, so a pivot reflects transforms
	 * already applied this frame - which is how a limb ends up rotating about its parent's current
	 * position rather than its resting one.
	 */
	private void setPivot(Mesh mesh, int[] groups, int dx, int dy, int dz,
		float[] outX, float[] outY, float[] outZ)
	{
		float sumX = 0f;
		float sumY = 0f;
		float sumZ = 0f;
		int counted = 0;

		for (int group : groups)
		{
			for (int vertex : mesh.getVertexGroup(group))
			{
				sumX += outX[vertex];
				sumY += outY[vertex];
				sumZ += outZ[vertex];
				counted++;
			}
		}

		if (counted > 0)
		{
			pivotX = dx + sumX / counted;
			pivotY = dy + sumY / counted;
			pivotZ = dz + sumZ / counted;
		}
		else
		{
			pivotX = dx;
			pivotY = dy;
			pivotZ = dz;
		}
	}

	private void translate(Mesh mesh, int[] groups, int dx, int dy, int dz,
		float[] outX, float[] outY, float[] outZ)
	{
		for (int group : groups)
		{
			for (int vertex : mesh.getVertexGroup(group))
			{
				outX[vertex] += dx;
				outY[vertex] += dy;
				outZ[vertex] += dz;
			}
		}
	}

	private void rotate(Mesh mesh, int[] groups, int dx, int dy, int dz,
		float[] outX, float[] outY, float[] outZ)
	{
		int angleX = (dx & 0xFF) * ROTATION_SCALE;
		int angleY = (dy & 0xFF) * ROTATION_SCALE;
		int angleZ = (dz & 0xFF) * ROTATION_SCALE;

		for (int group : groups)
		{
			for (int vertex : mesh.getVertexGroup(group))
			{
				float x = outX[vertex] - pivotX;
				float y = outY[vertex] - pivotY;
				float z = outZ[vertex] - pivotZ;

				// Axis order is load bearing: Z, then X, then Y. Rotations do not commute, so
				// reordering these silently produces a different pose rather than an error.
				if (angleZ != 0)
				{
					float sin = Perspective.SINE[angleZ] / TRIG_SCALE;
					float cos = Perspective.COSINE[angleZ] / TRIG_SCALE;
					float rotated = sin * y + cos * x;
					y = cos * y - sin * x;
					x = rotated;
				}

				if (angleX != 0)
				{
					float sin = Perspective.SINE[angleX] / TRIG_SCALE;
					float cos = Perspective.COSINE[angleX] / TRIG_SCALE;
					float rotated = cos * y - sin * z;
					z = sin * y + cos * z;
					y = rotated;
				}

				if (angleY != 0)
				{
					float sin = Perspective.SINE[angleY] / TRIG_SCALE;
					float cos = Perspective.COSINE[angleY] / TRIG_SCALE;
					float rotated = sin * z + cos * x;
					z = cos * z - sin * x;
					x = rotated;
				}

				outX[vertex] = x + pivotX;
				outY[vertex] = y + pivotY;
				outZ[vertex] = z + pivotZ;
			}
		}
	}

	private void scale(Mesh mesh, int[] groups, int dx, int dy, int dz,
		float[] outX, float[] outY, float[] outZ)
	{
		for (int group : groups)
		{
			for (int vertex : mesh.getVertexGroup(group))
			{
				outX[vertex] = (outX[vertex] - pivotX) * dx / Rig.SCALE_UNIT + pivotX;
				outY[vertex] = (outY[vertex] - pivotY) * dy / Rig.SCALE_UNIT + pivotY;
				outZ[vertex] = (outZ[vertex] - pivotZ) * dz / Rig.SCALE_UNIT + pivotZ;
			}
		}
	}
}
