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

import java.util.Set;
import java.util.function.Supplier;
import net.runelite.api.hooks.DrawCallbacks;

/**
 * Sees through the stack of model-substituting decorators sitting in the draw callbacks slot.
 * <p>
 * This plugin and Retro NPC Swapper each wrap the renderer to swap NPC models, and can stack on
 * top of one another. They are loaded by different classloaders, so neither can name the other's
 * decorator type. Instead, each decorator also implements {@link Supplier}, a JDK type both can see,
 * returning the callbacks it wraps. Only the decorators named here are unwrapped: anything else is
 * treated as the bottom of the chain, and a partner build too old to implement {@code Supplier} is
 * not unwrapped either, so the two keep standing each other down exactly as before.
 */
public final class RendererChain
{
	/** Decorators known to forward everything but {@code drawTemp} and to supply their delegate. */
	static final Set<String> KNOWN_DECORATORS = Set.of(
			"com.customnpcmodels.CustomDrawCallbacks", "com.retronpcswapper.RetroDrawCallbacks");

	/** More than the two known decorators could ever stack; stops a pathological chain. */
	private static final int MAX_DEPTH = 8;

	private RendererChain()
	{
	}

	/**
	 * Whether {@code callbacks}, whose class is {@code className}, is a known decorator whose delegate
	 * can be read.
	 */
	static boolean canUnwrap(String className, DrawCallbacks callbacks)
	{
		return KNOWN_DECORATORS.contains(className) && callbacks instanceof Supplier;
	}

	/**
	 * The callbacks directly beneath a known decorator, or null when {@code callbacks} is not one.
	 */
	private static DrawCallbacks unwrap(DrawCallbacks callbacks)
	{
		if (callbacks == null || !canUnwrap(callbacks.getClass().getName(), callbacks))
		{
			return null;
		}

		Object delegate = ((Supplier<?>) callbacks).get();
		return delegate instanceof DrawCallbacks && delegate != callbacks ? (DrawCallbacks) delegate : null;
	}

	/**
	 * The renderer at the bottom of the chain: {@code top} with every known decorator peeled off.
	 */
	public static DrawCallbacks base(DrawCallbacks top)
	{
		DrawCallbacks current = top;
		for (int depth = 0; depth < MAX_DEPTH; depth++)
		{
			DrawCallbacks next = unwrap(current);
			if (next == null)
			{
				return current;
			}
			current = next;
		}
		return current;
	}

	/**
	 * Whether {@code target} is anywhere in the chain starting at {@code top}, including the top.
	 */
	public static boolean contains(DrawCallbacks top, DrawCallbacks target)
	{
		if (target == null)
		{
			return false;
		}

		DrawCallbacks current = top;
		for (int depth = 0; depth <= MAX_DEPTH && current != null; depth++)
		{
			if (current == target)
			{
				return true;
			}
			current = unwrap(current);
		}
		return false;
	}

	/**
	 * The first decorator of exactly {@code type} in the chain starting at {@code top}, or null.
	 */
	public static <T extends DrawCallbacks> T find(DrawCallbacks top, Class<T> type)
	{
		DrawCallbacks current = top;
		for (int depth = 0; depth <= MAX_DEPTH && current != null; depth++)
		{
			if (current.getClass() == type)
			{
				return type.cast(current);
			}
			current = unwrap(current);
		}
		return null;
	}
}
