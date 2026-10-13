package org.opensearch.migrations.replay.tracing;

import java.util.Locale;
import java.util.function.LongSupplier;

import org.opensearch.migrations.replay.kafkasource.KafkaSourceOwner;
import org.opensearch.migrations.replay.kafkasource.KafkaSourcePort;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.LongCounter;
import io.opentelemetry.api.metrics.LongGauge;
import io.opentelemetry.api.metrics.LongUpDownCounter;
import io.opentelemetry.api.metrics.Meter;
import lombok.NonNull;

/**
 * Exposes whether Kafka records are conserved from observation through commit or explicit abandonment.
 *
 * <p>Monotonic counters classify records as read, committed, cancelled, commit-ineligible, or abandoned at
 * revocation, while an up-down counter shows the current outstanding population. Commit submissions and
 * resolutions are recorded separately because a rejected operation and an accepted operation with an
 * uncertain result have different data-safety implications.</p>
 *
 * <p>Attributes are limited to fixed enum-derived causes and outcomes. The metrics can therefore be
 * aggregated across partitions and requests without introducing record, connection, or topic cardinality.</p>
 */
public final class KafkaCommitStateMetrics implements KafkaSourceOwner.Metrics {
    public static final AttributeKey<String> CAUSE_ATTRIBUTE = AttributeKey.stringKey("cause");
    public static final AttributeKey<String> OUTCOME_ATTRIBUTE = AttributeKey.stringKey("outcome");
    public static final AttributeKey<String> FAILURE_CLASS_ATTRIBUTE =
        AttributeKey.stringKey("failure_class");
    private static final String RECORDS_UNIT = "records";
    private static final String PARTITIONS_UNIT = "partitions";

    public static final class MetricNames {
        private MetricNames() {}

        public static final String RECORDS_READ = "kafkaSourceRecordsRead";
        public static final String RECORDS_COMMITTED = "kafkaSourceRecordsCommitted";
        public static final String RECORDS_CANCELLED = "kafkaSourceRecordsCancelled";
        public static final String RECORDS_ABANDONED_AT_REVOCATION =
            "kafkaSourceRecordsAbandonedAtRevocation";
        public static final String RECORDS_COMMIT_INELIGIBLE =
            "kafkaSourceRecordsCommitIneligible";
        public static final String RECORDS_OUTSTANDING = "kafkaSourceRecordsOutstanding";
        public static final String COMMIT_ATTEMPTS_REJECTED =
            "kafkaSourceCommitAttemptsRejected";
        public static final String COMMIT_RESOLUTIONS = "kafkaSourceCommitResolutions";
        public static final String COMMIT_RETRY_ATTEMPTS =
            "kafkaSourceCommitRetryAttempts";
        public static final String COMMIT_RETRY_DEGRADED =
            "kafkaSourceCommitRetryDegraded";
        public static final String COMMIT_RETRY_RECOVERIES =
            "kafkaSourceCommitRetryRecoveries";
        public static final String ASSIGNED_PARTITIONS = "kafkaSourceAssignedPartitions";
        public static final String CONSUMER_LAG = "kafkaSourceConsumerLag";
        public static final String MAX_PARTITION_LAG = "kafkaSourceMaxPartitionLag";
        public static final String LAG_UNKNOWN_PARTITIONS = "kafkaSourceLagUnknownPartitions";
        public static final String OLDEST_COMMIT_HEAD_AGE = "kafkaSourceOldestCommitHeadAge";
        public static final String STALE_COMMIT_HEAD_PARTITIONS = "kafkaSourceStaleCommitHeadPartitions";
    }

    private final LongCounter recordsRead;
    private final LongCounter recordsCommitted;
    private final LongCounter recordsCancelled;
    private final LongCounter recordsAbandonedAtRevocation;
    private final LongCounter recordsCommitIneligible;
    private final LongUpDownCounter recordsOutstanding;
    private final LongCounter commitAttemptsRejected;
    private final LongCounter commitResolutions;
    private final LongCounter commitRetryAttempts;
    private final LongUpDownCounter commitRetryDegraded;
    private final LongCounter commitRetryRecoveries;
    private final LongGauge assignedPartitions;
    private final LongGauge consumerLag;
    private final LongGauge maxPartitionLag;
    private final LongGauge lagUnknownPartitions;
    private final LongGauge oldestCommitHeadAge;
    private final LongGauge staleCommitHeadPartitions;
    private final LongSupplier wallClockMillis;

    public KafkaCommitStateMetrics(@NonNull Meter meter) {
        this(meter, System::currentTimeMillis);
    }

    public KafkaCommitStateMetrics(@NonNull Meter meter, @NonNull LongSupplier wallClockMillis) {
        this.wallClockMillis = wallClockMillis;
        recordsRead = meter.counterBuilder(MetricNames.RECORDS_READ)
            .setDescription("Kafka records admitted into replay source processing")
            .setUnit(RECORDS_UNIT)
            .build();
        recordsCommitted = meter.counterBuilder(MetricNames.RECORDS_COMMITTED)
            .setDescription("Kafka records covered by an acknowledged committed position")
            .setUnit(RECORDS_UNIT)
            .build();
        recordsCancelled = meter.counterBuilder(MetricNames.RECORDS_CANCELLED)
            .setDescription("Kafka records cancelled during generation cleanup")
            .setUnit(RECORDS_UNIT)
            .build();
        recordsAbandonedAtRevocation =
            meter.counterBuilder(MetricNames.RECORDS_ABANDONED_AT_REVOCATION)
                .setDescription("Kafka records abandoned when partition ownership ended")
                .setUnit(RECORDS_UNIT)
                .build();
        recordsCommitIneligible = meter.counterBuilder(MetricNames.RECORDS_COMMIT_INELIGIBLE)
            .setDescription("Kafka records intentionally excluded from commit after a protocol violation")
            .setUnit(RECORDS_UNIT)
            .build();
        recordsOutstanding = meter.upDownCounterBuilder(MetricNames.RECORDS_OUTSTANDING)
            .setDescription("Read Kafka records without a terminal commit-accounting outcome")
            .setUnit(RECORDS_UNIT)
            .build();
        commitAttemptsRejected = meter.counterBuilder(MetricNames.COMMIT_ATTEMPTS_REJECTED)
            .setDescription("Kafka commit submissions rejected before broker movement was possible")
            .setUnit("attempts")
            .build();
        commitResolutions = meter.counterBuilder(MetricNames.COMMIT_RESOLUTIONS)
            .setDescription("Kafka commit operation resolutions by bounded outcome")
            .setUnit("events")
            .build();
        commitRetryAttempts = meter.counterBuilder(MetricNames.COMMIT_RETRY_ATTEMPTS)
            .setDescription("Kafka commit retries submitted after owner-local backoff")
            .setUnit("attempts")
            .build();
        commitRetryDegraded = meter.upDownCounterBuilder(MetricNames.COMMIT_RETRY_DEGRADED)
            .setDescription("Whether Kafka commit processing is currently degraded and retrying")
            .setUnit("episodes")
            .build();
        commitRetryRecoveries = meter.counterBuilder(MetricNames.COMMIT_RETRY_RECOVERIES)
            .setDescription("Kafka commit retry episodes recovered by an acknowledged commit")
            .setUnit("events")
            .build();
        assignedPartitions = meter.gaugeBuilder(MetricNames.ASSIGNED_PARTITIONS)
            .ofLongs()
            .setDescription("Kafka partitions this replayer currently owns")
            .setUnit(PARTITIONS_UNIT)
            .build();
        consumerLag = meter.gaugeBuilder(MetricNames.CONSUMER_LAG)
            .ofLongs()
            .setDescription("Records between the fetch position and the high watermark, summed over owned partitions")
            .setUnit(RECORDS_UNIT)
            .build();
        maxPartitionLag = meter.gaugeBuilder(MetricNames.MAX_PARTITION_LAG)
            .ofLongs()
            .setDescription("Largest fetch-position lag among owned partitions")
            .setUnit(RECORDS_UNIT)
            .build();
        lagUnknownPartitions = meter.gaugeBuilder(MetricNames.LAG_UNKNOWN_PARTITIONS)
            .ofLongs()
            .setDescription("Owned partitions without a fetch response yet, excluded from the lag gauges")
            .setUnit(PARTITIONS_UNIT)
            .build();
        oldestCommitHeadAge = meter.gaugeBuilder(MetricNames.OLDEST_COMMIT_HEAD_AGE)
            .ofLongs()
            .setDescription("Wall-clock time since the broker appended the oldest record holding back a commit")
            .setUnit("ms")
            .build();
        staleCommitHeadPartitions = meter.gaugeBuilder(MetricNames.STALE_COMMIT_HEAD_PARTITIONS)
            .ofLongs()
            .setDescription("Owned partitions whose commit head has been held longer than the stale threshold")
            .setUnit(PARTITIONS_UNIT)
            .build();
    }

    @Override
    public void recordsRead(long count) {
        recordsRead.add(count);
    }

    @Override
    public void recordsCommitted(long count) {
        recordsCommitted.add(count);
    }

    @Override
    public void recordsCancelled(long count) {
        recordsCancelled.add(count);
    }

    @Override
    public void recordsAbandonedAtRevocation(
        @NonNull KafkaSourceOwner.AbandonmentCause cause,
        long count
    ) {
        recordsAbandonedAtRevocation.add(
            count,
            Attributes.of(CAUSE_ATTRIBUTE, label(cause))
        );
    }

    @Override
    public void recordsCommitIneligible(long count) {
        recordsCommitIneligible.add(count);
    }

    @Override
    public void recordsOutstandingChanged(long delta) {
        recordsOutstanding.add(delta);
    }

    @Override
    public void commitAttemptRejected() {
        commitAttemptsRejected.add(1);
    }

    @Override
    public void commitResolved(@NonNull KafkaSourceOwner.CommitResolution resolution) {
        commitResolutions.add(1, Attributes.of(OUTCOME_ATTRIBUTE, label(resolution)));
    }

    @Override
    public void commitRetryAttempted(
        @NonNull KafkaSourcePort.CommitFailureClass failureClass
    ) {
        commitRetryAttempts.add(
            1,
            Attributes.of(FAILURE_CLASS_ATTRIBUTE, label(failureClass))
        );
    }

    @Override
    public void commitRetryDegradedChanged(long delta) {
        commitRetryDegraded.add(delta);
    }

    @Override
    public void commitRetryRecovered() {
        commitRetryRecoveries.add(1);
    }

    @Override
    public void sourcePositionObserved(@NonNull KafkaSourceOwner.SourcePosition position) {
        assignedPartitions.set(position.assignedPartitions());
        consumerLag.set(position.consumerLagRecords());
        maxPartitionLag.set(position.maxPartitionLagRecords());
        lagUnknownPartitions.set(position.lagUnknownPartitions());
        var headAppendTime = position.oldestCommitHeadLogAppendTimeMillis();
        oldestCommitHeadAge.set(
            headAppendTime.isPresent() ? Math.max(0, wallClockMillis.getAsLong() - headAppendTime.getAsLong()) : 0
        );
        staleCommitHeadPartitions.set(position.staleCommitHeadPartitions());
    }

    private static String label(Enum<?> value) {
        return value.name().toLowerCase(Locale.ROOT);
    }
}
