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
package com.customnpcmodels.dev;

import com.google.gson.Gson;
import java.io.IOException;
import java.io.Writer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ScheduledExecutorService;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Animation;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Model;
import net.runelite.api.NPC;
import net.runelite.api.events.ClientTick;
import net.runelite.api.events.NpcDespawned;
import net.runelite.api.events.NpcSpawned;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.util.Filepath;

/**
 * Records ground truth for the skeletal animation decoders, from the client itself. Dev only: it
 * lives in the test source set and ./gradlew run loads it only when given
 * {@code -Pcapture=<model id>:<sequence id>,<sequence id>...}.
 * <p>
 * Once logged in it poses that model with each sequence at every frame through
 * {@link Client#applyTransformations}, which needs no NPC at all. Meanwhile, for each skeletal
 * sequence it sees any NPC play, it follows one NPC playing it and records the frame the client
 * reports every cycle, over two of its loops. After {@link #CYCLES} cycles it writes it all to
 * {@code capture-<model id>.json} in its data folder.
 */
@Slf4j
@PluginDescriptor(
	name = "Skeletal Capture (dev)",
	internalName = "custom-npc-models-capture",
	developerPlugin = true
)
public class SkeletalCapturePlugin extends Plugin
{
	public static final String PROPERTY = "customnpcmodels.capture";

	/** 30 seconds from login, for an NPC with a skeletal animation to come into view. */
	private static final int CYCLES = 1500;

	@Inject
	private Client client;

	@Inject
	private ClientThread clientThread;

	@Inject
	private Gson gson;

	@Inject
	private ScheduledExecutorService executor;

	private int modelId;
	private int[] sequenceIds;

	private final Set<NPC> npcs = new HashSet<>();
	private final Map<Integer, Animation> animations = new HashMap<>();
	private final Map<Integer, Map<String, Object>> posed = new LinkedHashMap<>();
	/** Per skeletal sequence: the NPC followed, and per cycle {game cycle, NPC id, frame}. */
	private final Map<Integer, NPC> followed = new HashMap<>();
	private final Map<Integer, List<int[]>> counted = new LinkedHashMap<>();
	private int cycles;
	private boolean written;

	@Override
	protected void startUp()
	{
		String[] spec = System.getProperty(PROPERTY, "").split(":");
		modelId = Integer.parseInt(spec[0].trim());
		String[] ids = spec.length > 1 ? spec[1].split(",") : new String[0];
		sequenceIds = new int[ids.length];
		for (int i = 0; i < ids.length; i++)
		{
			sequenceIds[i] = Integer.parseInt(ids[i].trim());
		}
		log.info("Skeletal capture of model {} with sequences {}", modelId, (Object) ids);
	}

	@Override
	protected void shutDown()
	{
		npcs.clear();
		followed.clear();
	}

	@Subscribe
	public void onNpcSpawned(NpcSpawned event)
	{
		npcs.add(event.getNpc());
	}

	@Subscribe
	public void onNpcDespawned(NpcDespawned event)
	{
		npcs.remove(event.getNpc());
		followed.values().removeIf(npc -> npc == event.getNpc());
	}

	@Subscribe
	public void onClientTick(ClientTick event)
	{
		if (written || client.getGameState() != GameState.LOGGED_IN)
		{
			return;
		}
		if (posed.isEmpty())
		{
			pose();
		}

		for (NPC npc : npcs)
		{
			count(npc, npc.getAnimation(), npc.getAnimationFrame());
			count(npc, npc.getPoseAnimation(), npc.getPoseAnimationFrame());
		}

		if (++cycles >= CYCLES)
		{
			written = true;
			write();
		}
	}

	private Animation animation(int sequenceId)
	{
		return animations.computeIfAbsent(sequenceId, client::loadAnimation);
	}

	/** Follows one NPC per skeletal sequence, over two loops of it. */
	private void count(NPC npc, int sequenceId, int frame)
	{
		if (sequenceId == -1)
		{
			return;
		}
		Animation animation = animation(sequenceId);
		if (animation == null || !animation.isMayaAnim())
		{
			return;
		}
		NPC following = followed.computeIfAbsent(sequenceId, id -> npc);
		List<int[]> frames = counted.computeIfAbsent(sequenceId, id -> new ArrayList<>());
		if (following == npc && frames.size() < 2 * animation.getDuration() + 10)
		{
			frames.add(new int[]{client.getGameCycle(), npc.getId(), frame});
		}
	}

	private void pose()
	{
		Model model = client.loadModel(modelId);
		for (int sequenceId : sequenceIds)
		{
			Map<String, Object> entry = new LinkedHashMap<>();
			posed.put(sequenceId, entry);
			Animation animation = animation(sequenceId);
			if (animation == null || model == null)
			{
				continue;
			}
			entry.put("maya", animation.isMayaAnim());
			entry.put("duration", animation.getDuration());
			entry.put("numFrames", animation.getNumFrames());

			// One past the end, to see what the client does there
			int frames = animation.isMayaAnim() ? animation.getDuration() + 1 : animation.getNumFrames();
			List<Map<String, Object>> poses = new ArrayList<>();
			for (int frame = 0; frame < frames; frame++)
			{
				// The returned model is shared and overwritten by the next call, so copy it out now
				Model transformed = client.applyTransformations(model, animation, frame, null, 0);
				Map<String, Object> sample = new LinkedHashMap<>();
				sample.put("frame", frame);
				if (transformed != null)
				{
					putVertices(sample, transformed);
				}
				poses.add(sample);
			}
			entry.put("poses", poses);
		}
		if (model != null)
		{
			Map<String, Object> rest = new LinkedHashMap<>();
			putVertices(rest, model);
			posed.put(-1, rest);
		}
	}

	private static void putVertices(Map<String, Object> sample, Model model)
	{
		int count = model.getVerticesCount();
		float[] x = new float[count];
		float[] y = new float[count];
		float[] z = new float[count];
		System.arraycopy(model.getVerticesX(), 0, x, 0, count);
		System.arraycopy(model.getVerticesY(), 0, y, 0, count);
		System.arraycopy(model.getVerticesZ(), 0, z, 0, count);
		sample.put("x", x);
		sample.put("y", y);
		sample.put("z", z);
	}

	private void write()
	{
		Map<String, Object> capture = new LinkedHashMap<>();
		capture.put("modelId", modelId);
		capture.put("posed", new LinkedHashMap<>(posed));
		Map<Integer, Object> counts = new LinkedHashMap<>();
		counted.forEach((sequenceId, frames) ->
		{
			Animation animation = animations.get(sequenceId);
			Map<String, Object> entry = new LinkedHashMap<>();
			entry.put("duration", animation.getDuration());
			entry.put("numFrames", animation.getNumFrames());
			entry.put("frames", new ArrayList<>(frames));
			counts.put(sequenceId, entry);
		});
		capture.put("counted", counts);
		log.info("Skeletal capture: posed {} sequences, counted {} skeletal sequences", posed.size(), counts.size());

		// Off the client thread: it is several megabytes
		executor.submit(() ->
		{
			try
			{
				Filepath file = getPluginDirectory().joinSegment("capture-" + modelId + ".json");
				try (Writer writer = file.openBufferedWriter())
				{
					gson.toJson(capture, writer);
				}
				log.info("Skeletal capture written to {}", file);
				clientThread.invokeLater(() -> client.addChatMessage(ChatMessageType.GAMEMESSAGE, "",
					"Skeletal capture written to " + file + " (" + counts.size() + " NPC sequences counted)", null));
			}
			catch (IOException ex)
			{
				log.warn("Skeletal capture could not be written", ex);
			}
		});
	}
}
