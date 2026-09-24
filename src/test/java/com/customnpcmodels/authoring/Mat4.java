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

import net.runelite.api.Perspective;

/**
 * 4x4 affine matrices as {@code double[16]} in column-major order - glTF's own layout, so an inverse
 * bind matrix is read straight in - plus the rotation conventions both sides of the conversion need.
 *
 * <h2>The engine's rotation</h2>
 *
 * A rotate op carries three angles in 1/256ths of a turn and applies them Z, then X, then Y, each
 * with the signs {@code Skinner} uses. As matrices acting on column vectors that is
 * {@code R = Ry(y) * Rx(x) * Rz(z)} with
 * <pre>
 *   Rz = [ c  s  0 ]    Rx = [ 1  0  0 ]    Ry = [ c  0  s ]
 *        [-s  c  0 ]         [ 0  c -s ]         [ 0  1  0 ]
 *        [ 0  0  1 ]         [ 0  s  c ]         [-s  0  c ]
 * </pre>
 * {@link #rsRotation} builds it from the client's own trig tables, so it is bit-for-bit what the
 * skinner applies, and {@link #rsEuler} is its inverse.
 *
 * <h2>The two spaces</h2>
 *
 * The engine works in units of 1/128 tile with +Y down; glTF in metres with +Y up. {@link #RS_FROM_GLTF}
 * maps one onto the other: a tile is a metre, and Y is negated. It is a reflection, which is why
 * converted triangles have their winding reversed.
 */
final class Mat4
{
	static final double UNITS_PER_METRE = 128;

	/** glTF metres (+Y up) to engine units (+Y down). */
	static final double[] RS_FROM_GLTF = scale(UNITS_PER_METRE, -UNITS_PER_METRE, UNITS_PER_METRE);

	/** Engine units back to glTF metres. */
	static final double[] GLTF_FROM_RS = scale(1 / UNITS_PER_METRE, -1 / UNITS_PER_METRE, 1 / UNITS_PER_METRE);

	/** An angle step: rotations are stored in 8 bits, so 1/256 of a turn. */
	static final double ANGLE_STEP = 2 * Math.PI / 256;

	private Mat4()
	{
	}

	static double[] identity()
	{
		return scale(1, 1, 1);
	}

	static double[] scale(double x, double y, double z)
	{
		double[] m = new double[16];
		m[0] = x;
		m[5] = y;
		m[10] = z;
		m[15] = 1;
		return m;
	}

	static double[] translation(double x, double y, double z)
	{
		double[] m = identity();
		m[12] = x;
		m[13] = y;
		m[14] = z;
		return m;
	}

	static double get(double[] m, int row, int col)
	{
		return m[col * 4 + row];
	}

	static void set(double[] m, int row, int col, double value)
	{
		m[col * 4 + row] = value;
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

	static double[] multiply(double[]... ms)
	{
		double[] out = ms[0];
		for (int i = 1; i < ms.length; i++)
		{
			out = multiply(out, ms[i]);
		}
		return out;
	}

	static double[] transformPoint(double[] m, double x, double y, double z)
	{
		return new double[]{
			m[0] * x + m[4] * y + m[8] * z + m[12],
			m[1] * x + m[5] * y + m[9] * z + m[13],
			m[2] * x + m[6] * y + m[10] * z + m[14]};
	}

	/** Inverse of an affine matrix (last row 0 0 0 1). */
	static double[] invert(double[] m)
	{
		double a = get(m, 0, 0), b = get(m, 0, 1), c = get(m, 0, 2);
		double d = get(m, 1, 0), e = get(m, 1, 1), f = get(m, 1, 2);
		double g = get(m, 2, 0), h = get(m, 2, 1), i = get(m, 2, 2);

		double A = e * i - f * h;
		double B = -(d * i - f * g);
		double C = d * h - e * g;
		double det = a * A + b * B + c * C;
		if (Math.abs(det) < 1e-12)
		{
			throw new GltfException("A transform is singular and cannot be inverted");
		}

		double[] inv = identity();
		set(inv, 0, 0, A / det);
		set(inv, 0, 1, -(b * i - c * h) / det);
		set(inv, 0, 2, (b * f - c * e) / det);
		set(inv, 1, 0, B / det);
		set(inv, 1, 1, (a * i - c * g) / det);
		set(inv, 1, 2, -(a * f - c * d) / det);
		set(inv, 2, 0, C / det);
		set(inv, 2, 1, -(a * h - b * g) / det);
		set(inv, 2, 2, (a * e - b * d) / det);

		double[] t = transformPoint(inv, m[12], m[13], m[14]);
		inv[12] = -t[0];
		inv[13] = -t[1];
		inv[14] = -t[2];
		return inv;
	}

	/** Translation, rotation (unit quaternion x, y, z, w) and scale composed as T * R * S. */
	static double[] fromTrs(double[] t, double[] q, double[] s)
	{
		double[] m = quaternionToMatrix(q == null ? new double[]{0, 0, 0, 1} : q);
		double sx = s == null ? 1 : s[0];
		double sy = s == null ? 1 : s[1];
		double sz = s == null ? 1 : s[2];
		for (int row = 0; row < 3; row++)
		{
			m[row] *= sx;
			m[4 + row] *= sy;
			m[8 + row] *= sz;
		}
		if (t != null)
		{
			m[12] = t[0];
			m[13] = t[1];
			m[14] = t[2];
		}
		return m;
	}

	/**
	 * Splits an affine matrix into translation, rotation quaternion and per-axis scale. Assumes no
	 * shear; {@link #shear} measures how far that assumption is off.
	 */
	static double[][] toTrs(double[] m)
	{
		double[] t = {m[12], m[13], m[14]};
		double[] s = {
			Math.sqrt(m[0] * m[0] + m[1] * m[1] + m[2] * m[2]),
			Math.sqrt(m[4] * m[4] + m[5] * m[5] + m[6] * m[6]),
			Math.sqrt(m[8] * m[8] + m[9] * m[9] + m[10] * m[10])};
		if (determinant3(m) < 0)
		{
			s[0] = -s[0];
		}

		double[] r = identity();
		for (int row = 0; row < 3; row++)
		{
			r[row] = m[row] / s[0];
			r[4 + row] = m[4 + row] / s[1];
			r[8 + row] = m[8 + row] / s[2];
		}
		return new double[][]{t, matrixToQuaternion(r), s};
	}

	/** The largest dot product between two distinct basis columns once normalized: 0 for no shear. */
	static double shear(double[] m)
	{
		double[][] cols = new double[3][3];
		for (int c = 0; c < 3; c++)
		{
			double len = Math.sqrt(m[c * 4] * m[c * 4] + m[c * 4 + 1] * m[c * 4 + 1] + m[c * 4 + 2] * m[c * 4 + 2]);
			for (int r = 0; r < 3; r++)
			{
				cols[c][r] = len == 0 ? 0 : m[c * 4 + r] / len;
			}
		}
		double worst = 0;
		for (int a = 0; a < 3; a++)
		{
			for (int b = a + 1; b < 3; b++)
			{
				worst = Math.max(worst, Math.abs(cols[a][0] * cols[b][0] + cols[a][1] * cols[b][1] + cols[a][2] * cols[b][2]));
			}
		}
		return worst;
	}

	static double determinant3(double[] m)
	{
		return get(m, 0, 0) * (get(m, 1, 1) * get(m, 2, 2) - get(m, 1, 2) * get(m, 2, 1))
			- get(m, 0, 1) * (get(m, 1, 0) * get(m, 2, 2) - get(m, 1, 2) * get(m, 2, 0))
			+ get(m, 0, 2) * (get(m, 1, 0) * get(m, 2, 1) - get(m, 1, 1) * get(m, 2, 0));
	}

	/** The nearest pure rotation to the matrix's 3x3 part, by Gram-Schmidt on its columns. */
	static double[] rotationOnly(double[] m)
	{
		double[] x = normalize(new double[]{m[0], m[1], m[2]});
		double[] y = new double[]{m[4], m[5], m[6]};
		double dot = x[0] * y[0] + x[1] * y[1] + x[2] * y[2];
		y = normalize(new double[]{y[0] - dot * x[0], y[1] - dot * x[1], y[2] - dot * x[2]});
		double[] z = {x[1] * y[2] - x[2] * y[1], x[2] * y[0] - x[0] * y[2], x[0] * y[1] - x[1] * y[0]};

		double[] r = identity();
		System.arraycopy(x, 0, r, 0, 3);
		System.arraycopy(y, 0, r, 4, 3);
		System.arraycopy(z, 0, r, 8, 3);
		return r;
	}

	private static double[] normalize(double[] v)
	{
		double len = Math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]);
		if (len < 1e-12)
		{
			return new double[]{1, 0, 0};
		}
		return new double[]{v[0] / len, v[1] / len, v[2] / len};
	}

	static double[] quaternionToMatrix(double[] q)
	{
		double len = Math.sqrt(q[0] * q[0] + q[1] * q[1] + q[2] * q[2] + q[3] * q[3]);
		double x = q[0] / len, y = q[1] / len, z = q[2] / len, w = q[3] / len;

		double[] m = identity();
		set(m, 0, 0, 1 - 2 * (y * y + z * z));
		set(m, 0, 1, 2 * (x * y - z * w));
		set(m, 0, 2, 2 * (x * z + y * w));
		set(m, 1, 0, 2 * (x * y + z * w));
		set(m, 1, 1, 1 - 2 * (x * x + z * z));
		set(m, 1, 2, 2 * (y * z - x * w));
		set(m, 2, 0, 2 * (x * z - y * w));
		set(m, 2, 1, 2 * (y * z + x * w));
		set(m, 2, 2, 1 - 2 * (x * x + y * y));
		return m;
	}

	static double[] matrixToQuaternion(double[] m)
	{
		double m00 = get(m, 0, 0), m01 = get(m, 0, 1), m02 = get(m, 0, 2);
		double m10 = get(m, 1, 0), m11 = get(m, 1, 1), m12 = get(m, 1, 2);
		double m20 = get(m, 2, 0), m21 = get(m, 2, 1), m22 = get(m, 2, 2);

		double trace = m00 + m11 + m22;
		double x, y, z, w;
		if (trace > 0)
		{
			double s = Math.sqrt(trace + 1) * 2;
			w = 0.25 * s;
			x = (m21 - m12) / s;
			y = (m02 - m20) / s;
			z = (m10 - m01) / s;
		}
		else if (m00 > m11 && m00 > m22)
		{
			double s = Math.sqrt(1 + m00 - m11 - m22) * 2;
			w = (m21 - m12) / s;
			x = 0.25 * s;
			y = (m01 + m10) / s;
			z = (m02 + m20) / s;
		}
		else if (m11 > m22)
		{
			double s = Math.sqrt(1 + m11 - m00 - m22) * 2;
			w = (m02 - m20) / s;
			x = (m01 + m10) / s;
			y = 0.25 * s;
			z = (m12 + m21) / s;
		}
		else
		{
			double s = Math.sqrt(1 + m22 - m00 - m11) * 2;
			w = (m10 - m01) / s;
			x = (m02 + m20) / s;
			y = (m12 + m21) / s;
			z = 0.25 * s;
		}
		double len = Math.sqrt(x * x + y * y + z * z + w * w);
		return new double[]{x / len, y / len, z / len, w / len};
	}

	/** Spherical interpolation between two unit quaternions, taking the short way round. */
	static double[] slerp(double[] a, double[] b, double t)
	{
		double dot = a[0] * b[0] + a[1] * b[1] + a[2] * b[2] + a[3] * b[3];
		double[] end = b;
		if (dot < 0)
		{
			dot = -dot;
			end = new double[]{-b[0], -b[1], -b[2], -b[3]};
		}

		double wa;
		double wb;
		if (dot > 0.9995)
		{
			wa = 1 - t;
			wb = t;
		}
		else
		{
			double theta = Math.acos(dot);
			double sin = Math.sin(theta);
			wa = Math.sin((1 - t) * theta) / sin;
			wb = Math.sin(t * theta) / sin;
		}

		double[] q = new double[4];
		for (int i = 0; i < 4; i++)
		{
			q[i] = wa * a[i] + wb * end[i];
		}
		double len = Math.sqrt(q[0] * q[0] + q[1] * q[1] + q[2] * q[2] + q[3] * q[3]);
		for (int i = 0; i < 4; i++)
		{
			q[i] /= len;
		}
		return q;
	}

	/**
	 * The rotation a rotate op with these stored angles applies, from the client's own trig tables
	 * exactly as {@code Skinner} reads them.
	 */
	static double[] rsRotation(int dx, int dy, int dz)
	{
		double[] rz = identity();
		double[] rx = identity();
		double[] ry = identity();

		int z = (dz & 0xFF) * 8;
		int x = (dx & 0xFF) * 8;
		int y = (dy & 0xFF) * 8;

		double sz = Perspective.SINE[z] / 65536.0, cz = Perspective.COSINE[z] / 65536.0;
		double sx = Perspective.SINE[x] / 65536.0, cx = Perspective.COSINE[x] / 65536.0;
		double sy = Perspective.SINE[y] / 65536.0, cy = Perspective.COSINE[y] / 65536.0;

		set(rz, 0, 0, cz);
		set(rz, 0, 1, sz);
		set(rz, 1, 0, -sz);
		set(rz, 1, 1, cz);

		set(rx, 1, 1, cx);
		set(rx, 1, 2, -sx);
		set(rx, 2, 1, sx);
		set(rx, 2, 2, cx);

		set(ry, 0, 0, cy);
		set(ry, 0, 2, sy);
		set(ry, 2, 0, -sy);
		set(ry, 2, 2, cy);

		return multiply(ry, rx, rz);
	}

	/**
	 * The engine's Euler angles, in radians {x, y, z}, for a pure rotation: the inverse of
	 * {@link #rsRotation} before quantisation. See the class comment for the matrix this reads.
	 */
	static double[] rsEuler(double[] r)
	{
		double sinX = -get(r, 1, 2);
		sinX = Math.max(-1, Math.min(1, sinX));

		// |cos x| read from the matrix rather than recovered from asin: the trig tables are 16-bit
		// fixed point, so a quarter turn stores sin as 0.99998 and cos(asin) never reaches zero.
		// One angle step off a quarter turn has |cos x| of about 0.0245, so 0.01 only catches the
		// quarter turn itself.
		double cosX = Math.hypot(get(r, 1, 0), get(r, 1, 1));
		double x = Math.atan2(sinX, cosX);

		double y;
		double z;
		if (cosX > 0.01)
		{
			z = Math.atan2(-get(r, 1, 0), get(r, 1, 1));
			y = Math.atan2(get(r, 0, 2), get(r, 2, 2));
		}
		else
		{
			// Gimbal lock: X is a quarter turn, so Y and Z turn about the same axis. Put it all in Y.
			z = 0;
			y = Math.atan2(get(r, 0, 1) * sinX, get(r, 0, 0));
		}
		return new double[]{x, y, z};
	}

	/** Radians to the stored 8-bit angle, rounded to the nearest 1/256 of a turn. */
	static int quantizeAngle(double radians)
	{
		return Math.floorMod((int) Math.round(radians / ANGLE_STEP), 256);
	}
}
