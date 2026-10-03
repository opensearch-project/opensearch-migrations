package org.opensearch.migrations.replay.tracing;

import java.util.Locale;

import org.opensearch.migrations.replay.kafkasource.KafkaSourceOwner;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.LongCounter;
import io.opentelemetry.api.metrics.LongUpDownCounter;
import io.opentelemetry.api.metrics.Meter;
import lombok.NonNull;

/** Fixed-cardinality conservation and commit-resolution instruments owned by G4. */
public final class KafkaCommitStateMetrics implements KafkaSourceOwner.Metrics {
    public static final AttributeKey<String> CAUSE_ATTRIBUTE = AttributeKey.stringKey("cause");
    public static final AttributeKey<String> OUTCOME_ATTRIBUTE = AttributeKey.stringKey("outcome");
    private static final String RECORDS_UNIT = "records";

    public static final class MetricNames {
        private MetricNames() {}

        public static final String RECORDS_READ = "records_read";
        public static final String RECORDS_COMMITTED = "records_committed";
        public static final String RECORDS_CANCELLED = "records_cancelled";
        public static final String RECORDS_ABANDONED_AT_REVOCATION =
            "records_abandoned_at_revocation";
        public static final String RECORDS_COMMIT_INELIGIBLE = "records_commit_ineligible";
        public static final String RECORDS_OUTSTANDING = "records_outstanding";
        public static final String COMMIT_ATTEMPTS_REJECTED = "commit_attempts_rejected";
        public static final String COMMIT_RESOLUTIONS = "commit_resolutions";
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
            .setUnit(RECORDS_UNIT)
            .build();
        recordsCommitted = meter.counterBuilder(MetricNames.RECORDS_COMMITTED)
            .setUnit(RECORDS_UNIT)
            .build();
        recordsCancelled = meter.counterBuilder(MetricNames.RECORDS_CANCELLED)
            .setUnit(RECORDS_UNIT)
            .build();
        recordsAbandonedAtRevocation =
            meter.counterBuilder(MetricNames.RECORDS_ABANDONED_AT_REVOCATION)
                .setUnit(RECORDS_UNIT)
                .build();
        recordsCommitIneligible = meter.counterBuilder(MetricNames.RECORDS_COMMIT_INELIGIBLE)
            .setUnit(RECORDS_UNIT)
            .build();
        recordsOutstanding = meter.upDownCounterBuilder(MetricNames.RECORDS_OUTSTANDING)
            .setUnit(RECORDS_UNIT)
            .build();
        commitAttemptsRejected = meter.counterBuilder(MetricNames.COMMIT_ATTEMPTS_REJECTED)
            .setUnit("attempts")
            .build();
        commitResolutions = meter.counterBuilder(MetricNames.COMMIT_RESOLUTIONS)
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
