package org.opensearch.migrations.replay.lifecycle;

import lombok.NonNull;

public final class ReplayIdentity {
    private ReplayIdentity() {}

    public record SourceConnectionKey(@NonNull String nodeId, @NonNull String connectionId) {}

    public record ConnectionSessionKey(
        @NonNull SourceConnectionKey connection,
        int sessionNumber,
        int sourceGeneration
    ) {
        public ConnectionSessionKey {
            if (sessionNumber < 0) {
                throw new IllegalArgumentException("sessionNumber must not be negative");
            }
            if (sourceGeneration < 0) {
                throw new IllegalArgumentException("sourceGeneration must not be negative");
            }
        }
    }

    public record ReplayRequestId(@NonNull ConnectionSessionKey session, int requestIndex) {
        public ReplayRequestId {
            if (requestIndex < 0) {
                throw new IllegalArgumentException("requestIndex must not be negative");
            }
        }
    }

    public sealed interface RecordId permits KafkaRecordId, TrafficStreamRecordId, SourceControlRecordId {}

    public record KafkaRecordId(
        @NonNull String topic,
        int partition,
        long offset,
        int sourceGeneration
    ) implements RecordId {
        public KafkaRecordId {
            if (partition < 0) {
                throw new IllegalArgumentException("partition must not be negative");
            }
            if (offset < 0) {
                throw new IllegalArgumentException("offset must not be negative");
            }
            if (sourceGeneration < 0) {
                throw new IllegalArgumentException("sourceGeneration must not be negative");
            }
        }
    }

    public record TrafficStreamRecordId(
        @NonNull SourceConnectionKey connection,
        int trafficStreamIndex,
        int sourceGeneration
    ) implements RecordId {
        public TrafficStreamRecordId {
            if (trafficStreamIndex < 0) {
                throw new IllegalArgumentException("trafficStreamIndex must not be negative");
            }
            if (sourceGeneration < 0) {
                throw new IllegalArgumentException("sourceGeneration must not be negative");
            }
        }
    }

    public record SourceControlRecordId(
        @NonNull SourceConnectionKey connection,
        @NonNull String controlType,
        int sourceGeneration
    ) implements RecordId {
        public SourceControlRecordId {
            if (sourceGeneration < 0) {
                throw new IllegalArgumentException("sourceGeneration must not be negative");
            }
        }
    }
}
