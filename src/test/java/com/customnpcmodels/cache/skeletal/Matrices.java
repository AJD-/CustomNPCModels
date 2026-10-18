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

/**
 * 4x4 matrices as {@code double[16]} in column-major order, the layout the skeletal data is stored
 * in, with {@code multiply(a, b) = a * b} acting on column vectors - the conventions of gl-matrix,
 * which rs-map-viewer's skeletal code, ported here, is written against.
 */
final class Matrices
{
	private Matrices()
	{
	}

	static double[] identity()
	{
		double[] m = new double[16];
		m[0] = m[5] = m[10] = m[15] = 1;
		return m;
	}

	static double[] multiply(double[] a, double[] b)
	{
		double[] out = new double[16];
		for (int col = 0; col < 4; col++)
		{
			for (int row = 0; row < 4; row++)
			{
				double sum = 0;
				for (int k = 0; k < 4; k++)
				{
					sum += a[k * 4 + row] * b[col * 4 + k];
				}
				out[col * 4 + row] = sum;
			}
		}
		return out;
	}

	/** The inverse of a general 4x4 matrix, or the zero matrix when it has none, as gl-matrix gives. */
	static double[] invert(double[] a)
	{
		double a00 = a[0], a01 = a[1], a02 = a[2], a03 = a[3];
		double a10 = a[4], a11 = a[5], a12 = a[6], a13 = a[7];
		double a20 = a[8], a21 = a[9], a22 = a[10], a23 = a[11];
		double a30 = a[12], a31 = a[13], a32 = a[14], a33 = a[15];

		double b00 = a00 * a11 - a01 * a10;
		double b01 = a00 * a12 - a02 * a10;
		double b02 = a00 * a13 - a03 * a10;
		double b03 = a01 * a12 - a02 * a11;
		double b04 = a01 * a13 - a03 * a11;
		double b05 = a02 * a13 - a03 * a12;
		double b06 = a20 * a31 - a21 * a30;
		double b07 = a20 * a32 - a22 * a30;
		double b08 = a20 * a33 - a23 * a30;
		double b09 = a21 * a32 - a22 * a31;
		double b10 = a21 * a33 - a23 * a31;
		double b11 = a22 * a33 - a23 * a32;

		double det = b00 * b11 - b01 * b10 + b02 * b09 + b03 * b08 - b04 * b07 + b05 * b06;
		double[] out = new double[16];
		if (det == 0)
		{
			return out;
		}
		det = 1.0 / det;

		out[0] = (a11 * b11 - a12 * b10 + a13 * b09) * det;
		out[1] = (a02 * b10 - a01 * b11 - a03 * b09) * det;
		out[2] = (a31 * b05 - a32 * b04 + a33 * b03) * det;
		out[3] = (a22 * b04 - a21 * b05 - a23 * b03) * det;
		out[4] = (a12 * b08 - a10 * b11 - a13 * b07) * det;
		out[5] = (a00 * b11 - a02 * b08 + a03 * b07) * det;
		out[6] = (a32 * b02 - a30 * b05 - a33 * b01) * det;
		out[7] = (a20 * b05 - a22 * b02 + a23 * b01) * det;
		out[8] = (a10 * b10 - a11 * b08 + a13 * b06) * det;
		out[9] = (a01 * b08 - a00 * b10 - a03 * b06) * det;
		out[10] = (a30 * b04 - a31 * b02 + a33 * b00) * det;
		out[11] = (a21 * b02 - a20 * b04 - a23 * b00) * det;
		out[12] = (a11 * b07 - a10 * b09 - a12 * b06) * det;
		out[13] = (a00 * b09 - a01 * b07 + a02 * b06) * det;
		out[14] = (a31 * b01 - a30 * b03 - a32 * b00) * det;
		out[15] = (a20 * b03 - a21 * b01 + a22 * b00) * det;
		return out;
	}

	/** A rotation of {@code angle} radians about a unit axis, as a quaternion {x, y, z, w}. */
	static double[] axisAngle(double x, double y, double z, double angle)
	{
		double s = Math.sin(angle / 2);
		return new double[]{x * s, y * s, z * s, Math.cos(angle / 2)};
	}

	/** The Hamilton product {@code a * b}. */
	static double[] multiplyQuaternions(double[] a, double[] b)
	{
		return new double[]{
			a[0] * b[3] + a[3] * b[0] + a[1] * b[2] - a[2] * b[1],
			a[1] * b[3] + a[3] * b[1] + a[2] * b[0] - a[0] * b[2],
			a[2] * b[3] + a[3] * b[2] + a[0] * b[1] - a[1] * b[0],
			a[3] * b[3] - a[0] * b[0] - a[1] * b[1] - a[2] * b[2]
		};
	}

	static double[] fromQuaternion(double[] q)
	{
		double x = q[0], y = q[1], z = q[2], w = q[3];
		double x2 = x + x, y2 = y + y, z2 = z + z;
		double xx = x * x2, yx = y * x2, yy = y * y2;
		double zx = z * x2, zy = z * y2, zz = z * z2;
		double wx = w * x2, wy = w * y2, wz = w * z2;

		double[] out = new double[16];
		out[0] = 1 - yy - zz;
		out[1] = yx + wz;
		out[2] = zx - wy;
		out[4] = yx - wz;
		out[5] = 1 - xx - zz;
		out[6] = zy + wx;
		out[8] = zx + wy;
		out[9] = zy - wx;
		out[10] = 1 - xx - yy;
		out[15] = 1;
		return out;
	}

	static double[] scaling(double x, double y, double z)
	{
		double[] m = identity();
		m[0] = x;
		m[5] = y;
		m[10] = z;
		return m;
	}

	/** The lengths of the first three columns. */
	static double[] getScaling(double[] m)
	{
		return new double[]{
			Math.sqrt(m[0] * m[0] + m[1] * m[1] + m[2] * m[2]),
			Math.sqrt(m[4] * m[4] + m[5] * m[5] + m[6] * m[6]),
			Math.sqrt(m[8] * m[8] + m[9] * m[9] + m[10] * m[10])
		};
	}
}
