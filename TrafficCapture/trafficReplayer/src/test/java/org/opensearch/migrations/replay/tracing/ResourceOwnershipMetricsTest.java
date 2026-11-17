/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.migrations.replay.tracing;

import java.util.Set;

import org.opensearch.migrations.replay.lifecycle.ResourceOwnership;
import org.opensearch.migrations.tracing.InMemoryInstrumentationBundle;

import io.opentelemetry.api.common.Attributes;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class ResourceOwnershipMetricsTest {
    @Test
    void recordsConservedPopulationsAndBoundedFailureDimensions() {
        try (var telemetry = new InMemoryInstrumentationBundle(false, true)) {
            var metrics = new ResourceOwnershipMetrics(
                telemetry.openTelemetrySdk.getMeter("resource-ownership-test")
            );

            for (var type : ResourceOwnership.Type.values()) {
                metrics.ownershipChanged(type, 1, 2, 3);
                metrics.ownershipChanged(type, -1, -2, -3);
                metrics.duplicateClose(type);
                metrics.invariantFailure(type);
            }

            var recorded = telemetry.getFinishedMetrics();
            for (var metricName : Set.of(
                ResourceOwnershipMetrics.MetricNames.OWNED_HANDLES,
                ResourceOwnershipMetrics.MetricNames.OWNED_BUFFERS,
                ResourceOwnershipMetrics.MetricNames.OWNED_BYTES
            )) {
                assertEveryTypeHasValue(recorded, metricName, 0);
            }
            assertEveryTypeHasValue(
                recorded,
                ResourceOwnershipMetrics.MetricNames.DUPLICATE_CLOSE_ATTEMPTS,
                1
            );
            assertEveryTypeHasValue(
                recorded,
                ResourceOwnershipMetrics.MetricNames.INVARIANT_FAILURES,
                1
            );
        }
    }

    private static void assertEveryTypeHasValue(
        Iterable<io.opentelemetry.sdk.metrics.data.MetricData> metrics,
        String metricName,
        long expectedValue
    ) {
        for (var metric : metrics) {
            if (!metric.getName().equals(metricName)) {
                continue;
            }
            var points = metric.getLongSumData().getPoints();
            Assertions.assertEquals(ResourceOwnership.Type.values().length, points.size());
            for (var type : ResourceOwnership.Type.values()) {
                var attributes = Attributes.of(
                    ResourceOwnershipMetrics.RESOURCE_TYPE_ATTRIBUTE,
                    type.metricLabel()
                );
                var value = points.stream()
                    .filter(point -> point.getAttributes().equals(attributes))
                    .findFirst()
                    .orElseThrow()
                    .getValue();
                Assertions.assertEquals(expectedValue, value);
            }
            return;
        }
        throw new AssertionError("Missing metric " + metricName);
    }
}
