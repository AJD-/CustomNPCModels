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

import net.runelite.api.ModelData;

/**
 * Turns unlit HSL face colors into the per-corner lit colors the renderer draws.
 * <p>
 * Needed because {@link ModelData#light()} only works on a model the client itself decoded, and
 * injected geometry by definition is not one. Lighting is baked once, at rest pose, exactly as the
 * client does it - animation moves vertices afterward and the colors are not recomputed.
 *
 * <h2>Output encoding</h2>
 *
 * The renderer reads three parallel arrays of packed HSL, one per triangle corner, and two values in
 * {@code faceColors3} are sentinels rather than colors:
 * <ul>
 *   <li>{@code -1} - flat shaded, so {@code faceColors1} applies to the whole face</li>
 *   <li>{@code -2} - hidden, the face is skipped entirely</li>
 * </ul>
 * Both matter: {@code SceneUploader} and {@code Zone} count drawable faces by testing
 * {@code faceColors3 != -2}, so getting these wrong changes what is drawn rather than just how it
 * is shaded.
 *
 * <h2>Missing render types</h2>
 *
 * A mesh with no per-face render types (flat vs gouraud vs hidden) is lit as gouraud throughout,
 * which is what the overwhelming majority of models use anyway. Bundles generated from the cache
 * carry the real types, since {@code net.runelite.cache}'s {@code ModelDefinition} decodes them.
 */
public final class Lighter
{
	/** Face is shaded across its corners from the vertex normals. */
	static final int RENDER_TYPE_GOURAUD = 0;
	/** Face takes a single color from its own normal. */
	static final int RENDER_TYPE_FLAT = 1;
	/** Face is drawn unshaded. */
	static final int RENDER_TYPE_UNSHADED = 3;

	/** {@code faceColors3} sentinel: flat shaded. */
	static final int FLAT_SHADED = -1;
	/** {@code faceColors3} sentinel: face not drawn. */
	static final int HIDDEN = -2;

	/**
	 * Cross products are accumulated in ints, so they are halved until they fit rather than
	 * overflowing on a large face.
	 */
	private static final int NORMAL_CLAMP = 8192;

	private Lighter()
	{
	}

	/**
	 * Lights a mesh in place, writing into the three destination arrays.
	 *
	 * @param faceRenderTypes per-face shading type, or null to treat every face as gouraud
	 * @param faceTextures    per-face texture id, or null when untextured
	 */
	public static void light(
		int verticesCount, float[] verticesX, float[] verticesY, float[] verticesZ,
		int faceCount, int[] faceIndices1, int[] faceIndices2, int[] faceIndices3,
		short[] faceColors, byte[] faceRenderTypes, short[] faceTextures,
		int ambient, int contrast, int lightX, int lightY, int lightZ,
		int[] outColors1, int[] outColors2, int[] outColors3)
	{
		int lightMagnitude = (int) Math.sqrt((double) lightX * lightX
			+ (double) lightY * lightY + (double) lightZ * lightZ);
		int attenuation = contrast * lightMagnitude >> 8;

		VertexNormals normals = computeNormals(verticesCount, verticesX, verticesY, verticesZ,
			faceCount, faceIndices1, faceIndices2, faceIndices3, faceRenderTypes);

		for (int face = 0; face < faceCount; face++)
		{
			int renderType = faceRenderTypes == null ? RENDER_TYPE_GOURAUD : faceRenderTypes[face];
			boolean textured = faceTextures != null && faceTextures[face] != -1;

			// A textured face takes its shading from a fixed mid-gray rather than its own color
			int color = textured ? 127 : faceColors[face] & 0xFFFF;

			if (renderType == RENDER_TYPE_GOURAUD)
			{
				int a = faceIndices1[face];
				int b = faceIndices2[face];
				int c = faceIndices3[face];

				outColors1[face] = shade(color, ambient
					+ dot(normals, a, lightX, lightY, lightZ) / (attenuation * normals.magnitude[a]));
				outColors2[face] = shade(color, ambient
					+ dot(normals, b, lightX, lightY, lightZ) / (attenuation * normals.magnitude[b]));
				outColors3[face] = shade(color, ambient
					+ dot(normals, c, lightX, lightY, lightZ) / (attenuation * normals.magnitude[c]));
			}
			else if (renderType == RENDER_TYPE_FLAT)
			{
				int light = ambient + (lightX * normals.faceX[face]
					+ lightY * normals.faceY[face]
					+ lightZ * normals.faceZ[face]) / attenuation;

				outColors1[face] = shade(color, light);
				outColors3[face] = FLAT_SHADED;
			}
			else if (renderType == RENDER_TYPE_UNSHADED)
			{
				outColors1[face] = 128;
				outColors3[face] = FLAT_SHADED;
			}
			else
			{
				outColors3[face] = HIDDEN;
			}
		}
	}

	/**
	 * Applies a light level to a packed HSL color, keeping hue and saturation and replacing
	 * luminance.
	 * <p>
	 * The clamp to 2..126 is not cosmetic: 0 and 127 are reserved, and letting luminance reach
	 * them produces the wrong color rather than a slightly wrong brightness.
	 */
	private static int shade(int hsl, int light)
	{
		light = light * (hsl & 127) >> 7;

		if (light < 2)
		{
			light = 2;
		}
		else if (light > 126)
		{
			light = 126;
		}

		return (hsl & 0xFF80) + light;
	}

	private static int dot(VertexNormals normals, int vertex, int lightX, int lightY, int lightZ)
	{
		return lightX * normals.x[vertex] + lightY * normals.y[vertex] + lightZ * normals.z[vertex];
	}

	/**
	 * Accumulates per-vertex normals from the faces that share each vertex, and per-face normals for
	 * flat-shaded faces. Transcribed from {@code ModelDefinition.computeNormals}.
	 */
	private static VertexNormals computeNormals(
		int verticesCount, float[] verticesX, float[] verticesY, float[] verticesZ,
		int faceCount, int[] faceIndices1, int[] faceIndices2, int[] faceIndices3,
		byte[] faceRenderTypes)
	{
		VertexNormals normals = new VertexNormals(verticesCount, faceCount);

		for (int face = 0; face < faceCount; face++)
		{
			int a = faceIndices1[face];
			int b = faceIndices2[face];
			int c = faceIndices3[face];

			int abX = (int) (verticesX[b] - verticesX[a]);
			int abY = (int) (verticesY[b] - verticesY[a]);
			int abZ = (int) (verticesZ[b] - verticesZ[a]);

			int acX = (int) (verticesX[c] - verticesX[a]);
			int acY = (int) (verticesY[c] - verticesY[a]);
			int acZ = (int) (verticesZ[c] - verticesZ[a]);

			int nx = abY * acZ - acY * abZ;
			int ny = abZ * acX - acZ * abX;
			int nz = abX * acY - acX * abY;

			while (nx > NORMAL_CLAMP || ny > NORMAL_CLAMP || nz > NORMAL_CLAMP
				|| nx < -NORMAL_CLAMP || ny < -NORMAL_CLAMP || nz < -NORMAL_CLAMP)
			{
				nx >>= 1;
				ny >>= 1;
				nz >>= 1;
			}

			int length = (int) Math.sqrt((double) (nx * nx + ny * ny + nz * nz));
			if (length <= 0)
			{
				length = 1;
			}

			nx = nx * 256 / length;
			ny = ny * 256 / length;
			nz = nz * 256 / length;

			int renderType = faceRenderTypes == null ? RENDER_TYPE_GOURAUD : faceRenderTypes[face];
			if (renderType == RENDER_TYPE_GOURAUD)
			{
				normals.accumulate(a, nx, ny, nz);
				normals.accumulate(b, nx, ny, nz);
				normals.accumulate(c, nx, ny, nz);
			}
			else if (renderType == RENDER_TYPE_FLAT)
			{
				normals.faceX[face] = nx;
				normals.faceY[face] = ny;
				normals.faceZ[face] = nz;
			}
		}

		return normals;
	}

	/** Parallel arrays rather than objects, because this runs over every face of every mesh. */
	private static final class VertexNormals
	{
		private final int[] x;
		private final int[] y;
		private final int[] z;
		/** How many faces contributed, used to average the accumulated normal. */
		private final int[] magnitude;

		private final int[] faceX;
		private final int[] faceY;
		private final int[] faceZ;

		private VertexNormals(int verticesCount, int faceCount)
		{
			x = new int[verticesCount];
			y = new int[verticesCount];
			z = new int[verticesCount];
			magnitude = new int[verticesCount];
			faceX = new int[faceCount];
			faceY = new int[faceCount];
			faceZ = new int[faceCount];
		}

		private void accumulate(int vertex, int nx, int ny, int nz)
		{
			x[vertex] += nx;
			y[vertex] += ny;
			z[vertex] += nz;
			magnitude[vertex]++;
		}
	}
}
