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
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
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
 * {@code -Pcapture=<model id>:<sequence id>,...;<model id>:<sequence id>,...}.
 * <p>
 * Once logged in it poses each model with each of its sequences through
 * {@link Client#applyTransformations}, which needs no NPC at all, one frame per client cycle.
 * Meanwhile, for each skeletal sequence it sees any NPC play, it follows one NPC playing it over two
 * of its loops, recording the frame the client reports every cycle and, once per frame, the model
 * the client posed for that NPC to draw. After {@link #CYCLES} cycles it writes it all to
 * {@code capture.json} in its data folder.
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

	/** 30 seconds from login: time to pose a few sequences, and for a skeletal NPC to come into view. */
	private static final int CYCLES = 1500;

	@Inject
	private Client client;

	@Inject
	private ClientThread clientThread;

	@Inject
	private Gson gson;

	@Inject
	private ScheduledExecutorService executor;

	/** Per model, the sequences to pose it with. */
	private final Map<Integer, int[]> toPose = new LinkedHashMap<>();
	/** What is left to pose, one {model, sequence, frame} per cycle. */
	private final Deque<int[]> poseQueue = new ArrayDeque<>();

	private final Set<NPC> npcs = new HashSet<>();
	private final Map<Integer, Animation> animations = new HashMap<>();
	private final Map<Integer, Model> models = new HashMap<>();
	/** Per model: its rest vertices, and per sequence its timing and poses. */
	private final Map<Integer, Map<String, Object>> posed = new LinkedHashMap<>();
	/** Per skeletal sequence: the NPC followed, and per cycle {game cycle, NPC id, frame}. */
	private final Map<Integer, NPC> followed = new HashMap<>();
	private final Map<Integer, List<int[]>> counted = new LinkedHashMap<>();
	/** Per skeletal sequence: the followed NPC's drawn model, once per frame. */
	private final Map<Integer, Map<Integer, Map<String, Object>>> drawn = new LinkedHashMap<>();
	private boolean queued;
	private int cycles;
	private boolean written;

	@Override
	protected void startUp()
	{
		for (String spec : System.getProperty(PROPERTY, "").split(";"))
		{
			String[] parts = spec.split(":");
			String[] ids = parts.length > 1 ? parts[1].split(",") : new String[0];
			int[] sequenceIds = new int[ids.length];
			for (int i = 0; i < ids.length; i++)
			{
				sequenceIds[i] = Integer.parseInt(ids[i].trim());
			}
			toPose.put(Integer.parseInt(parts[0].trim()), sequenceIds);
		}
		log.info("Skeletal capture posing {}", System.getProperty(PROPERTY));
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
		if (!queued)
		{
			queued = true;
			queuePoses();
		}
		poseNext();

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
		if (following != npc || frames.size() >= 2 * animation.getDuration() + 10)
		{
			return;
		}
		frames.add(new int[]{client.getGameCycle(), npc.getId(), frame});

		Map<Integer, Map<String, Object>> samples = drawn.computeIfAbsent(sequenceId, id -> new TreeMap<>());
		if (!samples.containsKey(frame))
		{
			Model model = npc.getModel();
			if (model != null)
			{
				Map<String, Object> sample = new LinkedHashMap<>();
				sample.put("npcId", npc.getId());
				sample.put("cycle", client.getGameCycle());
				putVertices(sample, model);
				samples.put(frame, sample);
			}
		}
	}

	private void queuePoses()
	{
		toPose.forEach((modelId, sequenceIds) ->
		{
			Model model = client.loadModel(modelId);
			Map<String, Object> entry = new LinkedHashMap<>();
			posed.put(modelId, entry);
			if (model == null)
			{
				return;
			}
			models.put(modelId, model);
			Map<String, Object> rest = new LinkedHashMap<>();
			putVertices(rest, model);
			entry.put("rest", rest);

			for (int sequenceId : sequenceIds)
			{
				Animation animation = animation(sequenceId);
				if (animation == null)
				{
					continue;
				}
				Map<String, Object> sequence = new LinkedHashMap<>();
				sequence.put("maya", animation.isMayaAnim());
				sequence.put("duration", animation.getDuration());
				sequence.put("numFrames", animation.getNumFrames());
				sequence.put("poses", new ArrayList<Map<String, Object>>());
				entry.put(String.valueOf(sequenceId), sequence);

				// One past the end, to see what the client does there
				int frames = animation.isMayaAnim() ? animation.getDuration() + 1 : animation.getNumFrames();
				for (int frame = 0; frame < frames; frame++)
				{
					poseQueue.add(new int[]{modelId, sequenceId, frame});
				}
			}
		});
	}

	/** Poses one queued frame; one per cycle, in case the client prepares an animation over time. */
	@SuppressWarnings("unchecked")
	private void poseNext()
	{
		int[] job = poseQueue.poll();
		if (job == null)
		{
			return;
		}
		Model model = models.get(job[0]);
		Animation animation = animations.get(job[1]);
		Map<String, Object> sequence = (Map<String, Object>) posed.get(job[0]).get(String.valueOf(job[1]));

		// The returned model is shared and overwritten by the next call, so copy it out now
		Model transformed = client.applyTransformations(model, animation, job[2], null, 0);
		Map<String, Object> sample = new LinkedHashMap<>();
		sample.put("frame", job[2]);
		sample.put("cycle", client.getGameCycle());
		if (transformed != null)
		{
			putVertices(sample, transformed);
		}
		((List<Map<String, Object>>) sequence.get("poses")).add(sample);
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
		capture.put("posed", new LinkedHashMap<>(posed));
		capture.put("posesLeft", poseQueue.size());
		Map<Integer, Object> counts = new LinkedHashMap<>();
		counted.forEach((sequenceId, frames) ->
		{
			Animation animation = animations.get(sequenceId);
			Map<String, Object> entry = new LinkedHashMap<>();
			entry.put("duration", animation.getDuration());
			entry.put("numFrames", animation.getNumFrames());
			entry.put("frames", new ArrayList<>(frames));
			entry.put("drawn", drawn.getOrDefault(sequenceId, new TreeMap<>()));
			counts.put(sequenceId, entry);
		});
		capture.put("counted", counts);
		log.info("Skeletal capture: posed {} models ({} frames left unposed), counted {} skeletal sequences",
			posed.size(), poseQueue.size(), counts.size());

		// Off the client thread: it is several megabytes
		executor.submit(() ->
		{
			try
			{
				Filepath folder = getPluginDirectory();
				folder.createDirectories();
				Filepath file = folder.joinSegment("capture.json");
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
