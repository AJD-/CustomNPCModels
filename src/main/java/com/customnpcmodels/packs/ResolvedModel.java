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
package com.customnpcmodels.packs;

import com.customnpcmodels.inject.AssetBundle;
import com.customnpcmodels.inject.NpcBinding;
import lombok.Getter;

/**
 * The model an NPC id is drawn with, and the pack it comes from. Everything it is built and posed
 * from is looked up in {@link #source}, its own pack's bundle, so ids another pack reuses - meshes,
 * rigs, the sequences clips answer for - can never reach it.
 */
@Getter
public final class ResolvedModel
{
	private final String packId;

	/** Identifies this model across every pack, for switching it off; see {@link PackComposer#modelKey}. */
	private final String modelKey;

	private final NpcBinding binding;

	private final AssetBundle source;

	public ResolvedModel(String packId, String modelKey, NpcBinding binding, AssetBundle source)
	{
		this.packId = packId;
		this.modelKey = modelKey;
		this.binding = binding;
		this.source = source;
	}

	/**
	 * Whether {@code other} would build the same model: the same binding from the same read of the
	 * same pack. A pack read again is a new bundle, and so never the same.
	 */
	public boolean isSameAs(ResolvedModel other)
	{
		return other != null && other.binding == binding && other.source == source;
	}
}
