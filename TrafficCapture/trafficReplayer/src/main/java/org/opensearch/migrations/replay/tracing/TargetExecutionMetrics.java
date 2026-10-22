/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.migrations.replay.tracing;

import java.util.Locale;

import org.opensearch.migrations.replay.lifecycle.ReplayOutcomes.TargetAttemptOutcome;
import org.opensearch.migrations.replay.lifecycle.RequestReplayOwner;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.LongCounter;
import io.opentelemetry.api.metrics.Meter;
import lombok.NonNull;

/**
 * Counts bounded target-attempt failure outcomes that HTTP status metrics cannot represent.
 *
 * <p>No request or connection identity is attached. The only dimension is the closed
 * {@link TargetAttemptOutcome.NoTargetResponseKind} set.</p>
 */
public final class TargetExecutionMetrics implements RequestReplayOwner.Metrics {
    public static final AttributeKey<String> NO_RESPONSE_KIND_ATTRIBUTE =
        AttributeKey.stringKey("noResponseKind");

    public static final class MetricNames {
        private MetricNames() {}

        public static final String ATTEMPTS_WITHOUT_RESPONSE =
            "targetAttemptsWithoutResponse";
        public static final String RETRIES_EXHAUSTED =
            "targetRetriesExhausted";
    }

    private final LongCounter attemptsWithoutResponse;
    private final LongCounter retriesExhausted;

    public TargetExecutionMetrics(@NonNull Meter meter) {
        attemptsWithoutResponse = meter.counterBuilder(MetricNames.ATTEMPTS_WITHOUT_RESPONSE)
            .setDescription("Target attempts that ended without an HTTP response")
            .setUnit("attempts")
            .build();
        retriesExhausted = meter.counterBuilder(MetricNames.RETRIES_EXHAUSTED)
            .setDescription("Replay requests that reached the configured response retry limit")
            .setUnit("requests")
            .build();
    }

    @Override
    public void targetAttemptWithoutResponse(
        @NonNull TargetAttemptOutcome.NoTargetResponseKind kind
    ) {
        attemptsWithoutResponse.add(
            1,
            Attributes.of(
                NO_RESPONSE_KIND_ATTRIBUTE,
                kind.name().toLowerCase(Locale.ROOT)
            )
        );
    }

    @Override
    public void targetRetriesExhausted() {
        retriesExhausted.add(1);
    }
}
