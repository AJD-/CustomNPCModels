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

import com.customnpcmodels.inject.Mesh;

/**
 * Builds small meshes for tests, starting from a valid two-face quad at RS scale so each test
 * changes only the one column it is about.
 */
final class TestMesh
{
	private int id = 1;
	private int priority;
	private float[] vx = {0f, 128f, 128f, 0f};
	private float[] vy = {0f, 0f, 0f, 0f};
	private float[] vz = {0f, 0f, 128f, 128f};
	private int[] i1 = {0, 0};
	private int[] i2 = {1, 2};
	private int[] i3 = {2, 3};
	private short[] colors = {(short) 0x3A05, (short) 0x1234};
	private byte[] renderTypes;
	private byte[] transparencies;
	private byte[] priorities;
	private short[] textures;
	private byte[] textureCoords;
	private int[][] groups = {{0, 1}, {2, 3}};

	TestMesh id(int id)
	{
		this.id = id;
		return this;
	}

	TestMesh priority(int priority)
	{
		this.priority = priority;
		return this;
	}

	TestMesh vx(float[] vx)
	{
		this.vx = vx;
		return this;
	}

	TestMesh vy(float[] vy)
	{
		this.vy = vy;
		return this;
	}

	TestMesh vz(float[] vz)
	{
		this.vz = vz;
		return this;
	}

	TestMesh i1(int[] i1)
	{
		this.i1 = i1;
		return this;
	}

	TestMesh i2(int[] i2)
	{
		this.i2 = i2;
		return this;
	}

	TestMesh i3(int[] i3)
	{
		this.i3 = i3;
		return this;
	}

	TestMesh colors(short[] colors)
	{
		this.colors = colors;
		return this;
	}

	TestMesh renderTypes(byte[] renderTypes)
	{
		this.renderTypes = renderTypes;
		return this;
	}

	TestMesh transparencies(byte[] transparencies)
	{
		this.transparencies = transparencies;
		return this;
	}

	TestMesh priorities(byte[] priorities)
	{
		this.priorities = priorities;
		return this;
	}

	TestMesh textures(short[] textures)
	{
		this.textures = textures;
		return this;
	}

	TestMesh textureCoords(byte[] textureCoords)
	{
		this.textureCoords = textureCoords;
		return this;
	}

	TestMesh groups(int[][] groups)
	{
		this.groups = groups;
		return this;
	}

	Mesh build()
	{
		return new Mesh(id, priority, vx, vy, vz, i1, i2, i3, colors, renderTypes, transparencies,
			priorities, textures, textureCoords, null, null, null, groups);
	}
}
