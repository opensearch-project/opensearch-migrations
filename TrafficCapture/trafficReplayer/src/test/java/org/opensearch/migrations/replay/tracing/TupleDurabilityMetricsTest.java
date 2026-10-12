/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.migrations.replay.tracing;

import java.time.Duration;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

import org.opensearch.migrations.replay.sink.TupleWriter;
import org.opensearch.migrations.tracing.InMemoryInstrumentationBundle;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class TupleDurabilityMetricsTest {
    @Test
    void recordsConservationLatencyAndEveryBoundedFailureReason() {
        try (var telemetry = new InMemoryInstrumentationBundle(false, true)) {
            var metrics = new TupleDurabilityMetrics(
                telemetry.openTelemetrySdk.getMeter("tuple-durability-test")
            );
            metrics.writeAccepted();
            metrics.writeAccepted();
            metrics.writeAccepted();
            metrics.writeAccepted();
            metrics.writeAccepted();
            metrics.writeAccepted();
            metrics.retryScheduled();
            metrics.writeDurable(Duration.ofMillis(12));
            metrics.writeCancelled();
            for (var reason : TupleWriter.FailureReason.values()) {
                metrics.terminalFailure(reason);
            }

            var recorded = telemetry.getFinishedMetrics();
            Assertions.assertEquals(
                6,
                InMemoryInstrumentationBundle.getMetricValueOrZero(
                    recorded,
                    TupleDurabilityMetrics.MetricNames.WRITES_ACCEPTED
                )
            );
            Assertions.assertEquals(
                0,
                InMemoryInstrumentationBundle.getMetricValueOrZero(
                    recorded,
                    TupleDurabilityMetrics.MetricNames.WRITES_PENDING
                )
            );
            Assertions.assertEquals(
                1,
                InMemoryInstrumentationBundle.getMetricValueOrZero(
                    recorded,
                    TupleDurabilityMetrics.MetricNames.RETRIES
                )
            );
            Assertions.assertEquals(
                1,
                InMemoryInstrumentationBundle.getMetricValueOrZero(
                    recorded,
                    TupleDurabilityMetrics.MetricNames.WRITES_DURABLE
                )
            );
            Assertions.assertEquals(
                1,
                InMemoryInstrumentationBundle.getMetricValueOrZero(
                    recorded,
                    TupleDurabilityMetrics.MetricNames.WRITES_CANCELLED
                )
            );
            var failureMetric = recorded.stream()
                .filter(metric -> metric.getName().equals(
                    TupleDurabilityMetrics.MetricNames.TERMINAL_FAILURES
                ))
                .findFirst()
                .orElseThrow();
            Assertions.assertEquals(
                Arrays.stream(TupleWriter.FailureReason.values())
                    .map(reason -> reason.name().toLowerCase(java.util.Locale.ROOT))
                    .collect(Collectors.toSet()),
                failureMetric.getLongSumData().getPoints().stream()
                    .map(point -> point.getAttributes().get(
                        TupleDurabilityMetrics.FAILURE_REASON_ATTRIBUTE
                    ))
                    .collect(Collectors.toSet())
            );
            Assertions.assertEquals(
                Set.of(1L),
                failureMetric.getLongSumData().getPoints().stream()
                    .map(io.opentelemetry.sdk.metrics.data.LongPointData::getValue)
                    .collect(Collectors.toSet())
            );
            var latency = recorded.stream()
                .filter(metric -> metric.getName().equals(
                    TupleDurabilityMetrics.MetricNames.WRITE_LATENCY
                ))
                .findFirst()
                .orElseThrow()
                .getHistogramData()
                .getPoints()
                .stream()
                .findFirst()
                .orElseThrow();
            Assertions.assertEquals(1, latency.getCount());
            Assertions.assertEquals(12.0, latency.getSum());
        }
    }
}
