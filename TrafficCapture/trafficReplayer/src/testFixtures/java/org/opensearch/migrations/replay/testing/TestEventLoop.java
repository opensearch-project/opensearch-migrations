/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.migrations.replay.testing;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.Objects;
import java.util.PriorityQueue;
import java.util.Queue;
import java.util.concurrent.RejectedExecutionException;

import org.opensearch.migrations.replay.lifecycle.ActorMailbox;

/**
 * Single-threaded event-loop fixture driven explicitly by its test.
 *
 * <p>No background thread is created. Immediate tasks retain submission order and timers retain
 * deadline then insertion order. {@link #advance(Duration)} runs every task made eligible by the
 * time change, matching the event-loop behavior expected by existing replay lifecycle tests.
 */
public final class TestEventLoop implements ActorMailbox {
    private record Timer(Instant due, long sequence, Runnable command) {}

    private final Queue<Runnable> immediate = new ArrayDeque<>();
    private final PriorityQueue<Timer> timers = new PriorityQueue<>(
        Comparator.comparing(Timer::due).thenComparingLong(Timer::sequence)
    );
    private final FakeClock clock;
    private long nextSequence;
    private boolean running;
    private boolean rejectNewTasks;

    public TestEventLoop() {
        this(new FakeClock());
    }

    public TestEventLoop(FakeClock clock) {
        this.clock = Objects.requireNonNull(clock);
    }

    @Override
    public void execute(Runnable command) {
        Objects.requireNonNull(command);
        if (rejectNewTasks) {
            throw new RejectedExecutionException("test event loop rejected task");
        }
        immediate.add(command);
    }

    @Override
    public boolean inMailbox() {
        return running;
    }

    @Override
    public Instant now() {
        return clock.instant();
    }

    @Override
    public ScheduledTask schedule(Runnable command, Duration delay) {
        Objects.requireNonNull(command);
        Objects.requireNonNull(delay);
        if (delay.isNegative()) {
            throw new IllegalArgumentException("delay must not be negative");
        }
        if (rejectNewTasks) {
            throw new RejectedExecutionException("test event loop rejected timer");
        }
        var timer = new Timer(now().plus(delay), nextSequence++, command);
        timers.add(timer);
        return () -> timers.remove(timer);
    }

    public void runUntilIdle() {
        while (!immediate.isEmpty()) {
            runNext();
        }
    }

    public void runNext() {
        if (immediate.isEmpty()) {
            throw new AssertionError("No runnable task remains");
        }
        if (running) {
            throw new AssertionError("TestEventLoop cannot be pumped recursively");
        }
        running = true;
        try {
            immediate.remove().run();
        } finally {
            running = false;
        }
    }

    public void advance(Duration duration) {
        clock.advance(duration);
        while (!timers.isEmpty() && !timers.peek().due().isAfter(now())) {
            immediate.add(timers.remove().command());
        }
        runUntilIdle();
    }

    public int pendingTasks() {
        return immediate.size();
    }

    public int pendingTimers() {
        return timers.size();
    }

    /** Mimics an event loop that accepted tasks before termination but never ran them. */
    public void dropAcceptedWork() {
        immediate.clear();
        timers.clear();
    }

    public void rejectNewTasks() {
        rejectNewTasks = true;
    }
}
