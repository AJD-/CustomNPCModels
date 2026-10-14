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
 * lives in the test source set and ./gradlew run loads it only when given {@code -Pcapture=<npc id>}.
 * <p>
 * For the first NPC with that id it records, every client cycle, the action and pose sequences and
 * the frames the client reports for them. For each sequence it sees it records the client's own
 * length for it, and the NPC's first model posed by the client at every frame through
 * {@link Client#applyTransformations}. It also keeps, once per sequence and frame, the vertices of
 * the model the client posed for the NPC itself, which ties those frames to what is drawn. After
 * {@link #CYCLES} cycles it writes it all to {@code capture-<npc id>.json} in its data folder.
 */
@Slf4j
@PluginDescriptor(
	name = "Skeletal Capture (dev)",
	internalName = "custom-npc-models-capture",
	developerPlugin = true
)
public class SkeletalCapturePlugin extends Plugin
{
	public static final String NPC_PROPERTY = "customnpcmodels.capture";

	/** 30 seconds: long enough to watch the NPC stand through a loop, then walk through one. */
	private static final int CYCLES = 1500;

	@Inject
	private Client client;

	@Inject
	private ClientThread clientThread;

	@Inject
	private Gson gson;

	@Inject
	private ScheduledExecutorService executor;

	private int npcId;
	private NPC target;
	private boolean written;

	private final List<int[]> cycles = new ArrayList<>();
	private final Map<Integer, Map<String, Object>> sequences = new LinkedHashMap<>();
	private final List<Map<String, Object>> live = new ArrayList<>();
	private final Set<Long> liveSeen = new HashSet<>();

	@Override
	protected void startUp()
	{
		npcId = Integer.getInteger(NPC_PROPERTY, -1);
		log.info("Skeletal capture waiting for NPC {}", npcId);
	}

	@Override
	protected void shutDown()
	{
		target = null;
	}

	@Subscribe
	public void onNpcSpawned(NpcSpawned event)
	{
		if (target == null && event.getNpc().getId() == npcId)
		{
			target = event.getNpc();
		}
	}

	@Subscribe
	public void onNpcDespawned(NpcDespawned event)
	{
		if (event.getNpc() == target)
		{
			target = null;
		}
	}

	@Subscribe
	public void onClientTick(ClientTick event)
	{
		if (target == null || written)
		{
			return;
		}

		int action = target.getAnimation();
		int actionFrame = target.getAnimationFrame();
		int pose = target.getPoseAnimation();
		int poseFrame = target.getPoseAnimationFrame();
		cycles.add(new int[]{client.getGameCycle(), action, actionFrame, pose, poseFrame});

		captureSequence(action);
		captureSequence(pose);

		// The action wins over the pose when there is one, as the client draws it
		int shown = action != -1 ? action : pose;
		int shownFrame = action != -1 ? actionFrame : poseFrame;
		if (shown != -1 && liveSeen.add((long) shown << 32 | (shownFrame & 0xFFFFFFFFL)))
		{
			Model model = target.getModel();
			if (model != null)
			{
				Map<String, Object> sample = new LinkedHashMap<>();
				sample.put("sequence", shown);
				sample.put("frame", shownFrame);
				sample.put("cycle", client.getGameCycle());
				putVertices(sample, model);
				live.add(sample);
			}
		}

		if (cycles.size() >= CYCLES)
		{
			written = true;
			write();
		}
	}

	private void captureSequence(int sequenceId)
	{
		if (sequenceId == -1 || sequences.containsKey(sequenceId))
		{
			return;
		}

		Map<String, Object> entry = new LinkedHashMap<>();
		sequences.put(sequenceId, entry);
		Animation animation = client.loadAnimation(sequenceId);
		if (animation == null)
		{
			return;
		}
		entry.put("maya", animation.isMayaAnim());
		entry.put("duration", animation.getDuration());
		entry.put("numFrames", animation.getNumFrames());
		entry.put("frameLengths", animation.getFrameLengths());

		int[] models = target.getComposition().getModels();
		if (models == null || models.length == 0)
		{
			return;
		}
		entry.put("modelId", models[0]);
		Model model = client.loadModel(models[0]);
		if (model == null)
		{
			return;
		}

		// One past the end, to see what the client does there
		int frames = animation.isMayaAnim() ? animation.getDuration() + 1 : animation.getNumFrames();
		List<Map<String, Object>> posed = new ArrayList<>();
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
			posed.add(sample);
		}
		entry.put("posed", posed);
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
		capture.put("npcId", npcId);
		capture.put("cycles", new ArrayList<>(cycles));
		capture.put("sequences", new LinkedHashMap<>(sequences));
		capture.put("live", new ArrayList<>(live));

		// Off the client thread: it is several megabytes
		executor.submit(() ->
		{
			try
			{
				Filepath file = getPluginDirectory().joinSegment("capture-" + npcId + ".json");
				try (Writer writer = file.openBufferedWriter())
				{
					gson.toJson(capture, writer);
				}
				log.info("Skeletal capture written to {}", file);
				clientThread.invokeLater(() -> client.addChatMessage(ChatMessageType.GAMEMESSAGE, "",
					"Skeletal capture written to " + file, null));
			}
			catch (IOException ex)
			{
				log.warn("Skeletal capture could not be written", ex);
			}
		});
	}
}
