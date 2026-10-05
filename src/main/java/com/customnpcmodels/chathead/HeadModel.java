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

import com.customnpcmodels.inject.AssetBundle;
import com.customnpcmodels.inject.Clip;
import com.customnpcmodels.inject.Mesh;
import com.customnpcmodels.inject.Rig;
import lombok.Getter;

/**
 * A pack's own chathead, built for drawing: its mesh, the rig its emotes are keyed by, and its
 * colors lit once at rest, as the client lights an interface model.
 */
@Getter
public final class HeadModel
{
	private final Mesh mesh;

	/** Null for a head with no skeleton, which only ever draws at rest. */
	private final Rig rig;
	private final int rigId;
	private final AssetBundle source;
	private final int[] lit1;
	private final int[] lit2;
	private final int[] lit3;

	/** How far the head reaches above its origin, which the client centers it by. */
	private final int height;

	public HeadModel(Mesh mesh, Rig rig, int rigId, AssetBundle source, int[] lit1, int[] lit2, int[] lit3)
	{
		this.mesh = mesh;
		this.rig = rig;
		this.rigId = rigId;
		this.source = source;
		this.lit1 = lit1;
		this.lit2 = lit2;
		this.lit3 = lit3;
		float top = 0;
		for (int v = 0; v < mesh.getVerticesCount(); v++)
		{
			top = Math.max(top, -mesh.getVerticesY()[v]);
		}
		this.height = (int) Math.ceil(top);
	}

	/** The head's clip for an emote, or null when it has none. */
	public Clip clip(int sequenceId)
	{
		return rig == null ? null : source.getClip(rigId, sequenceId);
	}
}
