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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import com.customnpcmodels.CustomNpcModelsConfig;
import java.util.Arrays;
import java.util.Collections;
import net.runelite.client.config.ConfigManager;
import org.junit.Test;

public class PackSelectionTest
{
	@Test
	public void testAStoredSelectionReadsBack()
	{
		PackSelection selection = PackSelection.parse("local:My Pack,hub:goblins", "local:x|1005779",
			"dev,local:My Pack");

		assertFalse(selection.isPackEnabled("local:My Pack"));
		assertFalse(selection.isPackEnabled("hub:goblins"));
		assertTrue(selection.isPackEnabled("builtin"));
		assertFalse(selection.isModelEnabled("local:x|1005779"));
		assertTrue(selection.isModelEnabled("local:x|1005780"));
		assertEquals(Arrays.asList("dev", "local:My Pack"), selection.getOrder());
	}

	/** Hand-edited or half-written config must not throw, nor switch anything off by accident. */
	@Test
	public void testBlankAndMessyValuesAreTolerated()
	{
		PackSelection selection = PackSelection.parse(null, "", " , ,local:a ,local:a,");

		assertTrue(selection.getDisabledPacks().isEmpty());
		assertTrue(selection.getDisabledModels().isEmpty());
		assertEquals("blanks and repeats are dropped", Collections.singletonList("local:a"), selection.getOrder());
	}

	@Test
	public void testAListRoundTrips()
	{
		assertEquals(Arrays.asList("dev", "local:My Pack", "builtin"),
			PackSelection.split(PackSelection.join(Arrays.asList("dev", "local:My Pack", "builtin"))));
	}

	@Test
	public void testSwitchingOffAndOnWritesTheList()
	{
		ConfigManager config = mock(ConfigManager.class);
		when(config.getConfiguration(CustomNpcModelsConfig.GROUP, CustomNpcModelsConfig.DISABLED_PACKS))
			.thenReturn("local:a");
		PackSettings settings = new PackSettings(config);

		settings.setPackEnabled("local:b", false);
		verify(config).setConfiguration(CustomNpcModelsConfig.GROUP, CustomNpcModelsConfig.DISABLED_PACKS, "local:a,local:b");

		// Nothing is left off once "local:a" is back on, so the key is removed rather than emptied
		settings.setPackEnabled("local:a", true);
		verify(config).unsetConfiguration(CustomNpcModelsConfig.GROUP, CustomNpcModelsConfig.DISABLED_PACKS);
	}

	@Test
	public void testAChangeThatChangesNothingWritesNothing()
	{
		ConfigManager config = mock(ConfigManager.class);
		PackSettings settings = new PackSettings(config);

		settings.setModelEnabled("local:a|1", true);

		verify(config, never()).setConfiguration(anyString(), anyString(), anyString());
		verify(config, never()).unsetConfiguration(anyString(), eq(CustomNpcModelsConfig.DISABLED_MODELS));
	}
}
