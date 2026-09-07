package org.opensearch.migrations.replay.traffic.source;

import lombok.NonNull;

public sealed interface AbsenceProof permits AbsenceProof.LivenessOmission {
    record LivenessOmission(
        @NonNull String nodeId,
        int partition,
        @NonNull CompleteSnapshotSpan omittingSnapshot,
        long lastRecordOffsetForConnection
    ) implements AbsenceProof {
        public LivenessOmission {
            if (lastRecordOffsetForConnection >= omittingSnapshot.firstOffset()) {
                throw new IllegalArgumentException("The omission must follow the connection's last record");
            }
        }

        public String proofId() {
            return nodeId
                + ":"
                + partition
                + ":"
                + omittingSnapshot.sequence()
                + ":after-"
                + lastRecordOffsetForConnection;
        }
    }
}
