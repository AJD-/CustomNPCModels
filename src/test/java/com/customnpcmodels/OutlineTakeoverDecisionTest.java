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
package com.customnpcmodels;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

/**
 * Which of this plugin and Retro NPC Swapper draws Interact Highlight's NPC outlines.
 */
public class OutlineTakeoverDecisionTest
{
	@Test
	public void testTakesOverWhenWantedAndRetroDoesNot()
	{
		assertTrue(OutlineTakeover.shouldTakeOver(true, false, true, false));
	}

	@Test
	public void testLeavesTheOutlinesToRetroWhenItHasThem()
	{
		assertFalse(OutlineTakeover.shouldTakeOver(true, false, true, true));
	}

	@Test
	public void testKeepsTheOutlinesItAlreadyHas()
	{
		assertTrue(OutlineTakeover.shouldTakeOver(true, true, true, true));
	}

	@Test
	public void testWaitsForTheHandshake()
	{
		assertFalse(OutlineTakeover.shouldTakeOver(true, false, false, false));
	}

	@Test
	public void testLetsGoWhenNoLongerWanted()
	{
		assertFalse(OutlineTakeover.shouldTakeOver(false, true, true, false));
		assertFalse(OutlineTakeover.shouldTakeOver(false, false, true, false));
	}
}
