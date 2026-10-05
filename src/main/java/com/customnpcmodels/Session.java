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

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicInteger;
import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * Whether the plugin is running, and which start it is on, for work that comes back from another
 * thread to check before it acts. Also runs the plugin's disk work, so a stop can cancel all of it.
 */
@Singleton
class Session
{
	@Inject
	private ScheduledExecutorService executor;

	/** Work submitted and not yet known to be done, so {@link #stop} can cancel it. */
	private final Set<Future<?>> submitted = ConcurrentHashMap.newKeySet();

	/**
	 * Whether this plugin is still running, read by anything coming back from another thread.
	 *
	 * <p>Cancelling a task cannot stop one already past its own read, nor a {@code clientThread}
	 * runnable it has already queued, so this flag is what actually closes the window - cancel only
	 * keeps a task that never started from starting.
	 */
	private volatile boolean active;

	/**
	 * Bumped on every start and stop. Work queued off the client thread carries the value it was
	 * queued under and is dropped when that is stale, so a read queued before a quick restart cannot
	 * land in the plugin that replaced it - which {@link #active} alone cannot tell apart.
	 */
	private final AtomicInteger generation = new AtomicInteger();

	void start()
	{
		active = true;
		generation.incrementAndGet();
	}

	/** Ends the session, and cancels any work submitted under it that has not started. */
	void stop()
	{
		active = false;
		generation.incrementAndGet();

		// The executor is RuneLite's, so only the work we put on it gets cancelled. Work that's
		// already running carries on to its own isCurrent check, which drops its result.
		for (Future<?> future : submitted)
		{
			future.cancel(false);
		}
		submitted.clear();
	}

	/** Runs {@code task} on RuneLite's executor, to be cancelled by {@link #stop} if it has not started. */
	void submit(Runnable task)
	{
		submitted.removeIf(Future::isDone);
		submitted.add(executor.submit(task));
	}

	boolean isActive()
	{
		return active;
	}

	/** The token to queue work under, for {@link #isCurrent} to check when it comes back. */
	int token()
	{
		return generation.get();
	}

	/** Whether work queued under {@code token} still belongs to the running plugin. */
	boolean isCurrent(int token)
	{
		return active && token == generation.get();
	}
}
