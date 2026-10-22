/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.migrations.replay.util;

import java.time.Clock;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;

import org.opensearch.migrations.replay.kafkasource.KafkaSourceOwner;
import org.opensearch.migrations.replay.lifecycle.OutstandingOperationRegistry;
import org.opensearch.migrations.replay.tracing.ReplayActivityMetrics;
import org.opensearch.migrations.tracing.ActiveContextTracker;
import org.opensearch.migrations.tracing.ActiveContextTrackerByActivityType;

import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Periodically combines context, owner-obligation, and immutable Kafka-domain diagnostics.
 */
@Slf4j
public final class ActiveContextMonitor implements Runnable, AutoCloseable {
    public static final Duration DEFAULT_REPORT_INTERVAL = Duration.ofSeconds(30);
    static final String ACTIVE_WORK_LOGGER_NAME = "AllActiveWorkMonitor";

    private static final Logger ACTIVE_WORK_LOGGER = LoggerFactory.getLogger(ACTIVE_WORK_LOGGER_NAME);

    // The warnings target compiler-generated members of this public report contract.
    @SuppressWarnings({"java:S100", "java:S1172", "java:S1186"})
    public record Report(
        @NonNull ContextActivityCollector.Snapshot contexts,
        @NonNull OwnerOperationCollector.Snapshot ownerOperations,
        @NonNull KafkaDiagnosticCollector.Snapshot kafka,
        boolean finalSample
    ) {}

    private final ContextActivityCollector contextCollector;
    private final OwnerOperationCollector ownerOperationCollector;
    private final KafkaDiagnosticCollector kafkaDiagnosticCollector;
    private final ReplayActivityMetrics metrics;
    private final Consumer<Report> reporter;
    private final BooleanSupplier reportsOldestRecordDescendants;
    private final Duration reportInterval;
    private final ScheduledExecutorService scheduler;
    private final AtomicBoolean started = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final Object collectionLock = new Object();

    ActiveContextMonitor(
        @NonNull ContextActivityCollector contextCollector,
        @NonNull OwnerOperationCollector ownerOperationCollector,
        @NonNull KafkaDiagnosticCollector kafkaDiagnosticCollector,
        @NonNull ReplayActivityMetrics metrics,
        @NonNull Consumer<Report> reporter
    ) {
        this(
            contextCollector,
            ownerOperationCollector,
            kafkaDiagnosticCollector,
            metrics,
            reporter,
            () -> true,
            DEFAULT_REPORT_INTERVAL,
            null
        );
    }

    private ActiveContextMonitor(
        @NonNull ContextActivityCollector contextCollector,
        @NonNull OwnerOperationCollector ownerOperationCollector,
        @NonNull KafkaDiagnosticCollector kafkaDiagnosticCollector,
        @NonNull ReplayActivityMetrics metrics,
        @NonNull Consumer<Report> reporter,
        @NonNull BooleanSupplier reportsOldestRecordDescendants,
        @NonNull Duration reportInterval,
        ScheduledExecutorService scheduler
    ) {
        if (reportInterval.isZero() || reportInterval.isNegative()) {
            throw new IllegalArgumentException("reportInterval must be positive");
        }
        this.contextCollector = contextCollector;
        this.ownerOperationCollector = ownerOperationCollector;
        this.kafkaDiagnosticCollector = kafkaDiagnosticCollector;
        this.metrics = metrics;
        this.reporter = reporter;
        this.reportsOldestRecordDescendants = reportsOldestRecordDescendants;
        this.reportInterval = reportInterval;
        this.scheduler = scheduler;
    }

    public static ActiveContextMonitor system(
        @NonNull Clock clock,
        @NonNull ActiveContextTracker globalTracker,
        @NonNull ActiveContextTrackerByActivityType byActivityTracker,
        @NonNull Supplier<List<OutstandingOperationRegistry.Snapshot>> ownerSnapshots,
        @NonNull Supplier<KafkaSourceOwner.DiagnosticSnapshot> kafkaSnapshots,
        @NonNull ReplayActivityMetrics metrics
    ) {
        var scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            var thread = new Thread(runnable, "replay-activity-monitor");
            thread.setDaemon(true);
            return thread;
        });
        return new ActiveContextMonitor(
            new ContextActivityCollector(clock, globalTracker, byActivityTracker),
            new OwnerOperationCollector(clock, ownerSnapshots),
            new KafkaDiagnosticCollector(kafkaSnapshots),
            metrics,
            ActiveContextMonitor::logReport,
            // The descendant walk visits every active context and only its log line reports the result.
            ACTIVE_WORK_LOGGER::isInfoEnabled,
            DEFAULT_REPORT_INTERVAL,
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
                reportInterval.toNanos(),
                reportInterval.toNanos(),
                TimeUnit.NANOSECONDS
            );
        }
    }

    @Override
    // Any Throwable escaping this fixed-rate task would silently cancel every later sample.
    @SuppressWarnings("java:S1181")
    public void run() {
        synchronized (collectionLock) {
            if (closed.get()) {
                return;
            }
            try {
                collectAndReport(false);
            } catch (Throwable failure) {
                log.error("event=replay_activity_monitor_failed", failure);
            }
        }
    }

    private Report collectAndReport(boolean finalSample) {
        var contexts = contextCollector.collect(reportsOldestRecordDescendants.getAsBoolean());
        var owners = ownerOperationCollector.collect();
        var kafka = kafkaDiagnosticCollector.collect(
            contexts.oldestKafkaRecord().map(ContextActivityCollector.OldestKafkaRecord::recordId)
        );
        var report = new Report(contexts, owners, kafka, finalSample);
        metrics.recordSample(contexts, owners, kafka);
        reporter.accept(report);
        return report;
    }

    private static void logReport(Report report) {
        var contexts = report.contexts();
        var owners = report.ownerOperations();
        var kafka = report.kafka();
        var activityCounts = new LinkedHashMap<String, Long>();
        var activityOldestAgeMs = new LinkedHashMap<String, Long>();
        var activityOverThreshold = new LinkedHashMap<String, Long>();
        contexts.activities().forEach((name, activity) -> {
            activityCounts.put(name, activity.activeCount());
            activityOldestAgeMs.put(name, activity.oldestAge().toMillis());
            activityOverThreshold.put(name, activity.overThresholdCount());
        });
        var oldestRecord = contexts.oldestKafkaRecord();
        ACTIVE_WORK_LOGGER.info(
            "event=replay_health observed_at={} active_contexts={} activity_counts={} "
                + "oldest_context_age_ms={} activity_oldest_age_ms={} activity_over_threshold={} "
                + "owner_operations={} long_owner_operations={} oldest_kafka_record_age_ms={} "
                + "kafka_uncertain_partitions={} final_sample={}",
            contexts.observedAt(),
            contexts.totalActiveContexts(),
            activityCounts,
            contexts.globallyOldestAge().toMillis(),
            activityOldestAgeMs,
            activityOverThreshold,
            owners.activeCount(),
            owners.longRunningCount(),
            oldestRecord.map(kafkaRecord -> kafkaRecord.age().toMillis()).orElse(0L),
            kafka.uncertainPartitionCount(),
            report.finalSample()
        );

        oldestRecord.ifPresent(kafkaRecord -> {
            var generation = kafkaRecord.recordId().generation();
            ACTIVE_WORK_LOGGER.info(
                "event=replay_oldest_record topic={} partition={} generation={} offset={} age_ms={} "
                    + "active_descendants={} kafka_state={}",
                generation.topicPartition().topic(),
                generation.topicPartition().partition(),
                generation.localSequence(),
                kafkaRecord.recordId().offset(),
                kafkaRecord.age().toMillis(),
                kafkaRecord.descendantCount(),
                kafka.oldestRecord().map(diagnostic -> diagnostic.state().name()).orElse("UNKNOWN")
            );
            if (kafkaRecord.age().compareTo(ContextActivityCollector.DEFAULT_AGE_THRESHOLD) >= 0) {
                ACTIVE_WORK_LOGGER.warn(
                    "event=replay_stall_detected kind=kafka_record topic={} partition={} generation={} "
                        + "offset={} age_ms={} descendant_count={} descendants={} kafka_state={}",
                    generation.topicPartition().topic(),
                    generation.topicPartition().partition(),
                    generation.localSequence(),
                    kafkaRecord.recordId().offset(),
                    kafkaRecord.age().toMillis(),
                    kafkaRecord.descendantCount(),
                    kafkaRecord.descendants(),
                    kafka.oldestRecord().map(diagnostic -> diagnostic.state().name()).orElse("UNKNOWN")
                );
            }
        });

        contexts.oldContexts().forEach(context -> ACTIVE_WORK_LOGGER.warn(
            "event=replay_stall_detected kind=context activity={} age_ms={} ancestor_chain={} "
                + "record={} connection={} request={}",
            context.activity(),
            context.age().toMillis(),
            context.ancestorChain(),
            context.identity().kafkaRecordId().map(Object::toString).orElse("-"),
            context.identity().connectionProcessingId().map(Object::toString).orElse("-"),
            context.identity().replayRequestId().map(Object::toString).orElse("-")
        ));
        owners.longRunningOperations().forEach(ActiveContextMonitor::logOwnerOperation);
    }

    private static void logOwnerOperation(OwnerOperationCollector.Detail detail) {
        var snapshot = detail.operation();
        var generation = snapshot.partitionGenerationId();
        ACTIVE_WORK_LOGGER.warn(
            "event=replay_stall_detected kind=owner_operation topic={} partition={} generation={} "
                + "record={} connection={} request={} owner={} operation={} age_ms={} reason={} "
                + "scheduled_target_time={}",
            generation.topicPartition().topic(),
            generation.topicPartition().partition(),
            generation.localSequence(),
            snapshot.kafkaRecordId(),
            snapshot.connectionProcessingId(),
            snapshot.replayRequestId(),
            snapshot.ownerIdentity(),
            snapshot.operationType(),
            detail.elapsed().toMillis(),
            snapshot.waitReason(),
            snapshot.scheduledTargetTime()
        );
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        stopScheduler();
    }

    /**
     * Stops periodic collection and publishes the real state observed after an orderly drain.
     */
    public void closeAfterOrderlyDrain() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        stopScheduler();
        synchronized (collectionLock) {
            final Report report;
            try {
                report = collectAndReport(true);
            } catch (Exception failure) {
                throw new IllegalStateException(
                    "failed to collect final replay activity state after orderly drain",
                    failure
                );
            }
            assertDrained(report);
        }
    }

    private void stopScheduler() {
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
    }

    private static void assertDrained(Report report) {
        var contexts = report.contexts();
        var owners = report.ownerOperations();
        var activeActivityCount = contexts.activities()
            .values()
            .stream()
            .mapToLong(ContextActivityCollector.ActivitySummary::activeCount)
            .sum();
        if (contexts.totalActiveContexts() != 0
            || activeActivityCount != 0
            || owners.activeCount() != 0
            || report.kafka().uncertainPartitionCount() != 0) {
            ACTIVE_WORK_LOGGER.error(
                "event=replay_shutdown_activity_not_drained active_contexts={} "
                    + "active_contexts_by_activity={} owner_operations={} "
                    + "kafka_uncertain_partitions={}",
                contexts.totalActiveContexts(),
                activeActivityCount,
                owners.activeCount(),
                report.kafka().uncertainPartitionCount()
            );
            throw new IllegalStateException(
                "orderly replay shutdown retained active contexts, owner operations, "
                    + "or uncertain Kafka commit state"
            );
        }
    }
}
