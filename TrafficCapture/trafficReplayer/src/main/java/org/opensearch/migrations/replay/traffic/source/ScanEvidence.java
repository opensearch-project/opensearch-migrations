package org.opensearch.migrations.replay.traffic.source;

import org.opensearch.migrations.replay.lifecycle.ReplayIdentity.SourceConnectionKey;
import org.opensearch.migrations.replay.lifecycle.ReplayIdentity.SourcePartitionKey;

import lombok.NonNull;

public sealed interface ScanEvidence
    permits ScanEvidence.FollowUpPresent, ScanEvidence.ConfirmedAbsent, ScanEvidence.Inconclusive {

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
