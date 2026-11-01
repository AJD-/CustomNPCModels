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
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import java.util.function.Function;
import net.runelite.api.Model;
import net.runelite.api.NPC;
import org.junit.Before;
import org.junit.Test;

/**
 * What each partner said about Interact Highlight's NPC outlines, and whose poser draws an NPC.
 */
public class PartnerOutlinesTest
{
	private static final String SERVANTS = ModelSwapProtocol.SOURCE_CUSTOM_SERVANTS;
	private static final String RETRO = ModelSwapProtocol.SOURCE_RETRO_NPC_SWAPPER;

	private PartnerOutlines outlines;
	private Function<NPC, Model> servantsPoser;
	private Function<NPC, Model> retroPoser;
	private NPC npc;
	private Model servantsModel;
	private Model retroModel;

	@Before
	@SuppressWarnings("unchecked")
	public void setUp()
	{
		outlines = new PartnerOutlines();
		servantsPoser = mock(Function.class);
		retroPoser = mock(Function.class);
		npc = mock(NPC.class);
		servantsModel = mock(Model.class);
		retroModel = mock(Model.class);
	}

	private static ModelSwapProtocol.Outlines state(boolean owning, Function<NPC, Model> poser)
	{
		return new ModelSwapProtocol.Outlines(owning, poser);
	}

	@Test
	public void testNobodyOwnsAtFirst()
	{
		assertFalse(outlines.isAnyOwning());
		assertFalse(outlines.isOwning(SERVANTS));
		assertFalse(outlines.isOwning(RETRO));
		assertNull(outlines.pose(npc));
	}

	@Test
	public void testAnyOwningWhenEitherPartnerOwns()
	{
		outlines.accept(SERVANTS, state(true, null));
		assertTrue(outlines.isAnyOwning());
		assertTrue(outlines.isOwning(SERVANTS));
		assertFalse(outlines.isOwning(RETRO));

		outlines.forget(SERVANTS);
		outlines.accept(RETRO, state(true, null));
		assertTrue(outlines.isAnyOwning());
		assertTrue(outlines.isOwning(RETRO));
		assertFalse(outlines.isOwning(SERVANTS));
	}

	@Test
	public void testNotAnyOwningWhenNeitherOwns()
	{
		outlines.accept(SERVANTS, state(false, servantsPoser));
		outlines.accept(RETRO, state(false, retroPoser));

		assertFalse(outlines.isAnyOwning());
	}

	@Test
	public void testForgettingOnePartnerLeavesTheOther()
	{
		outlines.accept(SERVANTS, state(true, servantsPoser));
		outlines.accept(RETRO, state(true, retroPoser));
		when(retroPoser.apply(npc)).thenReturn(retroModel);

		outlines.forget(SERVANTS);

		assertFalse(outlines.isOwning(SERVANTS));
		assertTrue(outlines.isOwning(RETRO));
		assertTrue(outlines.isAnyOwning());
		assertNull(outlines.pose(SERVANTS, npc));
		assertSame(retroModel, outlines.pose(npc));
		verifyNoInteractions(servantsPoser);
	}

	@Test
	public void testALaterMessageReplacesTheEarlierOne()
	{
		outlines.accept(SERVANTS, state(true, servantsPoser));
		outlines.accept(SERVANTS, state(false, servantsPoser));

		assertFalse(outlines.isOwning(SERVANTS));
	}

	@Test
	public void testANullPoserWithdrawsTheEarlierOne()
	{
		outlines.accept(RETRO, state(true, retroPoser));
		when(retroPoser.apply(npc)).thenReturn(retroModel);
		assertSame(retroModel, outlines.pose(RETRO, npc));

		outlines.accept(RETRO, state(true, null));

		assertNull(outlines.pose(RETRO, npc));
		assertNull(outlines.pose(npc));
		assertTrue(outlines.isOwning(RETRO));
	}

	@Test
	public void testCustomServantsModelWinsWhenBothPose()
	{
		outlines.accept(SERVANTS, state(false, servantsPoser));
		outlines.accept(RETRO, state(true, retroPoser));
		when(servantsPoser.apply(npc)).thenReturn(servantsModel);
		when(retroPoser.apply(npc)).thenReturn(retroModel);

		assertSame(servantsModel, outlines.pose(npc));
		verify(retroPoser, never()).apply(npc);
	}

	@Test
	public void testRetroModelIsUsedWhenCustomServantsDrawsNoneOrThrows()
	{
		outlines.accept(SERVANTS, state(false, servantsPoser));
		outlines.accept(RETRO, state(false, retroPoser));
		when(retroPoser.apply(npc)).thenReturn(retroModel);

		when(servantsPoser.apply(npc)).thenReturn(null);
		assertSame(retroModel, outlines.pose(npc));

		when(servantsPoser.apply(npc)).thenThrow(new IllegalStateException("partner bug"));
		assertSame(retroModel, outlines.pose(npc));
	}

	@Test
	public void testPosingOneSourceAsksOnlyThatSource()
	{
		outlines.accept(SERVANTS, state(false, servantsPoser));
		outlines.accept(RETRO, state(false, retroPoser));
		when(retroPoser.apply(npc)).thenReturn(retroModel);

		assertSame(retroModel, outlines.pose(RETRO, npc));
		verify(retroPoser).apply(npc);
		verifyNoInteractions(servantsPoser);
	}

	@Test
	public void testOurOwnAndUnknownSourcesAreIgnored()
	{
		String self = ModelSwapProtocol.SOURCE_CUSTOM_NPC_MODELS;
		outlines.accept(self, state(true, servantsPoser));
		outlines.accept("someone-else", state(true, servantsPoser));
		outlines.accept(null, state(true, servantsPoser));
		outlines.accept(SERVANTS, null);
		outlines.forget(null);

		assertFalse(outlines.isOwning(self));
		assertFalse(outlines.isOwning("someone-else"));
		assertFalse(outlines.isOwning(null));
		assertFalse(outlines.isAnyOwning());
		assertNull(outlines.pose(self, npc));
		assertNull(outlines.pose(null, npc));
		assertNull(outlines.pose(npc));
		verifyNoInteractions(servantsPoser);
	}

	@Test
	public void testResetForgetsEverything()
	{
		outlines.accept(SERVANTS, state(true, servantsPoser));
		outlines.accept(RETRO, state(true, retroPoser));
		outlines.setHandshakeDone();

		outlines.reset();

		assertFalse(outlines.isAnyOwning());
		assertNull(outlines.pose(npc));
		assertFalse(outlines.isHandshakeDone());
		verifyNoInteractions(servantsPoser, retroPoser);
	}
}
