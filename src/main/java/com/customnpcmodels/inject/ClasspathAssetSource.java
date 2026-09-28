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

import java.io.IOException;
import java.io.InputStream;

/**
 * Reads the bundle shipped inside the plugin jar, plus the development bundle when one is present.
 * <p>
 * A missing resource is treated as "no custom models" rather than an error, so a build without a
 * bundle starts cleanly and substitutes nothing.
 * <p>
 * The development bundle only ever exists on the test classpath - the generator writes it to
 * {@code src/test/resources}, which is gitignored - so {@code ./gradlew run} sees it and the Hub jar,
 * built from the main sourceSet alone, never can. It carries round-trip verification assets exported
 * from the live cache, which must not ship. Its entries win over the shipped bundle's.
 */
public class ClasspathAssetSource implements AssetSource
{
	private static final String RESOURCE = "/com/customnpcmodels/custom-assets.dat";
	private static final String DEV_RESOURCE = "/com/customnpcmodels/custom-assets-dev.dat";

	@Override
	public AssetBundle load() throws IOException
	{
		return read(RESOURCE).overlay(read(DEV_RESOURCE));
	}

	private static AssetBundle read(String resource) throws IOException
	{
		try (InputStream in = ClasspathAssetSource.class.getResourceAsStream(resource))
		{
			if (in == null)
			{
				return AssetBundle.empty();
			}

			return AssetCodec.read(in);
		}
	}
}
