package org.opensearch.migrations.replay.tracing;

import org.opensearch.migrations.replay.lifecycle.ResourceOwnership;
import org.opensearch.migrations.tracing.InstrumentationTest;
import org.opensearch.migrations.tracing.TestContext;

import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.sdk.metrics.data.MetricData;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class ResourceOwnershipMetricsTest extends InstrumentationTest {

    @Override
    protected TestContext makeInstrumentationContext() {
        return TestContext.withAllTracking();
    }

    @Test
    void recordsOwnedBuffersAndOwnershipFailuresByResourceType() {
        var metrics = rootContext.getResourceOwnershipMetrics();
        var type = ResourceOwnership.Type.ATTEMPT_PAYLOAD;
        var attributes = Attributes.of(
            ResourceOwnershipMetrics.RESOURCE_TYPE_ATTRIBUTE,
            type.metricLabel()
        );

        metrics.ownershipChanged(type, 1, 2, 128);
        metrics.duplicateClose(type);
        metrics.invariantFailure(type);

        var recorded = rootContext.inMemoryInstrumentationBundle.getFinishedMetrics();
        Assertions.assertEquals(
            1,
            sumPoint(recorded, ResourceOwnershipMetrics.MetricNames.OWNED_HANDLES, attributes)
        );
        Assertions.assertEquals(
            2,
            sumPoint(recorded, ResourceOwnershipMetrics.MetricNames.OWNED_BUFFERS, attributes)
        );
        Assertions.assertEquals(
            128,
            sumPoint(recorded, ResourceOwnershipMetrics.MetricNames.OWNED_BYTES, attributes)
        );
        Assertions.assertEquals(
            1,
            sumPoint(recorded, ResourceOwnershipMetrics.MetricNames.DUPLICATE_CLOSE_ATTEMPTS, attributes)
        );
        Assertions.assertEquals(
            1,
            sumPoint(recorded, ResourceOwnershipMetrics.MetricNames.INVARIANT_FAILURES, attributes)
        );

        metrics.ownershipChanged(type, -1, -2, -128);
        recorded = rootContext.inMemoryInstrumentationBundle.getFinishedMetrics();
        Assertions.assertEquals(
            0,
            sumPoint(recorded, ResourceOwnershipMetrics.MetricNames.OWNED_HANDLES, attributes)
        );
        Assertions.assertEquals(
            0,
            sumPoint(recorded, ResourceOwnershipMetrics.MetricNames.OWNED_BUFFERS, attributes)
        );
        Assertions.assertEquals(
            0,
            sumPoint(recorded, ResourceOwnershipMetrics.MetricNames.OWNED_BYTES, attributes)
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
}
