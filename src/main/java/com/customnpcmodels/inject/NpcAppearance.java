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

/**
 * What an NPC definition does to its model on top of the mesh itself: recolors, its lighting, and
 * its resize. The plugin builds every model with these, and the authoring tools preview with them,
 * so a preview shows what the game draws.
 * <p>
 * Applied in the client's order: recolor, then light at rest and unscaled, then resize (per frame,
 * after the pose).
 */
public final class NpcAppearance
{
	/** The client's NPC lighting constants: base ambient and contrast, and the light direction. */
	private static final int NPC_AMBIENT = 64;
	private static final int NPC_CONTRAST = 850;
	private static final int NPC_LIGHT_X = -30;
	private static final int NPC_LIGHT_Y = -50;
	private static final int NPC_LIGHT_Z = -30;

	/** The definition stores contrast in steps the decoder multiplies by 5. */
	private static final int NPC_CONTRAST_STEP = 5;

	private NpcAppearance()
	{
	}

	/**
	 * A copy of {@code colors} with each color in {@code find} swapped for the one at the same index
	 * in {@code replace}, the first match winning. Null pairs swap nothing.
	 * <p>
	 * Done before lighting since lit colors are baked once and never recomputed, so a recolor applied
	 * afterward would have no effect.
	 */
	public static short[] recolor(short[] colors, short[] find, short[] replace)
	{
		short[] recolored = colors.clone();
		if (find == null || replace == null)
		{
			return recolored;
		}
		for (int face = 0; face < recolored.length; face++)
		{
			for (int pair = 0; pair < find.length; pair++)
			{
				if (recolored[face] == find[pair])
				{
					recolored[face] = replace[pair];
					break;
				}
			}
		}
		return recolored;
	}

	/**
	 * Lights {@code colors}, one per face of {@code mesh}, as the client lights an NPC, into the
	 * three per-corner arrays the renderer reads.
	 * <p>
	 * Lit at rest and unscaled, with the NPC lighting formula rather than {@code ModelData.light()}'s
	 * defaults, which are the item and scenery constants. Read out of the client's
	 * {@code NPCComposition}: {@code light(64 + ambient, 850 + contrast, -30, -50, -30)}, where the
	 * decoder has already multiplied the definition's contrast byte by 5. The light comes from above,
	 * where the defaults' comes mostly from the side, so using them darkens every upward-facing
	 * surface.
	 *
	 * @param ambient  the NPC definition's ambient byte, 0 when it has none
	 * @param contrast the NPC definition's contrast byte, 0 when it has none
	 */
	public static void light(Mesh mesh, short[] colors, int ambient, int contrast,
		int[] lit1, int[] lit2, int[] lit3)
	{
		Lighter.light(
			mesh.getVerticesCount(), mesh.getVerticesX(), mesh.getVerticesY(), mesh.getVerticesZ(),
			mesh.getFaceCount(), mesh.getFaceIndices1(), mesh.getFaceIndices2(), mesh.getFaceIndices3(),
			colors, mesh.getFaceRenderTypes(), mesh.getFaceTextures(),
			NPC_AMBIENT + ambient, NPC_CONTRAST + contrast * NPC_CONTRAST_STEP,
			NPC_LIGHT_X, NPC_LIGHT_Y, NPC_LIGHT_Z,
			lit1, lit2, lit3);
	}

	/** A definition's size, in 128ths as the rig's scale transforms are, as a factor. */
	public static float scale(int size)
	{
		return size / (float) Rig.SCALE_UNIT;
	}

	/**
	 * Resizes a posed model's first {@code count} vertices in place.
	 * <p>
	 * The resize comes after the pose, as the client orders it. A clip's translations and pivot
	 * offsets are absolute units authored against the unscaled mesh, so resizing the rest pose first
	 * and posing it afterward moves every translated part by the full unscaled amount - the limbs
	 * drift off the body they are attached to.
	 */
	public static void resize(float[] x, float[] y, float[] z, int count, float scaleXZ, float scaleY)
	{
		if (scaleXZ == 1f && scaleY == 1f)
		{
			return;
		}
		for (int v = 0; v < count; v++)
		{
			x[v] *= scaleXZ;
			y[v] *= scaleY;
			z[v] *= scaleXZ;
		}
	}
}
