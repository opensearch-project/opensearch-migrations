package org.opensearch.migrations.replay.tracing;

import java.util.Locale;

import org.opensearch.migrations.replay.kafkasource.KafkaSourceOwner;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.LongCounter;
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
    private static final String RECORDS_UNIT = "records";

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
    }

    private final LongCounter recordsRead;
    private final LongCounter recordsCommitted;
    private final LongCounter recordsCancelled;
    private final LongCounter recordsAbandonedAtRevocation;
    private final LongCounter recordsCommitIneligible;
    private final LongUpDownCounter recordsOutstanding;
    private final LongCounter commitAttemptsRejected;
    private final LongCounter commitResolutions;

    public KafkaCommitStateMetrics(@NonNull Meter meter) {
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

    private static String label(Enum<?> value) {
        return value.name().toLowerCase(Locale.ROOT);
    }
}
