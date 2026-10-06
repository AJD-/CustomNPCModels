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
import static org.junit.Assert.assertTrue;
import com.customnpcmodels.inject.AssetBundle;
import com.customnpcmodels.inject.Mesh;
import com.customnpcmodels.inject.NpcAppearance;
import com.customnpcmodels.inject.TestMesh;
import java.awt.image.BufferedImage;
import org.junit.Test;

public class HeadRendererTest
{
	/** One upright triangle, 128 wide and 128 tall, centered on the origin and facing the camera. */
	private static HeadModel triangle()
	{
		Mesh mesh = new TestMesh()
			.vx(new float[]{-64f, 64f, 0f}).vy(new float[]{64f, 64f, -64f}).vz(new float[]{0f, 0f, 0f})
			.i1(new int[]{0}).i2(new int[]{1}).i3(new int[]{2})
			.colors(new short[]{(short) 0x3A05})
			.groups(null)
			.build();
		int[] lit1 = new int[1];
		int[] lit2 = new int[1];
		int[] lit3 = new int[1];
		NpcAppearance.lightChathead(mesh, mesh.getFaceColors(), lit1, lit2, lit3);
		return new HeadModel(mesh, null, -1, AssetBundle.empty(), lit1, lit2, lit3);
	}

	private static BufferedImage render(HeadModel head, int width, int height)
	{
		Mesh mesh = head.getMesh();
		return new HeadRenderer().render(head, mesh.getVerticesX(), mesh.getVerticesY(), mesh.getVerticesZ(),
			new ChatheadCamera(0, 0, 0, 600), width, height);
	}

	@Test
	public void testDrawsTheHeadOpaqueAndLeavesTheRestClear()
	{
		BufferedImage image = render(triangle(), 100, 100);

		assertEquals("the triangle's middle is drawn", 0xFF, image.getRGB(50, 50) >>> 24);
		assertEquals("the corners are clear", 0, image.getRGB(0, 0) >>> 24);
	}

	/** A widget resized by the chatbox or client scaling is drawn at its new size every frame. */
	@Test
	public void testRendersAtTheSizeAsked()
	{
		HeadRenderer renderer = new HeadRenderer();
		HeadModel head = triangle();
		Mesh mesh = head.getMesh();
		ChatheadCamera camera = new ChatheadCamera(0, 0, 0, 600);

		BufferedImage small = renderer.render(head, mesh.getVerticesX(), mesh.getVerticesY(), mesh.getVerticesZ(), camera, 60, 40);
		assertEquals(60, small.getWidth());
		assertEquals(40, small.getHeight());
		BufferedImage large = renderer.render(head, mesh.getVerticesX(), mesh.getVerticesY(), mesh.getVerticesZ(), camera, 120, 90);
		assertEquals(120, large.getWidth());
		assertEquals(90, large.getHeight());
		assertTrue("the larger image is drawn too", (large.getRGB(60, 45) >>> 24) == 0xFF);
	}

	/**
	 * The client draws a chathead centered on its widget but past the widget's edges, as far as the
	 * dialogue reaches, so the head is drawn into the dialogue's bounds around the widget's center.
	 */
	@Test
	public void testDrawsAroundTheCenterAsked()
	{
		HeadModel head = triangle();
		Mesh mesh = head.getMesh();

		BufferedImage image = new HeadRenderer().render(head, mesh.getVerticesX(), mesh.getVerticesY(),
			mesh.getVerticesZ(), new ChatheadCamera(0, 0, 0, 600), 300, 120, 60, 60);

		assertEquals("the triangle is drawn around the given center", 0xFF, image.getRGB(60, 60) >>> 24);
		assertEquals("and not around the image's middle", 0, image.getRGB(150, 60) >>> 24);
	}

	/** The player's brightness setting lightens the head as it lightens everything the client draws. */
	@Test
	public void testDrawsAtThePlayersBrightness()
	{
		HeadModel head = triangle();
		Mesh mesh = head.getMesh();
		ChatheadCamera camera = new ChatheadCamera(0, 0, 0, 600);

		int plain = new HeadRenderer().render(head, mesh.getVerticesX(), mesh.getVerticesY(), mesh.getVerticesZ(),
			camera, 100, 100, 50, 50, 1.0).getRGB(50, 50);
		int bright = new HeadRenderer().render(head, mesh.getVerticesX(), mesh.getVerticesY(), mesh.getVerticesZ(),
			camera, 100, 100, 50, 50, 0.6).getRGB(50, 50);

		assertTrue("brighter at 0.6: " + Integer.toHexString(plain) + " vs " + Integer.toHexString(bright),
			(bright >> 8 & 255) > (plain >> 8 & 255));
	}
}
