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

import lombok.Getter;

/**
 * Which NPCs wear a custom model, and how it is dressed for them.
 * <p>
 * Carried in the bundle rather than written in code, so adding a model is an authoring job - a
 * glTF file and a manifest entry - and never a plugin change. Animation needs nothing here beyond
 * the rig: clips are keyed by that rig and the live sequence ids the NPC already plays.
 * <p>
 * The accessors hand back the live arrays. Nothing mutates a binding once it is built.
 */
@Getter
public final class NpcBinding
{
	/** For logging only - the manifest entry this came from. */
	private final String name;

	private final int[] npcIds;

	/** Bundle meshes merged, in order, into the model these NPCs are drawn with. */
	private final int[] meshIds;

	/**
	 * The rig this model's clips are expressed against, or {@link #STATIC} for a model with none.
	 * Clips are looked up by this and the live sequence together, so a model is only ever posed by
	 * its own clips - never by another model's that happens to answer for the same sequence.
	 */
	private final int rigId;

	/**
	 * Resize in 1/128ths, applied to the posed model - after animation, as the client resizes an
	 * NPC. 128 is unscaled.
	 */
	private final int scaleXZ;
	private final int scaleY;

	/** Palette recolor pairs, parallel arrays of packed HSL. Both null when there are none. */
	private final short[] recolorFind;
	private final short[] recolorReplace;

	/**
	 * The NPC definition's lighting adjustments, as the cache stores them (opcodes 100 and 101, one
	 * signed byte each). 0 for both is the usual case.
	 */
	private final int ambient;
	private final int contrast;

	/**
	 * The NPC whose chathead these NPCs show in dialogue instead of their own, or
	 * {@link #NO_CHATHEAD} to keep their own. A dialogue head can only draw from the game cache, so
	 * this names a cache NPC rather than a bundle mesh.
	 */
	private final int chatheadNpcId;

	/**
	 * The bundle mesh these NPCs show as their chathead in dialogue, drawn by the plugin, or
	 * {@link #NO_CHATHEAD}. A binding names this or {@link #chatheadNpcId}, never both.
	 */
	private final int chatheadMeshId;

	/** The rig the chathead mesh's emote clips are keyed by, or {@link #STATIC} for an unrigged head. */
	private final int chatheadRigId;

	/** The {@link #rigId} of a model with no rig, which only ever draws in its rest pose. */
	public static final int STATIC = -1;

	/** The {@link #chatheadNpcId} of a model that leaves the NPCs' own chathead alone. */
	public static final int NO_CHATHEAD = -1;

	public NpcBinding(String name, int[] npcIds, int[] meshIds, int rigId, int scaleXZ, int scaleY,
		short[] recolorFind, short[] recolorReplace, int ambient, int contrast, int chatheadNpcId,
		int chatheadMeshId, int chatheadRigId)
	{
		this.name = name;
		this.npcIds = npcIds;
		this.meshIds = meshIds;
		this.rigId = rigId;
		this.scaleXZ = scaleXZ;
		this.scaleY = scaleY;
		this.recolorFind = recolorFind;
		this.recolorReplace = recolorReplace;
		this.ambient = ambient;
		this.contrast = contrast;
		this.chatheadNpcId = chatheadNpcId;
		this.chatheadMeshId = chatheadMeshId;
		this.chatheadRigId = chatheadRigId;
	}

	public boolean hasRecolors()
	{
		return recolorFind != null && recolorFind.length > 0;
	}

	public boolean hasChathead()
	{
		return chatheadNpcId != NO_CHATHEAD;
	}

	public boolean hasCustomChathead()
	{
		return chatheadMeshId != NO_CHATHEAD;
	}
}
