/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.migrations.replay.tracing;

import org.opensearch.migrations.replay.datahandlers.TargetConnectionLimiter;
import org.opensearch.migrations.replay.http.retries.TargetRetryPacing;

import io.opentelemetry.api.metrics.Meter;
import lombok.NonNull;

/**
 * Reports how the process's connection limiter and retry pacing are holding back target traffic, so a
 * replay that falls behind because the target is shedding load is distinguishable from a slow replayer.
 */
public final class TargetPacingMetrics {
    public static final class MetricNames {
        public static final String CONNECTION_ALLOWED_RATE = "targetConnectionAllowedRate";
        public static final String CONNECTIONS_DELAYED = "targetConnectionsDelayed";
        public static final String CONNECTION_DELAY = "targetConnectionDelay";
        public static final String CONNECTION_RATE_DECREASES = "targetConnectionRateDecreases";
        public static final String CONNECTION_FAILURES = "targetConnectionFailures";
        public static final String RETRIES_BUDGET_DEFERRED = "targetRetriesBudgetDeferred";
        public static final String THROTTLED_RESPONSES = "targetThrottledResponses";

        private MetricNames() {}
    }

    private TargetPacingMetrics() {}

    public static void register(
        @NonNull Meter meter,
        @NonNull TargetConnectionLimiter connectionLimiter,
        @NonNull TargetRetryPacing retryPacing
    ) {
        if (connectionLimiter.isLimiting()) {
            meter.gaugeBuilder(MetricNames.CONNECTION_ALLOWED_RATE)
                .setDescription("New target connections per second the process currently allows")
                .setUnit("connections/s")
                .buildWithCallback(m -> m.record(connectionLimiter.ratePerSecond()));
            meter.counterBuilder(MetricNames.CONNECTIONS_DELAYED)
                .setDescription("Target connects that waited for the connection allowance")
                .setUnit("connects")
                .buildWithCallback(m -> m.record(connectionLimiter.totalDelayedReservations()));
            meter.counterBuilder(MetricNames.CONNECTION_DELAY)
                .setDescription("Total time target connects waited for the connection allowance")
                .setUnit("ms")
                .buildWithCallback(m -> m.record(connectionLimiter.totalDelayMillis()));
            meter.counterBuilder(MetricNames.CONNECTION_RATE_DECREASES)
                .setDescription("Times the connection allowance was halved after connect or TLS failures")
                .setUnit("decreases")
                .buildWithCallback(m -> m.record(connectionLimiter.totalRateDecreases()));
            meter.counterBuilder(MetricNames.CONNECTION_FAILURES)
                .setDescription("Target TCP connects and TLS handshakes that failed")
                .setUnit("connects")
                .buildWithCallback(m -> m.record(connectionLimiter.totalFailures()));
        }
        meter.counterBuilder(MetricNames.RETRIES_BUDGET_DEFERRED)
            .setDescription("Target retries delayed past their backoff because the process retry budget was spent")
            .setUnit("retries")
            .buildWithCallback(m -> m.record(retryPacing.budgetDeferredRetries()));
        meter.counterBuilder(MetricNames.THROTTLED_RESPONSES)
            .setDescription("Target responses that shed load (429 or the IAM concurrency 400) and were retried")
            .setUnit("responses")
            .buildWithCallback(m -> m.record(retryPacing.throttledResponses()));
    }
}
