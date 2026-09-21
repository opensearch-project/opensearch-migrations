package org.opensearch.migrations.replay;

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
