package org.opensearch.migrations.replay.tracing;

import java.util.OptionalLong;
import java.util.Set;

import org.opensearch.migrations.replay.kafkasource.KafkaSourceOwner;
import org.opensearch.migrations.replay.kafkasource.KafkaSourcePort;
import org.opensearch.migrations.tracing.InMemoryInstrumentationBundle;

import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.sdk.metrics.data.MetricData;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class KafkaCommitStateMetricsTest {
    private final InMemoryInstrumentationBundle telemetry =
        new InMemoryInstrumentationBundle(false, true);
    private final RootReplayerContext rootContext =
        new RootReplayerContext(telemetry.openTelemetrySdk);

    @AfterEach
    void closeTelemetry() {
        telemetry.close();
    }

    @Test
    void recordsConservationAndResolutionWithoutIdentityLabels() {
        var metrics = rootContext.getKafkaCommitStateMetrics();
        metrics.recordsRead(8);
        metrics.recordsOutstandingChanged(8);
        metrics.recordsCommitted(2);
        metrics.recordsOutstandingChanged(-2);
        metrics.recordsCancelled(1);
        metrics.recordsOutstandingChanged(-1);
        metrics.recordsCommitIneligible(1);
        metrics.recordsOutstandingChanged(-1);
        metrics.recordsAbandonedAtRevocation(KafkaSourceOwner.AbandonmentCause.REJECTED, 1);
        metrics.recordsAbandonedAtRevocation(KafkaSourceOwner.AbandonmentCause.UNKNOWN, 1);
        metrics.recordsAbandonedAtRevocation(KafkaSourceOwner.AbandonmentCause.UNSUBMITTED, 1);
        metrics.recordsOutstandingChanged(-3);
        metrics.commitAttemptRejected();
        metrics.commitAttemptRejected();
        metrics.commitResolved(KafkaSourceOwner.CommitResolution.ACKNOWLEDGED);
        metrics.commitResolved(KafkaSourceOwner.CommitResolution.REJECTED);
        metrics.commitResolved(KafkaSourceOwner.CommitResolution.OUTCOME_UNKNOWN);
        metrics.commitRetryDegradedChanged(1);
        metrics.commitRetryAttempted(KafkaSourcePort.CommitFailureClass.COORDINATOR);
        metrics.commitRetryAttempted(KafkaSourcePort.CommitFailureClass.TRANSPORT);
        metrics.commitRetryDegradedChanged(-1);
        metrics.commitRetryRecovered();

        var recorded = telemetry.getFinishedMetrics();
        assertPoint(recorded, KafkaCommitStateMetrics.MetricNames.RECORDS_READ, Attributes.empty(), 8);
        assertPoint(recorded, KafkaCommitStateMetrics.MetricNames.RECORDS_COMMITTED, Attributes.empty(), 2);
        assertPoint(recorded, KafkaCommitStateMetrics.MetricNames.RECORDS_CANCELLED, Attributes.empty(), 1);
        assertPoint(
            recorded,
            KafkaCommitStateMetrics.MetricNames.RECORDS_COMMIT_INELIGIBLE,
            Attributes.empty(),
            1
        );
        assertPoint(recorded, KafkaCommitStateMetrics.MetricNames.RECORDS_OUTSTANDING, Attributes.empty(), 1);
        assertPoint(
            recorded,
            KafkaCommitStateMetrics.MetricNames.COMMIT_ATTEMPTS_REJECTED,
            Attributes.empty(),
            2
        );
        assertPoint(
            recorded,
            KafkaCommitStateMetrics.MetricNames.COMMIT_RETRY_DEGRADED,
            Attributes.empty(),
            0
        );
        assertPoint(
            recorded,
            KafkaCommitStateMetrics.MetricNames.COMMIT_RETRY_RECOVERIES,
            Attributes.empty(),
            1
        );
        assertPoint(
            recorded,
            KafkaCommitStateMetrics.MetricNames.COMMIT_RETRY_ATTEMPTS,
            Attributes.of(
                KafkaCommitStateMetrics.FAILURE_CLASS_ATTRIBUTE,
                "coordinator"
            ),
            1
        );
        assertPoint(
            recorded,
            KafkaCommitStateMetrics.MetricNames.COMMIT_RETRY_ATTEMPTS,
            Attributes.of(
                KafkaCommitStateMetrics.FAILURE_CLASS_ATTRIBUTE,
                "transport"
            ),
            1
        );

        for (var cause : KafkaSourceOwner.AbandonmentCause.values()) {
            assertPoint(
                recorded,
                KafkaCommitStateMetrics.MetricNames.RECORDS_ABANDONED_AT_REVOCATION,
                Attributes.of(
                    KafkaCommitStateMetrics.CAUSE_ATTRIBUTE,
                    cause.name().toLowerCase(java.util.Locale.ROOT)
                ),
                1
            );
        }
        for (var resolution : Set.of(
            KafkaSourceOwner.CommitResolution.ACKNOWLEDGED,
            KafkaSourceOwner.CommitResolution.REJECTED,
            KafkaSourceOwner.CommitResolution.OUTCOME_UNKNOWN
        )) {
            assertPoint(
                recorded,
                KafkaCommitStateMetrics.MetricNames.COMMIT_RESOLUTIONS,
                Attributes.of(
                    KafkaCommitStateMetrics.OUTCOME_ATTRIBUTE,
                    resolution.name().toLowerCase(java.util.Locale.ROOT)
                ),
                1
            );
        }

        assertAttributeSets(
            recorded,
            KafkaCommitStateMetrics.MetricNames.RECORDS_ABANDONED_AT_REVOCATION,
            Set.of(
                Attributes.of(KafkaCommitStateMetrics.CAUSE_ATTRIBUTE, "rejected"),
                Attributes.of(KafkaCommitStateMetrics.CAUSE_ATTRIBUTE, "unknown"),
                Attributes.of(KafkaCommitStateMetrics.CAUSE_ATTRIBUTE, "unsubmitted")
            )
        );
        assertAttributeSets(
            recorded,
            KafkaCommitStateMetrics.MetricNames.COMMIT_RESOLUTIONS,
            Set.of(
                Attributes.of(KafkaCommitStateMetrics.OUTCOME_ATTRIBUTE, "acknowledged"),
                Attributes.of(KafkaCommitStateMetrics.OUTCOME_ATTRIBUTE, "rejected"),
                Attributes.of(KafkaCommitStateMetrics.OUTCOME_ATTRIBUTE, "outcome_unknown")
            )
        );
        assertAttributeSets(
            recorded,
            KafkaCommitStateMetrics.MetricNames.COMMIT_RETRY_ATTEMPTS,
            Set.of(
                Attributes.of(KafkaCommitStateMetrics.FAILURE_CLASS_ATTRIBUTE, "coordinator"),
                Attributes.of(KafkaCommitStateMetrics.FAILURE_CLASS_ATTRIBUTE, "transport")
            )
        );
    }

    @Test
    void sourcePositionPublishesLagAndCommitHeadAgeAsGauges() {
        var metrics = new KafkaCommitStateMetrics(telemetry.openTelemetrySdk.getMeter("test"), () -> 61_000L);
        metrics.sourcePositionObserved(new KafkaSourceOwner.SourcePosition(3, 900, 500, 1, OptionalLong.of(1_000L), 2));

        var recorded = telemetry.getFinishedMetrics();
        assertGauge(recorded, KafkaCommitStateMetrics.MetricNames.ASSIGNED_PARTITIONS, 3);
        assertGauge(recorded, KafkaCommitStateMetrics.MetricNames.CONSUMER_LAG, 900);
        assertGauge(recorded, KafkaCommitStateMetrics.MetricNames.MAX_PARTITION_LAG, 500);
        assertGauge(recorded, KafkaCommitStateMetrics.MetricNames.LAG_UNKNOWN_PARTITIONS, 1);
        assertGauge(recorded, KafkaCommitStateMetrics.MetricNames.OLDEST_COMMIT_HEAD_AGE, 60_000);
        assertGauge(recorded, KafkaCommitStateMetrics.MetricNames.STALE_COMMIT_HEAD_PARTITIONS, 2);

        metrics.sourcePositionObserved(new KafkaSourceOwner.SourcePosition(0, 0, 0, 0, OptionalLong.empty(), 0));
        assertGauge(telemetry.getFinishedMetrics(), KafkaCommitStateMetrics.MetricNames.OLDEST_COMMIT_HEAD_AGE, 0);
    }

    private void assertGauge(Iterable<MetricData> metrics, String name, long expectedValue) {
        for (var metric : metrics) {
            if (metric.getName().equals(name)) {
                Assertions.assertEquals(
                    expectedValue,
                    metric.getLongGaugeData().getPoints().stream().findFirst().orElseThrow().getValue()
                );
                return;
            }
        }
        throw new AssertionError("Missing metric " + name);
    }

    private void assertPoint(
        Iterable<MetricData> metrics,
        String name,
        Attributes attributes,
        long expectedValue
    ) {
        for (var metric : metrics) {
            if (metric.getName().equals(name)) {
                var value = metric.getLongSumData().getPoints().stream()
                    .filter(point -> point.getAttributes().equals(attributes))
                    .findFirst()
                    .orElseThrow()
                    .getValue();
                Assertions.assertEquals(expectedValue, value);
                return;
            }
        }
        throw new AssertionError("Missing metric " + name);
    }

    private void assertAttributeSets(
        Iterable<MetricData> metrics,
        String name,
        Set<Attributes> expectedAttributes
    ) {
        for (var metric : metrics) {
            if (metric.getName().equals(name)) {
                var actualAttributes = metric.getLongSumData().getPoints().stream()
                    .map(point -> point.getAttributes())
                    .collect(java.util.stream.Collectors.toSet());
                Assertions.assertEquals(expectedAttributes, actualAttributes);
                return;
            }
        }
        throw new AssertionError("Missing metric " + name);
    }
}
