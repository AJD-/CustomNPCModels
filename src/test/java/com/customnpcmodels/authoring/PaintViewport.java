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

import com.customnpcmodels.inject.Lighter;
import com.customnpcmodels.inject.Mesh;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Polygon;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.util.Arrays;
import java.util.BitSet;
import javax.swing.JComponent;

/**
 * Draws a {@link GlbPaintDocument}'s mesh at rest, lit the way the plugin lights it, and says which
 * face is under any pixel.
 *
 * <p>A small software rasterizer rather than a 3D library: NPC meshes are a few thousand faces at
 * most, and drawing them ourselves gives a face-id buffer, so picking is exact and free.
 */
final class PaintViewport extends JComponent
{
	// The NPC lighting ModelCache uses: light(64 + ambient, 850 + contrast * 5, -30, -50, -30)
	private static final int NPC_AMBIENT = 64;
	private static final int NPC_CONTRAST = 850;
	private static final int NPC_CONTRAST_STEP = 5;
	private static final int NPC_LIGHT_X = -30;
	private static final int NPC_LIGHT_Y = -50;
	private static final int NPC_LIGHT_Z = -30;

	/** Lighter's faceColors3 sentinels. */
	private static final int FLAT_SHADED = -1;
	private static final int HIDDEN = -2;

	private static final int BACKGROUND = 0x2B2B30;
	private static final double NEAR = 1;

	private GlbPaintDocument document;
	private final Mesh mesh;
	private final int ambient;
	private final int contrast;

	private final int[] lit1;
	private final int[] lit2;
	private final int[] lit3;
	private boolean gameLighting = true;

	// Camera: a Blender turntable orbiting a target (engine units). The view is drawn in glTF axes -
	// the engine's with Y negated, +Y up - so the model is not mirrored relative to Blender, and
	// yaw 0, pitch 0 is Blender's front view: looking down -Z. Starts a little above and to the side
	// of the face, which the exporter's models point along -Z.
	private double yaw = Math.PI * 3 / 4;
	private double pitch = 0.35;
	private double distance;
	private final double[] target = new double[3];
	private double radius;

	private BufferedImage image;
	private int[] faceBuffer = new int[0];
	private int hoverFace = -1;

	/** Faces left out of the drawing, and so out of picking - a part the author has hidden. */
	private BitSet hiddenFaces = new BitSet();

	/**
	 * @param ambient  the NPC definition's ambient byte, 0 when it has none
	 * @param contrast the NPC definition's contrast byte, 0 when it has none
	 */
	PaintViewport(GlbPaintDocument document, int ambient, int contrast)
	{
		this.document = document;
		this.mesh = document.mesh();
		this.ambient = ambient;
		this.contrast = contrast;
		int faces = mesh.getFaceCount();
		lit1 = new int[faces];
		lit2 = new int[faces];
		lit3 = new int[faces];
		setPreferredSize(new Dimension(900, 720));
		relight();
		frame();
	}

	/** Continues with a reloaded copy of the same file, whose faces are the same faces. */
	void setDocument(GlbPaintDocument document)
	{
		this.document = document;
		relight();
	}

	/** Recomputes shading after colors change. */
	void relight()
	{
		int faces = mesh.getFaceCount();
		short[] colors = new short[faces];
		for (int face = 0; face < faces; face++)
		{
			colors[face] = document.color(face);
		}
		Lighter.light(
			mesh.getVerticesCount(), mesh.getVerticesX(), mesh.getVerticesY(), mesh.getVerticesZ(),
			faces, mesh.getFaceIndices1(), mesh.getFaceIndices2(), mesh.getFaceIndices3(),
			colors, mesh.getFaceRenderTypes(), null,
			NPC_AMBIENT + ambient, NPC_CONTRAST + contrast * NPC_CONTRAST_STEP,
			NPC_LIGHT_X, NPC_LIGHT_Y, NPC_LIGHT_Z,
			lit1, lit2, lit3);
		repaint();
	}

	void setGameLighting(boolean gameLighting)
	{
		this.gameLighting = gameLighting;
		repaint();
	}

	/**
	 * Hides faces from the drawing. A hidden face cannot be picked either, so nothing can paint it
	 * until it is shown again. Shading is unchanged: lighting still counts every face.
	 */
	void setHiddenFaces(BitSet hidden)
	{
		hiddenFaces = (BitSet) hidden.clone();
		if (hoverFace >= 0 && hiddenFaces.get(hoverFace))
		{
			hoverFace = -1;
		}
		repaint();
	}

	boolean isHidden(int face)
	{
		return hiddenFaces.get(face);
	}

	/** Centers the visible part of the model - all of it when nothing is visible - and fits it in view. */
	void frame()
	{
		boolean[] framed = new boolean[mesh.getVerticesCount()];
		boolean anyVisible = hiddenFaces.cardinality() < mesh.getFaceCount();
		if (hiddenFaces.isEmpty() || !anyVisible)
		{
			Arrays.fill(framed, true);
		}
		else
		{
			for (int face = hiddenFaces.nextClearBit(0); face < mesh.getFaceCount(); face = hiddenFaces.nextClearBit(face + 1))
			{
				framed[mesh.getFaceIndices1()[face]] = true;
				framed[mesh.getFaceIndices2()[face]] = true;
				framed[mesh.getFaceIndices3()[face]] = true;
			}
		}

		double[] min = {Double.MAX_VALUE, Double.MAX_VALUE, Double.MAX_VALUE};
		double[] max = {-Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE};
		boolean any = false;
		for (int v = 0; v < mesh.getVerticesCount(); v++)
		{
			if (!framed[v])
			{
				continue;
			}
			any = true;
			double[] p = {mesh.getVerticesX()[v], mesh.getVerticesY()[v], mesh.getVerticesZ()[v]};
			for (int k = 0; k < 3; k++)
			{
				min[k] = Math.min(min[k], p[k]);
				max[k] = Math.max(max[k], p[k]);
			}
		}
		radius = 1;
		for (int k = 0; k < 3; k++)
		{
			target[k] = any ? (min[k] + max[k]) / 2 : 0;
			radius = Math.max(radius, (max[k] - min[k]) / 2);
		}
		distance = radius * 3.2;
		repaint();
	}

	/**
	 * Turns the view the way Blender's orbit does: as if the model were grabbed, so positive yaw
	 * swings the side facing the camera to the right and positive pitch tips it down.
	 */
	void orbit(double dYaw, double dPitch)
	{
		yaw += dYaw;
		pitch = Math.max(-1.5, Math.min(1.5, pitch + dPitch));
		repaint();
	}

	void zoom(double factor)
	{
		distance = Math.max(radius * 0.2, Math.min(radius * 20, distance * factor));
		repaint();
	}

	/** Moves the view by a screen-space drag, in pixels; the model follows the mouse. */
	void pan(int dx, int dy)
	{
		double scale = distance / focal();
		double[] right = cameraToWorld(1, 0, 0);
		double[] up = cameraToWorld(0, 1, 0);
		for (int k = 0; k < 3; k++)
		{
			target[k] -= (right[k] * dx - up[k] * dy) * scale;
		}
		repaint();
	}

	/** The face drawn at a component pixel, or -1. */
	int faceAt(int x, int y)
	{
		if (image == null || x < 0 || y < 0 || x >= image.getWidth() || y >= image.getHeight())
		{
			return -1;
		}
		return faceBuffer[y * image.getWidth() + x];
	}

	void setHoverFace(int face)
	{
		if (face != hoverFace)
		{
			hoverFace = face;
			repaint();
		}
	}

	@Override
	protected void paintComponent(Graphics g)
	{
		int width = Math.max(1, getWidth());
		int height = Math.max(1, getHeight());
		render(width, height);

		Graphics2D g2 = (Graphics2D) g;
		g2.drawImage(image, 0, 0, null);
		if (hoverFace >= 0)
		{
			double[][] corners = projectFace(hoverFace, width, height);
			if (corners != null)
			{
				Polygon outline = new Polygon();
				for (double[] corner : corners)
				{
					outline.addPoint((int) Math.round(corner[0]), (int) Math.round(corner[1]));
				}
				g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
				g2.setStroke(new BasicStroke(2f));
				g2.setColor(Color.WHITE);
				g2.drawPolygon(outline);
			}
		}
	}

	/** Draws the mesh into {@link #image} and refreshes the face-id buffer. */
	BufferedImage render(int width, int height)
	{
		if (image == null || image.getWidth() != width || image.getHeight() != height)
		{
			image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
			faceBuffer = new int[width * height];
		}
		int[] pixels = ((DataBufferInt) image.getRaster().getDataBuffer()).getData();
		float[] depth = new float[width * height];
		Arrays.fill(pixels, BACKGROUND);
		Arrays.fill(faceBuffer, -1);

		for (int face = 0; face < mesh.getFaceCount(); face++)
		{
			if (gameLighting && lit3[face] == HIDDEN || hiddenFaces.get(face))
			{
				continue;
			}
			double[][] p = projectFace(face, width, height);
			if (p == null)
			{
				continue;
			}
			int[] rgb = cornerColors(face);
			fillTriangle(pixels, depth, width, height, face, p, rgb);
		}
		return image;
	}

	private int[] cornerColors(int face)
	{
		if (!gameLighting)
		{
			int rgb = RsColor.hslToRgb(document.color(face) & 0xFFFF);
			return new int[]{rgb, rgb, rgb};
		}
		if (lit3[face] == FLAT_SHADED)
		{
			int rgb = RsColor.hslToRgb(lit1[face]);
			return new int[]{rgb, rgb, rgb};
		}
		return new int[]{RsColor.hslToRgb(lit1[face]), RsColor.hslToRgb(lit2[face]), RsColor.hslToRgb(lit3[face])};
	}

	/** Screen x, y and view depth for the face's three corners, or null when any is behind the camera. */
	private double[][] projectFace(int face, int width, int height)
	{
		int[] corners = {mesh.getFaceIndices1()[face], mesh.getFaceIndices2()[face], mesh.getFaceIndices3()[face]};
		double[][] out = new double[3][];
		double f = focal(height);
		for (int k = 0; k < 3; k++)
		{
			int v = corners[k];
			double[] c = worldToCamera(mesh.getVerticesX()[v], mesh.getVerticesY()[v], mesh.getVerticesZ()[v]);
			if (c[2] < NEAR)
			{
				return null;
			}
			out[k] = new double[]{width / 2.0 + c[0] * f / c[2], height / 2.0 - c[1] * f / c[2], c[2]};
		}
		return out;
	}

	private double focal()
	{
		return focal(Math.max(1, getHeight()));
	}

	private static double focal(int height)
	{
		return height * 1.4;
	}

	/**
	 * An engine point to camera space: x right, y up, and in [2] the depth in front of the camera.
	 * The point goes into glTF axes first (Y negated), then turns by yaw about the up axis and by
	 * pitch about the camera's right axis.
	 */
	private double[] worldToCamera(double x, double y, double z)
	{
		x -= target[0];
		y = -(y - target[1]);
		z -= target[2];
		double cosYaw = Math.cos(yaw);
		double sinYaw = Math.sin(yaw);
		double x1 = x * cosYaw + z * sinYaw;
		double z1 = -x * sinYaw + z * cosYaw;
		double cosPitch = Math.cos(pitch);
		double sinPitch = Math.sin(pitch);
		double y2 = y * cosPitch - z1 * sinPitch;
		double z2 = y * sinPitch + z1 * cosPitch;
		return new double[]{x1, y2, distance - z2};
	}

	/** A camera-space direction (x right, y up) as an engine direction: {@link #worldToCamera}'s rotation undone. */
	private double[] cameraToWorld(double x, double y, double z)
	{
		double cosPitch = Math.cos(pitch);
		double sinPitch = Math.sin(pitch);
		double y1 = y * cosPitch + z * sinPitch;
		double z1 = -y * sinPitch + z * cosPitch;
		double cosYaw = Math.cos(yaw);
		double sinYaw = Math.sin(yaw);
		return new double[]{x * cosYaw - z1 * sinYaw, -y1, x * sinYaw + z1 * cosYaw};
	}

	/** Z-buffered, Gouraud-shaded; depth is interpolated as 1/z so it is right under perspective. */
	private void fillTriangle(int[] pixels, float[] depth, int width, int height, int face, double[][] p, int[] rgb)
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

		for (int y = minY; y <= maxY; y++)
		{
			double py = y + 0.5;
			for (int x = minX; x <= maxX; x++)
			{
				double px = x + 0.5;
				double w0 = ((x1 - px) * (y2 - py) - (x2 - px) * (y1 - py)) / area;
				double w1 = ((x2 - px) * (y0 - py) - (x0 - px) * (y2 - py)) / area;
				double w2 = 1 - w0 - w1;
				if (w0 < 0 || w1 < 0 || w2 < 0)
				{
					continue;
				}

				float inverseZ = (float) (w0 * iz0 + w1 * iz1 + w2 * iz2);
				int at = y * width + x;
				if (inverseZ <= depth[at])
				{
					continue;
				}
				depth[at] = inverseZ;
				faceBuffer[at] = face;
				pixels[at] = blend(rgb, w0, w1, w2);
			}
		}
	}

	private static int blend(int[] rgb, double w0, double w1, double w2)
	{
		int r = (int) ((rgb[0] >> 16 & 255) * w0 + (rgb[1] >> 16 & 255) * w1 + (rgb[2] >> 16 & 255) * w2);
		int g = (int) ((rgb[0] >> 8 & 255) * w0 + (rgb[1] >> 8 & 255) * w1 + (rgb[2] >> 8 & 255) * w2);
		int b = (int) ((rgb[0] & 255) * w0 + (rgb[1] & 255) * w1 + (rgb[2] & 255) * w2);
		return Math.min(255, r) << 16 | Math.min(255, g) << 8 | Math.min(255, b);
	}
}
