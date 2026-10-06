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
package com.customnpcmodels.chathead;

import static org.junit.Assert.assertEquals;
import org.junit.Test;

public class ChatheadCameraTest
{
	private static double[] project(ChatheadCamera camera, double x, double y, double z)
	{
		double[] out = new double[3];
		camera.project(x, y, z, out);
		return out;
	}

	/** Looking straight on, a head's origin is the center of its widget, zoom away. */
	@Test
	public void testStraightOnTheOriginIsTheCenter()
	{
		double[] p = project(new ChatheadCamera(0, 0, 0, 800), 0, 0, 0);

		assertEquals(0, p[0], 1e-9);
		assertEquals(0, p[1], 1e-9);
		assertEquals(800, p[2], 1e-9);
	}

	/** A quarter turn of yaw (512 of 2048) swings a point on +x round onto the view axis. */
	@Test
	public void testYawTurnsAboutTheVerticalAxis()
	{
		double[] p = project(new ChatheadCamera(0, 0, 512, 800), 100, 0, 0);

		assertEquals(0, p[0], 1e-6);
		assertEquals(700, p[2], 1e-6);
	}

	/** Farther points are drawn smaller. */
	@Test
	public void testPerspective()
	{
		double[] near = project(new ChatheadCamera(0, 0, 0, 400), 50, 0, 0);
		double[] far = project(new ChatheadCamera(0, 0, 0, 800), 50, 0, 0);

		assertEquals(near[0] / 2, far[0], 1e-9);
	}
}
