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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;
import java.util.function.Supplier;
import net.runelite.api.hooks.DrawCallbacks;
import net.runelite.client.plugins.Plugin;
import org.junit.Test;

/**
 * Which slot holders the draw callbacks decorator is allowed to wrap.
 *
 * <p>117 HD is matched by class name, so it is tested with names rather than stand-in classes.
 * A stub declared under 117 HD's real package would sit on the {@code run} task's classpath, and
 * the Hub plugin's parent-first classloader could resolve 117 HD's own renderer to it.
 */
public class RendererHostTest
{
	private final Plugin gpu = gpuPlugin();

	/** Stands in for GpuPlugin: a plugin that registers itself as the draw callbacks. */
	private static Plugin gpuPlugin()
	{
		return mock(Plugin.class, withSettings().extraInterfaces(DrawCallbacks.class));
	}

	private static DrawCallbacks decorate(DrawCallbacks delegate)
	{
		return new CustomDrawCallbacks(delegate, (npc, vanilla) -> null);
	}

	@Test
	public void testTheGpuPluginIsWrapped()
	{
		assertTrue(CustomNpcModelsPlugin.isSupportedHost((DrawCallbacks) gpu, gpu));
	}

	@Test
	public void testAGpuPluginNotHoldingTheSlotIsNotEnough()
	{
		assertFalse(CustomNpcModelsPlugin.isSupportedHost((DrawCallbacks) gpuPlugin(), gpu));
	}

	@Test
	public void testAnUnknownRendererIsDeclined()
	{
		assertFalse(CustomNpcModelsPlugin.isSupportedHost(mock(DrawCallbacks.class), gpu));
		assertFalse(CustomNpcModelsPlugin.isSupportedHost(mock(DrawCallbacks.class), null));
	}

	@Test
	public void testAnEmptySlotIsDeclined()
	{
		assertFalse(CustomNpcModelsPlugin.isSupportedHost(null, gpu));
		assertFalse(CustomNpcModelsPlugin.isSupportedHost(null, null));
	}

	@Test
	public void testTheHdZoneRendererIsRecognised()
	{
		assertTrue(CustomNpcModelsPlugin.isHdZoneRenderer("rs117.hd.renderer.zone.ZoneRenderer"));
	}

	@Test
	public void testTheHdLegacyRendererIsDeclined()
	{
		// No drawTemp, no ZBUF: wrapping it would swap nothing, so there is nothing to attach to
		assertFalse(CustomNpcModelsPlugin.isHdZoneRenderer("rs117.hd.renderer.legacy.LegacyRenderer"));
	}

	@Test
	public void testOtherHdClassesAreDeclined()
	{
		// The plugin itself never holds the slot, and a lookalike package must not slip through
		assertFalse(CustomNpcModelsPlugin.isHdZoneRenderer("rs117.hd.HdPlugin"));
		assertFalse(CustomNpcModelsPlugin.isHdZoneRenderer("rs117.hd.renderer.zoned.Renderer"));
	}

	@Test
	public void testTheGpuPluginBeneathAKnownDecoratorIsWrapped()
	{
		assertTrue(CustomNpcModelsPlugin.isSupportedHost(decorate((DrawCallbacks) gpu), gpu));
		assertTrue(CustomNpcModelsPlugin.isSupportedHost(decorate(decorate((DrawCallbacks) gpu)), gpu));
	}

	@Test
	public void testAnUnknownRendererBeneathAKnownDecoratorIsDeclined()
	{
		assertFalse(CustomNpcModelsPlugin.isSupportedHost(decorate(mock(DrawCallbacks.class)), gpu));
	}

	@Test
	public void testAnUnknownDecoratorIsNotSeenThrough()
	{
		// Supplies the GPU plugin, but nothing says it forwards everything else to it
		DrawCallbacks unknown = mock(DrawCallbacks.class, withSettings().extraInterfaces(Supplier.class));
		when(((Supplier<?>) unknown).get()).thenReturn(gpu);

		assertFalse(CustomNpcModelsPlugin.isSupportedHost(unknown, gpu));
		assertSame(unknown, RendererChain.base(unknown));
	}
}
