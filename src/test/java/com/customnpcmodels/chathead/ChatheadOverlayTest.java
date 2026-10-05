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
package com.customnpcmodels.chathead;

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import com.customnpcmodels.inject.AssetBundle;
import com.customnpcmodels.inject.Mesh;
import com.customnpcmodels.inject.NpcAppearance;
import com.customnpcmodels.inject.TestMesh;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import net.runelite.api.Animation;
import net.runelite.api.Client;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;
import org.junit.Before;
import org.junit.Test;

/** ChatheadOverlay draws every frame, but renders the head again only when what it shows changes. */
public class ChatheadOverlayTest
{
	private static final int EMOTE = 554;

	/** Counts the renders that reach the rasterizer. */
	private static final class CountingRenderer extends HeadRenderer
	{
		int renders;

		@Override
		public BufferedImage render(HeadModel head, float[] x, float[] y, float[] z, ChatheadCamera camera,
			int width, int height, double centerX, double centerY, double brightness)
		{
			renders++;
			return super.render(head, x, y, z, camera, width, height, centerX, centerY, brightness);
		}
	}

	private final int[] cycle = {0};
	private final CountingRenderer renderer = new CountingRenderer();
	private final Graphics2D graphics = mock(Graphics2D.class);
	private Widget widget;
	private ChatheadOverlay overlay;

	@Before
	public void setUp()
	{
		widget = mock(Widget.class);
		when(widget.getBounds()).thenReturn(new Rectangle(10, 20, 40, 40));
		when(widget.getAnimationId()).thenReturn(EMOTE);
		when(widget.getRotationX()).thenReturn(40);
		when(widget.getRotationZ()).thenReturn(1882);
		when(widget.getModelZoom()).thenReturn(796);

		Animation emote = mock(Animation.class);
		when(emote.getFrameLengths()).thenReturn(new int[]{5, 5});

		Client client = mock(Client.class);
		when(client.getWidget(InterfaceID.ChatLeft.HEAD)).thenReturn(widget);
		when(client.getGameCycle()).thenAnswer(call -> cycle[0]);
		when(client.loadAnimation(EMOTE)).thenReturn(emote);

		Mesh mesh = new TestMesh().groups(null).build();
		int faces = mesh.getFaceCount();
		int[] lit1 = new int[faces];
		int[] lit2 = new int[faces];
		int[] lit3 = new int[faces];
		NpcAppearance.lightChathead(mesh, mesh.getFaceColors(), lit1, lit2, lit3);

		overlay = new ChatheadOverlay(client, renderer);
		overlay.show(new HeadModel(mesh, null, -1, AssetBundle.empty(), lit1, lit2, lit3), 0);
	}

	@Test
	public void testAnUnchangedFrameIsNotRenderedAgain()
	{
		overlay.render(graphics);
		overlay.render(graphics);
		cycle[0] = 2;
		overlay.render(graphics);

		assertEquals(1, renderer.renders);
	}

	@Test
	public void testTheNextEmoteFrameIsRendered()
	{
		overlay.render(graphics);
		cycle[0] = 5;
		overlay.render(graphics);

		assertEquals(2, renderer.renders);
	}

	@Test
	public void testAMovedWidgetIsRenderedAgain()
	{
		overlay.render(graphics);
		when(widget.getBounds()).thenReturn(new Rectangle(10, 20, 60, 60));
		overlay.render(graphics);

		assertEquals(2, renderer.renders);
	}
}
