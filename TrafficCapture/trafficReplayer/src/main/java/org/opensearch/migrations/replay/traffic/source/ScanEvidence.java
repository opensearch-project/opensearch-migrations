package org.opensearch.migrations.replay.traffic.source;

import org.opensearch.migrations.replay.lifecycle.ReplayIdentity.SourceConnectionKey;
import org.opensearch.migrations.replay.lifecycle.ReplayIdentity.SourcePartitionKey;

import lombok.NonNull;

public sealed interface ScanEvidence
    permits ScanEvidence.FollowUpPresent, ScanEvidence.ConfirmedAbsent, ScanEvidence.Inconclusive {

    /**
     * The connection is not dead: something was observed for it after its last replayed record.
     *
     * @param offset the highest offset at which the connection was seen alive, which is a diagnostic
     *     lower bound on its lifetime rather than the next offset replay will reach.  The scanner retains
     *     one offset per connection, and the threshold it is compared against advances as replay
     *     progresses, so identifying the *earliest* unreplayed follow-up would mean retaining every
     *     unreplayed offset per connection -- memory the accumulator already holds -- for a value no
     *     production path reads.
     */
    record FollowUpPresent(
        @NonNull SourcePartitionKey partition,
        @NonNull SourceConnectionKey connection,
        long offset
    ) implements ScanEvidence {}

    record ConfirmedAbsent(
        @NonNull SourcePartitionKey partition,
        @NonNull SourceConnectionKey connection,
        @NonNull FollowUpRequirement requirement,
        @NonNull AbsenceProof proof
    ) implements ScanEvidence {}

    record Inconclusive(
        @NonNull SourcePartitionKey partition,
        @NonNull SourceConnectionKey connection,
        @NonNull String reason
    ) implements ScanEvidence {}
}
