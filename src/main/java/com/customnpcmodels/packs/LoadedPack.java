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
import lombok.Value;

/**
 * One pack as read from where it lives: its bundle, or why that could not be read. A pack that fails
 * to read is kept rather than dropped, so it can say what went wrong, and the others still load.
 */
@Value
public class LoadedPack
{
	PackInfo info;

	/** Null when the pack could not be read. */
	AssetBundle bundle;

	/** Why the pack could not be read, or null when it was. */
	String error;

	public static LoadedPack loaded(PackInfo info, AssetBundle bundle)
	{
		return new LoadedPack(info, bundle, null);
	}

	public static LoadedPack failed(PackInfo info, String error)
	{
		return new LoadedPack(info, null, error);
	}

	public static LoadedPack failed(PackInfo info, Exception cause)
	{
		return failed(info, describe(cause));
	}

	/**
	 * What went wrong, never null - a truncated file throws an EOFException with no message at all,
	 * which would otherwise leave a failed pack with nothing to say for itself.
	 */
	static String describe(Exception cause)
	{
		return cause.getMessage() != null ? cause.getMessage() : cause.toString();
	}

	public boolean isLoaded()
	{
		return bundle != null;
	}

	public String getId()
	{
		return info.getId();
	}
}
