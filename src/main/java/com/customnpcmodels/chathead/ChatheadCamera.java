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

import net.runelite.api.widgets.Widget;

/**
 * Where the client draws an interface model, chatheads among them: the head turns (roll, then yaw)
 * about its origin, which is the widget's center, and is pushed {@code zoom} away along a view
 * pitched down by {@code pitch}, as the client's widget model drawing does. Angles are in the
 * client's 2048ths of a turn; the projection's focal length is the interface one.
 * <p>
 * Which of the widget's rotations is which axis is set in {@link #of} alone, so a mismatch found
 * against the vanilla head is a one-line fix.
 */
public final class ChatheadCamera
{
	/** The client's focal length for interface models. */
	public static final int FOCAL = 512;

	private static final double UNIT = 2 * Math.PI / 2048;

	private final double pitch;
	private final double roll;
	private final double yaw;
	private final int zoom;

	public ChatheadCamera(int pitch, int roll, int yaw, int zoom)
	{
		this.pitch = pitch * UNIT;
		this.roll = roll * UNIT;
		this.yaw = yaw * UNIT;
		this.zoom = zoom;
	}

	/** The camera a dialogue head widget draws its model with. */
	public static ChatheadCamera of(Widget widget)
	{
		return new ChatheadCamera(widget.getRotationX(), widget.getRotationY(), widget.getRotationZ(), widget.getModelZoom());
	}

	/**
	 * An engine point (y down) on screen: x and y from the center of the widget, and in {@code out[2]}
	 * the depth in front of the camera.
	 */
	public void project(double x, double y, double z, double[] out)
	{
		double cr = Math.cos(roll);
		double sr = Math.sin(roll);
		double x1 = y * sr + x * cr;
		double y1 = y * cr - x * sr;

		double cy = Math.cos(yaw);
		double sy = Math.sin(yaw);
		double x2 = z * sy + x1 * cy;
		double z2 = z * cy - x1 * sy;

		double cp = Math.cos(pitch);
		double sp = Math.sin(pitch);
		double y2 = y1 + sp * zoom;
		double z3 = z2 + cp * zoom;
		double y3 = y2 * cp - z3 * sp;
		double z4 = y2 * sp + z3 * cp;

		out[0] = x2 * FOCAL / z4;
		out[1] = y3 * FOCAL / z4;
		out[2] = z4;
	}
}
