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

import lombok.Getter;
import net.runelite.cache.io.InputStream;

/**
 * One skeletal (animaya) animation, as a sequence's {@code animMayaID} names it: index 22, group
 * {@code id >> 16}, file {@code id & 0xFFFF}. Its curves animate its skeleton's bones, nine
 * channels each - rotation, translation and scale about x, y and z - and anything a curve doesn't
 * animate keeps the bone's bind pose.
 * <p>
 * Ported from rs-map-viewer's {@code SkeletalSeq} (https://github.com/dennisdev/rs-map-viewer,
 * BSD-2-Clause). Alpha curves (on the legacy transforms) are decoded past but not kept: nothing here
 * animates face alpha.
 */
public final class SkeletalAnimation
{
	private static final int TRANSFORM_BONE = 1;

	/** Per curve type, the bone channel it drives: rotation x, y, z, then translation, then scale. */
	private static final int[] CHANNELS = {-1, 0, 1, 2, 3, 4, 5, 6, 7, 8, 0, 1, 2, 3, 4, 5, 0};

	@Getter
	private final int id;

	@Getter
	private final int skeletonId;

	/** The bind pose of the skeleton the curves are relative to. */
	@Getter
	private final int poseId;

	/** Per bone, per channel, the curve driving it, or null. */
	private final Curve[][] boneCurves;

	private SkeletalAnimation(int id, int skeletonId, int poseId, Curve[][] boneCurves)
	{
		this.id = id;
		this.skeletonId = skeletonId;
		this.poseId = poseId;
		this.boneCurves = boneCurves;
	}

	/** The id of the skeleton an animation's data names, read without decoding the rest. */
	public static int skeletonOf(byte[] data)
	{
		return (data[1] & 0xFF) << 8 | data[2] & 0xFF;
	}

	public static SkeletalAnimation decode(int id, byte[] data, Skeleton skeleton)
	{
		InputStream in = new InputStream(data);
		in.readUnsignedByte();                 // version
		int skeletonId = in.readUnsignedShort();
		if (skeletonId != skeleton.getId())
		{
			throw new IllegalArgumentException("Animation " + id + " is for skeleton " + skeletonId
				+ ", not " + skeleton.getId());
		}
		in.readUnsignedShort();
		in.readUnsignedShort();
		int poseId = in.readUnsignedByte();
		int curveCount = in.readUnsignedShort();

		Curve[][] boneCurves = new Curve[skeleton.getBoneCount()][];
		for (int i = 0; i < curveCount; i++)
		{
			int transformType = in.readUnsignedByte();
			int target = readSignedSmart(in);
			int curveType = in.readUnsignedByte();
			Curve curve = Curve.decode(in);

			if (transformType != TRANSFORM_BONE)
			{
				continue;
			}
			if (target < 0 || target >= boneCurves.length)
			{
				throw new IllegalArgumentException("Animation " + id + " animates bone " + target + " of "
					+ boneCurves.length);
			}
			if (boneCurves[target] == null)
			{
				boneCurves[target] = new Curve[9];
			}
			int channel = CHANNELS[curveType < 0 || curveType > 16 ? 0 : curveType];
			if (channel >= 0)
			{
				boneCurves[target][channel] = curve;
			}
		}
		if (in.remaining() != 0)
		{
			throw new IllegalArgumentException("Animation " + id + " has " + in.remaining() + " bytes past its curves");
		}
		return new SkeletalAnimation(id, skeletonId, poseId, boneCurves);
	}

	/** One byte as {@code value + 64} when its top bit is clear, else two as {@code value + 0xC000}. */
	private static int readSignedSmart(InputStream in)
	{
		return in.peek() >= 0 ? in.readUnsignedByte() - 64 : in.readUnsignedShort() - 0xC000;
	}

	/**
	 * A bone's matrix relative to its parent at a tick: its rotation (applied Z, then X, then Y as
	 * a quaternion), then its scale, then its translation, each channel from its curve when it has
	 * one and from the bind pose when not.
	 */
	double[] getLocal(Skeleton skeleton, int bone, int tick)
	{
		Curve[] curves = boneCurves[bone];
		double[] rotation = channels(curves, 0, skeleton.getRotation(bone, poseId), tick);
		double[] translation = channels(curves, 3, skeleton.getTranslation(bone, poseId), tick);
		double[] scale = channels(curves, 6, skeleton.getScaling(bone, poseId), tick);

		double[] q = Matrices.multiplyQuaternions(Matrices.axisAngle(0, 0, 1, rotation[2]), new double[]{0, 0, 0, 1});
		q = Matrices.multiplyQuaternions(Matrices.axisAngle(1, 0, 0, rotation[0]), q);
		q = Matrices.multiplyQuaternions(Matrices.axisAngle(0, 1, 0, rotation[1]), q);

		double[] m = Matrices.multiply(Matrices.scaling(scale[0], scale[1], scale[2]), Matrices.fromQuaternion(q));
		m[12] = translation[0];
		m[13] = translation[1];
		m[14] = translation[2];
		return m;
	}

	private static double[] channels(Curve[] curves, int first, double[] bind, int tick)
	{
		double[] out = bind.clone();
		if (curves != null)
		{
			for (int axis = 0; axis < 3; axis++)
			{
				if (curves[first + axis] != null)
				{
					out[axis] = curves[first + axis].getValue(tick);
				}
			}
		}
		return out;
	}
}
