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

import com.customnpcmodels.CustomDrawCallbacks;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.withSettings;
import java.util.function.Supplier;
import net.runelite.api.hooks.DrawCallbacks;
import org.junit.Test;

/**
 * Walking the stack of model-substituting decorators in the draw callbacks slot.
 *
 * <p>Retro NPC Swapper's decorator is only ever named here, never stubbed: a class declared under
 * its real name would sit on the {@code run} task's classpath, and the Hub plugin's parent-first
 * classloader could resolve Retro's own decorator to it. So the partner is covered by name through
 * {@link RendererChain#canUnwrap}, and chains are built from this plugin's real decorator.
 */
public class RendererChainTest
{
	private static final String RETRO = "com.retronpcswapper.RetroDrawCallbacks";

	private final DrawCallbacks renderer = mock(DrawCallbacks.class);

	private static CustomDrawCallbacks decorate(DrawCallbacks delegate)
	{
		return new CustomDrawCallbacks(delegate, (npc, vanilla) -> null);
	}

	@Test
	public void testRetroIsSeenThroughWhenItSuppliesItsDelegate()
	{
		assertTrue(RendererChain.canUnwrap(RETRO, mock(DrawCallbacks.class, withSettings().extraInterfaces(Supplier.class))));
	}

	@Test
	public void testARetroTooOldToSupplyItsDelegateIsNotSeenThrough()
	{
		// Retro NPC Swapper 2.2.3 and earlier: stand each other down, exactly as before
		assertFalse(RendererChain.canUnwrap(RETRO, mock(DrawCallbacks.class)));
	}

	@Test
	public void testAnUnknownSupplierIsNotSeenThrough()
	{
		assertFalse(RendererChain.canUnwrap("com.example.Decorator", mock(DrawCallbacks.class, withSettings().extraInterfaces(Supplier.class))));
	}

	@Test
	public void testThisPluginsDecoratorIsKnown()
	{
		assertTrue(RendererChain.canUnwrap(CustomDrawCallbacks.class.getName(), decorate(renderer)));
	}

	@Test
	public void testBaseIsTheRendererBeneathEveryDecorator()
	{
		assertSame(renderer, RendererChain.base(renderer));
		assertSame(renderer, RendererChain.base(decorate(renderer)));
		assertSame(renderer, RendererChain.base(decorate(decorate(renderer))));
		assertNull(RendererChain.base(null));
	}

	@Test
	public void testAnOverlongChainStopsWalking()
	{
		DrawCallbacks top = renderer;
		for (int i = 0; i < 50; i++)
		{
			top = decorate(top);
		}

		// Gives up at the depth cap rather than walking on, so this lands on a decorator
		assertTrue(RendererChain.base(top) instanceof CustomDrawCallbacks);
	}

	@Test
	public void testContainsFindsADecoratorAtAnyDepth()
	{
		CustomDrawCallbacks inner = decorate(renderer);
		CustomDrawCallbacks outer = decorate(inner);

		assertTrue(RendererChain.contains(outer, outer));
		assertTrue(RendererChain.contains(outer, inner));
		assertTrue(RendererChain.contains(outer, renderer));
		assertFalse(RendererChain.contains(inner, outer));
		assertFalse(RendererChain.contains(outer, decorate(renderer)));
		assertFalse(RendererChain.contains(outer, null));
		assertFalse(RendererChain.contains(null, inner));
	}

	@Test
	public void testFindReturnsTheTopmostDecoratorOfAType()
	{
		CustomDrawCallbacks inner = decorate(renderer);
		CustomDrawCallbacks outer = decorate(inner);

		assertSame(outer, RendererChain.find(outer, CustomDrawCallbacks.class));
		assertSame(inner, RendererChain.find(inner, CustomDrawCallbacks.class));
		assertNull(RendererChain.find(renderer, CustomDrawCallbacks.class));
	}
}
