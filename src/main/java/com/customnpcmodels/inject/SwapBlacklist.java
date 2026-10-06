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

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import net.runelite.api.gameval.NpcID;

/**
 * NPCs that are never drawn with a custom model, whatever a bundle binds them to.
 * <p>
 * Jagex's third-party client guidelines rule out anything that adds visual indicators of a boss
 * mechanic, and name wave-based minigames - the Fight Caves and the Inferno - explicitly. A custom
 * model keeps the live animations, so an author could exaggerate an attack tell or give each attack
 * style its own look. Rather than trust every bundle, these NPCs are refused wherever bindings enter
 * the plugin, and the authoring tools refuse to build them at all.
 * <p>
 * Other bosses are not on the list. A custom model plays the NPC's own sequences on the client's
 * timing, adds no indicator and leaves the clickbox as it was, and Hub packs are reviewed before
 * they are listed, so one that exaggerates an attack tell is not published.
 */
public final class SwapBlacklist
{
	/** Each blocked NPC id and the content it belongs to, for messages. */
	private static final Map<Integer, String> CONTENT;

	static
	{
		Map<Integer, String> content = new LinkedHashMap<>();

		block(content, "the Inferno",
			NpcID.INFERNO_NIBBLER,
			NpcID.INFERNO_CREATURE_HARPIE,
			NpcID.INFERNO_CREATURE_SPLITTER,
			NpcID.INFERNO_CREATURE_SPLITTER_MAGE,
			NpcID.INFERNO_CREATURE_SPLITTER_RANGE,
			NpcID.INFERNO_CREATURE_SPLITTER_MELEE,
			NpcID.INFERNO_CREATURE_MELEE,
			NpcID.INFERNO_CREATURE_MELEE_SMALL,
			NpcID.INFERNO_CREATURE_RANGER,
			NpcID.INFERNO_CREATURE_MAGER,
			NpcID.INFERNO_JAD,
			NpcID.INFERNO_JAD_HEALER,
			NpcID.INFERNO_RANGER_FINALWAVE,
			NpcID.INFERNO_MAGER_FINALWAVE,
			NpcID.INFERNO_JAD_FINALWAVE,
			NpcID.INFERNO_JAD_HEALER_FINALWAVE,
			NpcID.INFERNO_TZKALZUK_PLACEHOLDER,
			NpcID.INFERNO_MOVING_SAFESPOT,
			NpcID.INFERNO_ZUK_HEALER,
			NpcID.INFERNO_INVISIBLE_3X3,
			NpcID.INFERNO_SAFESPOT_DYING);

		block(content, "the Fight Caves",
			NpcID.TZHAAR_FIGHTCAVE_SWARM_1A,
			NpcID.TZHAAR_FIGHTCAVE_SWARM_1B,
			NpcID.TZHAAR_FIGHTCAVE_SWARM_2A,
			NpcID.TZHAAR_FIGHTCAVE_SWARM_2B,
			NpcID.TZHAAR_FIGHTCAVE_SWARM_2SPAWN,
			NpcID.TZHAAR_FIGHTCAVE_SWARM_3A,
			NpcID.TZHAAR_FIGHTCAVE_SWARM_3B,
			NpcID.TZHAAR_FIGHTCAVE_SWARM_4A,
			NpcID.TZHAAR_FIGHTCAVE_SWARM_4B,
			NpcID.TZHAAR_FIGHTCAVE_SWARM_5A,
			NpcID.TZHAAR_FIGHTCAVE_SWARM_5B,
			NpcID.TZHAAR_FIGHTCAVE_SWARM_BOSS,
			NpcID.TZHAAR_FIGHTCAVE_SWARM_BOSS_CLERIC,
			NpcID.CLANCUP_TZHAAR_FIGHTCAVE_SWARM_BOSS);

		// The same TzHaar wave monsters under their fight pit ids
		block(content, "the TzHaar fight pits",
			NpcID.TZHAAR_FIGHTPIT_SWARM_1A,
			NpcID.TZHAAR_FIGHTPIT_SWARM_1B,
			NpcID.TZHAAR_FIGHTPIT_SWARM_2A,
			NpcID.TZHAAR_FIGHTPIT_SWARM_2B,
			NpcID.TZHAAR_FIGHTPIT_SWARM_3A,
			NpcID.TZHAAR_FIGHTPIT_SWARM_3B,
			NpcID.TZHAAR_FIGHTPIT_SWARM_4A,
			NpcID.TZHAAR_FIGHTPIT_SWARM_4B,
			NpcID.TZHAAR_FIGHTPIT_SWARM_BOSS);

		block(content, "TzHaar-Ket-Rak's challenges",
			NpcID.JAD_CHALLENGE_JAD,
			NpcID.JAD_CHALLENGE_HEALER);

		block(content, "the Fortis Colosseum",
			NpcID.COLOSSEUM_GLORY,
			NpcID.COLOSSEUM_JAGUAR_WARRIOR,
			NpcID.COLOSSEUM_STANDARD_MAGER,
			NpcID.COLOSSEUM_MINOTAUR,
			NpcID.COLOSSEUM_MINOTAUR_ROUTEFIND,
			NpcID.COLOSSEUM_WARBANDER_RANGED_FEMALE,
			NpcID.COLOSSEUM_WARBANDER_MAGE_MALE,
			NpcID.COLOSSEUM_WARBANDER_MELEE_MALE,
			NpcID.COLOSSEUM_JAVELIN_COLOSSUS,
			NpcID.COLOSSEUM_MANTICORE,
			NpcID.COLOSSEUM_SHOCKWAVE_COLOSSUS,
			NpcID.COLOSSEUM_SAFESPOT_DYING,
			NpcID.COLOSSEUM_SOL_P1,
			NpcID.COLOSSEUM_DOOM_SCORPION,
			NpcID.COLOSSEUM_MODIFIER_BEES,
			NpcID.COLOSSEUM_BEAM_CRYSTAL,
			NpcID.COLOSSEUM_HEALING_TOTEM,
			NpcID.COLOSSEUM_SOLAR_FLARE,
			NpcID.COLOSSEUM_BOSS_SEATED,
			NpcID.COLOSSEUM_HUMAN_GIB,
			NpcID.COLOSSEUM_MANTICORE_GIB,
			NpcID.COLOSSEUM_MINOTAUR_GIB,
			NpcID.COLOSSEUM_COLOSSI_GIB,
			NpcID.COLOSSEUM_SOL_GIB);

		// Deadman copies of the monsters above
		block(content, "Deadman mode",
			NpcID.DEADMAN_BREACH_INFERNO_MELEE,
			NpcID.DEADMAN_BREACH_JAGUAR_WARRIOR,
			NpcID.DEADMAN_BREACH_JAD,
			NpcID.DEADMAN_BREACH_SOL_HEREDIT,
			NpcID.DEADMAN_BREACH_JAD_MINION,
			NpcID.DEADMAN_ALL_STARS_MISSION_SOL_HEREDIT,
			NpcID.DEADMAN_ALL_STARS_MISSION_JAD);

		CONTENT = Collections.unmodifiableMap(content);
	}

	private SwapBlacklist()
	{
	}

	private static void block(Map<Integer, String> content, String name, int... npcIds)
	{
		for (int npcId : npcIds)
		{
			content.put(npcId, name);
		}
	}

	public static boolean isBlocked(int npcId)
	{
		return CONTENT.containsKey(npcId);
	}

	/** The content a blocked NPC belongs to, such as "the Inferno", or null when it is not blocked. */
	public static String contentOf(int npcId)
	{
		return CONTENT.get(npcId);
	}
}
