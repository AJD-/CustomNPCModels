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

import com.customnpcmodels.compatibility.ModelSwapProtocol;
import java.util.Collections;
import java.util.Set;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.plugins.Plugin;

/**
 * Tells Retro NPC Swapper which NPC ids this plugin is drawing, so it leaves them alone. Client
 * thread only, except where said.
 */
@Singleton
@Slf4j
class RetroClaims
{
	/** Another Hub plugin, so recognized by class name alone like 117 HD. */
	private static final String RETRO_PLUGIN_CLASS = "com.retronpcswapper.RetroNpcSwapperPlugin";

	@Inject
	private EventBus eventBus;

	@Inject
	private ModelCache modelCache;

	// The NPC ids last claimed, so an unchanged set is not posted again
	private Set<Integer> posted = Collections.emptySet();

	/**
	 * Claims every bound NPC while custom models can be drawn at all, and none while they can't (off,
	 * detached, or stood down by a safety setting) so Retro can have them back. Posted only when the
	 * set changes, unless {@code always}.
	 */
	void publish(boolean drawing, boolean always)
	{
		Set<Integer> claims = drawing ? modelCache.boundNpcIds() : Collections.emptySet();

		if (!always && claims.equals(posted))
		{
			return;
		}

		posted = claims;
		log.debug("Claiming {} NPC id(s) for custom models", claims.size());
		eventBus.post(ModelSwapProtocol.claimsMessage(claims));
	}

	/**
	 * Gives every NPC back at once, as the plugin stops. Nothing is computed, so any thread will do,
	 * which is what lets Retro take them back even if queued client thread work never runs.
	 */
	void withdraw()
	{
		eventBus.post(ModelSwapProtocol.claimsMessage(Collections.emptySet()));
	}

	/** Forgets what was posted, so the next session starts from nothing. */
	void clear()
	{
		posted = Collections.emptySet();
	}

	/** Whether {@code plugin} is Retro NPC Swapper. Any thread. */
	static boolean isRetroPlugin(Plugin plugin)
	{
		return plugin != null && RETRO_PLUGIN_CLASS.equals(plugin.getClass().getName());
	}
}
