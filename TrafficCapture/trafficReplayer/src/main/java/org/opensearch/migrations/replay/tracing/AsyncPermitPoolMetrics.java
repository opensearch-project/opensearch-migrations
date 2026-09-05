package org.opensearch.migrations.replay.tracing;

import java.time.Duration;

import org.opensearch.migrations.replay.lifecycle.AsyncPermitPool;

import io.opentelemetry.api.metrics.DoubleHistogram;
import io.opentelemetry.api.metrics.LongCounter;
import io.opentelemetry.api.metrics.LongUpDownCounter;
import io.opentelemetry.api.metrics.Meter;
import lombok.NonNull;

public final class AsyncPermitPoolMetrics implements AsyncPermitPool.Metrics {
    public static final class MetricNames {
        private MetricNames() {}

        public static final String AVAILABLE = "permitPoolAvailable";
        public static final String QUEUED = "permitPoolQueued";
        public static final String HELD_DURATION = "permitPoolHeldDuration";
        public static final String CANCELLATION_COUNT = "permitPoolCancellationCount";
    }

    private final LongUpDownCounter available;
    private final LongUpDownCounter queued;
    private final DoubleHistogram heldDuration;
    private final LongCounter cancellationCount;

    public AsyncPermitPoolMetrics(@NonNull Meter meter) {
        available = meter.upDownCounterBuilder(MetricNames.AVAILABLE)
            .setUnit("permits")
            .build();
        queued = meter.upDownCounterBuilder(MetricNames.QUEUED)
            .setUnit("requests")
            .build();
        heldDuration = meter.histogramBuilder(MetricNames.HELD_DURATION)
            .setUnit("ms")
            .build();
        cancellationCount = meter.counterBuilder(MetricNames.CANCELLATION_COUNT)
            .setUnit("requests")
            .build();
    }

    @Override
    public void availableChanged(int delta) {
        available.add(delta);
    }

    @Override
    public void queuedChanged(int delta) {
        queued.add(delta);
    }

    @Override
    public void permitHeld(@NonNull Duration duration) {
        heldDuration.record(duration.toNanos() / 1_000_000.0);
    }

    @Override
    public void cancelled(int count) {
        cancellationCount.add(count);
    }
}
