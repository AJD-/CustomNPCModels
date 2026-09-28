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
package com.customnpcmodels.authoring;

import static org.junit.Assume.assumeTrue;
import com.customnpcmodels.cache.CacheFiles;
import com.customnpcmodels.cache.MeshFactory;
import com.customnpcmodels.inject.Clip;
import com.customnpcmodels.inject.Mesh;
import com.customnpcmodels.inject.MeshMerger;
import com.customnpcmodels.inject.Rig;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.runelite.api.gameval.AnimationID;
import net.runelite.api.gameval.NpcID;
import net.runelite.cache.NpcManager;
import net.runelite.cache.definitions.ModelDefinition;
import net.runelite.cache.definitions.NpcDefinition;
import net.runelite.cache.definitions.SequenceDefinition;
import net.runelite.cache.fs.Store;

/**
 * Real geometry and animation for the tests to check the pipeline against, decoded from the live
 * cache: the Giant Mole, the first authoring subject, the skeleton, whose mesh and clips are
 * already proven correct in game by Retro NPC Swapper, and Commander Zilyana, whose clips collapse a
 * group to nothing.
 * <p>
 * Nothing here is written anywhere. A test that asks for a fixture with no live cache on the
 * machine is skipped rather than failed.
 */
final class LiveFixtures
{
	/** The Giant Mole's four parts, in NPC definition order. */
	static final int[] MOLE_PARTS = {12076, 12075, 12074, 12077};
	static final int MOLE_READY = AnimationID.MOLE_READY;
	static final int MOLE_WALK = AnimationID.MOLE_WALK;

	static final int SKELETON_MESH = 2944;
	static final int SKELETON_READY = 262;
	static final int SKELETON_WALK = 259;

	/**
	 * Commander Zilyana, whose model carries an effect sphere on a group of its own that her standing
	 * and walking clips scale to nothing on every frame.
	 */
	static final int ZILYANA = NpcID.GODWARS_SARADOMIN_AVATAR;
	static final int ZILYANA_READY = AnimationID.GODWARS_SARADOMIN_READY;
	static final int ZILYANA_WALK = AnimationID.GODWARS_SARADOMIN_WALK;

	private static Store store;
	private static NpcManager npcs;
	private static final Map<Integer, Mesh> meshes = new HashMap<>();
	private static final Map<Integer, Clip> clips = new HashMap<>();
	private static final Map<Integer, Rig> rigs = new LinkedHashMap<>();

	private LiveFixtures()
	{
	}

	/** The live cache, or a skipped test when there is none. */
	static synchronized Store store() throws IOException
	{
		if (store == null)
		{
			store = CacheFiles.openLiveCache();
		}
		assumeTrue("no live OSRS cache on this machine", store != null);
		return store;
	}

	static synchronized Mesh mesh(int modelId) throws IOException
	{
		Mesh mesh = meshes.get(modelId);
		if (mesh == null)
		{
			ModelDefinition model = CacheFiles.decodeModel(store(), modelId);
			assumeTrue("model " + modelId + " does not decode", model != null);
			mesh = MeshFactory.toMesh(modelId, model);
			meshes.put(modelId, mesh);
		}
		return mesh;
	}

	static Mesh mole() throws IOException
	{
		List<Mesh> parts = new ArrayList<>();
		for (int part : MOLE_PARTS)
		{
			parts.add(mesh(part));
		}
		return MeshMerger.merge(MOLE_PARTS[0], parts);
	}

	static synchronized NpcDefinition npc(int npcId) throws IOException
	{
		if (npcs == null)
		{
			NpcManager manager = new NpcManager(store());
			manager.load();
			npcs = manager;
		}
		NpcDefinition npc = npcs.get(npcId);
		assumeTrue("npc " + npcId + " has no models", npc != null && npc.models != null);
		return npc;
	}

	static synchronized Clip clip(int sequenceId) throws IOException
	{
		Clip clip = clips.get(sequenceId);
		if (clip == null)
		{
			clip = CacheFiles.buildClip(store(), sequenceId, rigs);
			assumeTrue("sequence " + sequenceId + " does not decode", clip != null);
			clips.put(sequenceId, clip);
		}
		return clip;
	}

	static synchronized Rig rig(int rigId)
	{
		return rigs.get(rigId);
	}

	static synchronized Map<Integer, Rig> rigs()
	{
		return rigs;
	}

	static int liveFrameCount(int sequenceId) throws IOException
	{
		SequenceDefinition sequence = CacheFiles.loadSequence(store(), sequenceId);
		return sequence == null || sequence.frameIDs == null ? -1 : sequence.frameIDs.length;
	}
}
