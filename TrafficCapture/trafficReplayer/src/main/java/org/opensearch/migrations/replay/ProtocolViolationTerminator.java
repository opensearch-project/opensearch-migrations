/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.migrations.replay;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.IntConsumer;

import org.opensearch.migrations.replay.identity.KafkaRecordId;

import lombok.extern.slf4j.Slf4j;

/**
 * Gives already-admitted target and tuple side effects a fixed drain window after a capture-protocol
 * violation, then terminates with a reason-specific code.
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
        Scheduler scheduler,
        IntConsumer termination,
        Metrics metrics,
        AutoCloseable schedulerResource
    ) {
        if (drainLimit.isNegative()) {
            throw new IllegalArgumentException("drainLimit must not be negative");
        }
        this.drainLimit = drainLimit;
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.termination = Objects.requireNonNull(termination, "termination");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
        this.schedulerResource = Objects.requireNonNull(schedulerResource, "schedulerResource");
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

    public void begin(KafkaRecordId recordId, String diagnostic) {
        Objects.requireNonNull(recordId, "recordId");
        Objects.requireNonNull(diagnostic, "diagnostic");
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
