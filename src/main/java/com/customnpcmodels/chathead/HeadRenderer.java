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
package com.customnpcmodels.chathead;

import com.customnpcmodels.inject.Mesh;
import com.customnpcmodels.inject.RsColor;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/**
 * Draws a posed chathead into an image the size of the dialogue head widget, clear wherever the
 * head is not: z-buffered and Gouraud shaded, opaque faces first, then see-through ones farthest
 * first, as the client orders them. Ported from the authoring tools' viewport.
 * <p>
 * The image is reused while the size holds, so a frame allocates nothing. Client thread only.
 */
public final class HeadRenderer
{
	/** Lighter's corner sentinels. */
	private static final int FLAT_SHADED = -1;
	private static final int HIDDEN = -2;

	/** A face's transparency byte at which nothing of it is drawn: alpha is 1 - t / 255. */
	private static final int FULLY_TRANSPARENT = 255;

	private static final int OPAQUE = 0xFF000000;
	private static final double NEAR = 1;

	private BufferedImage image;
	private float[] depth = new float[0];
	private final double[] projected = new double[3];

	public BufferedImage render(HeadModel head, float[] x, float[] y, float[] z, ChatheadCamera camera,
		int width, int height)
	{
		if (image == null || image.getWidth() != width || image.getHeight() != height)
		{
			image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
			depth = new float[width * height];
		}
		int[] pixels = ((DataBufferInt) image.getRaster().getDataBuffer()).getData();
		Arrays.fill(pixels, 0);
		Arrays.fill(depth, 0f);

		Mesh mesh = head.getMesh();
		byte[] transparencies = mesh.getFaceTransparencies();
		List<double[][]> seeThrough = new ArrayList<>();
		List<Integer> seeThroughFaces = new ArrayList<>();
		for (int face = 0; face < mesh.getFaceCount(); face++)
		{
			int transparency = transparencies == null ? 0 : transparencies[face] & 0xFF;
			if (head.getLit3()[face] == HIDDEN || transparency == FULLY_TRANSPARENT)
			{
				continue;
			}
			double[][] p = projectFace(mesh, face, x, y, z, camera, head.getHeight(), width, height);
			if (p == null)
			{
				continue;
			}
			if (transparency != 0)
			{
				seeThrough.add(p);
				seeThroughFaces.add(face);
				continue;
			}
			fillTriangle(pixels, width, height, p, cornerColors(head, face), 1);
		}

		Integer[] order = new Integer[seeThrough.size()];
		for (int i = 0; i < order.length; i++)
		{
			order[i] = i;
		}
		Arrays.sort(order, Comparator.comparingDouble(i -> -meanDepth(seeThrough.get(i))));
		for (int i : order)
		{
			int face = seeThroughFaces.get(i);
			double alpha = (FULLY_TRANSPARENT - (transparencies[face] & 0xFF)) / (double) FULLY_TRANSPARENT;
			fillTriangle(pixels, width, height, seeThrough.get(i), cornerColors(head, face), alpha);
		}
		return image;
	}

	private static double meanDepth(double[][] p)
	{
		return (p[0][2] + p[1][2] + p[2][2]) / 3;
	}

	private static int[] cornerColors(HeadModel head, int face)
	{
		int[] lit1 = head.getLit1();
		int[] lit2 = head.getLit2();
		int[] lit3 = head.getLit3();
		if (lit3[face] == FLAT_SHADED)
		{
			int rgb = RsColor.hslToRgb(lit1[face]);
			return new int[]{rgb, rgb, rgb};
		}
		return new int[]{RsColor.hslToRgb(lit1[face]), RsColor.hslToRgb(lit2[face]), RsColor.hslToRgb(lit3[face])};
	}

	/** Screen x, y and depth of the face's corners, or null when any is behind the camera. */
	private double[][] projectFace(Mesh mesh, int face, float[] x, float[] y, float[] z, ChatheadCamera camera,
		int headHeight, int width, int height)
	{
		int[] corners = {mesh.getFaceIndices1()[face], mesh.getFaceIndices2()[face], mesh.getFaceIndices3()[face]};
		double[][] out = new double[3][];
		for (int k = 0; k < 3; k++)
		{
			int v = corners[k];
			camera.project(x[v], y[v], z[v], headHeight, projected);
			if (projected[2] < NEAR)
			{
				return null;
			}
			out[k] = new double[]{width / 2.0 + projected[0], height / 2.0 + projected[1], projected[2]};
		}
		return out;
	}

	/**
	 * Fills a triangle, interpolating depth as 1/z so it is right under perspective. A see-through
	 * face ({@code alpha} below 1) is blended over what is drawn, or drawn at its own opacity over
	 * clear, and leaves the depth alone so what is behind still shows.
	 */
	private void fillTriangle(int[] pixels, int width, int height, double[][] p, int[] rgb, double alpha)
	{
		double x0 = p[0][0], y0 = p[0][1];
		double x1 = p[1][0], y1 = p[1][1];
		double x2 = p[2][0], y2 = p[2][1];
		double area = (x1 - x0) * (y2 - y0) - (x2 - x0) * (y1 - y0);
		if (Math.abs(area) < 1e-9)
		{
			return;
		}

		int minX = Math.max(0, (int) Math.floor(Math.min(x0, Math.min(x1, x2))));
		int maxX = Math.min(width - 1, (int) Math.ceil(Math.max(x0, Math.max(x1, x2))));
		int minY = Math.max(0, (int) Math.floor(Math.min(y0, Math.min(y1, y2))));
		int maxY = Math.min(height - 1, (int) Math.ceil(Math.max(y0, Math.max(y1, y2))));
		double iz0 = 1 / p[0][2];
		double iz1 = 1 / p[1][2];
		double iz2 = 1 / p[2][2];

		for (int py = minY; py <= maxY; py++)
		{
			double cy = py + 0.5;
			for (int px = minX; px <= maxX; px++)
			{
				double cx = px + 0.5;
				double w0 = ((x1 - cx) * (y2 - cy) - (x2 - cx) * (y1 - cy)) / area;
				double w1 = ((x2 - cx) * (y0 - cy) - (x0 - cx) * (y2 - cy)) / area;
				double w2 = 1 - w0 - w1;
				if (w0 < 0 || w1 < 0 || w2 < 0)
				{
					continue;
				}

				float inverseZ = (float) (w0 * iz0 + w1 * iz1 + w2 * iz2);
				int at = py * width + px;
				if (inverseZ <= depth[at])
				{
					continue;
				}
				int color = blend(rgb, w0, w1, w2);
				if (alpha >= 1)
				{
					depth[at] = inverseZ;
					pixels[at] = OPAQUE | color;
				}
				else if (pixels[at] >>> 24 == 0)
				{
					pixels[at] = (int) Math.round(alpha * 255) << 24 | color;
				}
				else
				{
					pixels[at] = OPAQUE | mix(pixels[at], color, alpha);
				}
			}
		}
	}

	/** {@code over} laid on {@code under} at the given opacity; RGB only. */
	private static int mix(int under, int over, double alpha)
	{
		int r = (int) Math.round((under >> 16 & 255) * (1 - alpha) + (over >> 16 & 255) * alpha);
		int g = (int) Math.round((under >> 8 & 255) * (1 - alpha) + (over >> 8 & 255) * alpha);
		int b = (int) Math.round((under & 255) * (1 - alpha) + (over & 255) * alpha);
		return r << 16 | g << 8 | b;
	}

	private static int blend(int[] rgb, double w0, double w1, double w2)
	{
		int r = (int) ((rgb[0] >> 16 & 255) * w0 + (rgb[1] >> 16 & 255) * w1 + (rgb[2] >> 16 & 255) * w2);
		int g = (int) ((rgb[0] >> 8 & 255) * w0 + (rgb[1] >> 8 & 255) * w1 + (rgb[2] >> 8 & 255) * w2);
		int b = (int) ((rgb[0] & 255) * w0 + (rgb[1] & 255) * w1 + (rgb[2] & 255) * w2);
		return Math.min(255, r) << 16 | Math.min(255, g) << 8 | Math.min(255, b);
	}
}
