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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import com.customnpcmodels.ChatheadSwapper.Head;
import com.customnpcmodels.ChatheadSwapper.Swap;
import com.customnpcmodels.inject.NpcBinding;
import java.util.function.IntUnaryOperator;
import net.runelite.api.gameval.NpcID;
import net.runelite.api.widgets.WidgetModelType;
import org.junit.Test;

public class ChatheadSwapperTest
{
	private static final int MAID = NpcID.POH_SERVANT_WAITER_WOMAN;
	private static final int VYREWATCH = NpcID.SANG_MYQ3_FEMALE_WALK_VYREWATCH_1;
	private static final int MAID_INDEX = 42;

	/** The Maid's model names the Vyrewatch's chathead; nothing else names one. */
	private static final IntUnaryOperator MAID_SWAPPED = npcId -> npcId == MAID ? VYREWATCH : NpcBinding.NO_CHATHEAD;
	private static final IntUnaryOperator NOTHING_SWAPPED = npcId -> NpcBinding.NO_CHATHEAD;
	private static final IntUnaryOperator MAID_AT_INDEX = index -> index == MAID_INDEX ? MAID : -1;

	private static void assertShows(Head head, int type, int id)
	{
		assertEquals(type, head.getType());
		assertEquals(id, head.getId());
	}

	@Test
	public void testAnNpcsOwnHeadIsSwapped()
	{
		Head head = ChatheadSwapper.decide(WidgetModelType.NPC_CHATHEAD, MAID, null, MAID_AT_INDEX, MAID_SWAPPED);

		assertShows(head, WidgetModelType.NPC_CHATHEAD, VYREWATCH);
		assertEquals(new Swap(WidgetModelType.NPC_CHATHEAD, MAID, MAID, VYREWATCH), head.getShown());
	}

	@Test
	public void testAHeadByNpcIndexIsSwappedToTheNpcsChathead()
	{
		Head head = ChatheadSwapper.decide(WidgetModelType.NPC_INDEX_CHATHEAD, MAID_INDEX, null, MAID_AT_INDEX,
			MAID_SWAPPED);

		assertShows(head, WidgetModelType.NPC_CHATHEAD, VYREWATCH);
		assertEquals(new Swap(WidgetModelType.NPC_INDEX_CHATHEAD, MAID_INDEX, MAID, VYREWATCH), head.getShown());
	}

	@Test
	public void testAnNpcWithoutAChatheadKeepsItsOwn()
	{
		Head head = ChatheadSwapper.decide(WidgetModelType.NPC_CHATHEAD, MAID, null, MAID_AT_INDEX, NOTHING_SWAPPED);

		assertShows(head, WidgetModelType.NPC_CHATHEAD, MAID);
		assertNull(head.getShown());
	}

	@Test
	public void testAnIndexWithNoNpcIsLeftAlone()
	{
		Head head = ChatheadSwapper.decide(WidgetModelType.NPC_INDEX_CHATHEAD, MAID_INDEX + 1, null, MAID_AT_INDEX,
			MAID_SWAPPED);

		assertShows(head, WidgetModelType.NPC_INDEX_CHATHEAD, MAID_INDEX + 1);
		assertNull(head.getShown());
	}

	@Test
	public void testOtherKindsOfHeadAreLeftAlone()
	{
		for (int type : new int[]{WidgetModelType.LOCAL_PLAYER_CHATHEAD, WidgetModelType.MODEL, WidgetModelType.ITEM})
		{
			// A model or item whose id happens to match a swapped NPC is not that NPC
			Head head = ChatheadSwapper.decide(type, MAID, null, MAID_AT_INDEX, MAID_SWAPPED);

			assertShows(head, type, MAID);
			assertNull(head.getShown());
		}
	}

	@Test
	public void testASwappedHeadIsKeptWhileItsModelStillNamesIt()
	{
		Swap swap = new Swap(WidgetModelType.NPC_CHATHEAD, MAID, MAID, VYREWATCH);

		Head head = ChatheadSwapper.decide(WidgetModelType.NPC_CHATHEAD, VYREWATCH, swap, MAID_AT_INDEX, MAID_SWAPPED);

		assertShows(head, WidgetModelType.NPC_CHATHEAD, VYREWATCH);
		assertEquals(swap, head.getShown());
	}

	/** The model switched off, the setting turned off, or a safety setting stepping in. */
	@Test
	public void testASwappedHeadGoesBackWhenItsModelNoLongerNamesIt()
	{
		Swap byIndex = new Swap(WidgetModelType.NPC_INDEX_CHATHEAD, MAID_INDEX, MAID, VYREWATCH);

		Head head = ChatheadSwapper.decide(WidgetModelType.NPC_CHATHEAD, VYREWATCH, byIndex, MAID_AT_INDEX,
			NOTHING_SWAPPED);

		assertShows(head, WidgetModelType.NPC_INDEX_CHATHEAD, MAID_INDEX);
		assertNull(head.getShown());
	}

	@Test
	public void testASwappedHeadFollowsItsModelToAnotherChathead()
	{
		Swap swap = new Swap(WidgetModelType.NPC_CHATHEAD, MAID, MAID, VYREWATCH);
		int demon = NpcID.POH_SERVANT_DEMON;

		Head head = ChatheadSwapper.decide(WidgetModelType.NPC_CHATHEAD, VYREWATCH, swap, MAID_AT_INDEX,
			npcId -> npcId == MAID ? demon : NpcBinding.NO_CHATHEAD);

		assertShows(head, WidgetModelType.NPC_CHATHEAD, demon);
		assertEquals(new Swap(WidgetModelType.NPC_CHATHEAD, MAID, MAID, demon), head.getShown());
	}

	/** Each new dialogue page sets the NPC's own head again, which is swapped again. */
	@Test
	public void testANewPageIsSwappedAgain()
	{
		Swap swap = new Swap(WidgetModelType.NPC_CHATHEAD, MAID, MAID, VYREWATCH);

		Head head = ChatheadSwapper.decide(WidgetModelType.NPC_CHATHEAD, MAID, swap, MAID_AT_INDEX, MAID_SWAPPED);

		assertShows(head, WidgetModelType.NPC_CHATHEAD, VYREWATCH);
		assertEquals(swap, head.getShown());
	}

	/** A head set to an NPC's own chathead which is the swap's target is still that NPC's own. */
	@Test
	public void testANpcWhoseChatheadIsItsOwnIsLeftAlone()
	{
		Head head = ChatheadSwapper.decide(WidgetModelType.NPC_CHATHEAD, VYREWATCH, null, MAID_AT_INDEX,
			npcId -> VYREWATCH);

		assertShows(head, WidgetModelType.NPC_CHATHEAD, VYREWATCH);
		assertNull(head.getShown());
	}
}
