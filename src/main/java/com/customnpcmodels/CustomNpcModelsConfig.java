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

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigSection;

@ConfigGroup(CustomNpcModelsConfig.GROUP)
public interface CustomNpcModelsConfig extends Config
{
	String GROUP = "customnpcmodels";

	/** Also written programmatically, so the key is named rather than repeated as a literal. */
	String OVERRIDE_INTERACT_HIGHLIGHT = "overrideInteractHighlight";

	/**
	 * Which packs and models are switched off, and the order packs take priority in, each a
	 * comma-separated list. Set from the side panel rather than here, so they have no item of their
	 * own - packs are found at runtime, and a setting can't list them.
	 */
	String DISABLED_PACKS = "disabledPacks";
	String DISABLED_MODELS = "disabledModels";
	String PACK_ORDER = "packOrder";

	/**
	 * Read-only notice for users, not a setting.
	 */
	@ConfigItem(
		keyName = "gpuRequiredNotice",
		name = "<html><body style='width:170px'>This plugin requires the <b>GPU</b> or <b>117 HD</b>"
			+ " plugin to be enabled. Models are swapped as the scene is drawn, so nothing changes"
			+ " while neither is rendering, or while 117 HD's <b>Legacy renderer</b> option is on."
			+ "</body></html>",
		description = "Custom models are substituted while the GPU or 117 HD plugin renders the scene, so one must be enabled.",
		position = 0
	)
	default void gpuRequiredNotice()
	{
	}

	@ConfigItem(
		keyName = "enabled",
		name = "Show custom models",
		description = "Draw NPCs that have a custom model with it, in place of their usual one.",
		position = 1
	)
	default boolean enabled()
	{
		return true;
	}

	@ConfigSection(
		name = "Safety",
		description = "Safety settings to disable custom models in dangerous areas or worlds",
		position = 2,
		closedByDefault = true
	)
	String safetySection = "safetySection";

	@ConfigItem(
		keyName = "disablePvpWorld",
		name = "Disable on PvP worlds",
		description = "Disable custom models for all NPCs when on a PvP world.",
		section = safetySection,
		position = 1
	)
	default boolean disablePvpWorld()
	{
		return true;
	}

	@ConfigItem(
		keyName = "disableWilderness",
		name = "Disable in Wilderness",
		description = "Disable custom models for all NPCs while in the Wilderness.",
		section = safetySection,
		position = 2
	)
	default boolean disableWilderness()
	{
		return true;
	}

	@ConfigSection(
		name = "Compatibility",
		description = "Settings for working alongside other plugins",
		position = 3,
		closedByDefault = true
	)
	String compatibilitySection = "compatibilitySection";

	@ConfigItem(
		keyName = OVERRIDE_INTERACT_HIGHLIGHT,
		name = "Fix Interact Highlight outlines",
		description = "<html><body style='width:170px'>Draw the Interact Highlight plugin's NPC "
			+ "outlines around the custom model instead of the original one.<br><br>While this is on "
			+ "and models are being swapped, Interact Highlight's own <b>NPCs: Show on hover</b> "
			+ "and <b>Show on interact</b> are turned off and this plugin draws those outlines in "
			+ "their place, using that plugin's own colors and border settings. Both are turned back "
			+ "on when this plugin stops.</body></html>",
		section = compatibilitySection,
		position = 1
	)
	default boolean overrideInteractHighlight()
	{
		return false;
	}
}
