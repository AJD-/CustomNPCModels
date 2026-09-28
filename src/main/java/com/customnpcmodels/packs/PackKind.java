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

/**
 * Where a pack came from. It decides the pack's id, so packs of different kinds can never share
 * one - and with it, whether they are switched on.
 */
public enum PackKind
{
	/** The development bundle, only ever on the classpath under {@code ./gradlew run}. */
	DEV("Dev"),
	/** Installed from the Custom Model Hub. */
	HUB("Hub"),
	/** Put in the local packs folder by the user. */
	LOCAL("Local"),
	/** Shipped inside the plugin jar. */
	BUILTIN("Built-in");

	/** How the kind is shown beside a pack's name. */
	private final String label;

	PackKind(String label)
	{
		this.label = label;
	}

	public String getLabel()
	{
		return label;
	}

	/**
	 * The id of a pack of this kind. {@code folder} names a hub or local pack's folder, and is ignored
	 * for the two classpath packs, of which there is only ever one each.
	 */
	public String packId(String folder)
	{
		switch (this)
		{
			case DEV:
				return "dev";
			case BUILTIN:
				return "builtin";
			case HUB:
				return "hub:" + folder;
			default:
				return "local:" + folder;
		}
	}
}
