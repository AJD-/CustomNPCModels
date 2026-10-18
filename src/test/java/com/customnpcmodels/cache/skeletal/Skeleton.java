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
 * The bones of a skeletal (animaya) rig: the section of a framemap (index 1) that follows its
 * legacy transform table. Each bone has a parent and, per bind pose, a matrix relative to that
 * parent, which a skeletal animation's curves override channel by channel.
 * <p>
 * Ported from rs-map-viewer's {@code Dat2SeqBase}, {@code SkeletalBase} and {@code SkeletalBone}
 * (https://github.com/dennisdev/rs-map-viewer, BSD-2-Clause).
 */
public final class Skeleton
{
	@Getter
	private final int id;

	/** The legacy transform table's length, which alpha curves are indexed against. */
	@Getter
	private final int legacyCount;

	private final Bone[] bones;

	private Skeleton(int id, int legacyCount, Bone[] bones)
	{
		this.id = id;
		this.legacyCount = legacyCount;
		this.bones = bones;
	}

	/** Decodes a framemap, or returns null when it carries no bones: a legacy rig only. */
	public static Skeleton decode(int id, byte[] data)
	{
		InputStream in = new InputStream(data);
		int count = in.readUnsignedByte();
		in.skip(count);                           // types
		int labels = 0;
		for (int i = 0; i < count; i++)
		{
			labels += in.readUnsignedByte();
		}
		in.skip(labels);

		if (in.remaining() <= 0)
		{
			return null;
		}
		int boneCount = in.readUnsignedShort();
		if (boneCount == 0)
		{
			return null;
		}
		int poseCount = in.readUnsignedByte();
		Bone[] bones = new Bone[boneCount];
		for (int i = 0; i < boneCount; i++)
		{
			bones[i] = new Bone(in, poseCount);
		}
		if (in.remaining() != 0)
		{
			throw new IllegalArgumentException("Skeleton " + id + " has " + in.remaining() + " bytes past its bones");
		}
		for (Bone bone : bones)
		{
			if (bone.parentId >= boneCount)
			{
				throw new IllegalArgumentException("Skeleton " + id + " names parent " + bone.parentId
					+ " of " + boneCount + " bones");
			}
		}
		return new Skeleton(id, count, bones);
	}

	public int getBoneCount()
	{
		return bones.length;
	}

	public int getPoseCount()
	{
		return bones.length == 0 ? 0 : bones[0].local.length;
	}

	/** A bone's parent, or -1 for a root. */
	public int getParent(int bone)
	{
		return bones[bone].parentId;
	}

	/** A bone's matrix relative to its parent in a bind pose. */
	public double[] getLocal(int bone, int pose)
	{
		return bones[bone].local[pose].clone();
	}

	/** A bone's matrix in model space in a bind pose: its parents' locals composed down to it. */
	public double[] getModel(int bone, int pose)
	{
		Bone b = bones[bone];
		if (b.model[pose] == null)
		{
			b.model[pose] = b.parentId < 0
				? b.local[pose].clone()
				: Matrices.multiply(getModel(b.parentId, pose), b.local[pose]);
		}
		return b.model[pose];
	}

	double[] getRotation(int bone, int pose)
	{
		return bones[bone].rotation[pose];
	}

	double[] getTranslation(int bone, int pose)
	{
		return bones[bone].translation[pose];
	}

	double[] getScaling(int bone, int pose)
	{
		return bones[bone].scaling[pose];
	}

	private static final class Bone
	{
		final int parentId;
		final double[][] local;
		final double[][] model;
		final double[][] rotation;
		final double[][] translation;
		final double[][] scaling;

		Bone(InputStream in, int poseCount)
		{
			parentId = in.readShort();
			local = new double[poseCount][];
			model = new double[poseCount][];
			rotation = new double[poseCount][];
			translation = new double[poseCount][];
			scaling = new double[poseCount][];
			for (int pose = 0; pose < poseCount; pose++)
			{
				double[] m = new double[16];
				for (int i = 0; i < 16; i++)
				{
					m[i] = Float.intBitsToFloat(in.readInt());
				}
				// Three more floats per pose that nothing reads
				in.skip(12);
				local[pose] = m;

				// The channels a curve-less bone keeps: the rotation is read off the inverse
				rotation[pose] = rotationOf(Matrices.invert(m));
				translation[pose] = new double[]{m[12], m[13], m[14]};
				scaling[pose] = Matrices.getScaling(m);
			}
		}

		private static double[] rotationOf(double[] m)
		{
			double[] out = new double[3];
			out[0] = -Math.asin(Math.max(-1, Math.min(1, m[6])));
			if (Math.abs(Math.cos(out[0])) > 0.005)
			{
				out[1] = Math.atan2(m[2], m[10]);
				out[2] = Math.atan2(m[4], m[5]);
			}
			else
			{
				double sinY = m[1];
				double cosY = m[0];
				out[1] = m[6] < 0 ? Math.atan2(sinY, cosY) : -Math.atan2(sinY, cosY);
			}
			return out;
		}
	}
}
