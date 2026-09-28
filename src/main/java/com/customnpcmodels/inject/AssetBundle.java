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
package com.customnpcmodels.inject;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Everything needed to draw and animate custom NPC models: meshes, the rigs they are bound to, the
 * clips that drive them, and the bindings that say which NPCs wear them.
 *
 * <p>Meshes and rigs are keyed by the synthetic ids the authoring manifest assigns. Clips are keyed
 * by their rig and the <em>live</em> sequence id they stand in for, because the client keeps playing
 * that sequence and hands over its frame index - which is why a clip's sequence is not a free choice.
 * The rig is what keeps two models that answer for the same sequence apart.
 */
public final class AssetBundle
{
	private final Map<Integer, Mesh> meshes;
	private final Map<Integer, Rig> rigs;
	private final Map<Long, Clip> clips;
	private final List<NpcBinding> bindings;

	/** Bindings indexed by NPC id, so the spawn path is a lookup. */
	private final Map<Integer, NpcBinding> bindingsByNpc;

	public AssetBundle(Map<Integer, Mesh> meshes, Map<Integer, Rig> rigs, Collection<Clip> clips)
	{
		this(meshes, rigs, clips, Collections.emptyList());
	}

	public AssetBundle(Map<Integer, Mesh> meshes, Map<Integer, Rig> rigs,
		Collection<Clip> clips, List<NpcBinding> bindings)
	{
		this.meshes = Collections.unmodifiableMap(new LinkedHashMap<>(meshes));
		this.rigs = Collections.unmodifiableMap(new LinkedHashMap<>(rigs));
		this.bindings = Collections.unmodifiableList(new ArrayList<>(bindings));

		Map<Long, Clip> byKey = new LinkedHashMap<>();
		for (Clip clip : clips)
		{
			byKey.put(clipKey(clip.getRigId(), clip.getSequenceId()), clip);
		}
		this.clips = Collections.unmodifiableMap(byKey);

		Map<Integer, NpcBinding> byNpc = new HashMap<>();
		for (NpcBinding binding : bindings)
		{
			for (int npcId : binding.getNpcIds())
			{
				byNpc.put(npcId, binding);
			}
		}
		this.bindingsByNpc = Collections.unmodifiableMap(byNpc);
	}

	public static AssetBundle empty()
	{
		return new AssetBundle(Collections.emptyMap(), Collections.emptyMap(), Collections.emptyList());
	}

	/** The one key a rig and a live sequence share, so a clip is found by both at once. */
	public static long clipKey(int rigId, int sequenceId)
	{
		return ((long) rigId << 32) | (sequenceId & 0xFFFFFFFFL);
	}

	public Mesh getMesh(int meshId)
	{
		return meshes.get(meshId);
	}

	public Rig getRig(int rigId)
	{
		return rigs.get(rigId);
	}

	/** The clip on {@code rigId} standing in for live sequence {@code sequenceId}, or null. */
	public Clip getClip(int rigId, int sequenceId)
	{
		return clips.get(clipKey(rigId, sequenceId));
	}

	/** The binding an NPC id wears, or null when it has no custom model. */
	public NpcBinding getBinding(int npcId)
	{
		return bindingsByNpc.get(npcId);
	}

	public Map<Integer, Mesh> getMeshes()
	{
		return meshes;
	}

	public Map<Integer, Rig> getRigs()
	{
		return rigs;
	}

	public Collection<Clip> getClips()
	{
		return clips.values();
	}

	public List<NpcBinding> getBindings()
	{
		return bindings;
	}

	public boolean isEmpty()
	{
		return meshes.isEmpty() && rigs.isEmpty() && clips.isEmpty() && bindings.isEmpty();
	}

	@Override
	public String toString()
	{
		return "AssetBundle{meshes=" + meshes.size()
			+ ", rigs=" + rigs.size()
			+ ", clips=" + clips.size()
			+ ", bindings=" + bindings.size() + "}";
	}
}
