/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.migrations.replay.tracing;

import java.time.Duration;

import org.opensearch.migrations.replay.lifecycle.TargetAttemptPermitProvider;

import io.opentelemetry.api.metrics.DoubleHistogram;
import io.opentelemetry.api.metrics.LongCounter;
import io.opentelemetry.api.metrics.LongUpDownCounter;
import io.opentelemetry.api.metrics.Meter;
import lombok.NonNull;

/**
 * Describes contention and permit conservation at the application-wide target-attempt limit.
 *
 * <p>Acquisition counters distinguish requests, queued waiters, cancellations, and failures; permit counters
 * distinguish acquisition, current active ownership, and release. The hold-duration histogram shows how long
 * capacity remains occupied, making a saturated limit distinguishable from leaked permits or slow target
 * attempts.</p>
 *
 * <p>The instruments intentionally carry no request or connection identifiers. All dimensions are implicit
 * in the bounded event types, so concurrency telemetry remains fixed-cardinality under load.</p>
 */
public final class TargetAttemptPermitMetrics implements TargetAttemptPermitProvider.Metrics {
    private static final String UNIT_REQUESTS = "requests";
    private static final String UNIT_PERMITS = "permits";

    public static final class MetricNames {
        private MetricNames() {}

        public static final String ACQUISITION_REQUESTS =
            "targetAttemptPermitAcquisitionRequests";
        public static final String ACQUISITIONS_PENDING =
            "targetAttemptPermitAcquisitionsPending";
        public static final String ACQUISITIONS_CANCELLED =
            "targetAttemptPermitAcquisitionsCancelled";
        public static final String ACQUISITIONS_FAILED =
            "targetAttemptPermitAcquisitionsFailed";
        public static final String PERMITS_ACQUIRED = "targetAttemptPermitsAcquired";
        public static final String PERMITS_ACTIVE = "targetAttemptPermitsActive";
        public static final String PERMITS_RELEASED = "targetAttemptPermitsReleased";
        public static final String PERMIT_HELD_DURATION =
            "targetAttemptPermitHeldDuration";
    }

    private final LongCounter acquisitionRequests;
    private final LongUpDownCounter acquisitionsPending;
    private final LongCounter acquisitionsCancelled;
    private final LongCounter acquisitionsFailed;
    private final LongCounter permitsAcquired;
    private final LongUpDownCounter permitsActive;
    private final LongCounter permitsReleased;
    private final DoubleHistogram permitHeldDuration;

    public TargetAttemptPermitMetrics(@NonNull Meter meter) {
        acquisitionRequests = meter.counterBuilder(MetricNames.ACQUISITION_REQUESTS)
            .setUnit(UNIT_REQUESTS)
            .build();
        acquisitionsPending = meter.upDownCounterBuilder(MetricNames.ACQUISITIONS_PENDING)
            .setUnit(UNIT_REQUESTS)
            .build();
        acquisitionsCancelled = meter.counterBuilder(MetricNames.ACQUISITIONS_CANCELLED)
            .setUnit(UNIT_REQUESTS)
            .build();
        acquisitionsFailed = meter.counterBuilder(MetricNames.ACQUISITIONS_FAILED)
            .setUnit(UNIT_REQUESTS)
            .build();
        permitsAcquired = meter.counterBuilder(MetricNames.PERMITS_ACQUIRED)
            .setUnit(UNIT_PERMITS)
            .build();
        permitsActive = meter.upDownCounterBuilder(MetricNames.PERMITS_ACTIVE)
            .setUnit(UNIT_PERMITS)
            .build();
        permitsReleased = meter.counterBuilder(MetricNames.PERMITS_RELEASED)
            .setUnit(UNIT_PERMITS)
            .build();
        permitHeldDuration = meter.histogramBuilder(MetricNames.PERMIT_HELD_DURATION)
            .setUnit("ms")
            .build();
    }

    @Override
    public void acquisitionRequested() {
        acquisitionRequests.add(1);
    }

    @Override
    public void acquisitionPendingChanged(int delta) {
        acquisitionsPending.add(delta);
    }

    @Override
    public void acquisitionCancelled() {
        acquisitionsCancelled.add(1);
    }

    @Override
    public void acquisitionFailed() {
        acquisitionsFailed.add(1);
    }

    @Override
    public void permitAcquired() {
        permitsAcquired.add(1);
    }

    @Override
    public void activePermitsChanged(int delta) {
        permitsActive.add(delta);
    }

    @Override
    public void permitReleased() {
        permitsReleased.add(1);
    }

    @Override
    public void permitHeld(@NonNull Duration duration) {
        permitHeldDuration.record(duration.toNanos() / 1_000_000.0);
    }
}
