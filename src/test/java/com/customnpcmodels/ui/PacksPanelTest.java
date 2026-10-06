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
package com.customnpcmodels.ui;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import com.customnpcmodels.packs.HubEntry;
import com.customnpcmodels.packs.PackKind;
import com.customnpcmodels.packs.PackView;
import java.awt.Component;
import java.awt.Container;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.JLabel;
import javax.swing.SwingUtilities;
import org.junit.Test;

/**
 * The panel keeps the cards a new snapshot does not change, so switching one pack does not clear and
 * rebuild the whole list.
 */
public class PacksPanelTest
{
	private static final PacksPanel.Actions NO_ACTIONS = new PacksPanel.Actions()
	{
		@Override
		public void setPackEnabled(String packId, boolean enabled)
		{
		}

		@Override
		public void setModelEnabled(String modelKey, boolean enabled)
		{
		}

		@Override
		public void setOrder(List<String> packIds)
		{
		}

		@Override
		public void importPack()
		{
		}

		@Override
		public void refresh()
		{
		}

		@Override
		public void loadHub()
		{
		}

		@Override
		public void installHubPack(HubEntry entry)
		{
		}

		@Override
		public void removeHubPack(String folder)
		{
		}

		@Override
		public void removeLocalPack(String folder)
		{
		}
	};

	private static PackView pack(String id, String name, boolean enabled)
	{
		return new PackView(id, name, PackKind.LOCAL, null, null, null, null, null, enabled, Collections.emptyList());
	}

	@Test
	public void testSwitchingOnePackKeepsTheOtherCards() throws Exception
	{
		AtomicReference<Exception> failure = new AtomicReference<>();
		SwingUtilities.invokeAndWait(() ->
		{
			try
			{
				PacksPanel panel = new PacksPanel(NO_ACTIONS);
				panel.setPacks(Arrays.asList(pack("local:a", "Alpha", true), pack("local:b", "Beta", true),
					pack("local:c", "Gamma", true)));
				JLabel alpha = nameLabel(panel, "Alpha");
				JLabel beta = nameLabel(panel, "Beta");
				JLabel gamma = nameLabel(panel, "Gamma");

				// What the plugin hands back after Beta is switched off
				panel.setPacks(Arrays.asList(pack("local:a", "Alpha", true), pack("local:b", "Beta", false),
					pack("local:c", "Gamma", true)));

				assertSame("an unchanged card is kept", alpha, nameLabel(panel, "Alpha"));
				assertSame("an unchanged card is kept", gamma, nameLabel(panel, "Gamma"));
				assertNotSame("the switched card shows its new state", beta, nameLabel(panel, "Beta"));
			}
			catch (Exception | AssertionError ex)
			{
				failure.set(ex instanceof Exception ? (Exception) ex : new Exception(ex));
			}
		});
		if (failure.get() != null)
		{
			throw failure.get();
		}
	}

	@Test
	public void testMovingAPackKeepsTheCardsWhoseEndsDoNotChange() throws Exception
	{
		AtomicReference<Exception> failure = new AtomicReference<>();
		SwingUtilities.invokeAndWait(() ->
		{
			try
			{
				PacksPanel panel = new PacksPanel(NO_ACTIONS);
				PackView a = pack("local:a", "Alpha", true);
				PackView b = pack("local:b", "Beta", true);
				PackView c = pack("local:c", "Gamma", true);
				PackView d = pack("local:d", "Delta", true);
				panel.setPacks(Arrays.asList(a, b, c, d));
				JLabel beta = nameLabel(panel, "Beta");
				JLabel gamma = nameLabel(panel, "Gamma");

				// Beta and Gamma swap places, and neither becomes first or last
				panel.setPacks(Arrays.asList(a, c, b, d));

				assertSame(beta, nameLabel(panel, "Beta"));
				assertSame(gamma, nameLabel(panel, "Gamma"));
			}
			catch (Exception | AssertionError ex)
			{
				failure.set(ex instanceof Exception ? (Exception) ex : new Exception(ex));
			}
		});
		if (failure.get() != null)
		{
			throw failure.get();
		}
	}

	/** The label showing a pack's name, found anywhere in the panel. */
	private static JLabel nameLabel(Container root, String name)
	{
		JLabel found = find(root, name);
		assertNotNull("no label for " + name, found);
		return found;
	}

	private static JLabel find(Container root, String name)
	{
		for (Component child : root.getComponents())
		{
			if (child instanceof JLabel && ((JLabel) child).getText() != null
				&& ((JLabel) child).getText().contains(">" + name + "<"))
			{
				return (JLabel) child;
			}
			if (child instanceof Container)
			{
				JLabel found = find((Container) child, name);
				if (found != null)
				{
					return found;
				}
			}
		}
		return null;
	}
}
