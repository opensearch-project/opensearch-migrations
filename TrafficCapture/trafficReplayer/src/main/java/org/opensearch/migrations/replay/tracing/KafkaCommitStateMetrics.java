package org.opensearch.migrations.replay.tracing;

import java.time.Duration;

import org.opensearch.migrations.replay.kafka.TrackingKafkaConsumer;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.DoubleHistogram;
import io.opentelemetry.api.metrics.LongUpDownCounter;
import io.opentelemetry.api.metrics.Meter;
import lombok.NonNull;

public final class KafkaCommitStateMetrics implements TrackingKafkaConsumer.Metrics {
    public static final AttributeKey<Long> PARTITION_ATTRIBUTE = AttributeKey.longKey("partition");
    public static final AttributeKey<Long> GENERATION_ATTRIBUTE = AttributeKey.longKey("generation");

    public static final class MetricNames {
        private MetricNames() {}

        public static final String UNRESOLVED_OBLIGATIONS = "kafkaUnresolvedObligations";
        public static final String STAGED_COMMIT_PARTITIONS = "kafkaStagedCommitPartitions";
        public static final String PENDING_COMMIT_ACKNOWLEDGEMENTS = "kafkaPendingCommitAcknowledgements";
        public static final String COMMIT_LATENCY = "kafkaCommitLatency";
        public static final String COMMIT_HEAD_AGE = "kafkaCommitHeadAge";
    }

    private final LongUpDownCounter unresolvedObligations;
    private final LongUpDownCounter stagedCommitPartitions;
    private final LongUpDownCounter pendingCommitAcknowledgements;
    private final DoubleHistogram commitLatency;
    private final DoubleHistogram commitHeadAge;

    public KafkaCommitStateMetrics(@NonNull Meter meter) {
        unresolvedObligations = meter.upDownCounterBuilder(MetricNames.UNRESOLVED_OBLIGATIONS)
            .setUnit("records")
            .build();
        stagedCommitPartitions = meter.upDownCounterBuilder(MetricNames.STAGED_COMMIT_PARTITIONS)
            .setUnit("partitions")
            .build();
        pendingCommitAcknowledgements = meter.upDownCounterBuilder(MetricNames.PENDING_COMMIT_ACKNOWLEDGEMENTS)
            .setUnit("records")
            .build();
        commitLatency = meter.histogramBuilder(MetricNames.COMMIT_LATENCY)
            .setUnit("ms")
            .build();
        commitHeadAge = meter.histogramBuilder(MetricNames.COMMIT_HEAD_AGE)
            .setUnit("ms")
            .build();
    }

    @Override
    public void unresolvedObligationsChanged(int delta) {
        unresolvedObligations.add(delta);
    }

    @Override
    public void stagedCommitPartitionsChanged(int delta) {
        stagedCommitPartitions.add(delta);
    }

    @Override
    public void pendingAcknowledgementsChanged(int generation, int delta) {
        pendingCommitAcknowledgements.add(
            delta,
            Attributes.of(GENERATION_ATTRIBUTE, (long) generation)
        );
    }

    @Override
    public void commitAcknowledged(int generation, @NonNull Duration latency) {
        commitLatency.record(
            nonNegativeMilliseconds(latency),
            Attributes.of(GENERATION_ATTRIBUTE, (long) generation)
        );
    }

    @Override
    public void commitHeadObserved(int partition, int generation, @NonNull Duration age) {
        commitHeadAge.record(
            nonNegativeMilliseconds(age),
            Attributes.of(
                PARTITION_ATTRIBUTE,
                (long) partition,
                GENERATION_ATTRIBUTE,
                (long) generation
            )
        );
    }

    private static double nonNegativeMilliseconds(Duration duration) {
        return Math.max(0, duration.toNanos() / 1_000_000.0);
    }
}
