package org.opensearch.migrations.replay.tracing;

// REBUILD-LIMBO(G2) -- nothing in this file is live yet. Javadoc is left outside the marked
// regions so it needs no escaping and keeps its blame; it documents code that is not compiled.
// Resolve each region to dead, keep, or refactor deliberately. If a member is deleted, delete its
// javadoc with it. See AGENTS.md section 8a.
// Cascade from the left-behind legacy set. Unresolved: TargetAttemptPermitProvider . Carried byte-identical so the behaviour stays enumerable; its milestone strips the legacy references and un-marks it.
// Un-mark a member by deleting the delimiter lines around it and splitting this region; the
// code between them is verbatim, so blame survives. Read this before writing anything new

// REBUILD-LIMBO-START(G2)
/*

import java.time.Duration;

import org.opensearch.migrations.replay.lifecycle.TargetAttemptPermitProvider;

import io.opentelemetry.api.metrics.DoubleHistogram;
import io.opentelemetry.api.metrics.LongCounter;
import io.opentelemetry.api.metrics.LongUpDownCounter;
import io.opentelemetry.api.metrics.Meter;
import lombok.NonNull;

public final class AsyncPermitPoolMetrics implements TargetAttemptPermitProvider.Metrics {
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

*/
// REBUILD-LIMBO-END(G2)