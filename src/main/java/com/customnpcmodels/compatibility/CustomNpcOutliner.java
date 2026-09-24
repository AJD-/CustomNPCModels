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

import java.awt.Color;
import javax.inject.Inject;
import javax.inject.Singleton;

import com.customnpcmodels.CustomDrawCallbacks;
import com.customnpcmodels.ModelCache;
import net.runelite.api.Client;
import net.runelite.api.Model;
import net.runelite.api.NPC;
import net.runelite.api.Perspective;
import net.runelite.api.RuneLiteObject;
import net.runelite.api.WorldView;
import net.runelite.api.coords.LocalPoint;
import net.runelite.client.ui.overlay.outline.ModelOutlineRenderer;

/**
 * Outlines the custom geometry an NPC is actually drawn with.
 *
 * <p>{@link ModelOutlineRenderer}'s {@code Actor} overload reads {@code Actor#getModel()}, which is
 * always the vanilla mesh - substitution happens further down in {@link CustomDrawCallbacks} and
 * never changes what the API reports - so an NPC we swap gets outlined around a silhouette that is
 * not on screen. The {@link RuneLiteObject} overload is the one public entry point that outlines a
 * caller-supplied model, so a scratch object carries the posed custom model into it.
 *
 * <p>That object is never activated. It is a model carrier handed straight to the outline renderer,
 * never registered with the client, so it adds nothing to the scene and no clickbox of its own.
 */
@Singleton
public class CustomNpcOutliner
{
	@Inject
	private Client client;

	@Inject
	private ModelOutlineRenderer modelOutlineRenderer;

	@Inject
	private ModelCache modelCache;

	// Built lazily and reused - creating one per frame would churn for no reason
	private RuneLiteObject carrier;

	/**
	 * Outlines the custom model an NPC is drawn with, matching what
	 * {@link ModelOutlineRenderer#drawOutline(net.runelite.api.Actor, int, Color, int)} would
	 * produce for the vanilla one.
	 *
	 * @return false when no custom geometry is available, leaving the caller to fall back to the
	 * vanilla outline
	 */
	boolean drawOutline(NPC npc, int outlineWidth, Color color, int feather)
	{
		LocalPoint lp = npc.getLocalLocation();
		WorldView wv = npc.getWorldView();
		if (lp == null || wv == null)
		{
			return false;
		}

		Model posed = modelCache.pose(npc);
		if (posed == null)
		{
			return false;
		}

		if (carrier == null)
		{
			carrier = client.createRuneLiteObject();
		}

		carrier.setModel(posed);

		// The controller setters are used directly rather than setLocation, which would resolve a
		// tile height only for the footprint-aware one below to overwrite it
		carrier.setX(lp.getX());
		carrier.setY(lp.getY());
		carrier.setWorldView(lp.getWorldView());
		carrier.setLevel(wv.getPlane());

		// Mirrors what the Actor overload computes. Using the plain tile height instead would leave
		// a multi-tile NPC outlined above or below where it is drawn.
		carrier.setZ(Perspective.getFootprintTileHeight(client, lp, wv.getPlane(), npc.getFootprintSize())
			- npc.getAnimationHeightOffset());
		carrier.setOrientation(npc.getCurrentOrientation());

		// Projects and rasterize the model before returning, which is what makes handing it the
		// shared transform buffer from pose() safe
		modelOutlineRenderer.drawOutline(carrier, outlineWidth, color, feather);
		return true;
	}

	/**
	 * Drops the scratch object, releasing its reference to a posed model.
	 */
	public void clear()
	{
		carrier = null;
	}
}
