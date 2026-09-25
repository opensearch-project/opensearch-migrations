/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.migrations.replay.tracing;

import org.opensearch.migrations.replay.ProtocolViolationTerminator;

import io.opentelemetry.api.metrics.LongCounter;
import io.opentelemetry.api.metrics.Meter;
import lombok.NonNull;

/** Fixed-cardinality observability for the bounded protocol-violation termination path. */
public final class ProtocolViolationMetrics implements ProtocolViolationTerminator.Metrics {
    public static final class MetricNames {
        private MetricNames() {}

        public static final String DRAINS_STARTED = "replayProtocolViolationDrainsStarted";
        public static final String TERMINATIONS_TRIGGERED =
            "replayProtocolViolationTerminationsTriggered";
    }

    private final LongCounter drainsStarted;
    private final LongCounter terminationsTriggered;

    public ProtocolViolationMetrics(@NonNull Meter meter) {
        drainsStarted = meter.counterBuilder(MetricNames.DRAINS_STARTED)
            .setUnit("drains")
            .build();
        terminationsTriggered = meter.counterBuilder(MetricNames.TERMINATIONS_TRIGGERED)
            .setUnit("terminations")
            .build();
    }

    @Override
    public void drainStarted() {
        drainsStarted.add(1);
    }

    @Override
    public void terminationTriggered() {
        terminationsTriggered.add(1);
    }
}
