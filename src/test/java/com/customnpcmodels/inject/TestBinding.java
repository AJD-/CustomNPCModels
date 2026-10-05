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
 * Builds bindings for tests: unrigged, unscaled, with no recolors or lighting changes, unless a test
 * says otherwise - so each names only what it is about.
 */
public final class TestBinding
{
	private final String name;
	private final int[] npcIds;
	private final int[] meshIds;
	private int rigId = NpcBinding.STATIC;
	private int scaleXZ = Rig.SCALE_UNIT;
	private int scaleY = Rig.SCALE_UNIT;
	private short[] recolorFind;
	private short[] recolorReplace;
	private int ambient;
	private int contrast;

	private TestBinding(String name, int[] npcIds, int[] meshIds)
	{
		this.name = name;
		this.npcIds = npcIds;
		this.meshIds = meshIds;
	}

	public static TestBinding of(String name, int[] npcIds, int[] meshIds)
	{
		return new TestBinding(name, npcIds, meshIds);
	}

	public TestBinding rig(int rigId)
	{
		this.rigId = rigId;
		return this;
	}

	public TestBinding scale(int scaleXZ, int scaleY)
	{
		this.scaleXZ = scaleXZ;
		this.scaleY = scaleY;
		return this;
	}

	public TestBinding recolors(short[] find, short[] replace)
	{
		this.recolorFind = find;
		this.recolorReplace = replace;
		return this;
	}

	public TestBinding lighting(int ambient, int contrast)
	{
		this.ambient = ambient;
		this.contrast = contrast;
		return this;
	}

	public NpcBinding build()
	{
		return new NpcBinding(name, npcIds, meshIds, rigId, scaleXZ, scaleY, recolorFind, recolorReplace,
			ambient, contrast);
	}
}
