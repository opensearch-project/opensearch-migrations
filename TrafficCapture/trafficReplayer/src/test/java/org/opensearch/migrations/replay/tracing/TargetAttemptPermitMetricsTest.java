/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.migrations.replay.tracing;

import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.Set;

import org.opensearch.migrations.tracing.InMemoryInstrumentationBundle;

import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.sdk.metrics.data.MetricData;
import io.opentelemetry.sdk.metrics.data.PointData;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class TargetAttemptPermitMetricsTest {
    @Test
    void recordsFixedCardinalityPermitConservationAndHeldDuration() {
        try (var telemetry = new InMemoryInstrumentationBundle(false, true)) {
            var metrics = new TargetAttemptPermitMetrics(
                telemetry.openTelemetrySdk.getMeter("target-attempt-permit-test")
            );
            metrics.acquisitionRequested();
            metrics.acquisitionRequested();
            metrics.acquisitionPendingChanged(1);
            metrics.acquisitionPendingChanged(-1);
            metrics.acquisitionCancelled();
            metrics.permitAcquired();
            metrics.activePermitsChanged(1);
            metrics.activePermitsChanged(-1);
            metrics.permitReleased();
            metrics.permitHeld(Duration.ofMillis(5));

            var recorded = telemetry.getFinishedMetrics();
            assertLongPoint(
                recorded,
                TargetAttemptPermitMetrics.MetricNames.ACQUISITION_REQUESTS,
                2
            );
            assertLongPoint(
                recorded,
                TargetAttemptPermitMetrics.MetricNames.ACQUISITIONS_PENDING,
                0
            );
            assertLongPoint(
                recorded,
                TargetAttemptPermitMetrics.MetricNames.ACQUISITIONS_CANCELLED,
                1
            );
            assertLongPoint(
                recorded,
                TargetAttemptPermitMetrics.MetricNames.PERMITS_ACQUIRED,
                1
            );
            assertLongPoint(
                recorded,
                TargetAttemptPermitMetrics.MetricNames.PERMITS_ACTIVE,
                0
            );
            assertLongPoint(
                recorded,
                TargetAttemptPermitMetrics.MetricNames.PERMITS_RELEASED,
                1
            );
            assertHistogram(
                recorded,
                TargetAttemptPermitMetrics.MetricNames.PERMIT_HELD_DURATION,
                1,
                5.0
            );
        }
    }

    private static void assertLongPoint(
        Iterable<MetricData> metrics,
        String name,
        long expectedValue
    ) {
        for (var metric : metrics) {
            if (metric.getName().equals(name)) {
                var points = metric.getLongSumData().getPoints();
                Assertions.assertEquals(Set.of(Attributes.empty()), attributeSets(points));
                Assertions.assertEquals(
                    expectedValue,
                    points.stream().findFirst().orElseThrow().getValue()
                );
                return;
            }
        }
        throw new AssertionError("Missing metric " + name);
    }

    private static void assertHistogram(
        Iterable<MetricData> metrics,
        String name,
        long expectedCount,
        double expectedSum
    ) {
        for (var metric : metrics) {
            if (metric.getName().equals(name)) {
                var points = metric.getHistogramData().getPoints();
                Assertions.assertEquals(Set.of(Attributes.empty()), attributeSets(points));
                var point = points.stream().findFirst().orElseThrow();
                Assertions.assertEquals(expectedCount, point.getCount());
                Assertions.assertEquals(expectedSum, point.getSum());
                return;
            }
        }
        throw new AssertionError("Missing metric " + name);
    }

    private static Set<Attributes> attributeSets(
        Iterable<? extends PointData> points
    ) {
        var attributes = new LinkedHashSet<Attributes>();
        points.forEach(point -> attributes.add(point.getAttributes()));
        return attributes;
    }
}
