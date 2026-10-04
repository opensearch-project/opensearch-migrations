package org.opensearch.migrations.replay.tracing;

import org.opensearch.migrations.replay.lifecycle.ResourceOwnership;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.LongCounter;
import io.opentelemetry.api.metrics.LongUpDownCounter;
import io.opentelemetry.api.metrics.Meter;
import lombok.NonNull;

/**
 * Exposes the live reference-counted payload population and ownership invariant failures.
 *
 * <p>Up-down counters track owned wrapper handles, Netty buffers, and readable bytes by the small fixed set
 * of payload types. They rise when Java-level ownership is created and fall only after the underlying release
 * succeeds, so nonzero residuals identify retained replay memory rather than merely allocated objects.</p>
 *
 * <p>Separate counters record duplicate close attempts and failed release invariants. Resource identities are
 * intentionally absent from attributes, keeping leak detection aggregatable without request-level metric
 * cardinality.</p>
 */
public final class ResourceOwnershipMetrics implements ResourceOwnership.Metrics {
    public static final AttributeKey<String> RESOURCE_TYPE_ATTRIBUTE =
        AttributeKey.stringKey("resourceType");

    public static final class MetricNames {
        private MetricNames() {}

        public static final String OWNED_HANDLES = "resourceOwnedHandles";
        public static final String OWNED_BUFFERS = "resourceOwnedBuffers";
        public static final String OWNED_BYTES = "resourceOwnedBytes";
        public static final String DUPLICATE_CLOSE_ATTEMPTS = "resourceDuplicateCloseAttempts";
        public static final String INVARIANT_FAILURES = "resourceOwnershipInvariantFailures";
    }

    private final LongUpDownCounter ownedHandles;
    private final LongUpDownCounter ownedBuffers;
    private final LongUpDownCounter ownedBytes;
    private final LongCounter duplicateCloseAttempts;
    private final LongCounter invariantFailures;

    public ResourceOwnershipMetrics(@NonNull Meter meter) {
        ownedHandles = meter.upDownCounterBuilder(MetricNames.OWNED_HANDLES)
            .setUnit("handles")
            .build();
        ownedBuffers = meter.upDownCounterBuilder(MetricNames.OWNED_BUFFERS)
            .setUnit("buffers")
            .build();
        ownedBytes = meter.upDownCounterBuilder(MetricNames.OWNED_BYTES)
            .setUnit("By")
            .build();
        duplicateCloseAttempts = meter.counterBuilder(MetricNames.DUPLICATE_CLOSE_ATTEMPTS)
            .setUnit("attempts")
            .build();
        invariantFailures = meter.counterBuilder(MetricNames.INVARIANT_FAILURES)
            .setUnit("failures")
            .build();
    }

    @Override
    public void ownershipChanged(
        @NonNull ResourceOwnership.Type type,
        int handleDelta,
        int bufferDelta,
        long byteDelta
    ) {
        var attributes = attributes(type);
        ownedHandles.add(handleDelta, attributes);
        ownedBuffers.add(bufferDelta, attributes);
        ownedBytes.add(byteDelta, attributes);
    }

    @Override
    public void duplicateClose(@NonNull ResourceOwnership.Type type) {
        duplicateCloseAttempts.add(1, attributes(type));
    }

    @Override
    public void invariantFailure(@NonNull ResourceOwnership.Type type) {
        invariantFailures.add(1, attributes(type));
    }

    private static Attributes attributes(ResourceOwnership.Type type) {
        return Attributes.of(RESOURCE_TYPE_ATTRIBUTE, type.metricLabel());
    }
}
