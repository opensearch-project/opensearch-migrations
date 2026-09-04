package org.opensearch.migrations.replay.traffic.source;

import lombok.NonNull;

public sealed interface AbsenceProof permits AbsenceProof.LivenessOmission {
    record LivenessOmission(
        @NonNull String nodeId,
        int partition,
        @NonNull CompleteSnapshotSpan firstOmittingSnapshot,
        @NonNull CompleteSnapshotSpan secondOmittingSnapshot,
        long lastRecordOffsetForConnection
    ) implements AbsenceProof {
        public LivenessOmission {
            if (lastRecordOffsetForConnection >= firstOmittingSnapshot.firstOffset()) {
                throw new IllegalArgumentException("The first omission must follow the connection's last record");
            }
            if (firstOmittingSnapshot.lastOffset() >= secondOmittingSnapshot.firstOffset()) {
                throw new IllegalArgumentException("Snapshot spans must be strictly offset ordered");
            }
            if (!firstOmittingSnapshot.routingPlanId().equals(secondOmittingSnapshot.routingPlanId())) {
                throw new IllegalArgumentException("Snapshot routing plans must match");
            }
            if (secondOmittingSnapshot.sequence() != firstOmittingSnapshot.sequence() + 1) {
                throw new IllegalArgumentException("Omission snapshots must be consecutive");
            }
        }

        public String proofId() {
            return nodeId
                + ":"
                + partition
                + ":"
                + firstOmittingSnapshot.sequence()
                + "-"
                + secondOmittingSnapshot.sequence()
                + ":after-"
                + lastRecordOffsetForConnection;
        }
    }
}
