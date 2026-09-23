package org.opensearch.migrations.replay.lifecycle;

// REBUILD-LIMBO(G3) -- nothing in this file is live yet. Javadoc is left outside the marked
// regions so it needs no escaping and keeps its blame; it documents code that is not compiled.
// Resolve each region to dead, keep, or refactor deliberately. If a member is deleted, delete its
// javadoc with it. See AGENTS.md section 8a.
// Superseded by the eight design identities in replay/identity/. Nothing live uses these records any
// more; the remaining references are all inside marked regions, so G3 deletes this file along with the
// callers it refactors. replayRequestId also needs UniqueReplayerRequestKey, which G3 replaces.

// REBUILD-LIMBO-START(G3)
/*


import org.apache.kafka.common.TopicPartition;

import lombok.NonNull;

public final class ReplayIdentity {
    private ReplayIdentity() {}

    public record SourceConnectionKey(@NonNull String nodeId, @NonNull String connectionId) {}

    public record PartitionGenerationId(
        @NonNull TopicPartition topicPartition,
        long localSequence
    ) {
        public PartitionGenerationId {
            if (localSequence < 0) {
                throw new IllegalArgumentException("localSequence must not be negative");
            }
        }
    }

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

    public record SourceConnectionPartitionGenerationKey(
        @NonNull SourceConnectionKey connection,
        int partition,
        int sourceGeneration
    ) {
        public SourceConnectionPartitionGenerationKey {
            if (partition < 0) {
                throw new IllegalArgumentException("partition must not be negative");
            }
            if (sourceGeneration < 0) {
                throw new IllegalArgumentException("sourceGeneration must not be negative");
            }
        }
    }

    public record SourcePartitionKey(
        @NonNull String sourceId,
        int partition,
        int sourceGeneration
    ) {
        public SourcePartitionKey {
            if (partition < 0) {
                throw new IllegalArgumentException("partition must not be negative");
            }
            if (sourceGeneration < 0) {
                throw new IllegalArgumentException("sourceGeneration must not be negative");
            }
        }

        public PartitionGenerationId partitionGenerationId() {
            return new PartitionGenerationId(
                new TopicPartition(sourceId, partition),
                sourceGeneration
            );
        }
    }

    public sealed interface ReplayWorkId permits ReplayRequestId, ReplaySessionWorkId {}

    public sealed interface RecordAssociationId permits
        SourceRequestAssemblyId,
        ReplayRequestId,
        TerminalSourceConnectionId {}

    public record SourceRequestAssemblyId(
        @NonNull ConnectionSessionKey session,
        int requestIndex
    ) implements RecordAssociationId {
        public SourceRequestAssemblyId {
            if (requestIndex < 0) {
                throw new IllegalArgumentException("requestIndex must not be negative");
            }
        }
    }

    public record ReplayRequestId(
        @NonNull ConnectionSessionKey session,
        int requestIndex
    ) implements ReplayWorkId, RecordAssociationId {
        public ReplayRequestId {
            if (requestIndex < 0) {
                throw new IllegalArgumentException("requestIndex must not be negative");
            }
        }
    }

    public record TerminalSourceConnectionId(
        @NonNull ConnectionSessionKey session,
        int interactionIndex
    ) implements RecordAssociationId {
        public TerminalSourceConnectionId {
            if (interactionIndex < 0) {
                throw new IllegalArgumentException("interactionIndex must not be negative");
            }
        }
    }

    public record ReplaySessionWorkId(
        @NonNull ConnectionSessionKey session,
        int interactionIndex,
        @NonNull String operation
    ) implements ReplayWorkId {
        public ReplaySessionWorkId {
            if (interactionIndex < 0) {
                throw new IllegalArgumentException("interactionIndex must not be negative");
            }
        }
    }

    import org.opensearch.migrations.replay.datatypes.UniqueReplayerRequestKey;  // hoist on un-comment
    public static ReplayRequestId replayRequestId(@NonNull UniqueReplayerRequestKey requestKey) {
        return new ReplayRequestId(
            new ConnectionSessionKey(
                new SourceConnectionKey(
                    requestKey.trafficStreamKey.getNodeId(),
                    requestKey.trafficStreamKey.getConnectionId()
                ),
                requestKey.sourceRequestIndexSessionIdentifier,
                requestKey.trafficStreamKey.getSourceGeneration()
            ),
            requestKey.getReplayerRequestIndex()
        );
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


*/
// REBUILD-LIMBO-END(G3)