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

import net.runelite.cache.io.InputStream;

/**
 * One animated channel of a skeletal animation: keyframes with tangents, pre-sampled to one value
 * per tick from the first keyframe to the last when decoded, as the client does.
 * <p>
 * Ported from rs-map-viewer's {@code Curve} and {@code CurveInterp}
 * (https://github.com/dennisdev/rs-map-viewer, BSD-2-Clause), which follow the client closely, down
 * to its obfuscated temporaries; the arithmetic is kept in floats, as the client keeps it. One quirk
 * is kept on purpose: the values before the first keyframe and after the last are worked out after
 * the keyframes are dropped, so they are always 0.
 */
final class Curve
{
	private static final float ULP = 1.1920929E-7f;
	private static final float ULP2 = 2 * ULP;
	private static final float THIRD = 0.33333334f;

	/** Out-of-range handling: 0 holds the end keyframe; the others repeat, mirror or extend the curve. */
	private int startInterp;
	private int endInterp;
	private boolean bezier;

	private Point[] points;
	private int startTick;
	private int endTick;
	private float[] values;
	private float minValue;
	private float maxValue;

	private int pointIndex;
	private boolean pointIndexUpdated = true;

	private boolean interpLinear;
	private float interpV0;
	private float interpV1;
	private float interpV2;
	private float interpV3;
	private float interpV4;
	private float interpV5;
	private float interpV6;
	private float interpV7;
	private float interpV8;
	private float interpV9;

	static Curve decode(InputStream in)
	{
		Curve curve = new Curve();
		int count = in.readUnsignedShort();
		in.readUnsignedByte();                 // a type nothing reads
		curve.startInterp = interpType(in.readUnsignedByte());
		curve.endInterp = interpType(in.readUnsignedByte());
		curve.bezier = in.readUnsignedByte() != 0;

		curve.points = new Point[count];
		for (int i = 0; i < count; i++)
		{
			Point point = new Point();
			point.x = in.readShort();
			point.y = readFloat(in);
			point.inX = readFloat(in);
			point.inY = readFloat(in);
			point.outX = readFloat(in);
			point.outY = readFloat(in);
			curve.points[i] = point;
			if (i > 0)
			{
				curve.points[i - 1].next = point;
			}
		}
		curve.load();
		return curve;
	}

	private static float readFloat(InputStream in)
	{
		return Float.intBitsToFloat(in.readInt());
	}

	private static int interpType(int id)
	{
		return id < 0 || id > 4 ? 0 : id;
	}

	private void load()
	{
		if (points.length == 0)
		{
			points = null;
			return;
		}
		startTick = points[0].x;
		endTick = points[points.length - 1].x;
		values = new float[endTick - startTick + 1];
		for (int t = startTick; t <= endTick; t++)
		{
			values[t - startTick] = interpolate(t);
		}

		points = null;
		// The client's order, so these come out 0 (see the class comment)
		minValue = interpolate(startTick - 1);
		maxValue = interpolate(endTick + 1);
	}

	/** The value at a tick. */
	float getValue(int t)
	{
		if (values == null)
		{
			return 0;
		}
		if (t < startTick)
		{
			return minValue;
		}
		if (t > endTick)
		{
			return maxValue;
		}
		return values[t - startTick];
	}

	private int getPointIndex(float t)
	{
		if (points == null)
		{
			return pointIndex;
		}
		if (pointIndex < 0 || points[pointIndex].x > t
			|| (points[pointIndex].next != null && points[pointIndex].next.x <= t))
		{
			if (t < startTick || t > endTick)
			{
				return -1;
			}
			int newIndex = pointIndex;
			int start = 0;
			int end = points.length - 1;
			do
			{
				int middle = (start + end) >> 1;
				if (t < points[middle].x)
				{
					if (t > points[middle - 1].x)
					{
						newIndex = middle - 1;
						break;
					}
					end = middle - 1;
				}
				else
				{
					if (t <= points[middle].x)
					{
						newIndex = middle;
						break;
					}
					if (t < points[middle + 1].x)
					{
						newIndex = middle;
						break;
					}
					start = middle + 1;
				}
			}
			while (start <= end);

			if (pointIndex != newIndex)
			{
				pointIndex = newIndex;
				pointIndexUpdated = true;
			}
		}
		return pointIndex;
	}

	private Point getPoint(float t)
	{
		if (points == null)
		{
			return null;
		}
		int index = getPointIndex(t);
		return index < 0 || index >= points.length ? null : points[index];
	}

	private float interpolate(float t)
	{
		if (points == null || points.length == 0)
		{
			return 0;
		}
		if (t < startTick)
		{
			return startInterp == 0 ? points[0].y : extrapolate(t, true);
		}
		if (t > endTick)
		{
			return endInterp == 0 ? points[points.length - 1].y : extrapolate(t, false);
		}
		Point point = getPoint(t);
		if (point == null)
		{
			return 0;
		}

		boolean step = false;
		boolean hold = false;
		if (point.outX == 0 && point.outY == 0)
		{
			step = true;
		}
		else if (point.outX == Float.MAX_VALUE && point.outY == Float.MAX_VALUE)
		{
			hold = true;
		}
		else if (point.next == null)
		{
			step = true;
		}
		else if (pointIndexUpdated)
		{
			prepareSegment(point);
			pointIndexUpdated = false;
		}

		if (step)
		{
			return point.y;
		}
		if (hold)
		{
			return point.x != t && point.next != null ? point.next.y : point.y;
		}
		if (bezier)
		{
			return evaluateBezier(t);
		}
		float dt = t - interpV0;
		return interpV5 + dt * ((dt * interpV2 + interpV3) * dt + interpV4);
	}

	private void prepareSegment(Point point)
	{
		float var5 = point.x;
		float var9 = point.y;
		float var6 = point.outX * THIRD + var5;
		float var10 = point.outY * THIRD + var9;
		float var8 = point.next.x;
		float var12 = point.next.y;
		float var7 = var8 - point.next.inX * THIRD;
		float var11 = var12 - point.next.inY * THIRD;
		if (bezier)
		{
			float var15 = var10;
			float var16 = var11;
			float var17 = var8 - var5;
			if (var17 != 0.0f)
			{
				float var18 = var6 - var5;
				float var19 = var7 - var5;
				float[] var29 = {var18 / var17, var19 / var17};
				interpLinear = var29[0] == THIRD && var29[1] == 0.6666667f;
				float var21 = var29[0];
				float var22 = var29[1];
				if (var29[0] < 0.0f)
				{
					var29[0] = 0.0f;
				}
				if (var29[1] > 1.0f)
				{
					var29[1] = 1.0f;
				}
				if (var29[0] > 1.0f || var29[1] < -1.0f)
				{
					clampControlPoints(var29);
				}
				if (var29[0] != var21 && var21 != 0.0f)
				{
					var15 = (var10 - var9) * var29[0] / var21 + var9;
				}
				if (var22 != var29[1] && var22 != 1.0f)
				{
					var16 = var12 - (1.0f - var29[1]) * (var12 - var11) / (1.0f - var22);
				}

				interpV0 = var5;
				interpV1 = var8;
				float var23 = var29[0];
				float var24 = var29[1];
				float var25 = var23 - 0.0f;
				float var26 = var24 - var23;
				float var27 = 1.0f - var24;
				float var28 = var26 - var25;
				interpV5 = var27 - var26 - var28;
				interpV4 = var28 + var28 + var28;
				interpV3 = var25 + var25 + var25;
				interpV2 = 0.0f;
				var25 = var15 - var9;
				var26 = var16 - var15;
				var27 = var12 - var16;
				var28 = var26 - var25;
				interpV9 = var27 - var26 - var28;
				interpV8 = var28 + var28 + var28;
				interpV7 = var25 + var25 + var25;
				interpV6 = var9;
			}
		}
		else
		{
			interpV0 = var5;
			float var13 = var8 - var5;
			float var14 = var12 - var9;
			float var15 = var6 - var5;
			float var16 = 0.0f;
			float var17 = 0.0f;
			if (var15 != 0.0f)
			{
				var16 = (var10 - var9) / var15;
			}
			var15 = var8 - var7;
			if (var15 != 0.0f)
			{
				var17 = (var12 - var11) / var15;
			}
			float var18 = 1.0f / (var13 * var13);
			float var19 = var16 * var13;
			float var20 = var17 * var13;
			interpV2 = var18 * (var19 + var20 - var14 - var14) / var13;
			interpV3 = var18 * (var14 + var14 + var14 - var19 - var19 - var20);
			interpV4 = var16;
			interpV5 = var9;
		}
	}

	private float extrapolate(float t, boolean isStart)
	{
		if (points == null || points.length == 0)
		{
			return 0;
		}
		float var4 = points[0].x;
		float var5 = points[points.length - 1].x;
		float var6 = var5 - var4;
		if (var6 == 0.0f)
		{
			return points[0].y;
		}

		float var7 = t > var5 ? (t - var5) / var6 : (t - var4) / var6;
		float var8 = (int) var7;
		float var10 = Math.abs(var7 - var8);
		float var11 = var10 * var6;
		var8 = Math.abs(1.0f + var8);
		float var12 = var8 / 2.0f;
		float var14 = (int) var12;
		var10 = var12 - var14;
		int type = isStart ? startInterp : endInterp;
		if (isStart)
		{
			if (type == 4)
			{
				var11 = var10 != 0.0f ? var11 + var4 : var5 - var11;
			}
			else if (type == 2 || type == 3)
			{
				var11 = var5 - var11;
			}
			else if (type == 1)
			{
				var11 = var4 - t;
				float in = points[0].inX;
				float inY = points[0].inY;
				float output = points[0].y;
				if (in != 0.0f)
				{
					output -= var11 * inY / in;
				}
				return output;
			}
		}
		else
		{
			if (type == 4)
			{
				var11 = var10 != 0.0f ? var5 - var11 : var11 + var4;
			}
			else if (type == 2 || type == 3)
			{
				var11 += var4;
			}
			else if (type == 1)
			{
				var11 = t - var5;
				Point last = points[points.length - 1];
				float output = last.y;
				if (last.outX != 0.0f)
				{
					output += last.outY * var11 / last.outX;
				}
				return output;
			}
		}
		float output = interpolate(var11);
		if (type == 3)
		{
			float span = points[points.length - 1].y - points[0].y;
			output = isStart ? output - span * var8 : output + span * var8;
		}
		return output;
	}

	private static void clampControlPoints(float[] v)
	{
		v[1] = 1.0f - v[1];
		if (v[0] < 0.0f)
		{
			v[0] = 0.0f;
		}
		if (v[1] < 0.0f)
		{
			v[1] = 0.0f;
		}
		if (v[0] > 1.0f || v[1] > 1.0f)
		{
			float var1 = 1.0f + v[0] * (v[0] - 2.0f + v[1]) + (v[1] - 2.0f) * v[1];
			if (var1 + ULP > 0.0f)
			{
				if (ULP + v[0] < 1.3333334f)
				{
					float var2 = v[0] - 2.0f;
					float var3 = v[0] - 1.0f;
					float var4 = (float) Math.sqrt(var2 * var2 - 4.0f * var3 * var3);
					float var5 = 0.5f * (var4 + -var2);
					if (v[1] + ULP > var5)
					{
						v[1] = var5 - ULP;
					}
					else
					{
						float var6 = (-var2 - var4) * 0.5f;
						if (v[1] < var6 + ULP)
						{
							v[1] = var6 + ULP;
						}
					}
				}
				else
				{
					v[0] = 1.3333334f - ULP;
					v[1] = THIRD - ULP;
				}
			}
		}
		v[1] = 1.0f - v[1];
	}

	private float evaluateBezier(float t)
	{
		float v0;
		if (interpV0 == t)
		{
			v0 = 0.0f;
		}
		else if (t == interpV1)
		{
			v0 = 1.0f;
		}
		else
		{
			v0 = (t - interpV0) / (interpV1 - interpV0);
		}

		float v1;
		if (interpLinear)
		{
			v1 = v0;
		}
		else
		{
			float[] input = {interpV2 - v0, interpV3, interpV4, interpV5};
			float[] output = new float[5];
			v1 = solve(input, 3, 0.0f, true, 1.0f, true, output) == 1 ? output[0] : 0.0f;
		}
		return v1 * (interpV7 + v1 * (v1 * interpV9 + interpV8)) + interpV6;
	}

	private static float horner(float[] values, int lastIndex, float x)
	{
		float output = values[lastIndex];
		for (int i = lastIndex - 1; i >= 0; i--)
		{
			output = output * x + values[i];
		}
		return output;
	}

	/**
	 * The roots of a polynomial between {@code low} and {@code high}, into {@code roots}; returns how
	 * many, or -1 for a polynomial that is zero throughout.
	 */
	private static int solve(float[] var0, int var1, float var2, boolean var3, float var4, boolean var5, float[] var6)
	{
		float var7 = 0.0f;
		for (int i = 0; i < var1 + 1; i++)
		{
			var7 += Math.abs(var0[i]);
		}

		float var44 = (Math.abs(var2) + Math.abs(var4)) * (var1 + 1) * ULP;
		if (var7 <= var44)
		{
			return -1;
		}
		float[] var9 = new float[var1 + 1];
		for (int i = 0; i < var1 + 1; i++)
		{
			var9[i] = 1.0f / var7 * var0[i];
		}
		while (Math.abs(var9[var1]) < var44)
		{
			var1--;
		}

		int status = 0;
		if (var1 == 0)
		{
			return status;
		}
		if (var1 == 1)
		{
			var6[0] = -var9[0] / var9[1];
			boolean var42 = var3 ? var2 < var6[0] + var44 : var2 < var6[0] - var44;
			boolean var43 = var5 ? var4 > var6[0] - var44 : var4 > var6[0] + var44;
			status = var42 && var43 ? 1 : 0;
			if (status > 0)
			{
				if (var3 && var6[0] < var2)
				{
					var6[0] = var2;
				}
				else if (var5 && var6[0] > var4)
				{
					var6[0] = var4;
				}
			}
			return status;
		}

		float[] field4756 = var9;
		int field4757 = var1;
		float[] var12 = new float[var1 + 1];
		for (int var13 = 1; var13 <= var1; var13++)
		{
			var12[var13 - 1] = var13 * var9[var13];
		}
		float[] var41 = new float[var1 + 1];
		int recursiveStatus = solve(var12, var1 - 1, var2, false, var4, false, var41);
		if (recursiveStatus == -1)
		{
			return 0;
		}

		boolean var15 = false;
		float var17 = 0.0f;
		float var18;
		float var19 = 0.0f;
		for (int s = 0; s <= recursiveStatus; s++)
		{
			if (status > var1)
			{
				return status;
			}

			float var16;
			if (s == 0)
			{
				var16 = var2;
				var18 = horner(var9, var1, var2);
				if (Math.abs(var18) <= var44 && var3)
				{
					var6[status++] = var2;
				}
			}
			else
			{
				var16 = var19;
				var18 = var17;
			}

			if (recursiveStatus == s)
			{
				var19 = var4;
				var15 = false;
			}
			else
			{
				var19 = var41[s];
			}

			var17 = horner(var9, var1, var19);
			if (var15)
			{
				var15 = false;
			}
			else if (Math.abs(var17) < var44)
			{
				if (recursiveStatus != s || var5)
				{
					var6[status++] = var19;
					var15 = true;
				}
			}
			else if (var18 < 0.0f && var17 > 0.0f || var18 > 0.0f && var17 < 0.0f)
			{
				int var22 = status++;
				float var24 = var16;
				float var25 = var19;
				float var26 = horner(field4756, field4757, var16);
				float var23;
				if (Math.abs(var26) < ULP)
				{
					var23 = var16;
				}
				else
				{
					float var27 = horner(field4756, field4757, var19);
					if (Math.abs(var27) < ULP)
					{
						var23 = var19;
					}
					else
					{
						float var28 = 0.0f;
						float var29 = 0.0f;
						float var30 = 0.0f;
						float var35 = 0.0f;
						boolean var36 = true;
						boolean var37;
						do
						{
							var37 = false;
							if (var36)
							{
								var28 = var24;
								var35 = var26;
								var29 = var25 - var24;
								var30 = var29;
								var36 = false;
							}

							if (Math.abs(var35) < Math.abs(var27))
							{
								var24 = var25;
								var25 = var28;
								var28 = var24;
								var26 = var27;
								var27 = var35;
								var35 = var26;
							}

							float var38 = ULP2 * Math.abs(var25) + 0.0f;
							float var39 = 0.5f * (var28 - var25);
							boolean var40 = Math.abs(var39) > var38 && var27 != 0.0f;
							if (var40)
							{
								if (Math.abs(var30) < var38 || Math.abs(var26) <= Math.abs(var27))
								{
									var29 = var39;
									var30 = var39;
								}
								else
								{
									float var34 = var27 / var26;
									float var31;
									float var32;
									if (var24 == var28)
									{
										var31 = var39 * 2.0f * var34;
										var32 = 1.0f - var34;
									}
									else
									{
										var32 = var26 / var35;
										float var33 = var27 / var35;
										var31 = var34 * (var39 * 2.0f * var32 * (var32 - var33) - (var33 - 1.0f) * (var25 - var24));
										var32 = (var33 - 1.0f) * (var32 - 1.0f) * (var34 - 1.0f);
									}

									if (var31 > 0.0f)
									{
										var32 = -var32;
									}
									else
									{
										var31 = -var31;
									}

									var34 = var30;
									var30 = var29;
									if (2.0f * var31 < 3.0f * var39 * var32 - Math.abs(var32 * var38)
										&& var31 < Math.abs(var32 * var34 * 0.5f))
									{
										var29 = var31 / var32;
									}
									else
									{
										var29 = var39;
										var30 = var39;
									}
								}

								var24 = var25;
								var26 = var27;
								if (Math.abs(var29) > var38)
								{
									var25 += var29;
								}
								else if (var39 > 0.0f)
								{
									var25 += var38;
								}
								else
								{
									var25 -= var38;
								}

								var27 = horner(field4756, field4757, var25);
								if (var27 * (var35 / Math.abs(var35)) > 0.0f)
								{
									var36 = true;
								}
								var37 = true;
							}
						}
						while (var37);

						var23 = var25;
					}
				}

				var6[var22] = var23;
				if (status > 1 && var6[status - 2] >= var6[status - 1] - var44)
				{
					var6[status - 2] = 0.5f * (var6[status - 2] + var6[status - 1]);
					status--;
				}
			}
		}
		return status;
	}

	private static final class Point
	{
		int x;
		float y;
		float inX;
		float inY;
		float outX;
		float outY;
		Point next;
	}
}
