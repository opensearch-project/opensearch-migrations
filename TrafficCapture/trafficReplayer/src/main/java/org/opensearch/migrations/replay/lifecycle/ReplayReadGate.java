package org.opensearch.migrations.replay.lifecycle;

import java.time.Duration;
import java.time.Instant;

import org.opensearch.migrations.replay.traffic.source.BufferedFlowController;

import lombok.NonNull;

/**
 * Converts the settled source-time watermark into the exact source read frontier.
 */
public final class ReplayReadGate {
    private final Duration epsilon;
    private final BufferedFlowController flowController;
    private Instant frontier = Instant.MIN;

    public ReplayReadGate(
        @NonNull Duration epsilon,
        @NonNull BufferedFlowController flowController
    ) {
        if (epsilon.isNegative()) {
            throw new IllegalArgumentException("epsilon must not be negative");
        }
        this.epsilon = epsilon;
        this.flowController = flowController;
    }

    public void advanceTo(@NonNull Instant settledWatermark) {
        if (settledWatermark.equals(Instant.MIN)) {
            return;
        }
        var candidate = settledWatermark.plus(epsilon);
        if (!candidate.equals(frontier)) {
            frontier = candidate;
            flowController.stopReadsPast(candidate);
        }
    }

    public Instant frontier() {
        return frontier;
    }
}
