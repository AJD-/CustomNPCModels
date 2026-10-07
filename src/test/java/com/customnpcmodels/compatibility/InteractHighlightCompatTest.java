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
package com.customnpcmodels.compatibility;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import com.google.inject.Guice;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.config.ConfigProfile;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.plugins.PluginManager;
import org.junit.Test;

public class InteractHighlightCompatTest
{
	private final ConfigManager configManager = mock(ConfigManager.class);

	private final InteractHighlightCompat compat = Guice.createInjector(binder ->
	{
		binder.bind(ConfigManager.class).toInstance(configManager);
		binder.bind(PluginManager.class).toInstance(mock(PluginManager.class));
	}).getInstance(InteractHighlightCompat.class);

	private static ConfigProfile profile(long id)
	{
		ConfigProfile profile = mock(ConfigProfile.class);
		when(profile.getId()).thenReturn(id);
		return profile;
	}

	private static ConfigChanged hoverTicked()
	{
		ConfigChanged event = new ConfigChanged();
		event.setGroup("interacthighlight");
		event.setKey("npcShowHover");
		event.setOldValue("false");
		event.setNewValue("true");
		return event;
	}

	@Test
	public void testTickingTheSettingWhileSuppressedIsAnOverride()
	{
		ConfigProfile profile = profile(1);
		when(configManager.getProfile()).thenReturn(profile);
		compat.suppress();

		assertTrue(compat.isUserOverride(hoverTicked()));
	}

	/**
	 * A profile switch posts the new profile's saved values before ProfileChanged, after ConfigManager
	 * has moved to that profile. A saved true there is not the user turning the outlines back on.
	 */
	@Test
	public void testAProfileSwitchIsNotAnOverride()
	{
		ConfigProfile first = profile(1);
		ConfigProfile second = profile(2);
		when(configManager.getProfile()).thenReturn(first);
		compat.suppress();

		when(configManager.getProfile()).thenReturn(second);
		assertFalse(compat.isUserOverride(hoverTicked()));
	}
}
