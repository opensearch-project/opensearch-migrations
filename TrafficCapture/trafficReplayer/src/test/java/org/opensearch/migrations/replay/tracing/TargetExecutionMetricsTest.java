/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.migrations.replay.tracing;

import java.util.Set;

import org.opensearch.migrations.replay.lifecycle.ReplayOutcomes.TargetAttemptOutcome;
import org.opensearch.migrations.tracing.InMemoryInstrumentationBundle;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class TargetExecutionMetricsTest {
    @Test
    void recordsEveryBoundedNoResponseKindAndRetryExhaustion() {
        try (var telemetry = new InMemoryInstrumentationBundle(false, true)) {
            var metrics = new TargetExecutionMetrics(
                telemetry.openTelemetrySdk.getMeter("target-execution-test")
            );
            for (var kind : TargetAttemptOutcome.NoTargetResponseKind.values()) {
                metrics.targetAttemptWithoutResponse(kind);
            }
            metrics.targetRetriesExhausted();

            var recorded = telemetry.getFinishedMetrics();
            var noResponseMetric = recorded.stream()
                .filter(metric -> metric.getName().equals(
                    TargetExecutionMetrics.MetricNames.ATTEMPTS_WITHOUT_RESPONSE
                ))
                .findFirst()
                .orElseThrow();
            Assertions.assertEquals(
                Set.of("read_timeout", "transport_failure", "missing_http_response"),
                noResponseMetric.getLongSumData().getPoints().stream()
                    .map(point -> point.getAttributes().get(
                        TargetExecutionMetrics.NO_RESPONSE_KIND_ATTRIBUTE
                    ))
                    .collect(java.util.stream.Collectors.toSet())
            );
            Assertions.assertEquals(
                3,
                noResponseMetric.getLongSumData().getPoints().stream()
                    .mapToLong(io.opentelemetry.sdk.metrics.data.LongPointData::getValue)
                    .sum()
            );
            Assertions.assertEquals(
                1,
                InMemoryInstrumentationBundle.getMetricValueOrZero(
                    recorded,
                    TargetExecutionMetrics.MetricNames.RETRIES_EXHAUSTED
                )
            );
        }
    }
}
