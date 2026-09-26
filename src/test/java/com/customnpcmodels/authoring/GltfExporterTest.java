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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import net.runelite.api.gameval.NpcID;
import net.runelite.cache.definitions.NpcDefinition;
import org.junit.Test;

/** Which sequences an export takes when none are named. */
public class GltfExporterTest
{
	/**
	 * The mole's definition names only standing and walking, but its rig carries its digging, death
	 * and attack sequences too - the ones a Blender edit has to keep.
	 */
	@Test
	public void testDefaultsToEverySequenceOnTheRig() throws Exception
	{
		List<String> report = new ArrayList<>();
		List<Integer> sequences = new ArrayList<>(GltfExporter.defaultSequences(LiveFixtures.store(),
			LiveFixtures.npc(NpcID.MOLE_GIANT), report::add));

		assertEquals(Arrays.asList(LiveFixtures.MOLE_READY, LiveFixtures.MOLE_WALK, 3310, 3311, 3312, 3314, 3315),
			sequences);
		assertEquals(1, report.size());
	}

	/** The humanoid rig animates thousands of sequences, so only the definition's own are taken. */
	@Test
	public void testFallsBackToTheDefinitionOnASharedRig() throws Exception
	{
		NpcDefinition man = LiveFixtures.npc(NpcID.MAN);
		List<String> report = new ArrayList<>();
		List<Integer> sequences = new ArrayList<>(GltfExporter.defaultSequences(LiveFixtures.store(), man, report::add));

		assertEquals(man.standingAnimation, (int) sequences.get(0));
		assertTrue("sequences " + sequences, sequences.size() <= 15);
		assertTrue("report " + report, report.size() == 1 && report.get(0).contains("-Pseqs"));
	}
}
