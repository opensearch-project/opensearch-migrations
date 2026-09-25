/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.migrations.replay.intake;

import java.util.List;
import java.util.Objects;

import org.opensearch.migrations.replay.identity.CancellationDeadline;
import org.opensearch.migrations.replay.identity.ConnectionProcessingId;
import org.opensearch.migrations.replay.identity.PartitionBatchRequestId;
import org.opensearch.migrations.replay.identity.PartitionGenerationId;
import org.opensearch.migrations.replay.kafkasource.ApplicationKafkaRecord;

/**
 * Inputs submitted to the replay-intake owner. The Kafka thread, Netty event loops, and tuple I/O may submit
 * them; <strong>only</strong> the replay-intake thread removes them and changes replay-intake state.
 *
 * <p>These are <em>events that replay intake processes, not states.</em> The distinction matters: an input
 * reports that something completed elsewhere, and intake decides what changes as a result. It never carries
 * mutable state, and it never carries authority to change state on the submitter's behalf.</p>
 *
 * <p>This family belongs to <strong>one</strong> owner. The pre-rebuild implementation made the same-named
 * interface permit four owners' input families at once — a permit provider, a progress controller, a record
 * tracker, and the request-lifecycle pair — which left the exhaustiveness of a switch over it meaningless,
 * since the cases were not a single owner's vocabulary. Nothing but replay-intake inputs may be added here.</p>
 *
 * <p>Every input carries its {@link PartitionGenerationId} so that duplicate, stale-generation, and
 * already-cleaned-generation deliveries are decidable at the point of handling rather than inferred.</p>
 *
 * <p>The queue does not accept a callback or a {@code Runnable}, which would hide which intake fields it can
 * change and bypass the exhaustive switch. Submissions from one Netty event loop preserve that sender's order;
 * correctness must not depend on any total order <em>between</em> independent event loops, which is why these
 * carry request and connection identities.</p>
 *
 * <p>Defined by {@code docs/captureAndReplay/replayerKafkaSourceAndIntakeLowLevelDesign.md} section 4.1.</p>
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
    record PartitionGenerationAssigned(PartitionGenerationId generation) implements ReplayIntakeInput {
        public PartitionGenerationAssigned {
            Objects.requireNonNull(generation, "generation");
        }
    }

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
    record PartitionRecordBatch(PartitionBatchRequestId requestId, List<ApplicationKafkaRecord> records)
        implements ReplayIntakeInput {
        public PartitionRecordBatch {
            Objects.requireNonNull(requestId, "requestId");
            Objects.requireNonNull(records, "records");
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
     * Kafka began revoking the partition and supplied the grace deadline.
     *
     * <p>Intake stops admitting records from the generation, cancels work that has not started an external
     * operation, and distributes scoped graceful cancellation. Work already sent to the target, and the tuple
     * chain that work requires, may finish before the deadline.</p>
     */
    record GracefulGenerationCancellation(PartitionGenerationId generation, CancellationDeadline deadline)
        implements ReplayIntakeInput {
        public GracefulGenerationCancellation {
            Objects.requireNonNull(generation, "generation");
            Objects.requireNonNull(deadline, "deadline");
        }
    }

    /** The revocation grace period ended; every remaining owner in the generation begins immediate cleanup. */
    record ForceGenerationCancellation(PartitionGenerationId generation) implements ReplayIntakeInput {
        public ForceGenerationCancellation {
            Objects.requireNonNull(generation, "generation");
        }
    }

    /**
     * A finalized imported partition has no later record.
     *
     * <p>Applies the finite-input expiration rules <em>without</em> creating Kafka timestamp or offset
     * evidence, because an archive has no broker to supply either.</p>
     */
    record FinalizedArchivePartitionEnd(PartitionGenerationId generation) implements ReplayIntakeInput {
        public FinalizedArchivePartitionEnd {
            Objects.requireNonNull(generation, "generation");
        }
    }

    /**
     * A normally completed connection owner has no requests, queued work, target connection, timers, or
     * retained data left.
     *
     * <p>Removes the mapping used to send later messages to that connection owner. This event does
     * <strong>not</strong> itself finish a Kafka record.</p>
     */
    record ConnectionOwnerFinished(
        PartitionGenerationId generation,
        ConnectionProcessingId connectionProcessingId
    ) implements ReplayIntakeInput {
        public ConnectionOwnerFinished {
            Objects.requireNonNull(generation, "generation");
            Objects.requireNonNull(connectionProcessingId, "connectionProcessingId");
        }
    }

    /**
     * A connection owner and all of its requests finished cancellation cleanup after Kafka revoked the
     * partition.
     *
     * <p>Records that this connection no longer prevents cleanup of the revoked assignment.
     * <strong>Cancelled Kafka work does not become committable.</strong></p>
     */
    record ConnectionCleanupFinished(
        PartitionGenerationId generation,
        ConnectionProcessingId connectionProcessingId
    ) implements ReplayIntakeInput {
        public ConnectionCleanupFinished {
            Objects.requireNonNull(generation, "generation");
            Objects.requireNonNull(connectionProcessingId, "connectionProcessingId");
        }
    }

    /** The partition generation this input belongs to. */
    PartitionGenerationId generation();
}
