package org.opensearch.migrations.replay.util;


import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Supplier;

import org.opensearch.migrations.replay.lifecycle.OutstandingOperationRegistry;

import lombok.extern.slf4j.Slf4j;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Periodically reports correctness-relevant asynchronous work that remains registered past the configured
 * threshold. It reads immutable owner-published snapshots and cannot complete or mutate the operations.
 */
@Slf4j
public final class ActiveContextMonitor implements Runnable, AutoCloseable {
    public static final Duration DEFAULT_LONG_RUNNING_THRESHOLD = Duration.ofSeconds(30);
    public static final Duration DEFAULT_REPORT_INTERVAL = Duration.ofSeconds(30);
    static final String ACTIVE_WORK_LOGGER_NAME = "AllActiveWorkMonitor";

    private static final Logger ACTIVE_WORK_LOGGER =
        LoggerFactory.getLogger(ACTIVE_WORK_LOGGER_NAME);

    public record Report(
        OutstandingOperationRegistry.Snapshot snapshot,
        Duration elapsed
    ) {
        public Report {
            Objects.requireNonNull(snapshot, "snapshot");
            Objects.requireNonNull(elapsed, "elapsed");
        }
    }

    record Status(
        Instant observedAt,
        int activeCount,
        int longRunningCount
    ) {
        public Status {
            Objects.requireNonNull(observedAt, "observedAt");
            if (activeCount < 0 || longRunningCount < 0
                || longRunningCount > activeCount) {
                throw new IllegalArgumentException(
                    "activity counts must satisfy 0 <= longRunningCount <= activeCount"
                );
            }
        }
    }

    private final Clock clock;
    private final Duration longRunningThreshold;
    private final Duration reportInterval;
    private final Supplier<List<OutstandingOperationRegistry.Snapshot>> snapshotSupplier;
    private final Consumer<Report> reporter;
    private final Consumer<Status> statusReporter;
    private final ScheduledExecutorService scheduler;
    private final AtomicBoolean started = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();

    public ActiveContextMonitor(
        Clock clock,
        Duration longRunningThreshold,
        Supplier<List<OutstandingOperationRegistry.Snapshot>> snapshotSupplier,
        Consumer<Report> reporter
    ) {
        this(
            clock,
            longRunningThreshold,
            DEFAULT_REPORT_INTERVAL,
            snapshotSupplier,
            reporter,
            ignored -> {},
            null
        );
    }

    ActiveContextMonitor(
        Clock clock,
        Duration longRunningThreshold,
        Supplier<List<OutstandingOperationRegistry.Snapshot>> snapshotSupplier,
        Consumer<Report> reporter,
        Consumer<Status> statusReporter
    ) {
        this(
            clock,
            longRunningThreshold,
            DEFAULT_REPORT_INTERVAL,
            snapshotSupplier,
            reporter,
            statusReporter,
            null
        );
    }

    private ActiveContextMonitor(
        Clock clock,
        Duration longRunningThreshold,
        Duration reportInterval,
        Supplier<List<OutstandingOperationRegistry.Snapshot>> snapshotSupplier,
        Consumer<Report> reporter,
        Consumer<Status> statusReporter,
        ScheduledExecutorService scheduler
    ) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.longRunningThreshold = requirePositive(longRunningThreshold, "longRunningThreshold");
        this.reportInterval = requirePositive(reportInterval, "reportInterval");
        this.snapshotSupplier = Objects.requireNonNull(snapshotSupplier, "snapshotSupplier");
        this.reporter = Objects.requireNonNull(reporter, "reporter");
        this.statusReporter = Objects.requireNonNull(statusReporter, "statusReporter");
        this.scheduler = scheduler;
    }

    public static ActiveContextMonitor system(
        Clock clock,
        Supplier<List<OutstandingOperationRegistry.Snapshot>> snapshotSupplier
    ) {
        var scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            var thread = new Thread(runnable, "replay-long-running-activity-monitor");
            thread.setDaemon(true);
            return thread;
        });
        return new ActiveContextMonitor(
            clock,
            DEFAULT_LONG_RUNNING_THRESHOLD,
            DEFAULT_REPORT_INTERVAL,
            snapshotSupplier,
            ActiveContextMonitor::logReport,
            ActiveContextMonitor::logStatus,
            scheduler
        );
    }

    public void start() {
        if (!started.compareAndSet(false, true)) {
            throw new IllegalStateException("activity monitor already started");
        }
        if (scheduler != null) {
            scheduler.scheduleAtFixedRate(
                this,
                longRunningThreshold.toNanos(),
                reportInterval.toNanos(),
                TimeUnit.NANOSECONDS
            );
        }
    }

    @Override
    public void run() {
        if (closed.get()) {
            return;
        }
        try {
            var now = clock.instant();
            var snapshots = List.copyOf(snapshotSupplier.get());
            var longRunningReports = snapshots.stream()
                .map(snapshot -> new Report(snapshot, Duration.between(snapshot.startTime(), now)))
                .filter(report -> !report.elapsed().isNegative())
                .filter(report -> report.elapsed().compareTo(longRunningThreshold) >= 0)
                .sorted(
                    Comparator.comparing(Report::elapsed).reversed()
                        .thenComparing(report -> report.snapshot().ownerIdentity())
                        .thenComparingLong(report -> report.snapshot().operationId())
                )
                .toList();
            statusReporter.accept(new Status(now, snapshots.size(), longRunningReports.size()));
            longRunningReports.forEach(reporter);
        } catch (Throwable failure) {
            log.error("Long-running replay activity reporting failed", failure);
        }
    }

    private static void logStatus(Status status) {
        ACTIVE_WORK_LOGGER.info(
            "Active replay work: observedAt={} active={} longRunning={}",
            status.observedAt(),
            status.activeCount(),
            status.longRunningCount()
        );
    }

    private static void logReport(Report report) {
        var snapshot = report.snapshot();
        var generation = snapshot.partitionGenerationId();
        ACTIVE_WORK_LOGGER.warn(
            "Long-running replay activity: partition={} generation={} record={} capturedConnection={} "
                + "connectionProcessing={} request={} owner={} operation={} elapsed={} reason={} "
                + "scheduledTargetTime={}",
            generation.topicPartition(),
            generation.localSequence(),
            snapshot.kafkaRecordId(),
            snapshot.connectionProcessingId().capturedConnectionId(),
            snapshot.connectionProcessingId(),
            snapshot.replayRequestId(),
            snapshot.ownerIdentity(),
            snapshot.operationType(),
            report.elapsed(),
            snapshot.waitReason(),
            snapshot.scheduledTargetTime()
        );
    }

    private static Duration requirePositive(Duration value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value;
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
    }
}
