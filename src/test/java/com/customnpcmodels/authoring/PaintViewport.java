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

/**
 * Draws a {@link GlbPaintDocument}'s mesh at rest in its current colors, lit the way the plugin
 * lights it, and says which face is under any pixel.
 */
final class PaintViewport extends ModelViewport
{
	private GlbPaintDocument document;

	/**
	 * @param ambient  the NPC definition's ambient byte, 0 when it has none
	 * @param contrast the NPC definition's contrast byte, 0 when it has none
	 */
	PaintViewport(GlbPaintDocument document, int ambient, int contrast)
	{
		super(document.mesh(), ambient, contrast);
		this.document = document;
		relight();
		frame();
	}

	/** Continues with a reloaded copy of the same file, whose faces are the same faces. */
	void setDocument(GlbPaintDocument document)
	{
		this.document = document;
		relight();
	}

	@Override
	protected short faceColor(int face)
	{
		return document.color(face);
	}
}
