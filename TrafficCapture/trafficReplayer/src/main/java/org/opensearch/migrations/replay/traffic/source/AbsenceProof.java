package org.opensearch.migrations.replay.traffic.source;

import lombok.NonNull;

public sealed interface AbsenceProof permits AbsenceProof.LivenessOmission, AbsenceProof.NoMoreWrites {
    String proofId();

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

        @Override
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

    record NoMoreWrites(
        @NonNull String nodeId,
        int partition,
        @NonNull String declaredBy,
        long declarationOffset,
        long lastRecordOffsetForConnection
    ) implements AbsenceProof {
        public NoMoreWrites {
            if (nodeId.isBlank()) {
                throw new IllegalArgumentException("nodeId must not be blank");
            }
            if (declaredBy.isBlank()) {
                throw new IllegalArgumentException("declaredBy must not be blank");
            }
            if (lastRecordOffsetForConnection >= declarationOffset) {
                throw new IllegalArgumentException(
                    "The no-more-writes declaration must follow the connection's last record"
                );
            }
        }

        public boolean peerDeclared() {
            return !nodeId.equals(declaredBy);
        }

        @Override
        public String proofId() {
            return nodeId
                + ":"
                + partition
                + ":no-more-writes@"
                + declarationOffset
                + ":by-"
                + declaredBy
                + ":after-"
                + lastRecordOffsetForConnection;
        }
    }
}
