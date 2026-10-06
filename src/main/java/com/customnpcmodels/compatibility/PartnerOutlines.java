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

import java.util.function.Function;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Model;
import net.runelite.api.NPC;

/**
 * What Retro NPC Swapper last said about Interact Highlight's NPC outlines: whether it draws them,
 * and how to pose the NPCs it swaps so ours can be drawn around them. Recorded as soon as a message
 * arrives, on whatever thread posted it, so a decision made after it always sees it.
 */
@Singleton
@Slf4j
public class PartnerOutlines
{
	private volatile boolean owning;
	private volatile Function<NPC, Model> poser;

	// Whether this start has asked Retro for its state, and so whether its silence means it is absent
	private volatile boolean handshakeDone;

	public void accept(ModelSwapProtocol.Outlines outlines)
	{
		owning = outlines.isOwning();
		poser = outlines.getPoser();
	}

	/** Whether Retro has taken Interact Highlight's NPC outlines over. */
	public boolean isOwning()
	{
		return owning;
	}

	public boolean isHandshakeDone()
	{
		return handshakeDone;
	}

	public void setHandshakeDone()
	{
		handshakeDone = true;
	}

	/** Forgets Retro's state, as it stops. */
	public void forget()
	{
		owning = false;
		poser = null;
	}

	/** Forgets everything, as this plugin stops. */
	public void reset()
	{
		forget();
		handshakeDone = false;
	}

	/**
	 * The model Retro draws an NPC with, or null when it draws none for it. Client thread only, and
	 * the model is overwritten by Retro's next pose, so it has to be used straight away.
	 */
	public Model pose(NPC npc)
	{
		Function<NPC, Model> partnerPoser = poser;
		if (partnerPoser == null)
		{
			return null;
		}

		// Another plugin's code: whatever goes wrong in it costs that NPC its outline, not the frame
		try
		{
			return partnerPoser.apply(npc);
		}
		catch (RuntimeException e)
		{
			log.debug("Retro NPC Swapper could not pose NPC {}", npc.getId(), e);
			return null;
		}
	}
}
