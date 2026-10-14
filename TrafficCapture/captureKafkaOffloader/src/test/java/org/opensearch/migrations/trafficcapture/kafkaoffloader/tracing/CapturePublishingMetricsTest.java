package org.opensearch.migrations.trafficcapture.kafkaoffloader.tracing;

import java.util.Collection;
import java.util.Set;

import org.opensearch.migrations.tracing.InMemoryInstrumentationBundle;
import org.opensearch.migrations.trafficcapture.kafkaoffloader.tracing.CapturePublishingMetrics.FailureReason;
import org.opensearch.migrations.trafficcapture.kafkaoffloader.tracing.CapturePublishingMetrics.PublishOutcome;
import org.opensearch.migrations.trafficcapture.kafkaoffloader.tracing.CapturePublishingMetrics.RecordKind;
import org.opensearch.migrations.trafficcapture.kafkaoffloader.tracing.CapturePublishingMetrics.RoutingTransition;

import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.sdk.metrics.data.MetricData;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CapturePublishingMetricsTest {
    @Test
    void recordsBytesWritersConnectionsAndBoundedOutcomesAreConserved() {
        try (var telemetry = new InMemoryInstrumentationBundle(false, true)) {
            var metrics = new CapturePublishingMetrics(
                telemetry.openTelemetrySdk.getMeter("captureProxy")
            );

            metrics.publicationAccepted(RecordKind.TRAFFIC, 10);
            metrics.publicationCompleted(RecordKind.TRAFFIC, PublishOutcome.ACKNOWLEDGED, 10);
            metrics.publicationAccepted(RecordKind.HEARTBEAT, 5);
            metrics.publicationCompleted(RecordKind.HEARTBEAT, PublishOutcome.ACKNOWLEDGED, 5);
            metrics.publicationAccepted(RecordKind.HEARTBEAT, 7);
            metrics.publicationCompleted(RecordKind.HEARTBEAT, PublishOutcome.FAILED, 7);
            metrics.heartbeatDeadlineViolated();
            metrics.brokerTimeViolated();
            for (var transition : RoutingTransition.values()) {
                metrics.routingTransition(transition);
            }
            metrics.writersActivated(2, 0);
            metrics.writersActivated(1, 2);
            metrics.writerRetired();
            metrics.writerRetired();
            metrics.activeWritersBeganDraining(1);
            metrics.writerRetired();
            metrics.writeGateTripped(FailureReason.HEARTBEAT_DEADLINE);
            metrics.writeRejected();
            metrics.publisherFailed(FailureReason.HEARTBEAT_DEADLINE);

            var recorded = telemetry.getFinishedMetrics();
            assertPoint(
                recorded,
                CapturePublishingMetrics.MetricNames.RECORDS_ACCEPTED,
                Attributes.of(CapturePublishingMetrics.RECORD_KIND, "traffic"),
                1
            );
            assertPoint(
                recorded,
                CapturePublishingMetrics.MetricNames.RECORDS_ACCEPTED,
                Attributes.of(CapturePublishingMetrics.RECORD_KIND, "heartbeat"),
                2
            );
            assertPoint(
                recorded,
                CapturePublishingMetrics.MetricNames.BYTES_ACCEPTED,
                Attributes.of(CapturePublishingMetrics.RECORD_KIND, "heartbeat"),
                12
            );
            assertPoint(
                recorded,
                CapturePublishingMetrics.MetricNames.COMPLETIONS,
                Attributes.of(
                    CapturePublishingMetrics.RECORD_KIND,
                    "heartbeat",
                    CapturePublishingMetrics.OUTCOME,
                    "failed"
                ),
                1
            );
            assertPoint(
                recorded,
                CapturePublishingMetrics.MetricNames.RECORDS_AWAITING_ACKNOWLEDGEMENT,
                Attributes.of(CapturePublishingMetrics.RECORD_KIND, "traffic"),
                0
            );
            assertPoint(
                recorded,
                CapturePublishingMetrics.MetricNames.BYTES_AWAITING_ACKNOWLEDGEMENT,
                Attributes.of(CapturePublishingMetrics.RECORD_KIND, "heartbeat"),
                0
            );
            assertPoint(
                recorded,
                CapturePublishingMetrics.MetricNames.HEARTBEAT_SUBMISSIONS,
                Attributes.empty(),
                2
            );
            assertPoint(
                recorded,
                CapturePublishingMetrics.MetricNames.HEARTBEAT_ACKNOWLEDGEMENTS,
                Attributes.empty(),
                1
            );
            assertPoint(
                recorded,
                CapturePublishingMetrics.MetricNames.HEARTBEAT_DEADLINE_VIOLATIONS,
                Attributes.empty(),
                1
            );
            assertPoint(
                recorded,
                CapturePublishingMetrics.MetricNames.BROKER_TIME_VIOLATIONS,
                Attributes.empty(),
                1
            );
            assertPoint(
                recorded,
                CapturePublishingMetrics.MetricNames.WRITERS_ACTIVE,
                Attributes.empty(),
                0
            );
            assertPoint(
                recorded,
                CapturePublishingMetrics.MetricNames.WRITERS_DRAINING,
                Attributes.empty(),
                0
            );
            assertPoint(
                recorded,
                CapturePublishingMetrics.MetricNames.WRITERS_RETIRED,
                Attributes.empty(),
                3
            );
            assertPoint(
                recorded,
                CapturePublishingMetrics.MetricNames.WRITES_REJECTED,
                Attributes.empty(),
                1
            );
            assertPoint(
                recorded,
                CapturePublishingMetrics.MetricNames.PUBLISHER_FAILURES,
                Attributes.of(CapturePublishingMetrics.REASON, "heartbeat_deadline"),
                1
            );
            assertAttributeSets(
                recorded,
                CapturePublishingMetrics.MetricNames.ROUTING_TRANSITIONS,
                Set.of(
                    Attributes.of(CapturePublishingMetrics.TRANSITION, "assignment_received"),
                    Attributes.of(CapturePublishingMetrics.TRANSITION, "generation_prepared"),
                    Attributes.of(CapturePublishingMetrics.TRANSITION, "generation_activated")
                )
            );
        }
    }

    private static void assertPoint(
        Collection<MetricData> metrics,
        String name,
        Attributes attributes,
        long expectedValue
    ) {
        var value = metrics.stream()
            .filter(metric -> metric.getName().equals(name))
            .flatMap(metric -> metric.getLongSumData().getPoints().stream())
            .filter(point -> point.getAttributes().equals(attributes))
            .findFirst()
            .orElseThrow(() -> new AssertionError("Missing metric " + name + " " + attributes))
            .getValue();
        assertEquals(expectedValue, value);
    }

    private static void assertAttributeSets(
        Collection<MetricData> metrics,
        String name,
        Set<Attributes> expected
    ) {
        var actual = metrics.stream()
            .filter(metric -> metric.getName().equals(name))
            .flatMap(metric -> metric.getLongSumData().getPoints().stream())
            .map(point -> point.getAttributes())
            .collect(java.util.stream.Collectors.toSet());
        assertEquals(expected, actual);
    }
}
