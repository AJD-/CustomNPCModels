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

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

/** Undo and redo for painting. Each entry is one stroke or fill, as the faces it changed. */
final class PaintHistory
{
	/** One face's change. */
	static final class Change
	{
		final int face;
		final short before;
		final short after;

		Change(int face, short before, short after)
		{
			this.face = face;
			this.before = before;
			this.after = after;
		}
	}

	/** Where a change is applied: paints one face one color. */
	@FunctionalInterface
	interface Canvas
	{
		void paint(int face, short color);
	}

	private final Deque<List<Change>> undo = new ArrayDeque<>();
	private final Deque<List<Change>> redo = new ArrayDeque<>();

	/** Records a finished stroke. Whatever was undone before it can no longer be redone. */
	void record(List<Change> stroke)
	{
		undo.push(stroke);
		redo.clear();
	}

	/** Takes back the last stroke, last face first. @return false when there is nothing to undo */
	boolean undo(Canvas canvas)
	{
		if (undo.isEmpty())
		{
			return false;
		}
		List<Change> changes = undo.pop();
		for (int i = changes.size() - 1; i >= 0; i--)
		{
			canvas.paint(changes.get(i).face, changes.get(i).before);
		}
		redo.push(changes);
		return true;
	}

	/** Puts back the last stroke undone. @return false when there is nothing to redo */
	boolean redo(Canvas canvas)
	{
		if (redo.isEmpty())
		{
			return false;
		}
		List<Change> changes = redo.pop();
		for (Change change : changes)
		{
			canvas.paint(change.face, change.after);
		}
		undo.push(changes);
		return true;
	}
}
