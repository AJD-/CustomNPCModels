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
 * A skeleton, in the engine's own terms: an ordered list of transforms, each naming the vertex
 * groups it moves.
 *
 * <p>There is no parent/child hierarchy here. Nesting is expressed by membership instead - a
 * transform that should carry a limb and everything attached to it simply lists every one of those
 * groups. Anything authored from a modern rig has to be flattened the same way, with a parent's
 * group set being the union of its own and all its descendants'.
 *
 * <p>Corresponds to {@code FramemapDefinition} in the cache library.
 */
public final class Rig
{
	@Getter
	private final int id;
	private final int[] types;
	private final int[][] groups;

	public Rig(int id, int[] types, int[][] groups)
	{
		this.id = id;
		this.types = types;
		this.groups = groups;
	}

	/**
	 * Transform type per entry: 0 pivot, 1 translate, 2 rotate, 3 scale, 5 alpha.
	 */
	public int getType(int transform)
	{
		return types[transform];
	}

	/**
	 * The vertex groups a transform moves. Returned directly rather than copied - this is read once
	 * per transform per frame per NPC, and callers must not mutate it.
	 */
	public int[] getGroups(int transform)
	{
		return groups[transform];
	}

	public int getTransformCount()
	{
		return types.length;
	}

	int[] getTypes()
	{
		return types;
	}

	int[][] getAllGroups()
	{
		return groups;
	}
}
