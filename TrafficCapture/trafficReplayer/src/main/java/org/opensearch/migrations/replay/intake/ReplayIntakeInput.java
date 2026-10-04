/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.migrations.replay.intake;

import java.util.List;

import org.opensearch.migrations.replay.identity.CancellationGrace;
import org.opensearch.migrations.replay.identity.ConnectionProcessingId;
import org.opensearch.migrations.replay.identity.PartitionBatchRequestId;
import org.opensearch.migrations.replay.identity.PartitionGenerationId;
import org.opensearch.migrations.replay.kafkasource.ApplicationKafkaRecord;

import lombok.NonNull;

/**
 * Defines the immutable event vocabulary consumed by the replay-intake state machine.
 *
 * <p>Kafka batches, ownership changes, request milestones, and connection cleanup can originate on
 * independent threads. They are represented as data and serialized through one queue so none of those
 * threads mutates intake state directly. The sealed hierarchy keeps the transition switch exhaustive and
 * prevents arbitrary callbacks from hiding state changes.</p>
 *
 * <p>Every event carries its {@link PartitionGenerationId}, and finer-grained events also carry their
 * request or connection identity. Intake can therefore reject duplicates and ignore stale completions from
 * a draining generation without relying on arrival order between unrelated producers.</p>
 */
public sealed interface ReplayIntakeInput permits
    ReplayIntakeInput.PartitionGenerationAssigned,
    ReplayIntakeInput.PartitionRecordBatch,
    ReplayIntakeInput.GracefulGenerationCancellation,
    ReplayIntakeInput.ForceGenerationCancellation,
    ReplayIntakeInput.FinalizedArchivePartitionEnd,
    RequestLifecycleInput,
    ReplayIntakeInput.ConnectionOwnerFinished,
    ReplayIntakeInput.ConnectionCleanupFinished {

    /**
     * Kafka assigned a partition and the source allocated a new process-local generation.
     *
     * <p>Creates the corresponding partition intake state expecting the source-local bootstrap batch.
     * Applying this input also runs the ordinary demand pass over every assigned generation and may request
     * one additional batch. Prior-generation cleanup delays source reading without rejecting that request.</p>
     */
    record PartitionGenerationAssigned(@NonNull PartitionGenerationId generation) implements ReplayIntakeInput {}

    /**
     * Kafka returned the records that satisfy one outstanding request for this partition generation.
     *
     * <p>Intake applies every record in order, closes that batch request, recomputes demand, and may then
     * submit the next request. It cannot request the next batch before fully applying this one.</p>
     *
     * <p>Carries the {@link PartitionBatchRequestId} rather than only the generation, so that a delivered batch
     * is matched to exactly one outstanding request. An empty poll does not produce this input at all — it
     * leaves the request outstanding — so a batch here always has records.</p>
     */
    record PartitionRecordBatch(
        @NonNull PartitionBatchRequestId requestId,
        @NonNull List<ApplicationKafkaRecord> records
    )
        implements ReplayIntakeInput {
        public PartitionRecordBatch {
            if (records.isEmpty()) {
                throw new IllegalArgumentException(
                    "a delivered batch must contain records; an empty poll leaves the request outstanding");
            }
            records = List.copyOf(records);
        }

        @Override
        public PartitionGenerationId generation() {
            return requestId.generation();
        }
    }

    /**
     * The generation entered revocation or orderly-shutdown grace.
     *
     * <p>Intake stops admitting records from the generation, cancels work that has not started an external
     * operation, and distributes the typed grace mode. Revocation is deadline-bound; orderly shutdown drains
     * admitted complete requests without a process-local deadline.</p>
     */
    record GracefulGenerationCancellation(
        @NonNull PartitionGenerationId generation,
        @NonNull CancellationGrace grace
    ) implements ReplayIntakeInput {}

    /** Every remaining owner in the revoked generation begins immediate cancellation cleanup. */
    record ForceGenerationCancellation(@NonNull PartitionGenerationId generation) implements ReplayIntakeInput {}

    /**
     * A finalized imported partition has no later record.
     *
     * <p>Applies the finite-input expiration rules <em>without</em> creating Kafka timestamp or offset
     * evidence, because an archive has no broker to supply either.</p>
     */
    record FinalizedArchivePartitionEnd(@NonNull PartitionGenerationId generation) implements ReplayIntakeInput {}

    /**
     * A normally completed connection owner has no requests, queued work, target connection, timers, or
     * retained data left.
     *
     * <p>Removes the mapping used to send later messages to that connection owner. This event does
     * <strong>not</strong> itself finish a Kafka record.</p>
     */
    record ConnectionOwnerFinished(
        @NonNull PartitionGenerationId generation,
        @NonNull ConnectionProcessingId connectionProcessingId
    ) implements ReplayIntakeInput {}

    /**
     * A connection owner and all of its requests finished cancellation cleanup after Kafka revoked the
     * partition.
     *
     * <p>Records that this connection no longer prevents cleanup of the revoked assignment.
     * <strong>Cancelled Kafka work does not become committable.</strong></p>
     */
    record ConnectionCleanupFinished(
        @NonNull PartitionGenerationId generation,
        @NonNull ConnectionProcessingId connectionProcessingId
    ) implements ReplayIntakeInput {}

    /** The partition generation this input belongs to. */
    PartitionGenerationId generation();
}
