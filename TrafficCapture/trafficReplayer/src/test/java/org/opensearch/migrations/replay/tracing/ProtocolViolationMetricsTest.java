/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.migrations.replay.tracing;

import org.opensearch.migrations.tracing.InMemoryInstrumentationBundle;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class ProtocolViolationMetricsTest {
    @Test
    void recordsBothIrreversibleProtocolViolationMilestones() {
        try (var telemetry = new InMemoryInstrumentationBundle(false, true)) {
            var metrics = new ProtocolViolationMetrics(
                telemetry.openTelemetrySdk.getMeter("protocol-violation-test")
            );

            metrics.drainStarted();
            metrics.terminationTriggered();

            var recorded = telemetry.getFinishedMetrics();
            Assertions.assertEquals(
                1,
                InMemoryInstrumentationBundle.getMetricValueOrZero(
                    recorded,
                    ProtocolViolationMetrics.MetricNames.DRAINS_STARTED
                )
            );
            Assertions.assertEquals(
                1,
                InMemoryInstrumentationBundle.getMetricValueOrZero(
                    recorded,
                    ProtocolViolationMetrics.MetricNames.TERMINATIONS_TRIGGERED
                )
            );
        }
    }
}
