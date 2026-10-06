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

import com.customnpcmodels.inject.Clip;
import com.customnpcmodels.inject.Mesh;
import com.customnpcmodels.inject.Skinner;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.util.HashMap;
import java.util.Map;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.Animation;
import net.runelite.api.Client;
import net.runelite.api.TextureProvider;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;

/**
 * Draws a pack's own chathead over the dialogue head widget, which ChatheadSwapper has blanked:
 * posed by the line's emote, centered on the widget and with its camera, every frame. Like the
 * client, it may draw past the widget's own edges, as far as the dialogue reaches.
 * <p>
 * The widget does not say which frame of the emote the client is on, so the head keeps its own time
 * from when the page opened, with the frame lengths the client plays that emote with. An emote the
 * head has no clip for, or a skeletal one, shows the head at rest.
 * <p>
 * The pose changes every few client cycles at most, and a head at rest never does, so the image is
 * only posed and rendered again when something it shows changes; every other frame draws the last
 * one. Client thread only.
 */
@Singleton
public class ChatheadOverlay extends Overlay
{
	/** Frame lengths of an emote the client can't time: skeletal, or not found. */
	private static final int[] UNTIMED = new int[0];

	private final Client client;
	private final HeadRenderer renderer;
	private final Skinner skinner = new Skinner();
	private final Map<Integer, int[]> frameLengths = new HashMap<>();

	private HeadModel head;
	private int startCycle;
	private int animation = -1;
	private float[] x = new float[0];
	private float[] y = new float[0];
	private float[] z = new float[0];

	// What the last image was rendered for; null after show, so a new head is always rendered
	private BufferedImage image;
	private HeadModel renderedHead;
	private int renderedEmote;
	private int renderedFrame;
	private final Rectangle renderedBounds = new Rectangle();
	private final Rectangle renderedArea = new Rectangle();
	private int renderedPitch;
	private int renderedRoll;
	private int renderedYaw;
	private int renderedZoom;
	private double renderedBrightness;

	@Inject
	public ChatheadOverlay(Client client)
	{
		this(client, new HeadRenderer());
	}

	ChatheadOverlay(Client client, HeadRenderer renderer)
	{
		this.client = client;
		this.renderer = renderer;
		setPosition(OverlayPosition.DYNAMIC);
		setLayer(OverlayLayer.ABOVE_WIDGETS);
	}

	/** Draws {@code head} from now on, its emote starting at {@code gameCycle}; null draws nothing. */
	public void show(HeadModel head, int gameCycle)
	{
		this.head = head;
		startCycle = gameCycle;
		animation = -1;
		renderedHead = null;
		int count = head == null ? 0 : head.getMesh().getVerticesCount();
		if (x.length < count)
		{
			x = new float[count];
			y = new float[count];
			z = new float[count];
		}
	}

	public void hide()
	{
		head = null;
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		HeadModel head = this.head;
		Widget widget = head == null ? null : client.getWidget(InterfaceID.ChatLeft.HEAD);
		if (widget == null || widget.isHidden())
		{
			return null;
		}
		Rectangle bounds = widget.getBounds();
		if (bounds.width <= 0 || bounds.height <= 0)
		{
			return null;
		}

		int emote = widget.getAnimationId();
		if (emote != animation)
		{
			animation = emote;
			startCycle = client.getGameCycle();
		}
		int frame = EmoteClock.frameAt(lengths(emote), client.getGameCycle() - startCycle);

		// The head is bigger than its widget, so it is drawn over the whole dialogue around the widget
		Widget dialogue = client.getWidget(InterfaceID.ChatLeft.UNIVERSE);
		Rectangle area = dialogue == null || dialogue.isHidden() ? bounds : dialogue.getBounds();
		// The client lightens everything it draws by the player's brightness setting, chatheads too
		TextureProvider textures = client.getTextureProvider();
		double brightness = textures == null ? 1 : textures.getBrightness();

		if (head != renderedHead || emote != renderedEmote || frame != renderedFrame
			|| !bounds.equals(renderedBounds) || !area.equals(renderedArea)
			|| widget.getRotationX() != renderedPitch || widget.getRotationY() != renderedRoll
			|| widget.getRotationZ() != renderedYaw || widget.getModelZoom() != renderedZoom
			|| brightness != renderedBrightness)
		{
			Clip clip = frame < 0 ? null : head.clip(emote);
			Mesh mesh = head.getMesh();
			skinner.pose(mesh, head.getRig(), clip, frame, x, y, z);
			image = renderer.render(head, x, y, z, ChatheadCamera.of(widget), area.width, area.height,
				bounds.getCenterX() - area.x, bounds.getCenterY() - area.y, brightness);

			renderedHead = head;
			renderedEmote = emote;
			renderedFrame = frame;
			renderedBounds.setBounds(bounds);
			renderedArea.setBounds(area);
			renderedPitch = widget.getRotationX();
			renderedRoll = widget.getRotationY();
			renderedYaw = widget.getRotationZ();
			renderedZoom = widget.getModelZoom();
			renderedBrightness = brightness;
		}
		graphics.drawImage(image, area.x, area.y, null);
		return null;
	}

	/** An emote's frame lengths, loaded once. */
	private int[] lengths(int emote)
	{
		if (emote < 0)
		{
			return UNTIMED;
		}
		return frameLengths.computeIfAbsent(emote, id ->
		{
			Animation sequence = client.loadAnimation(id);
			return sequence == null || sequence.isMayaAnim() || sequence.getFrameLengths() == null
				? UNTIMED : sequence.getFrameLengths();
		});
	}
}
