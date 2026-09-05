package org.opensearch.migrations.replay.tracing;

import java.time.Duration;

import org.opensearch.migrations.tracing.InstrumentationTest;
import org.opensearch.migrations.tracing.TestContext;

import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.sdk.metrics.data.MetricData;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class KafkaCommitStateMetricsTest extends InstrumentationTest {

    @Override
    protected TestContext makeInstrumentationContext() {
        return TestContext.withAllTracking();
    }

    @Test
    void recordsCommitStateAndLatencyWithoutHighCardinalityIdentityLabels() {
        var metrics = rootContext.getKafkaCommitStateMetrics();
        metrics.unresolvedObligationsChanged(3);
        metrics.unresolvedObligationsChanged(-1);
        metrics.stagedCommitPartitionsChanged(1);
        metrics.pendingAcknowledgementsChanged(7, 2);
        metrics.pendingAcknowledgementsChanged(7, -1);
        metrics.commitAcknowledged(7, Duration.ofMillis(25));
        metrics.commitHeadObserved(3, 7, Duration.ofSeconds(5));

        var recorded = rootContext.inMemoryInstrumentationBundle.getFinishedMetrics();
        Assertions.assertEquals(
            2,
            sumPoint(recorded, KafkaCommitStateMetrics.MetricNames.UNRESOLVED_OBLIGATIONS, Attributes.empty())
        );
        Assertions.assertEquals(
            1,
            sumPoint(recorded, KafkaCommitStateMetrics.MetricNames.STAGED_COMMIT_PARTITIONS, Attributes.empty())
        );
        var generationAttributes = Attributes.of(KafkaCommitStateMetrics.GENERATION_ATTRIBUTE, 7L);
        Assertions.assertEquals(
            1,
            sumPoint(
                recorded,
                KafkaCommitStateMetrics.MetricNames.PENDING_COMMIT_ACKNOWLEDGEMENTS,
                generationAttributes
            )
        );
        assertHistogram(
            recorded,
            KafkaCommitStateMetrics.MetricNames.COMMIT_LATENCY,
            generationAttributes,
            1,
            25
        );
        assertHistogram(
            recorded,
            KafkaCommitStateMetrics.MetricNames.COMMIT_HEAD_AGE,
            Attributes.of(
                KafkaCommitStateMetrics.PARTITION_ATTRIBUTE,
                3L,
                KafkaCommitStateMetrics.GENERATION_ATTRIBUTE,
                7L
            ),
            1,
            5_000
        );
    }

    private long sumPoint(Iterable<MetricData> metrics, String name, Attributes attributes) {
        for (var metric : metrics) {
            if (metric.getName().equals(name)) {
                return metric.getLongSumData().getPoints().stream()
                    .filter(point -> point.getAttributes().equals(attributes))
                    .findFirst()
                    .orElseThrow()
                    .getValue();
            }
        }
        throw new AssertionError("Missing metric " + name);
    }

    private void assertHistogram(
        Iterable<MetricData> metrics,
        String name,
        Attributes attributes,
        long expectedCount,
        double expectedSum
    ) {
        for (var metric : metrics) {
            if (metric.getName().equals(name)) {
                var point = metric.getHistogramData().getPoints().stream()
                    .filter(candidate -> candidate.getAttributes().equals(attributes))
                    .findFirst()
                    .orElseThrow();
                Assertions.assertEquals(expectedCount, point.getCount());
                Assertions.assertEquals(expectedSum, point.getSum());
                return;
            }
        }
        throw new AssertionError("Missing metric " + name);
    }
}
