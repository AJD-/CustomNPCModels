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

import com.customnpcmodels.inject.AssetBundle;
import com.customnpcmodels.inject.Clip;
import com.customnpcmodels.inject.InjectedModel;
import com.customnpcmodels.inject.Lighter;
import com.customnpcmodels.inject.Mesh;
import com.customnpcmodels.inject.MeshMerger;
import com.customnpcmodels.inject.NpcBinding;
import com.customnpcmodels.inject.Rig;
import com.customnpcmodels.inject.Skinner;
import com.customnpcmodels.inject.SwapBlacklist;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Model;
import net.runelite.api.NPC;

/**
 * Holds the custom replacement geometry, built once per NPC id and reused every frame.
 * <p>
 * The draw callback runs per entity per frame, so it must do a map lookup and a skin and nothing
 * else. Everything expensive - merging, recoloring, scaling and lighting - happens here, driven from
 * NPC spawn and transform events rather than from the render path.
 * <p>
 * Every model comes from the bundle. There is deliberately no fallback to the client's own cache:
 * an authored mesh id means nothing to the live cache, so a bundle miss must leave the NPC vanilla
 * rather than load whatever unrelated geometry happens to sit at that id.
 */
@Singleton
@Slf4j
public class ModelCache
{
	/** The client's NPC lighting constants: base ambient and contrast, and the light direction. */
	private static final int NPC_AMBIENT = 64;
	private static final int NPC_CONTRAST = 850;
	private static final int NPC_LIGHT_X = -30;
	private static final int NPC_LIGHT_Y = -50;
	private static final int NPC_LIGHT_Z = -30;

	/** The definition stores contrast in steps the decoder multiplies by 5. */
	private static final int NPC_CONTRAST_STEP = 5;

	/** NPC ids whose models could not be built, so spawns stop retrying them. */
	private final Set<Integer> unbuildable = new HashSet<>();

	/** NPC id and animation pairs already reported by {@link #reportAction}, so each is said once. */
	private final Set<Long> reportedActions = new HashSet<>();

	/** Custom geometry, rigs, clips and bindings. Empty until the bundle finishes loading. */
	private AssetBundle bundle = AssetBundle.empty();

	/** Fully prepared geometry per NPC id. */
	private final Map<Integer, BuiltModel> builtModels = new HashMap<>();

	private final Skinner skinner = new Skinner();

	/**
	 * NPC ids currently eligible for substitution. The eligibility decision itself stays in the
	 * plugin, which weighs bindings, config and safety settings; this is only the memo of it, so the
	 * render path is a lookup and nothing more.
	 */
	private final Set<Integer> substituted = new HashSet<>();

	/**
	 * Marks an NPC id as being substituted, so {@link #pose(NPC)} will supply geometry for it.
	 */
	public void setSubstituted(int npcId)
	{
		substituted.add(npcId);
	}

	public void clearSubstituted(int npcId)
	{
		substituted.remove(npcId);
	}

	public boolean isSubstituted(int npcId)
	{
		return substituted.contains(npcId);
	}

	/**
	 * Builds and caches the model for an NPC id if it is not already present.
	 * Must be called on the client thread.
	 *
	 * @return whether geometry is available for this id, either just built or already cached. A false
	 *     is the caller's signal to leave the NPC alone entirely - including for an NPC the bundle has
	 *     no binding for, which is the common case.
	 */
	public boolean ensureBuilt(int npcId)
	{
		// setBundle already took these out of the bindings; checked again so no path can build one
		if (SwapBlacklist.isBlocked(npcId) || unbuildable.contains(npcId))
		{
			return false;
		}

		if (builtModels.containsKey(npcId))
		{
			return true;
		}

		NpcBinding binding = bundle.getBinding(npcId);
		if (binding == null)
		{
			return false;
		}

		BuiltModel built = build(binding);
		if (built == null)
		{
			// Remember the failure so every subsequent spawn does not repeat the work
			unbuildable.add(npcId);
			return false;
		}

		builtModels.put(npcId, built);
		log.debug("Built custom model '{}' for NPC id {} ({} verts, {} faces)",
			binding.getName(), npcId, built.mesh.getVerticesCount(), built.mesh.getFaceCount());
		return true;
	}

	/**
	 * Publishes the asset bundle. Client thread only; the load itself happens off it.
	 */
	public void setBundle(AssetBundle bundle)
	{
		bundle = bundle == null ? AssetBundle.empty() : bundle;

		// Every binding reaches the plugin through here, so this is where the blacklist holds: an NPC
		// taken out now is never built, drawn or claimed, whichever bundle named it
		for (NpcBinding binding : bundle.getBindings())
		{
			for (int npcId : binding.getNpcIds())
			{
				if (SwapBlacklist.isBlocked(npcId))
				{
					log.debug("NPC {} ({}) is never swapped; dropping it from binding '{}'",
						npcId, SwapBlacklist.contentOf(npcId), binding.getName());
				}
			}
		}
		this.bundle = bundle.withoutNpcs(SwapBlacklist.ids());

		// The bundle arrives off-thread and can land after NPCs were already checked against an
		// empty one. Dropping what was built makes them pick it up on the next check.
		builtModels.clear();
		unbuildable.clear();

		log.debug("Custom NPC model bundle loaded: {}", this.bundle);
	}

	/**
	 * Every NPC id the bundle has a binding for, whether or not its model has been built yet.
	 * Client thread only.
	 */
	public Set<Integer> boundNpcIds()
	{
		Set<Integer> ids = new HashSet<>();
		for (NpcBinding binding : bundle.getBindings())
		{
			for (int npcId : binding.getNpcIds())
			{
				ids.add(npcId);
			}
		}
		return ids;
	}

	/**
	 * Poses the model for an NPC, or null when it is not being substituted.
	 *
	 * <p>The returned model is shared by every NPC of this id and is overwritten by the next pose, so
	 * it has to be consumed before anything else runs. Both callers do: the draw callback hands it
	 * straight to the renderer, and the outline renderer projects and rasterizes it before returning.
	 * Must be called on the client thread.
	 */
	public Model pose(NPC npc)
	{
		int npcId = npc.getId();
		if (!substituted.contains(npcId))
		{
			return null;
		}

		BuiltModel built = builtModels.get(npcId);
		return built == null ? null : pose(npc, built);
	}

	/**
	 * Prepares the geometry for a binding, or null when the bundle cannot supply all of it.
	 *
	 * <p>What the client would do once for a cache model it decoded itself - merging, recoloring,
	 * lighting - happens here instead, at spawn. The resize is per pose, after skinning.
	 */
	private BuiltModel build(NpcBinding binding)
	{
		int[] meshIds = binding.getMeshIds();
		List<Mesh> parts = new ArrayList<>(meshIds.length);
		for (int meshId : meshIds)
		{
			Mesh part = bundle.getMesh(meshId);
			if (part != null)
			{
				parts.add(part);
			}
		}

		if (parts.isEmpty() || parts.size() < meshIds.length)
		{
			// Never drawn partially: the codec checks every binding's meshes resolve, so reaching
			// here means the bundle and this code disagree, and a model missing a part is worse
			// than the vanilla one
			log.debug("Bundle has {} of {} parts for '{}' ({}); refusing a partial merge",
				parts.size(), meshIds.length, binding.getName(), Arrays.toString(meshIds));
			return null;
		}

		Mesh mesh = MeshMerger.merge(meshIds[0], parts);

		// Recolor before lighting, not after: lit colors are baked once and never recomputed, so a
		// recolor applied afterward would have nothing left to bite on
		short[] colors = mesh.getFaceColors().clone();
		if (binding.hasRecolors())
		{
			short[] find = binding.getRecolorFind();
			short[] replace = binding.getRecolorReplace();
			for (int face = 0; face < colors.length; face++)
			{
				for (int pair = 0; pair < find.length; pair++)
				{
					if (colors[face] == find[pair])
					{
						colors[face] = replace[pair];
						break;
					}
				}
			}
		}

		mesh = recolored(mesh, colors);

		int faceCount = mesh.getFaceCount();
		int[] colors1 = new int[faceCount];
		int[] colors2 = new int[faceCount];
		int[] colors3 = new int[faceCount];

		// Lit at rest and unscaled, with the NPC lighting formula rather than ModelData.light()'s
		// defaults, which are the item and scenery constants. Read out of the client's NPCComposition:
		// light(64 + ambient, 850 + contrast, -30, -50, -30), where the decoder has already
		// multiplied the definition's contrast byte by 5. The light comes from above, where the
		// defaults' comes mostly from the side, so using them darkens every upward-facing surface.
		Lighter.light(
			mesh.getVerticesCount(), mesh.getVerticesX(), mesh.getVerticesY(), mesh.getVerticesZ(),
			faceCount, mesh.getFaceIndices1(), mesh.getFaceIndices2(), mesh.getFaceIndices3(),
			mesh.getFaceColors(), mesh.getFaceRenderTypes(), mesh.getFaceTextures(),
			NPC_AMBIENT + binding.getAmbient(), NPC_CONTRAST + binding.getContrast() * NPC_CONTRAST_STEP,
			NPC_LIGHT_X, NPC_LIGHT_Y, NPC_LIGHT_Z,
			colors1, colors2, colors3);

		BuiltModel built = new BuiltModel(mesh, binding.getScaleXZ() / 128f, binding.getScaleY() / 128f);
		built.model.bind(mesh, colors1, colors2, colors3);
		return built;
	}

	/**
	 * Returns the mesh with the recolored palette.
	 * <p>
	 * Faces, texture mapping, rigging and vertices are shared with the bundle mesh - only the
	 * colors differ per NPC, and neither the bundle nor any other NPC sees this copy.
	 */
	private static Mesh recolored(Mesh mesh, short[] colors)
	{
		return new Mesh(mesh.getId(), mesh.getPriority(),
			mesh.getVerticesX(), mesh.getVerticesY(), mesh.getVerticesZ(),
			mesh.getFaceIndices1(), mesh.getFaceIndices2(), mesh.getFaceIndices3(),
			colors, mesh.getFaceRenderTypes(), mesh.getFaceTransparencies(),
			mesh.getFaceRenderPriorities(), mesh.getFaceTextures(),
			mesh.getTextureCoords(), mesh.getTexIndices1(), mesh.getTexIndices2(),
			mesh.getTexIndices3(), mesh.getVertexGroups());
	}

	/**
	 * Poses built geometry for the frame the client is currently showing.
	 * <p>
	 * An action animation wins over the movement pose when the bundle carries it. The client
	 * layers the two using the sequence's interleave mask; until the skinner implements that, the
	 * action replacing the pose outright is the closer of the two approximations, because an action
	 * is what the whole body is doing.
	 */
	private Model pose(NPC npc, BuiltModel built)
	{
		Mesh mesh = built.mesh;
		InjectedModel model = built.model;

		int action = npc.getAnimation();
		Clip clip = bundle.getClip(action);
		int frame = npc.getAnimationFrame();

		if (action != -1)
		{
			reportAction(npc.getId(), action, frame, clip);
		}

		if (clip == null || !clip.hasFrame(frame))
		{
			clip = bundle.getClip(npc.getPoseAnimation());
			frame = npc.getPoseAnimationFrame();
		}

		Rig rig = clip == null ? null : bundle.getRig(clip.getRigId());

		// A missing clip is not a failure - it leaves the mesh in its rest pose, which is far better
		// than not drawing the NPC at all
		float[] x = model.getVerticesX();
		float[] y = model.getVerticesY();
		float[] z = model.getVerticesZ();
		skinner.pose(mesh, rig, clip, frame, x, y, z);

		// The resize comes after the pose, as the client orders it. A clip's translations and pivot
		// offsets are absolute units authored against the unscaled mesh, so resizing the rest pose
		// first and posing it afterward moves every translated part by the full unscaled amount -
		// the limbs drift off the body they are attached to.
		if (built.scaleXZ != 1f || built.scaleY != 1f)
		{
			for (int v = 0; v < mesh.getVerticesCount(); v++)
			{
				x[v] *= built.scaleXZ;
				y[v] *= built.scaleY;
				z[v] *= built.scaleXZ;
			}
		}
		model.calculateBoundsCylinder();

		return model;
	}

	/**
	 * Says once, per NPC id and animation, what the pose did with an action animation.
	 * <p>
	 * Bounded by construction: one line per id and animation, not per frame.
	 */
	private void reportAction(int npcId, int action, int frame, Clip clip)
	{
		if (!log.isDebugEnabled() || !reportedActions.add(((long) npcId << 32) | (action & 0xFFFFFFFFL)))
		{
			return;
		}

		if (clip == null)
		{
			log.debug("NPC {} plays animation {}, which the bundle has no clip for - it will hold "
				+ "its movement pose", npcId, action);
		}
		else if (!clip.hasFrame(frame))
		{
			log.debug("NPC {} plays animation {} at frame {}, past the {} frames of its clip",
				npcId, action, frame, clip.getFrameCount());
		}
		else
		{
			log.debug("NPC {} plays animation {}, entering its clip at frame {} of {}",
				npcId, action, frame, clip.getFrameCount());
		}
	}

	public void clear()
	{
		// The bundle goes with everything else. This is a singleton that outlives the plugin, so
		// keeping the decoded geometry here would hold it until the next start for nothing - and a
		// restart reads it again anyway, which is what makes dropping it free.
		bundle = AssetBundle.empty();

		substituted.clear();
		reportedActions.clear();
		builtModels.clear();
		unbuildable.clear();
	}

	/** Geometry for one NPC id: the mesh it was built from, and the model handed out. */
	private static final class BuiltModel
	{
		private final Mesh mesh;

		/** The binding's resize as factors, applied to every pose. */
		private final float scaleXZ;
		private final float scaleY;

		/**
		 * Reused across every NPC of this id and every frame. Safe for the same reason the client's
		 * own posed model is: it is consumed by the renderer before anything else can pose again.
		 */
		private final InjectedModel model = new InjectedModel();

		private BuiltModel(Mesh mesh, float scaleXZ, float scaleY)
		{
			this.mesh = mesh;
			this.scaleXZ = scaleXZ;
			this.scaleY = scaleY;
		}
	}
}
