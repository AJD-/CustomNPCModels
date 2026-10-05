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
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import com.customnpcmodels.chathead.ChatheadOverlay;
import com.customnpcmodels.inject.AssetBundle;
import com.customnpcmodels.inject.Mesh;
import com.customnpcmodels.inject.NpcBinding;
import com.customnpcmodels.inject.TestBinding;
import com.customnpcmodels.inject.TestMesh;
import com.customnpcmodels.packs.TestPacks;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import net.runelite.api.Client;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.NpcID;
import net.runelite.api.widgets.Widget;
import net.runelite.api.widgets.WidgetModelType;
import org.junit.Before;
import org.junit.Test;

/** ChatheadSwapper frame to frame: what it leaves the dialogue head widget showing. */
public class ChatheadSwapperWiringTest
{
	private static final int MAID = NpcID.POH_SERVANT_WAITER_WOMAN;

	/** The widget's model type and id, which the client and the swapper both set. */
	private final int[] shows = {WidgetModelType.NPC_CHATHEAD, MAID};
	private final boolean[] hidden = {false};

	private CustomNpcModelsConfig config;
	private ChatheadSwapper swapper;

	@Before
	public void setUp()
	{
		Widget head = mock(Widget.class);
		when(head.getModelType()).thenAnswer(call -> shows[0]);
		when(head.getModelId()).thenAnswer(call -> shows[1]);
		when(head.isHidden()).thenAnswer(call -> hidden[0]);
		when(head.setModelType(anyInt())).thenAnswer(call ->
		{
			shows[0] = call.getArgument(0);
			return head;
		});
		when(head.setModelId(anyInt())).thenAnswer(call ->
		{
			shows[1] = call.getArgument(0);
			return head;
		});

		Client client = mock(Client.class);
		when(client.getWidget(InterfaceID.ChatLeft.HEAD)).thenReturn(head);

		// The Maid's model carries a head of its own
		Map<Integer, Mesh> meshes = new LinkedHashMap<>();
		meshes.put(1, new TestMesh().id(1).build());
		meshes.put(2, new TestMesh().id(2).build());
		NpcBinding maid = TestBinding.of("maid", new int[]{MAID}, new int[]{1}).chatheadMesh(2, NpcBinding.STATIC).build();
		ModelCache cache = new ModelCache();
		cache.setCatalog(TestPacks.catalogOf(new AssetBundle(meshes, Collections.emptyMap(), Collections.emptyList(),
			Collections.singletonList(maid))));

		config = mock(CustomNpcModelsConfig.class);
		when(config.swapChatheads()).thenReturn(true);
		swapper = new ChatheadSwapper(client, cache, config, new ChatheadOverlay(client));
	}

	private void frame()
	{
		swapper.onBeforeRender(null);
	}

	private void assertShows(int type, int id)
	{
		assertEquals("model type", type, shows[0]);
		assertEquals("model id", id, shows[1]);
	}

	/**
	 * The head hidden and shown again on the same page (the chatbox hidden and shown) still shows the
	 * swap, so it is still the swapper's to put back.
	 */
	@Test
	public void testAHeadHiddenAndShownAgainKeepsItsSwap()
	{
		swapper.start(() -> true);
		frame();
		assertShows(WidgetModelType.NULL, -1);

		hidden[0] = true;
		frame();
		hidden[0] = false;
		frame();
		when(config.swapChatheads()).thenReturn(false);
		frame();

		assertShows(WidgetModelType.NPC_CHATHEAD, MAID);
	}

	/**
	 * A plugin switched off and straight back on: its stop runs on the client thread after the new
	 * start, and must not switch swapping off behind it.
	 */
	@Test
	public void testAStopQueuedBehindARestartLeavesItSwapping()
	{
		swapper.start(() -> true);
		swapper.start(() -> true);
		swapper.stop();

		frame();

		assertShows(WidgetModelType.NULL, -1);
	}
}
