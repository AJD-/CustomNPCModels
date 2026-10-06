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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import net.runelite.api.Model;
import net.runelite.api.NPC;
import net.runelite.client.events.PluginMessage;
import org.junit.Test;

public class ModelSwapProtocolTest
{
	@Test
	public void testClaimsRoundTrip()
	{
		Set<Integer> ids = new HashSet<>(Arrays.asList(5779, 5780));
		assertEquals(ids, ModelSwapProtocol.readClaims(ModelSwapProtocol.claimsMessage(ids)));
		assertEquals(Collections.emptySet(), ModelSwapProtocol.readClaims(ModelSwapProtocol.claimsMessage(Collections.emptySet())));
	}

	@Test
	public void testClaimsAreACopyNobodyCanModify()
	{
		Set<Integer> ids = new HashSet<>(Collections.singleton(5779));
		PluginMessage message = ModelSwapProtocol.claimsMessage(ids);
		ids.add(1);

		Set<?> sent = (Set<?>) message.getData().get(ModelSwapProtocol.KEY_NPC_IDS);
		assertEquals(Collections.singleton(5779), sent);
		try
		{
			sent.clear();
		}
		catch (UnsupportedOperationException expected)
		{
			return;
		}
		throw new AssertionError("the claimed set was modifiable");
	}

	@Test
	public void testOtherMessagesAreNotClaims()
	{
		assertNull(ModelSwapProtocol.readClaims(null));
		assertNull(ModelSwapProtocol.readClaims(new PluginMessage("some-plugin", ModelSwapProtocol.CLAIMS)));
		assertNull(ModelSwapProtocol.readClaims(ModelSwapProtocol.synMessage(ModelSwapProtocol.SOURCE_RETRO_NPC_SWAPPER)));
	}

	@Test
	public void testAnotherVersionIsIgnored()
	{
		Map<String, Object> data = new HashMap<>(ModelSwapProtocol.claimsMessage(Collections.singleton(5779)).getData());
		data.put(ModelSwapProtocol.KEY_VERSION, ModelSwapProtocol.VERSION + 1);
		assertNull(ModelSwapProtocol.readClaims(new PluginMessage(ModelSwapProtocol.NAMESPACE, ModelSwapProtocol.CLAIMS, data)));
	}

	@Test
	public void testClaimsFromAnotherSourceAreIgnored()
	{
		Map<String, Object> data = new HashMap<>(ModelSwapProtocol.claimsMessage(Collections.singleton(5779)).getData());
		data.put(ModelSwapProtocol.KEY_SOURCE, ModelSwapProtocol.SOURCE_RETRO_NPC_SWAPPER);
		assertNull(ModelSwapProtocol.readClaims(new PluginMessage(ModelSwapProtocol.NAMESPACE, ModelSwapProtocol.CLAIMS, data)));
	}

	@Test
	public void testSyncReqIsRecognisedFromThePartnerOnly()
	{
		PluginMessage syn = ModelSwapProtocol.synMessage(ModelSwapProtocol.SOURCE_RETRO_NPC_SWAPPER);
		assertTrue(ModelSwapProtocol.isSyncReq(syn, ModelSwapProtocol.SOURCE_RETRO_NPC_SWAPPER));
		assertFalse(ModelSwapProtocol.isSyncReq(syn, ModelSwapProtocol.SOURCE_CUSTOM_NPC_MODELS));
		assertFalse(ModelSwapProtocol.isSyncReq(ModelSwapProtocol.claimsMessage(Collections.emptySet()),
			ModelSwapProtocol.SOURCE_CUSTOM_NPC_MODELS));
		assertFalse(ModelSwapProtocol.isSyncReq(new PluginMessage(ModelSwapProtocol.NAMESPACE, ModelSwapProtocol.SYN),
			ModelSwapProtocol.SOURCE_RETRO_NPC_SWAPPER));
	}

	@Test
	public void testOutlinesRoundTrip()
	{
		Function<NPC, Model> poser = npc -> null;
		ModelSwapProtocol.Outlines outlines = ModelSwapProtocol.readOutlines(
			ModelSwapProtocol.outlinesMessage(ModelSwapProtocol.SOURCE_RETRO_NPC_SWAPPER, true, poser),
			ModelSwapProtocol.SOURCE_RETRO_NPC_SWAPPER);
		assertNotNull(outlines);
		assertTrue(outlines.isOwning());
		assertSame(poser, outlines.getPoser());
	}

	@Test
	public void testOutlinesWithoutAPoserWithdrawIt()
	{
		PluginMessage message = ModelSwapProtocol.outlinesMessage(ModelSwapProtocol.SOURCE_CUSTOM_NPC_MODELS, false, null);
		assertFalse(message.getData().containsKey(ModelSwapProtocol.KEY_POSER));

		ModelSwapProtocol.Outlines outlines = ModelSwapProtocol.readOutlines(message, ModelSwapProtocol.SOURCE_CUSTOM_NPC_MODELS);
		assertNotNull(outlines);
		assertFalse(outlines.isOwning());
		assertNull(outlines.getPoser());
	}

	@Test
	public void testOwnOutlinesAreNotThePartners()
	{
		PluginMessage message = ModelSwapProtocol.outlinesMessage(ModelSwapProtocol.SOURCE_CUSTOM_NPC_MODELS, true, npc -> null);
		assertNull(ModelSwapProtocol.readOutlines(message, ModelSwapProtocol.SOURCE_RETRO_NPC_SWAPPER));
	}

	@Test
	public void testOtherMessagesAreNotOutlines()
	{
		assertNull(ModelSwapProtocol.readOutlines(null, ModelSwapProtocol.SOURCE_RETRO_NPC_SWAPPER));
		assertNull(ModelSwapProtocol.readOutlines(ModelSwapProtocol.synMessage(ModelSwapProtocol.SOURCE_RETRO_NPC_SWAPPER),
			ModelSwapProtocol.SOURCE_RETRO_NPC_SWAPPER));

		Map<String, Object> data = new HashMap<>(
			ModelSwapProtocol.outlinesMessage(ModelSwapProtocol.SOURCE_RETRO_NPC_SWAPPER, true, null).getData());
		data.remove(ModelSwapProtocol.KEY_OWNING);
		assertNull(ModelSwapProtocol.readOutlines(new PluginMessage(ModelSwapProtocol.NAMESPACE, ModelSwapProtocol.OUTLINES, data),
			ModelSwapProtocol.SOURCE_RETRO_NPC_SWAPPER));
	}

	@Test
	public void testOutlinesOfAnotherVersionAreIgnored()
	{
		Map<String, Object> data = new HashMap<>(
			ModelSwapProtocol.outlinesMessage(ModelSwapProtocol.SOURCE_RETRO_NPC_SWAPPER, true, null).getData());
		data.put(ModelSwapProtocol.KEY_VERSION, ModelSwapProtocol.VERSION + 1);
		assertNull(ModelSwapProtocol.readOutlines(new PluginMessage(ModelSwapProtocol.NAMESPACE, ModelSwapProtocol.OUTLINES, data),
			ModelSwapProtocol.SOURCE_RETRO_NPC_SWAPPER));
	}

	@Test
	public void testOptOutIsRecognisedFromThePartnerOnly()
	{
		PluginMessage optOut = ModelSwapProtocol.optOutMessage(ModelSwapProtocol.SOURCE_RETRO_NPC_SWAPPER);
		assertTrue(ModelSwapProtocol.isOptOut(optOut, ModelSwapProtocol.SOURCE_RETRO_NPC_SWAPPER));
		assertFalse(ModelSwapProtocol.isOptOut(optOut, ModelSwapProtocol.SOURCE_CUSTOM_NPC_MODELS));
		assertFalse(ModelSwapProtocol.isOptOut(ModelSwapProtocol.synMessage(ModelSwapProtocol.SOURCE_RETRO_NPC_SWAPPER),
			ModelSwapProtocol.SOURCE_RETRO_NPC_SWAPPER));
	}
}
