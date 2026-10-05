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
package com.customnpcmodels;

import com.customnpcmodels.ui.PacksPanel;
import java.util.function.Consumer;
import javax.inject.Singleton;
import javax.swing.SwingUtilities;

/**
 * The side panel, as anything outside the EDT reaches it: only while it exists, and only on the
 * EDT. It is made in startUp and dropped in shutDown, and work coming back from other threads may
 * outlive it.
 */
@Singleton
class PanelBridge
{
	/** Read from OkHttp threads, the executor and the EDT, so volatile. */
	private volatile PacksPanel panel;

	void set(PacksPanel panel)
	{
		this.panel = panel;
	}

	/** The panel, or null when there is none. For the EDT, which can use it straight away. */
	PacksPanel get()
	{
		return panel;
	}

	/** Runs {@code update} on the panel, on the EDT, if the panel is still there by then. Any thread. */
	void update(Consumer<PacksPanel> update)
	{
		SwingUtilities.invokeLater(() ->
		{
			PacksPanel shown = panel;
			if (shown != null)
			{
				update.accept(shown);
			}
		});
	}

	/** Shows a message in the panel, in red when it is an error. Any thread. */
	void showStatus(String message, boolean error)
	{
		update(shown -> shown.showStatus(message, error));
	}
}
