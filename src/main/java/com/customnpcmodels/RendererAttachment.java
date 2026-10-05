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

import com.customnpcmodels.compatibility.RendererChain;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.hooks.DrawCallbacks;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginManager;
import net.runelite.client.plugins.gpu.GpuPlugin;

/**
 * Keeps {@link CustomDrawCallbacks} in the client's draw callbacks slot, over whichever supported
 * renderer holds it. Client thread only.
 */
@Singleton
@Slf4j
class RendererAttachment
{
	/** The renderer 117 HD registers by default; its legacy renderer lives in a sibling package. */
	private static final String HD_ZONE_RENDERER_PACKAGE = "rs117.hd.renderer.zone.";

	private static final String HD_PLUGIN_CLASS = "rs117.hd.HdPlugin";

	@Inject
	private Client client;

	@Inject
	private PluginManager pluginManager;

	@Inject
	private ModelCache modelCache;

	// Our decorator, while it is in the client's draw callbacks chain
	private CustomDrawCallbacks wrapper;

	// Class of the last renderer attach() declined, so the decline is logged once rather than per tick
	private String declinedHost;

	// Resolved once - the plugin list does not change identity, and attach() is polled per tick
	private Plugin gpuPlugin;

	/** Whether our decorator is in the draw callbacks chain, so NPCs can be drawn custom at all. */
	boolean isAttached()
	{
		return wrapper != null;
	}

	/** Whether our decorator has fallen out of the chain, or was never in it. */
	boolean isLost()
	{
		return wrapper == null || !RendererChain.contains(client.getDrawCallbacks(), wrapper);
	}

	/**
	 * Takes over the client's draw callbacks slot by wrapping whichever supported renderer holds it:
	 * the GPU plugin, or 117 HD's zone renderer - directly, or beneath Retro NPC Swapper's decorator.
	 * <p>
	 * Declines for anything else - no renderer at all, an unknown one, 117 HD's legacy renderer
	 * (which implements no {@code drawTemp}), or a decorator it cannot see through, including a Retro
	 * NPC Swapper too old to stack. Which of the two stacks on top does not matter: Retro leaves the
	 * NPCs this plugin claims alone, so each NPC is only ever swapped by one of them.
	 *
	 * @return whether this attached or detached us. Only a real change is worth acting on - this is
	 * polled every tick while detached.
	 */
	boolean attach()
	{
		DrawCallbacks current = client.getDrawCallbacks();
		if (RendererChain.contains(current, wrapper))
		{
			return false;
		}

		// We are not in the chain anymore; drop the stale reference before deciding whether we can
		// retake the slot
		boolean wasAttached = wrapper != null;
		wrapper = null;

		// Left beneath Retro NPC Swapper by an earlier detach, which could not unlink it from under
		// another plugin's decorator. Picking it back up keeps a second one from stacking on top.
		CustomDrawCallbacks buried = RendererChain.find(current, CustomDrawCallbacks.class);
		if (buried != null)
		{
			wrapper = buried;
			declinedHost = null;
			log.debug("Re-adopted custom draw callbacks beneath {}", current.getClass().getName());
		}
		else if (isSupportedHost(current, findGpuPlugin()))
		{
			CustomDrawCallbacks callbacks = new CustomDrawCallbacks(current, modelCache::pose);
			client.setDrawCallbacks(callbacks);
			wrapper = callbacks;
			declinedHost = null;
			log.debug("Attached custom draw callbacks over {}", current.getClass().getName());
		}
		else if (current != null && !current.getClass().getName().equals(declinedHost))
		{
			// Polled every tick while detached, so only said once per renderer
			declinedHost = current.getClass().getName();
			log.debug("Draw callbacks held by unsupported renderer {}; skipping model swap", declinedHost);
		}

		boolean attached = wrapper != null;
		return attached != wasAttached;
	}

	/** Hands the draw callbacks slot back to the renderer. */
	void detach()
	{
		if (wrapper != null && client.getDrawCallbacks() == wrapper)
		{
			// Restore the delegate, never null - nulling the slot would leave the renderer running
			// with no callbacks registered
			client.setDrawCallbacks(wrapper.get());
			log.debug("Detached custom draw callbacks");
		}
		else if (wrapper != null)
		{
			// Something else holds the slot. If it wrapped our wrapper - Retro NPC Swapper does - that
			// decorator stays in its chain, only forwarding once the cache's memo is cleared, and the
			// next attach() picks it back up.
			log.debug("Draw callbacks slot no longer ours at detach; leaving it untouched");
		}
		wrapper = null;
	}

	/**
	 * Whether {@code current} is a renderer {@link CustomDrawCallbacks} can substitute models through,
	 * either itself or beneath Retro NPC Swapper's decorator.
	 * <p>
	 * 117 HD is a Plugin Hub plugin loaded in its own classloader, so it is recognized by class
	 * name alone - there is no compile or runtime dependency on it. The zone renderer package is
	 * allowlisted rather than the legacy one denylisted, so a renderer 117 HD adds or renames later
	 * is declined instead of wrapped blind.
	 */
	static boolean isSupportedHost(DrawCallbacks current, Plugin gpu)
	{
		DrawCallbacks renderer = RendererChain.base(current);
		return renderer != null && (renderer == gpu || isHdZoneRenderer(renderer.getClass().getName()));
	}

	static boolean isHdZoneRenderer(String className)
	{
		return className.startsWith(HD_ZONE_RENDERER_PACKAGE);
	}

	/** Whether {@code plugin} is a renderer this attaches over, whose start or stop moves the slot. */
	static boolean isRendererPlugin(Plugin plugin)
	{
		return plugin instanceof GpuPlugin
			|| (plugin != null && HD_PLUGIN_CLASS.equals(plugin.getClass().getName()));
	}

	private Plugin findGpuPlugin()
	{
		if (gpuPlugin == null)
		{
			for (Plugin plugin : pluginManager.getPlugins())
			{
				if (plugin instanceof GpuPlugin)
				{
					gpuPlugin = plugin;
					break;
				}
			}
		}
		return gpuPlugin;
	}
}
