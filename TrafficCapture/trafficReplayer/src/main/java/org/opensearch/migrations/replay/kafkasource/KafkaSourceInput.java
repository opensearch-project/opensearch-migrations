/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.migrations.replay.kafkasource;

import java.util.Objects;

import org.opensearch.migrations.replay.identity.KafkaRecordId;
import org.opensearch.migrations.replay.identity.PartitionBatchRequestId;
import org.opensearch.migrations.replay.identity.PartitionGenerationId;

/**
 * Messages from replay intake to the Kafka source owner. Only the Kafka thread removes and applies them.
 *
 * <p>The family is closed and exhaustively switched: correctness-critical switches over it contain no
 * {@code default} branch, so adding a variant breaks every handling site rather than being silently ignored
 * ({@code replayerLLD §4}).</p>
 *
 * <p>The queue does not accept a callback or a {@code Runnable}. Such a value would hide which Kafka-source
 * fields it can change and would bypass the exhaustive switch — which is the property this family exists to
 * provide, not an incidental style choice.</p>
 *
 * <p>Required submission reports acceptance. Queue rejection or an unexpected failure to submit is
 * process-fatal; the queue is never allowed to discard an input silently.</p>
 *
 * <p>Defined by {@code docs/captureAndReplay/replayerKafkaSourceAndIntakeLowLevelDesign.md} section 4.2.</p>
 */
public sealed interface KafkaSourceInput {

    /**
     * Replay intake has completely applied the previous batch and needs the next available records for this
     * partition generation.
     *
     * <p>This is <strong>not</strong> a general resume notification. It is a request with an identity and
     * exactly one eventual result: one {@code PartitionRecordBatch}, or cancellation because that partition
     * generation ended. Replay intake may have at most one outstanding for a partition generation, which is
     * what makes that limit checkable rather than assumed.</p>
     *
     * <p>The Kafka source resumes the partition only when assignment, prior-generation cleanup, and lifecycle
     * state <em>also</em> permit reading. Those three pause reasons are independent and must not alias.</p>
     */
    record RequestNextPartitionBatch(PartitionBatchRequestId requestId) implements KafkaSourceInput {
        public RequestNextPartitionBatch {
            Objects.requireNonNull(requestId, "requestId");
        }

        @Override
        public PartitionGenerationId generation() {
            return requestId.generation();
        }
    }

    /**
     * Every required operation associated with one Kafka record has finished.
     *
     * <p>This is the <strong>only</strong> input that can advance a commit position. No request, accumulator,
     * target result, or retry policy may commit or retain a record — commit authority is computed by the Kafka
     * source alone, as a contiguous prefix from the observed-record head.</p>
     */
    record RecordProcessingFinished(KafkaRecordId recordId) implements KafkaSourceInput {
        public RecordProcessingFinished {
            Objects.requireNonNull(recordId, "recordId");
        }

        @Override
        public PartitionGenerationId generation() {
            return recordId.generation();
        }
    }

    /**
     * Replay intake has removed all process-local state belonging to one revoked partition assignment.
     *
     * <p>It allows a newer assignment of that partition to begin reading. It does <strong>not</strong> commit
     * records from the revoked assignment: cancellation cleanup never authorizes a commit
     * ({@code replayerLLD §6}).</p>
     */
    record GenerationCleanupFinished(PartitionGenerationId generation) implements KafkaSourceInput {
        public GenerationCleanupFinished {
            Objects.requireNonNull(generation, "generation");
        }
    }

    /**
     * Replay intake detected invalid capture input at a specific Kafka partition and offset.
     *
     * <p>Stops admitting later Kafka records and begins the diagnostic, bounded-drain, and process-termination
     * path. The violating record is marked commit-ineligible and commits at and past its offset are blocked, so
     * a restart stops at the same record rather than skipping past it.</p>
     */
    record CaptureProtocolViolationDetected(KafkaRecordId recordId, String diagnostic)
        implements KafkaSourceInput {
        public CaptureProtocolViolationDetected {
            Objects.requireNonNull(recordId, "recordId");
            Objects.requireNonNull(diagnostic, "diagnostic");
        }

        @Override
        public PartitionGenerationId generation() {
            return recordId.generation();
        }
    }

    /**
     * The partition generation this input belongs to. Every input carries it so the Kafka source can reject a
     * stale-generation delivery rather than applying it to a successor assignment.
     */
    PartitionGenerationId generation();
}
