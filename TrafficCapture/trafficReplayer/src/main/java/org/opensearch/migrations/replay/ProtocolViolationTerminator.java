/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.migrations.replay;

import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.IntConsumer;

import org.opensearch.migrations.replay.identity.KafkaRecordId;

import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;

/**
 * Bounds how long replay may remain alive after detecting malformed or internally inconsistent capture
 * data.
 *
 * <p>A protocol violation prevents the stream from being interpreted safely, so the process must
 * terminate rather than continue consuming. Work admitted before the violation may already have target
 * or tuple-output side effects in flight, however. This class starts one fixed grace period in which that
 * work may settle, then requests reason-specific process termination regardless of what remains.</p>
 *
 * <p>The transition is one-shot: the first violation starts the timer and supplies the diagnostic record;
 * subsequent reports cannot extend the deadline. This prevents a damaged stream from keeping the process
 * alive indefinitely by repeatedly restarting its drain period.</p>
 */
@Slf4j
public final class ProtocolViolationTerminator implements AutoCloseable {
    public static final Duration DRAIN_LIMIT = Duration.ofSeconds(60);
    public static final int EXIT_CODE = 81;

    @FunctionalInterface
    public interface Scheduler {
        void schedule(Duration delay, Runnable task);
    }

    public interface Metrics {
        Metrics NOOP = new Metrics() {};

        default void drainStarted() {}
        default void terminationTriggered() {}
    }

    private final Duration drainLimit;
    private final Scheduler scheduler;
    private final IntConsumer termination;
    private final Metrics metrics;
    private final AutoCloseable schedulerResource;
    private final AtomicBoolean started = new AtomicBoolean();

    public ProtocolViolationTerminator(
        Scheduler scheduler,
        IntConsumer termination,
        Metrics metrics
    ) {
        this(DRAIN_LIMIT, scheduler, termination, metrics, () -> {});
    }

    private ProtocolViolationTerminator(
        Duration drainLimit,
        @NonNull Scheduler scheduler,
        @NonNull IntConsumer termination,
        @NonNull Metrics metrics,
        @NonNull AutoCloseable schedulerResource
    ) {
        if (drainLimit.isNegative()) {
            throw new IllegalArgumentException("drainLimit must not be negative");
        }
        this.drainLimit = drainLimit;
        this.scheduler = scheduler;
        this.termination = termination;
        this.metrics = metrics;
        this.schedulerResource = schedulerResource;
    }

    /** Creates the fixed production timer. G9 may replace only the process-supervisor termination sink. */
    public static ProtocolViolationTerminator system(IntConsumer termination) {
        return system(termination, Metrics.NOOP);
    }

    /** Creates the fixed production timer with process-wide observability. */
    public static ProtocolViolationTerminator system(IntConsumer termination, Metrics metrics) {
        ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            var thread = new Thread(runnable, "replay-protocol-violation-terminator");
            thread.setDaemon(true);
            return thread;
        });
        return new ProtocolViolationTerminator(
            DRAIN_LIMIT,
            (delay, task) -> executor.schedule(task, delay.toNanos(), TimeUnit.NANOSECONDS),
            termination,
            metrics,
            executor::shutdownNow
        );
    }

    public void begin(@NonNull KafkaRecordId recordId, @NonNull String diagnostic) {
        if (!started.compareAndSet(false, true)) {
            return;
        }
        log.atError()
            .setMessage("Capture protocol violation at {}; draining admitted side effects for {}: {}")
            .addArgument(recordId)
            .addArgument(drainLimit)
            .addArgument(diagnostic)
            .log();
        metrics.drainStarted();
        scheduler.schedule(drainLimit, () -> {
            metrics.terminationTriggered();
            termination.accept(EXIT_CODE);
        });
    }

    public boolean started() {
        return started.get();
    }

    @Override
    public void close() throws Exception {
        schedulerResource.close();
    }
}
