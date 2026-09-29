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

/**
 * Converts between the engine's packed HSL face colors and RGB.
 *
 * <p>A packed color is {@code hue << 10 | saturation << 7 | luminance}: 6, 3 and 7 bits. Note what
 * luminance means to the engine - the lighter multiplies the light level by it, so it behaves as a
 * reflectance rather than a brightness, and 0 renders black under any light. That is why colors
 * coming back from an author are clamped to 1..126.
 * <p>
 * The forward direction is the client's own palette arithmetic, with each field sampled at the
 * center of its bucket (the {@code 1/128} and {@code 1/16} offsets) and no brightness curve. The
 * reverse has no closed form that lands back on the same bucket, so it starts from the analytic
 * inverse and searches the neighboring buckets for the nearest RGB.
 */
final class RsColor
{
	static final int MIN_LUMINANCE = 1;
	static final int MAX_LUMINANCE = 126;

	private RsColor()
	{
	}

	/** Packed HSL to 0xRRGGBB, in the display (sRGB) space the client draws in. */
	static int hslToRgb(int hsl)
	{
		double hue = (hsl >> 10 & 63) / 64.0 + 0.0078125;
		double saturation = (hsl >> 7 & 7) / 8.0 + 0.0625;
		double luminance = (hsl & 127) / 128.0;

		double q = luminance < 0.5
			? luminance * (1 + saturation)
			: luminance + saturation - luminance * saturation;
		double p = 2 * luminance - q;

		double r = channel(p, q, wrap(hue + 1 / 3.0));
		double g = channel(p, q, hue);
		double b = channel(p, q, wrap(hue - 1 / 3.0));

		return toByte(r) << 16 | toByte(g) << 8 | toByte(b);
	}

	/**
	 * 0xRRGGBB to the packed HSL whose {@link #hslToRgb} is nearest, luminance clamped to
	 * {@link #MIN_LUMINANCE}..{@link #MAX_LUMINANCE}.
	 */
	static short rgbToHsl(int rgb)
	{
		int r = rgb >> 16 & 255;
		int g = rgb >> 8 & 255;
		int b = rgb & 255;

		double rf = r / 256.0;
		double gf = g / 256.0;
		double bf = b / 256.0;
		double max = Math.max(rf, Math.max(gf, bf));
		double min = Math.min(rf, Math.min(gf, bf));
		double luminance = (max + min) / 2;

		double hue = 0;
		double saturation = 0;
		if (max != min)
		{
			double delta = max - min;
			saturation = luminance < 0.5 ? delta / (max + min) : delta / (2 - max - min);
			if (max == rf)
			{
				hue = (gf - bf) / delta + (gf < bf ? 6 : 0);
			}
			else if (max == gf)
			{
				hue = (bf - rf) / delta + 2;
			}
			else
			{
				hue = (rf - gf) / delta + 4;
			}
			hue /= 6;
		}

		int h0 = Math.floorMod((int) Math.round((hue - 0.0078125) * 64), 64);
		int s0 = clamp((int) Math.round((saturation - 0.0625) * 8), 0, 7);
		int l0 = clamp((int) Math.round(luminance * 128), MIN_LUMINANCE, MAX_LUMINANCE);

		int best = pack(h0, s0, l0);
		long bestDistance = distance(hslToRgb(best), rgb);
		for (int dh = -2; dh <= 2; dh++)
		{
			for (int ds = -2; ds <= 2; ds++)
			{
				for (int dl = -3; dl <= 3; dl++)
				{
					int s = s0 + ds;
					int l = l0 + dl;
					if (s < 0 || s > 7 || l < MIN_LUMINANCE || l > MAX_LUMINANCE)
					{
						continue;
					}
					int candidate = pack(Math.floorMod(h0 + dh, 64), s, l);
					long d = distance(hslToRgb(candidate), rgb);
					if (d < bestDistance)
					{
						best = candidate;
						bestDistance = d;
					}
				}
			}
		}
		return (short) best;
	}

	/** Display (sRGB) channel 0..255 to the linear 0..1 value glTF's COLOR_0 carries. */
	static double srgbToLinear(int channel)
	{
		double c = channel / 255.0;
		return c <= 0.04045 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4);
	}

	/** Linear 0..1 back to a display (sRGB) channel 0..255. */
	static int linearToSrgb(double linear)
	{
		double c = Math.max(0, Math.min(1, linear));
		double s = c <= 0.0031308 ? c * 12.92 : 1.055 * Math.pow(c, 1 / 2.4) - 0.055;
		return clamp((int) Math.round(s * 255), 0, 255);
	}

	private static int pack(int hue, int saturation, int luminance)
	{
		return hue << 10 | saturation << 7 | luminance;
	}

	private static long distance(int a, int b)
	{
		long dr = (a >> 16 & 255) - (b >> 16 & 255);
		long dg = (a >> 8 & 255) - (b >> 8 & 255);
		long db = (a & 255) - (b & 255);
		return dr * dr + dg * dg + db * db;
	}

	private static double wrap(double t)
	{
		if (t < 0)
		{
			return t + 1;
		}
		return t > 1 ? t - 1 : t;
	}

	private static double channel(double p, double q, double t)
	{
		if (6 * t < 1)
		{
			return p + (q - p) * 6 * t;
		}
		if (2 * t < 1)
		{
			return q;
		}
		if (3 * t < 2)
		{
			return p + (q - p) * (2 / 3.0 - t) * 6;
		}
		return p;
	}

	private static int toByte(double value)
	{
		return clamp((int) (value * 256), 0, 255);
	}

	private static int clamp(int value, int min, int max)
	{
		return Math.max(min, Math.min(max, value));
	}
}
