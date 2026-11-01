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

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Model;
import net.runelite.api.NPC;

/**
 * What each partner in {@link ModelSwapProtocol#PARTNERS} last said about Interact Highlight's NPC
 * outlines: whether it draws them, and how to pose the NPCs it swaps so ours can be drawn around
 * them.
 * <p>
 * Recorded as soon as a message arrives, on whatever thread posted it, so a decision made after it
 * always sees it. Our own messages and those of unknown sources are ignored. State is kept per
 * source, so one partner stopping ({@link #forget}) leaves the other's alone.
 * <p>
 * {@link #pose(NPC)} tries the partners' posers in {@link ModelSwapProtocol#PARTNERS} order and
 * takes the first model, so Custom Servants' wins over Retro NPC Swapper's: a servant it claims is
 * its to draw, so its poser has the model on screen. A partner that draws nothing for the NPC, or
 * fails to pose it, passes it on to the next. Posing is client thread only.
 */
@Singleton
@Slf4j
public class PartnerOutlines
{
	// Written by accept, on the sender's thread, and read on the client thread
	private final Map<String, ModelSwapProtocol.Outlines> bySource = new ConcurrentHashMap<>();

	// Whether this start has asked the partners for their state, and so whether their silence means
	// they are absent
	private volatile boolean handshakeDone;

	/**
	 * Records {@code source}'s outlines state, replacing what it said before. A source not in
	 * {@link ModelSwapProtocol#PARTNERS}, ours included, is ignored. Any thread.
	 */
	public void accept(String source, ModelSwapProtocol.Outlines outlines)
	{
		// List.of throws on a null lookup, and a partner's message must not throw in our handler
		if (source == null || outlines == null || !ModelSwapProtocol.PARTNERS.contains(source))
		{
			log.debug("Ignoring outlines {} from {}", outlines, source);
			return;
		}

		bySource.put(source, outlines);
	}

	/** Forgets {@code source}'s state, as it stops. Any thread. */
	public void forget(String source)
	{
		if (source != null)
		{
			bySource.remove(source);
		}
	}

	/** Whether {@code source} has taken Interact Highlight's NPC outlines over. Any thread. */
	public boolean isOwning(String source)
	{
		ModelSwapProtocol.Outlines outlines = source == null ? null : bySource.get(source);
		return outlines != null && outlines.isOwning();
	}

	/** Whether any partner has taken Interact Highlight's NPC outlines over. Any thread. */
	public boolean isAnyOwning()
	{
		for (String source : ModelSwapProtocol.PARTNERS)
		{
			if (isOwning(source))
			{
				return true;
			}
		}
		return false;
	}

	public boolean isHandshakeDone()
	{
		return handshakeDone;
	}

	public void setHandshakeDone()
	{
		handshakeDone = true;
	}

	/** Forgets everything, as this plugin stops. Any thread. */
	public void reset()
	{
		bySource.clear();
		handshakeDone = false;
	}

	/**
	 * The model a partner draws an NPC with, trying them in {@link ModelSwapProtocol#PARTNERS}
	 * order, or null when none draws one for it. Client thread only, and the model is overwritten
	 * by that partner's next pose, so it has to be used straight away.
	 */
	public Model pose(NPC npc)
	{
		for (String source : ModelSwapProtocol.PARTNERS)
		{
			Model model = pose(source, npc);
			if (model != null)
			{
				return model;
			}
		}
		return null;
	}

	/**
	 * The model {@code source} draws an NPC with, or null when it has no poser or draws none for
	 * it. Client thread only, and the model is overwritten by that partner's next pose, so it has to
	 * be used straight away.
	 */
	public Model pose(String source, NPC npc)
	{
		ModelSwapProtocol.Outlines outlines = source == null ? null : bySource.get(source);
		Function<NPC, Model> poser = outlines == null ? null : outlines.getPoser();
		if (poser == null)
		{
			return null;
		}

		// Another plugin's code: whatever goes wrong in it costs that NPC its outline, not the frame
		try
		{
			return poser.apply(npc);
		}
		catch (RuntimeException e)
		{
			log.debug("{} could not pose NPC {}", source, npc.getId(), e);
			return null;
		}
	}
}
