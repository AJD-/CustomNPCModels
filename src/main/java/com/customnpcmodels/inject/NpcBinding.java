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

import lombok.Getter;

/**
 * Which NPCs wear a custom model, and how it is dressed for them.
 * <p>
 * Carried in the bundle rather than written in code, so adding a model is an authoring job - a
 * glTF file and a manifest entry - and never a plugin change. Animation needs no entry here at all:
 * clips are keyed by the live sequence ids the NPC already plays.
 * <p>
 * The accessors hand back the live arrays. Nothing mutates a binding once it is built.
 */
@Getter
public final class NpcBinding
{
	/** For logging only - the manifest entry this came from. */
	private final String name;

	private final int[] npcIds;

	/** Bundle meshes merged, in order, into the model these NPCs are drawn with. */
	private final int[] meshIds;

	/**
	 * Resize in 1/128ths, applied to the posed model - after animation, as the client resizes an
	 * NPC. 128 is unscaled.
	 */
	private final int scaleXZ;
	private final int scaleY;

	/** Palette recolor pairs, parallel arrays of packed HSL. Both null when there are none. */
	private final short[] recolorFind;
	private final short[] recolorReplace;

	/**
	 * The NPC definition's lighting adjustments, as the cache stores them (opcodes 100 and 101, one
	 * signed byte each). 0 for both is the usual case.
	 */
	private final int ambient;
	private final int contrast;

	public NpcBinding(String name, int[] npcIds, int[] meshIds, int scaleXZ, int scaleY,
		short[] recolorFind, short[] recolorReplace)
	{
		this(name, npcIds, meshIds, scaleXZ, scaleY, recolorFind, recolorReplace, 0, 0);
	}

	public NpcBinding(String name, int[] npcIds, int[] meshIds, int scaleXZ, int scaleY,
		short[] recolorFind, short[] recolorReplace, int ambient, int contrast)
	{
		this.name = name;
		this.npcIds = npcIds;
		this.meshIds = meshIds;
		this.scaleXZ = scaleXZ;
		this.scaleY = scaleY;
		this.recolorFind = recolorFind;
		this.recolorReplace = recolorReplace;
		this.ambient = ambient;
		this.contrast = contrast;
	}

	public boolean hasRecolors()
	{
		return recolorFind != null && recolorFind.length > 0;
	}
}
