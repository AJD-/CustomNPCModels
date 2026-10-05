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

import com.customnpcmodels.CustomNpcModelsConfig;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginManager;
import net.runelite.client.plugins.interacthighlight.InteractHighlightConfig;
import net.runelite.client.plugins.interacthighlight.InteractHighlightPlugin;

/**
 * Turns off Interact Highlight's own NPC outlines while this plugin draws them instead.
 * <p>
 * An outline has to be suppressed rather than painted over: the outline renderer writes straight
 * into the frame buffer, so a second overlay drawing later only adds a second outline. Interact
 * Highlight's overlay is package private and cannot be filtered or subclassed, but its config
 * interface is public, and it reads that config on every frame - so clearing the two NPC keys stops
 * it drawing NPCs on the next frame while it goes on drawing objects, ground items and players with
 * its own code.
 * <p>
 * The previous values are stashed in this plugin's own config group, not held in memory, so a
 * client that dies while suppressing is repaired on the next startup rather than leaving the user's
 * Interact Highlight settings off for good.
 */
@Singleton
@Slf4j
public class InteractHighlightCompat
{
	private static final String GROUP = "interacthighlight";
	private static final String SHOW_HOVER = "npcShowHover";
	private static final String SHOW_INTERACT = "npcShowInteract";

	private static final String STASH_PREFIX = "interactHighlightStash_";

	/** Distinguishes "the key had no saved value" from "the key was saved as false". */
	private static final String STASH_UNSET = "unset";

	@Inject
	private ConfigManager configManager;

	@Inject
	private PluginManager pluginManager;

	// Resolved lazily rather than bound with @Provides: a second Config binding in this plugin's
	// injector is what PluginManager#getPluginConfigProxy picks up, and the config panel would then
	// show Interact Highlight's settings under this plugin.
	private InteractHighlightConfig config;

	// The plugin list does not change identity, so this is resolved once
	private Plugin interactHighlightPlugin;
	private boolean resolved;

	private boolean suppressing;

	// Set while our own writes are in flight. ConfigChanged is posted synchronously from
	// setConfiguration, so this is enough to tell our writes from the user's.
	private boolean selfWrite;

	/**
	 * Whether the user wants NPC hover outlines, ignoring our own suppression of the setting.
	 * <p>
	 * While suppressing, the live value of these two keys is false because we put it there, so
	 * reading them back would answer "no outlines" and nothing would ever be drawn. The stash holds
	 * what the user actually chose, so it is the source of truth for as long as it exists.
	 */
	boolean npcShowHover()
	{
		return wanted(SHOW_HOVER, config().npcShowHover());
	}

	/**
	 * Whether the user wants NPC interact outlines. See {@link #npcShowHover()}.
	 */
	boolean npcShowInteract()
	{
		return wanted(SHOW_INTERACT, config().npcShowInteract());
	}

	private boolean wanted(String key, boolean live)
	{
		if (!suppressing)
		{
			return live;
		}

		String stashed = configManager.getConfiguration(CustomNpcModelsConfig.GROUP, STASH_PREFIX + key);
		if (stashed == null)
		{
			return live;
		}

		// Nothing was saved for the key before we wrote to it, so the plugin's own default stands
		return STASH_UNSET.equals(stashed) || Boolean.parseBoolean(stashed);
	}

	/**
	 * Interact Highlight's own settings, so the outlines drawn in its place use its colors and
	 * border sizing. Callers must not read {@code npcShowHover} or {@code npcShowInteract} from
	 * this - those two are suppressed while the takeover is live; use the accessors above.
	 */
	InteractHighlightConfig config()
	{
		if (config == null)
		{
			config = configManager.getConfig(InteractHighlightConfig.class);
		}
		return config;
	}

	public boolean isInteractHighlightPlugin(Plugin plugin)
	{
		return plugin instanceof InteractHighlightPlugin;
	}

	/**
	 * Whether the Interact Highlight plugin is running, and therefore whether its NPC outlines are
	 * the ones that need replacing.
	 */
	public boolean isInteractHighlightActive()
	{
		if (!resolved)
		{
			for (Plugin plugin : pluginManager.getPlugins())
			{
				if (isInteractHighlightPlugin(plugin))
				{
					interactHighlightPlugin = plugin;
					break;
				}
			}
			resolved = true;
		}

		return interactHighlightPlugin != null && pluginManager.isPluginActive(interactHighlightPlugin);
	}

	public void suppress()
	{
		if (suppressing)
		{
			return;
		}

		// Set before the stash is written: that write posts a ConfigChanged on our own group, which
		// comes straight back here, and re-reading the keys then would stash our own false values
		// over the user's real ones
		suppressing = true;

		if (configManager.getConfiguration(CustomNpcModelsConfig.GROUP, STASH_PREFIX + SHOW_HOVER) == null)
		{
			stash(SHOW_HOVER);
			stash(SHOW_INTERACT);
		}

		write(SHOW_HOVER, "false");
		write(SHOW_INTERACT, "false");
		log.debug("Suppressed Interact Highlight NPC outlines");
	}

	public void restore()
	{
		if (!suppressing)
		{
			return;
		}

		suppressing = false;
		unstash(SHOW_HOVER);
		unstash(SHOW_INTERACT);
		log.debug("Restored Interact Highlight NPC outlines");
	}

	/**
	 * Restores settings left suppressed by a session that did not shut down cleanly. Call before
	 * deciding whether to suppress again.
	 */
	public void restoreStaleStash()
	{
		if (configManager.getConfiguration(CustomNpcModelsConfig.GROUP, STASH_PREFIX + SHOW_HOVER) == null)
		{
			return;
		}

		log.debug("Restoring Interact Highlight settings stashed by a previous session");
		suppressing = true;
		restore();
	}

	/**
	 * Whether a config change is the user turning Interact Highlight's NPC outlines back on while
	 * we have them suppressed.
	 */
	public boolean isUserOverride(ConfigChanged event)
	{
		return !selfWrite
			&& suppressing
			&& GROUP.equals(event.getGroup())
			&& (SHOW_HOVER.equals(event.getKey()) || SHOW_INTERACT.equals(event.getKey()))
			&& "true".equals(event.getNewValue());
	}

	/**
	 * Hands Interact Highlight's NPC outlines back after the user re-enabled one of the settings
	 * themselves.
	 * <p>
	 * The key they just set keeps their value - writing the stash back over it would undo the
	 * choice they made. The other one is restored normally, so turning "show on hover" back on does
	 * not silently leave "show on interact" off.
	 * <p>
	 * This gives up the two foreign keys and nothing else: the caller must turn
	 * {@code overrideInteractHighlight} off afterward, which is what actually ends the takeover.
	 * Left on, the config still asks for a takeover this object is no longer suppressing for, and
	 * both plugins draw their outlines.
	 */
	public void optOut(String changedKey)
	{
		discard(changedKey);

		String other = SHOW_HOVER.equals(changedKey) ? SHOW_INTERACT : SHOW_HOVER;
		unstash(other);
		suppressing = false;
	}

	/**
	 * Drops the in-memory suppression state without writing anything.
	 * <p>
	 * For a profile switch: the config that was suppressed belongs to the profile we just left
	 * and is no longer addressable, so writing a restore now would push the old profile's values
	 * into the new one. The stash left behind there is repaired by {@link #restoreStaleStash()} the
	 * next time that profile is active.
	 */
	public void forget()
	{
		suppressing = false;
	}

	public static boolean isStashKey(String key)
	{
		return key != null && key.startsWith(STASH_PREFIX);
	}

	private void stash(String key)
	{
		String current = configManager.getConfiguration(GROUP, key);
		configManager.setConfiguration(CustomNpcModelsConfig.GROUP, STASH_PREFIX + key,
			current == null ? STASH_UNSET : current);
	}

	private void unstash(String key)
	{
		String stashed = configManager.getConfiguration(CustomNpcModelsConfig.GROUP, STASH_PREFIX + key);
		if (stashed == null)
		{
			// Nothing was ever stashed, so there is nothing to put back
			return;
		}

		selfWrite = true;
		try
		{
			if (STASH_UNSET.equals(stashed))
			{
				configManager.unsetConfiguration(GROUP, key);
			}
			else
			{
				configManager.setConfiguration(GROUP, key, stashed);
			}
		}
		finally
		{
			selfWrite = false;
		}

		discard(key);
	}

	private void write(String key, String value)
	{
		selfWrite = true;
		try
		{
			configManager.setConfiguration(GROUP, key, value);
		}
		finally
		{
			selfWrite = false;
		}
	}

	private void discard(String key)
	{
		configManager.unsetConfiguration(CustomNpcModelsConfig.GROUP, STASH_PREFIX + key);
	}
}
