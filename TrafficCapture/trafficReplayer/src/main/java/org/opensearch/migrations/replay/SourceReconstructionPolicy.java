package org.opensearch.migrations.replay;

// REBUILD-LIMBO(G5) -- nothing in this file is live yet. Javadoc is left outside the marked
// regions so it needs no escaping and keeps its blame; it documents code that is not compiled.
// Resolve each region to dead, keep, or refactor deliberately. If a member is deleted, delete its
// javadoc with it. See AGENTS.md section 8a.
// Cascade from the left-behind legacy set. Unresolved: RequestResponsePacketPair . Carried byte-identical so the behaviour stays enumerable; its milestone strips the legacy references and un-marks it.
// Un-mark a member by deleting the delimiter lines around it and splitting this region; the
// code between them is verbatim, so blame survives. Read this before writing anything new

// REBUILD-LIMBO-START(G5)
/*

import java.util.Objects;

import org.opensearch.migrations.replay.lifecycle.ReplayOutcomes.SourceOutcome;

final class SourceReconstructionPolicy {
    private final boolean structuralExpiration;

    SourceReconstructionPolicy(boolean structuralExpiration) {
        this.structuralExpiration = structuralExpiration;
    }

    SourceOutcome classify(RequestResponsePacketPair rrPair) {
        return classify(rrPair.completionStatus, rrPair.structuralProofId);
    }

    SourceOutcome classify(
        RequestResponsePacketPair.ReconstructionStatus status,
        String structuralProofId
    ) {
        return switch (status) {
            case COMPLETE -> new SourceOutcome.Complete();
            case CONFIRMED_DEAD -> new SourceOutcome.ConfirmedDead(
                Objects.requireNonNull(
                    structuralProofId,
                    "Confirmed-dead source outcomes require a structural proof identity"
                )
            );
            case EXPIRED_PREMATURELY -> structuralExpiration
                ? new SourceOutcome.Inconclusive("timestamp-only source expiry has no structural proof")
                : new SourceOutcome.LegacyExpired();
            case CLOSED_PREMATURELY ->
                new SourceOutcome.Shutdown("source closed before request reconstruction completed");
            case TRAFFIC_SOURCE_READER_INTERRUPTED ->
                new SourceOutcome.Interrupted("Kafka source generation was reassigned");
        };
    }

}

*/
// REBUILD-LIMBO-END(G5)