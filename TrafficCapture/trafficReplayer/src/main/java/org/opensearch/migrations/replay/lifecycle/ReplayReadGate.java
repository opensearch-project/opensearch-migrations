package org.opensearch.migrations.replay.lifecycle;

// REBUILD-LIMBO(G11) -- nothing in this file is live yet. Javadoc is left outside the marked
// regions so it needs no escaping and keeps its blame; it documents code that is not compiled.
// Resolve each region to dead, keep, or refactor deliberately. If a member is deleted, delete its
// javadoc with it. See AGENTS.md section 8a.
// Carried verbatim. This was the pre-rebuild implementation of a responsibility the design
// reassigns, so it is the input to that refactor rather than something to re-derive. Resolve it to
// dead, keep, or refactor deliberately -- see AGENTS.md section 8a, and read this before writing

// REBUILD-LIMBO-START(G11)
/*

import java.time.Duration;
import java.time.Instant;

import org.opensearch.migrations.replay.traffic.source.BufferedFlowController;

import lombok.NonNull;

*/
// REBUILD-LIMBO-END(G11)
/**
 * Converts the settled source-time watermark into the exact source read frontier.
 */
// REBUILD-LIMBO-START(G11)
/*
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

*/
// REBUILD-LIMBO-END(G11)