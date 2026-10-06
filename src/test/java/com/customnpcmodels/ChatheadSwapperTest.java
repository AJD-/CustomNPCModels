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
import static org.junit.Assert.assertSame;
import com.customnpcmodels.ChatheadSwapper.Head;
import com.customnpcmodels.ChatheadSwapper.Swap;
import com.customnpcmodels.ChatheadSwapper.Target;
import java.util.function.IntFunction;
import java.util.function.IntUnaryOperator;
import net.runelite.api.gameval.NpcID;
import net.runelite.api.widgets.WidgetModelType;
import org.junit.Test;

public class ChatheadSwapperTest
{
	private static final int MAID = NpcID.POH_SERVANT_WAITER_WOMAN;
	private static final int VYREWATCH = NpcID.SANG_MYQ3_FEMALE_WALK_VYREWATCH_1;
	private static final int MAID_INDEX = 42;

	private static final Target BORROWED = new Target(WidgetModelType.NPC_CHATHEAD, VYREWATCH);
	private static final Target DRAWN = Target.DRAWN;

	private static final IntUnaryOperator SINGLE = npcId -> npcId;
	private static final IntUnaryOperator MAID_AT_INDEX = index -> index == MAID_INDEX ? MAID : -1;
	private static final IntFunction<Target> NOTHING = npcId -> null;

	private static IntFunction<Target> maidWears(Target target)
	{
		return npcId -> npcId == MAID ? target : null;
	}

	private static void assertShows(Head head, int type, int id)
	{
		assertEquals(type, head.getType());
		assertEquals(id, head.getId());
	}

	@Test
	public void testAnNpcsOwnHeadIsSwappedToABorrowedOne()
	{
		Head head = ChatheadSwapper.decide(WidgetModelType.NPC_CHATHEAD, MAID, null, MAID_AT_INDEX, SINGLE, maidWears(BORROWED));

		assertShows(head, WidgetModelType.NPC_CHATHEAD, VYREWATCH);
		assertEquals(new Swap(WidgetModelType.NPC_CHATHEAD, MAID, MAID, BORROWED), head.getShown());
	}

	/** A head the plugin draws itself blanks the widget, so only the drawn head shows. */
	@Test
	public void testAnNpcsOwnHeadIsBlankedForADrawnOne()
	{
		Head head = ChatheadSwapper.decide(WidgetModelType.NPC_CHATHEAD, MAID, null, MAID_AT_INDEX, SINGLE, maidWears(DRAWN));

		assertShows(head, WidgetModelType.NULL, -1);
		assertEquals(new Swap(WidgetModelType.NPC_CHATHEAD, MAID, MAID, DRAWN), head.getShown());
	}

	@Test
	public void testAHeadByNpcIndexIsSwapped()
	{
		Head head = ChatheadSwapper.decide(WidgetModelType.NPC_INDEX_CHATHEAD, MAID_INDEX, null, MAID_AT_INDEX, SINGLE,
			maidWears(DRAWN));

		assertShows(head, WidgetModelType.NULL, -1);
		assertEquals(new Swap(WidgetModelType.NPC_INDEX_CHATHEAD, MAID_INDEX, MAID, DRAWN), head.getShown());
	}

	@Test
	public void testAMultiNpcIsSwappedAsTheNpcItShows()
	{
		int butler = NpcID.POH_SERVANT_DEMON;
		int multi = NpcID.POH_SERVANT_MULTI_DEMON;
		Head head = ChatheadSwapper.decide(WidgetModelType.NPC_CHATHEAD, multi, null, MAID_AT_INDEX,
			npcId -> npcId == multi ? butler : npcId, npcId -> npcId == butler ? DRAWN : null);

		assertShows(head, WidgetModelType.NULL, -1);
		assertEquals(new Swap(WidgetModelType.NPC_CHATHEAD, multi, butler, DRAWN), head.getShown());
	}

	@Test
	public void testAMultiNpcShowingNothingIsLeftAlone()
	{
		Head head = ChatheadSwapper.decide(WidgetModelType.NPC_CHATHEAD, MAID, null, MAID_AT_INDEX, npcId -> -1,
			maidWears(DRAWN));

		assertShows(head, WidgetModelType.NPC_CHATHEAD, MAID);
		assertNull(head.getShown());
	}

	@Test
	public void testAnNpcWithoutAChatheadKeepsItsOwn()
	{
		Head head = ChatheadSwapper.decide(WidgetModelType.NPC_CHATHEAD, MAID, null, MAID_AT_INDEX, SINGLE, NOTHING);

		assertShows(head, WidgetModelType.NPC_CHATHEAD, MAID);
		assertNull(head.getShown());
	}

	@Test
	public void testOtherKindsOfHeadAreLeftAlone()
	{
		for (int type : new int[]{WidgetModelType.LOCAL_PLAYER_CHATHEAD, WidgetModelType.MODEL, WidgetModelType.ITEM})
		{
			Head head = ChatheadSwapper.decide(type, MAID, null, MAID_AT_INDEX, SINGLE, maidWears(DRAWN));

			assertShows(head, type, MAID);
			assertNull(head.getShown());
		}
	}

	@Test
	public void testADrawnHeadIsKeptWhileItsModelStillNamesIt()
	{
		Swap swap = new Swap(WidgetModelType.NPC_CHATHEAD, MAID, MAID, DRAWN);

		Head head = ChatheadSwapper.decide(WidgetModelType.NULL, -1, swap, MAID_AT_INDEX, SINGLE, maidWears(DRAWN));

		assertShows(head, WidgetModelType.NULL, -1);
		assertSame("kept, so the emote clock is not restarted", swap, head.getShown());
	}

	/** The model switched off, the setting turned off, or a safety setting stepping in. */
	@Test
	public void testACustomHeadGoesBackWhenItsModelNoLongerNamesIt()
	{
		Swap swap = new Swap(WidgetModelType.NPC_CHATHEAD, MAID, MAID, DRAWN);

		Head head = ChatheadSwapper.decide(WidgetModelType.NULL, -1, swap, MAID_AT_INDEX, SINGLE, NOTHING);

		assertShows(head, WidgetModelType.NPC_CHATHEAD, MAID);
		assertNull(head.getShown());
	}

	@Test
	public void testASwapFollowsItsModelToABorrowedHead()
	{
		Swap swap = new Swap(WidgetModelType.NPC_CHATHEAD, MAID, MAID, DRAWN);

		Head head = ChatheadSwapper.decide(WidgetModelType.NULL, -1, swap, MAID_AT_INDEX, SINGLE, maidWears(BORROWED));

		assertShows(head, WidgetModelType.NPC_CHATHEAD, VYREWATCH);
		assertEquals(new Swap(WidgetModelType.NPC_CHATHEAD, MAID, MAID, BORROWED), head.getShown());
	}

	/** Each new page sets the NPC's own head again: a new swap, so the drawn emote starts over. */
	@Test
	public void testANewPageIsANewSwap()
	{
		Swap swap = new Swap(WidgetModelType.NPC_CHATHEAD, MAID, MAID, DRAWN);

		Head head = ChatheadSwapper.decide(WidgetModelType.NPC_CHATHEAD, MAID, swap, MAID_AT_INDEX, SINGLE, maidWears(DRAWN));

		assertShows(head, WidgetModelType.NULL, -1);
		assertEquals(swap, head.getShown());
		assertEquals("a fresh swap", false, swap == head.getShown());
	}

	/** The dialogue moves on to another speaker without closing: that head is not touched. */
	@Test
	public void testAnotherSpeakersHeadIsLeftAlone()
	{
		Swap swap = new Swap(WidgetModelType.NPC_CHATHEAD, MAID, MAID, DRAWN);
		int other = NpcID.POH_SERVANT_DEMON;

		Head head = ChatheadSwapper.decide(WidgetModelType.NPC_CHATHEAD, other, swap, MAID_AT_INDEX, SINGLE, maidWears(DRAWN));

		assertShows(head, WidgetModelType.NPC_CHATHEAD, other);
		assertNull(head.getShown());
	}

	@Test
	public void testANpcWhoseChatheadIsItsOwnIsLeftAlone()
	{
		Head head = ChatheadSwapper.decide(WidgetModelType.NPC_CHATHEAD, VYREWATCH, null, MAID_AT_INDEX, SINGLE,
			npcId -> BORROWED);

		assertShows(head, WidgetModelType.NPC_CHATHEAD, VYREWATCH);
		assertNull(head.getShown());
	}
}
