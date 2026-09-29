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

import java.util.Collections;
import java.util.List;

/**
 * A named, contiguous run of a mesh's faces - one of the models a multi-model NPC is merged from.
 * <p>
 * The merge appends each model's faces after the last's, so a model is always one run. The
 * writer gives each part a glTF mesh of its own, which Blender imports as its own object and the
 * painter can hide.
 */
final class MeshPart
{
	final String name;
	final int firstFace;
	final int faceCount;

	MeshPart(String name, int firstFace, int faceCount)
	{
		this.name = name;
		this.firstFace = firstFace;
		this.faceCount = faceCount;
	}

	/** The whole mesh as one part, which the writer lays out exactly as it did before parts existed. */
	static List<MeshPart> whole(int faceCount)
	{
		return Collections.singletonList(new MeshPart(null, 0, faceCount));
	}

	/** The name a merged NPC's part goes by, zero-padded so name order is part order. */
	static String name(int index, int modelId)
	{
		return String.format("part_%02d_model_%d", index, modelId);
	}

	@Override
	public String toString()
	{
		return name + ": faces " + firstFace + "-" + (firstFace + faceCount - 1);
	}
}
