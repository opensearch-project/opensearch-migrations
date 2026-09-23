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
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.PriorityQueue;
import java.util.Queue;
import java.util.concurrent.Callable;
import java.util.concurrent.Delayed;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelPromise;
import io.netty.channel.DefaultChannelPromise;
import io.netty.channel.EventLoop;
import io.netty.channel.EventLoopGroup;
import io.netty.util.concurrent.AbstractEventExecutor;
import io.netty.util.concurrent.BlockingOperationException;
import io.netty.util.concurrent.DefaultPromise;
import io.netty.util.concurrent.EventExecutor;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.Promise;
import io.netty.util.concurrent.ScheduledFuture;

/**
 * Single-threaded event-loop fixture driven explicitly by its test.
 *
 * <p>No background thread is created. Immediate tasks retain submission order and timers retain
 * deadline then insertion order. {@link #advance(Duration)} runs every task made eligible by the
 * time change, matching the event-loop behavior expected by existing replay lifecycle tests.
 *
 * <p>This is a real Netty {@link EventLoop} so that owners can be assigned to it exactly as
 * {@code replayerConnectionAndRequestLowLevelDesign.md} section 1 requires — there is no owner
 * executor, and every owner asserts affinity with {@link #inEventLoop()}. It must <em>not</em> extend
 * {@code AbstractScheduledEventExecutor}: Netty's scheduler reads {@code System.nanoTime()}, which would
 * make timer expiry depend on wall-clock time. Timers here are queued against the injected
 * {@link FakeClock} and fire only when a test advances it.
 *
 * <p>{@link #inEventLoop()} is true only while a task submitted to this loop is running, so state
 * touched from outside a pumped task fails an affinity assertion rather than racing.
 *
 * <p>Nothing on this fixture blocks. {@link #awaitTermination(long, TimeUnit)} reports current
 * state instead of waiting, and waiting on a timer future throws, because no other thread exists
 * to make progress on this loop's behalf.
 *
 * <p>One thing it cannot do: hold a real Netty channel. Netty's built-in channels gate
 * registration on their own loop type, so they reject every custom {@code EventLoop} -- see
 * {@link #register(Channel)}. Owners do not need that combination, because target I/O reaches them
 * through {@code TargetChannelPort} rather than through a channel registered to their loop.
 */
public final class TestEventLoop extends AbstractEventExecutor implements EventLoop {
    /**
     * Bound on how many times {@link #advance(Duration)} re-drains timers that came due while
     * earlier timers ran. Exceeding it means a timer keeps rescheduling itself at or before the
     * current time, which would spin forever; failing beats hanging.
     */
    private static final int MAX_TIMER_ROUNDS_PER_ADVANCE = 10_000;

    private final Queue<Runnable> immediate = new ArrayDeque<>();
    private final PriorityQueue<ScheduledTimer<?>> timers = new PriorityQueue<>(
        Comparator.<ScheduledTimer<?>, Instant>comparing(timer -> timer.due)
            .thenComparingLong(timer -> timer.sequence)
    );
    private final FakeClock clock;
    private final Promise<Void> terminationFuture = new DefaultPromise<>(this);
    private long nextSequence;
    private boolean running;
    private Thread pumpingThread;
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
    public boolean inEventLoop(Thread thread) {
        Objects.requireNonNull(thread);
        return running && thread == pumpingThread;
    }

    public Instant now() {
        return clock.instant();
    }

    /**
     * Schedules against the injected clock. Unlike the {@link ScheduledFuture}-contract overloads,
     * a negative delay is a test bug rather than a request to run immediately, so it throws.
     */
    public ScheduledFuture<?> schedule(Runnable command, Duration delay) {
        Objects.requireNonNull(command);
        Objects.requireNonNull(delay);
        if (delay.isNegative()) {
            throw new IllegalArgumentException("delay must not be negative");
        }
        return addTimer(delay, asCallable(command));
    }

    @Override
    public ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
        Objects.requireNonNull(command);
        return addTimer(nonNegativeDelay(delay, unit), asCallable(command));
    }

    @Override
    public <V> ScheduledFuture<V> schedule(Callable<V> command, long delay, TimeUnit unit) {
        Objects.requireNonNull(command);
        return addTimer(nonNegativeDelay(delay, unit), command);
    }

    public void runUntilIdle() {
        while (!immediate.isEmpty()) {
            runNext();
        }
        completeTerminationIfIdle();
    }

    public void runNext() {
        if (immediate.isEmpty()) {
            throw new AssertionError("No runnable task remains");
        }
        if (running) {
            throw new AssertionError("TestEventLoop cannot be pumped recursively");
        }
        running = true;
        pumpingThread = Thread.currentThread();
        try {
            immediate.remove().run();
        } finally {
            running = false;
            pumpingThread = null;
        }
    }

    /**
     * Moves the clock forward and settles the loop: every timer the new time makes due runs, and
     * so does every timer that becomes due while those run, because a real event loop would not
     * leave an already-expired deadline pending.
     */
    public void advance(Duration duration) {
        clock.advance(duration);
        var rounds = 0;
        do {
            if (++rounds > MAX_TIMER_ROUNDS_PER_ADVANCE) {
                throw new AssertionError(
                    "advance() ran "
                        + MAX_TIMER_ROUNDS_PER_ADVANCE
                        + " rounds of due timers without settling; a timer is rescheduling itself"
                        + " at or before the current time"
                );
            }
            promoteDueTimers();
            runUntilIdle();
        } while (hasDueTimer());
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
        completeTerminationIfIdle();
    }

    public void rejectNewTasks() {
        rejectNewTasks = true;
    }

    @Override
    public void shutdown() {
        rejectNewTasks();
        completeTerminationIfIdle();
    }

    @Override
    public List<Runnable> shutdownNow() {
        var dropped = List.copyOf(immediate);
        rejectNewTasks();
        dropAcceptedWork();
        return dropped;
    }

    @Override
    public Future<?> shutdownGracefully(long quietPeriod, long timeout, TimeUnit unit) {
        shutdown();
        return terminationFuture;
    }

    @Override
    public Future<?> terminationFuture() {
        return terminationFuture;
    }

    @Override
    public boolean isShuttingDown() {
        return rejectNewTasks;
    }

    @Override
    public boolean isShutdown() {
        return rejectNewTasks;
    }

    @Override
    public boolean isTerminated() {
        return rejectNewTasks && immediate.isEmpty() && timers.isEmpty();
    }

    /** Reports current state rather than waiting; no other thread can advance this loop. */
    @Override
    public boolean awaitTermination(long timeout, TimeUnit unit) {
        return isTerminated();
    }

    @Override
    public EventLoop next() {
        return this;
    }

    @Override
    public EventLoopGroup parent() {
        return this;
    }

    @Override
    public Iterator<EventExecutor> iterator() {
        return List.<EventExecutor>of(this).iterator();
    }

    /**
     * Throws, and the throwing is the feature. Every channel Netty ships checks the loop's concrete type
     * in {@code isCompatible} and rejects this one by calling {@code promise.setFailure} and returning —
     * so a caller that does not inspect the returned future sees a channel that silently never registered,
     * and then hangs. Failing at the call site turns that into a message on the responsible line.
     */
    @Override
    public ChannelFuture register(Channel channel) {
        throw cannotHoldChannels();
    }

    @Override
    public ChannelFuture register(ChannelPromise promise) {
        throw cannotHoldChannels();
    }

    @Override
    @Deprecated
    public ChannelFuture register(Channel channel, ChannelPromise promise) {
        throw cannotHoldChannels();
    }

    private static UnsupportedOperationException cannotHoldChannels() {
        return new UnsupportedOperationException(
            "TestEventLoop cannot hold a Netty channel, and no built-in channel can register to any"
                + " custom EventLoop: LocalChannel and SimpleNettyHttpServer clients require"
                + " SingleThreadEventLoop, NIO channels require NioEventLoop, EmbeddedChannel requires"
                + " EmbeddedEventLoop. Becoming one of those would restore the wall-clock scheduler and"
                + " the real thread this fixture exists to avoid, so deterministic time and a real"
                + " channel cannot be combined in one test. Pick a tier: TestEventLoop + FakeClock with"
                + " a fake TargetChannelPort for owner logic, ordering, timers and cancellation; or a"
                + " real channel on a real NioEventLoopGroup with real time for integration."
                + " See AGENTS.md section 4."
        );
    }

    private <V> ScheduledFuture<V> addTimer(Duration delay, Callable<V> command) {
        if (rejectNewTasks) {
            throw new RejectedExecutionException("test event loop rejected timer");
        }
        var timer = new ScheduledTimer<>(this, now().plus(delay), nextSequence++, command);
        timers.add(timer);
        return timer;
    }

    private void promoteDueTimers() {
        while (hasDueTimer()) {
            var timer = timers.remove();
            immediate.add(timer::run);
        }
    }

    private boolean hasDueTimer() {
        return !timers.isEmpty() && !timers.peek().due.isAfter(now());
    }

    private void completeTerminationIfIdle() {
        if (isTerminated()) {
            terminationFuture.trySuccess(null);
        }
    }

    private static Callable<Void> asCallable(Runnable command) {
        return () -> {
            command.run();
            return null;
        };
    }

    private static Duration nonNegativeDelay(long delay, TimeUnit unit) {
        Objects.requireNonNull(unit);
        return Duration.ofNanos(Math.max(0, unit.toNanos(delay)));
    }

    /**
     * A queued timer and the handle the caller cancels it with. These are one object so that
     * cancelling the future actually removes the timer from the queue, which is what keeps
     * {@link #pendingTimers()} an honest leak check.
     */
    private static final class ScheduledTimer<V> extends DefaultPromise<V>
        implements
            ScheduledFuture<V> {
        private final TestEventLoop loop;
        private final Instant due;
        private final long sequence;
        private final Callable<V> command;

        private ScheduledTimer(TestEventLoop loop, Instant due, long sequence, Callable<V> command) {
            super(loop);
            this.loop = loop;
            this.due = due;
            this.sequence = sequence;
            this.command = command;
        }

        private void run() {
            if (isCancelled()) {
                return;
            }
            try {
                setSuccess(command.call());
            } catch (Throwable thrown) {
                setFailure(thrown);
            }
        }

        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            if (!super.cancel(mayInterruptIfRunning)) {
                return false;
            }
            loop.timers.remove(this);
            return true;
        }

        @Override
        public long getDelay(TimeUnit unit) {
            return unit.convert(Duration.between(loop.now(), due).toNanos(), TimeUnit.NANOSECONDS);
        }

        @Override
        public int compareTo(Delayed other) {
            if (this == other) {
                return 0;
            }
            if (other instanceof ScheduledTimer<?> peer && peer.loop == loop) {
                var byDeadline = due.compareTo(peer.due);
                return byDeadline != 0 ? byDeadline : Long.compare(sequence, peer.sequence);
            }
            return Long.compare(
                getDelay(TimeUnit.NANOSECONDS),
                other.getDelay(TimeUnit.NANOSECONDS)
            );
        }

        /** A pumped loop makes no progress while a caller blocks on it, so blocking is a bug. */
        @Override
        protected void checkDeadLock() {
            throw new BlockingOperationException(
                "TestEventLoop timers complete only while the test pumps the loop; waiting on this"
                    + " future can never succeed"
            );
        }
    }
}
