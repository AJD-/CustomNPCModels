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

import java.util.Set;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.GameObject;
import net.runelite.api.Model;
import net.runelite.api.NPC;
import net.runelite.api.Projection;
import net.runelite.api.Renderable;
import net.runelite.api.Scene;
import net.runelite.api.SceneTileModel;
import net.runelite.api.SceneTilePaint;
import net.runelite.api.Texture;
import net.runelite.api.TileObject;
import net.runelite.api.WorldView;
import net.runelite.api.hooks.DrawCallbacks;

/**
 * Decorates the renderer currently holding {@code Client.setDrawCallbacks} (the bundled GPU plugin,
 * or 117 HD's zone renderer) so custom geometry can be substituted for an NPC at draw time.
 *
 * <p>Only {@link #drawTemp} does anything other than forward: temporary entities (NPCs, players,
 * projectiles, spotanims) are drawn through it, and the {@code Model} arrives as a parameter, so
 * handing the delegate a different one is enough to change what is rendered. The delegate keeps
 * doing all the actual upload work.
 *
 * <p>Every other method forwards verbatim. This is deliberate and load bearing: the methods on
 * {@link DrawCallbacks} are {@code default} no-ops, so any method left un-overridden here would
 * silently drop that part of rendering rather than fail loudly - omitting
 * {@link #drawScenePaint} alone would make terrain disappear.
 *
 * <p>Note the clickbox is unaffected by substitution. Under the ZBUF path the client resolves the
 * model, culls clickboxes and registers the hit target before invoking these callbacks, so the
 * clickbox continues to describe the original model. That addresses the "modifying, moving, or
 * resizing the clickboxes of in-game elements is strictly prohibited" rule.
 *
 * <p>The same split is why anything that outlines an NPC through the API - {@code Actor#getModel()}
 * is read-only and never routes through here - traces the original silhouette rather than the one
 * on screen. {@link com.customnpcmodels.compatibility.CustomInteractHighlightOverlay} redraws
 * those outlines around the substituted geometry, so the highlight follows what is rendered while
 * the clickbox still follows the original model, and the two can disagree at the edges.
 */
@Slf4j
public class CustomDrawCallbacks implements DrawCallbacks
{
	/**
	 * Supplies replacement geometry for an NPC, or {@code null} to leave it alone.
	 */
	@FunctionalInterface
	public interface ModelSubstitutor
	{
		Model substitute(NPC npc, Model vanilla);
	}

	@Getter
	private final DrawCallbacks delegate;

	private final ModelSubstitutor substitutor;

	public CustomDrawCallbacks(DrawCallbacks delegate, ModelSubstitutor substitutor)
	{
		this.delegate = delegate;
		this.substitutor = substitutor;
	}

	@Override
	public void drawTemp(Projection worldProjection, Scene scene, GameObject gameObject, Model m, int orient, int x, int y, int z)
	{
		Model substitute = null;

		Renderable renderable = gameObject.getRenderable();
		if (renderable instanceof NPC)
		{
			// Never allow a substitution failure to take the renderer down with it
			try
			{
				substitute = substitutor.substitute((NPC) renderable, m);
			}
			catch (Exception ex)
			{
				substitute = null;
				log.debug("Custom model substitution failed, drawing the vanilla model", ex);
			}
		}

		delegate.drawTemp(worldProjection, scene, gameObject, substitute != null ? substitute : m, orient, x, y, z);
	}

	// --- Everything below forwards verbatim -------------------------------------------------

	@Override
	public void draw(Projection projection, Scene scene, Renderable renderable, int orientation, int x, int y, int z, long hash)
	{
		delegate.draw(projection, scene, renderable, orientation, x, y, z, hash);
	}

	@Override
	public void drawScenePaint(Scene scene, SceneTilePaint paint, int plane, int tileX, int tileZ)
	{
		delegate.drawScenePaint(scene, paint, plane, tileX, tileZ);
	}

	@Override
	public void drawSceneTileModel(Scene scene, SceneTileModel model, int tileX, int tileZ)
	{
		delegate.drawSceneTileModel(scene, model, tileX, tileZ);
	}

	@Override
	public void draw(int overlayColor)
	{
		delegate.draw(overlayColor);
	}

	@Override
	public void drawScene(double cameraX, double cameraY, double cameraZ, double cameraPitch, double cameraYaw, int plane)
	{
		delegate.drawScene(cameraX, cameraY, cameraZ, cameraPitch, cameraYaw, plane);
	}

	@Override
	public void postDrawScene()
	{
		delegate.postDrawScene();
	}

	@Override
	public void animate(Texture texture, int diff)
	{
		delegate.animate(texture, diff);
	}

	@Override
	public void loadScene(Scene scene)
	{
		delegate.loadScene(scene);
	}

	@Override
	public void swapScene(Scene scene)
	{
		delegate.swapScene(scene);
	}

	@Override
	public boolean tileInFrustum(Scene scene, float pitchSin, float pitchCos, float yawSin, float yawCos,
		int cameraX, int cameraY, int cameraZ, int plane, int msx, int msy)
	{
		return delegate.tileInFrustum(scene, pitchSin, pitchCos, yawSin, yawCos, cameraX, cameraY, cameraZ, plane, msx, msy);
	}

	@Override
	public boolean zoneInFrustum(int zoneX, int zoneZ, int maxY, int minY)
	{
		return delegate.zoneInFrustum(zoneX, zoneZ, maxY, minY);
	}

	@Override
	public void loadScene(WorldView worldView, Scene scene)
	{
		delegate.loadScene(worldView, scene);
	}

	@Override
	public void despawnWorldView(WorldView worldView)
	{
		delegate.despawnWorldView(worldView);
	}

	@Override
	public void preSceneDraw(Scene scene, Projection entityProjection,
		float cameraX, float cameraY, float cameraZ, float cameraPitch, float cameraYaw,
		int minLevel, int level, int maxLevel, Set<Integer> hideRoofIds)
	{
		delegate.preSceneDraw(scene, entityProjection, cameraX, cameraY, cameraZ, cameraPitch, cameraYaw,
			minLevel, level, maxLevel, hideRoofIds);
	}

	@Override
	@Deprecated
	public void preSceneDraw(Scene scene,
		float cameraX, float cameraY, float cameraZ, float cameraPitch, float cameraYaw,
		int minLevel, int level, int maxLevel, Set<Integer> hideRoofIds)
	{
		delegate.preSceneDraw(scene, cameraX, cameraY, cameraZ, cameraPitch, cameraYaw,
			minLevel, level, maxLevel, hideRoofIds);
	}

	@Override
	public void postSceneDraw(Scene scene)
	{
		delegate.postSceneDraw(scene);
	}

	@Override
	public void drawPass(Projection entityProjection, Scene scene, int pass)
	{
		delegate.drawPass(entityProjection, scene, pass);
	}

	@Override
	public void drawZoneOpaque(Projection entityProjection, Scene scene, int zx, int zz)
	{
		delegate.drawZoneOpaque(entityProjection, scene, zx, zz);
	}

	@Override
	public void drawZoneAlpha(Projection entityProjection, Scene scene, int level, int zx, int zz)
	{
		delegate.drawZoneAlpha(entityProjection, scene, level, zx, zz);
	}

	@Override
	public void drawDynamic(Projection worldProjection, Scene scene, TileObject tileObject, Renderable r, Model m,
		int orient, int x, int y, int z)
	{
		delegate.drawDynamic(worldProjection, scene, tileObject, r, m, orient, x, y, z);
	}

	@Override
	public void drawDynamic(int renderThreadId, Projection worldProjection, Scene scene, TileObject tileObject,
		Renderable r, Model m, int orient, int x, int y, int z)
	{
		delegate.drawDynamic(renderThreadId, worldProjection, scene, tileObject, r, m, orient, x, y, z);
	}

	@Override
	public void invalidateZone(Scene scene, int zx, int zz)
	{
		delegate.invalidateZone(scene, zx, zz);
	}
}
