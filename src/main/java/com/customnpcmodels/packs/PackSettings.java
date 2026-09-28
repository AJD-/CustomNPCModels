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

import com.customnpcmodels.CustomNpcModelsConfig;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.client.config.ConfigManager;

/**
 * The user's {@link PackSelection}, kept in config. Each write posts a {@code ConfigChanged}, which
 * is what makes the plugin decide again which models are drawn - so the panel only ever writes here.
 */
@Singleton
public class PackSettings
{
	private final ConfigManager configManager;

	@Inject
	PackSettings(ConfigManager configManager)
	{
		this.configManager = configManager;
	}

	public PackSelection read()
	{
		return PackSelection.parse(get(CustomNpcModelsConfig.DISABLED_PACKS), get(CustomNpcModelsConfig.DISABLED_MODELS),
			get(CustomNpcModelsConfig.PACK_ORDER));
	}

	public void setPackEnabled(String packId, boolean enabled)
	{
		toggle(CustomNpcModelsConfig.DISABLED_PACKS, packId, enabled);
	}

	public void setModelEnabled(String modelKey, boolean enabled)
	{
		toggle(CustomNpcModelsConfig.DISABLED_MODELS, modelKey, enabled);
	}

	/** Every pack id, highest priority first. */
	public void setOrder(List<String> packIds)
	{
		write(CustomNpcModelsConfig.PACK_ORDER, packIds);
	}

	private void toggle(String key, String entry, boolean enabled)
	{
		// Held as what is off, so switching on is taking it out of the list
		Set<String> disabled = new LinkedHashSet<>(PackSelection.split(get(key)));
		if (enabled ? disabled.remove(entry) : disabled.add(entry))
		{
			write(key, disabled);
		}
	}

	private String get(String key)
	{
		return configManager.getConfiguration(CustomNpcModelsConfig.GROUP, key);
	}

	private void write(String key, Collection<String> entries)
	{
		if (entries.isEmpty())
		{
			// Rather than an empty value, so a profile with nothing switched off stores nothing
			configManager.unsetConfiguration(CustomNpcModelsConfig.GROUP, key);
		}
		else
		{
			configManager.setConfiguration(CustomNpcModelsConfig.GROUP, key, PackSelection.join(entries));
		}
	}
}
