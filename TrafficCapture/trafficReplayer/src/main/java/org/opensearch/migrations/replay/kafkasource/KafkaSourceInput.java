/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.migrations.replay.kafkasource;

import org.opensearch.migrations.replay.identity.KafkaRecordId;
import org.opensearch.migrations.replay.identity.PartitionBatchRequestId;
import org.opensearch.migrations.replay.identity.PartitionGenerationId;

import lombok.NonNull;

/**
 * Defines the immutable commands and completion evidence applied to Kafka-source state.
 *
 * <p>The events request one partition batch, report that a record's dependent work is finished, confirm
 * cleanup of a retired generation, or identify a poison capture record. The sealed vocabulary keeps source
 * transitions exhaustive and prevents arbitrary callbacks from mutating demand, commit, or lifecycle state
 * outside the Kafka thread.</p>
 *
 * <p>Every event is generation-qualified, allowing stale messages to be distinguished from work for the
 * current ownership period. In particular, record completion is the sole input that can advance commit
 * eligibility, while generation cleanup releases a read barrier but grants no commit authority.</p>
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
    record RequestNextPartitionBatch(@NonNull PartitionBatchRequestId requestId) implements KafkaSourceInput {
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
    record RecordProcessingFinished(@NonNull KafkaRecordId recordId) implements KafkaSourceInput {
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
    record GenerationCleanupFinished(@NonNull PartitionGenerationId generation) implements KafkaSourceInput {}

    /**
     * Replay intake detected invalid capture input at a specific Kafka partition and offset.
     *
     * <p>Stops admitting later Kafka records and begins the diagnostic, bounded-drain, and process-termination
     * path. The violating record is marked commit-ineligible and commits at and past its offset are blocked, so
     * a restart stops at the same record rather than skipping past it.</p>
     */
    record CaptureProtocolViolationDetected(
        @NonNull KafkaRecordId recordId,
        @NonNull String diagnostic
    ) implements KafkaSourceInput {
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
