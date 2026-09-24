/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.migrations.replay.kafkasource;

import java.util.Objects;
import java.util.Optional;

import org.opensearch.migrations.replay.identity.PartitionBatchRequestId;
import org.opensearch.migrations.replay.identity.PartitionGenerationId;

import org.apache.kafka.common.TopicPartition;

/**
 * One assigned partition generation's source state, held by {@code KafkaSourceOwner} and confined to the
 * Kafka thread. Defined by {@code kafkaLLD §5.1}.
 *
 * <p>Three conditions independently prohibit reading: no outstanding batch request, prior-generation
 * cleanup pending, and lifecycle state no longer permitting intake. They are stored separately and
 * <strong>must not alias</strong> — clearing one never clears another — because each is cleared by a
 * different message from a different owner. Collapsing them into one boolean is how a partition resumes
 * while another reason still forbids reading.
 *
 * <p>{@link #isKafkaPaused()} tracks what Kafka was actually told, so the owner calls {@code pause} or
 * {@code resume} only on a change. Kafka does not preserve pause state across an assignment change, so this
 * is reset rather than trusted after a rebalance.
 */
public final class PartitionSourceState {

    private final PartitionGenerationId generation;
    private final ObservedRecordCommitQueue commitQueue;
    private PartitionBatchRequestId outstandingRequest;
    private boolean priorGenerationCleanupPending;
    private boolean lifecycleAllowsIntake = true;
    private boolean kafkaPaused = true;
    private boolean pendingCommit;
    private long recordsRead;
    private long recordsCommitted;
    /** Finished records whose commit has not yet been acknowledged; credited when one is. */
    private long recordsAwaitingCommit;

    public PartitionSourceState(PartitionGenerationId generation) {
        this.generation = Objects.requireNonNull(generation, "generation");
        this.commitQueue = new ObservedRecordCommitQueue(generation);
    }

    public PartitionGenerationId generation() {
        return generation;
    }

    /**
     * The observed-record queue for this generation. {@code kafkaLLD §5.1} lists it as part of this state, so
     * it is held here rather than in a parallel map the owner would have to keep in step -- and a new
     * generation gets a new queue because it may not reuse the previous one's mutable state.
     */
    public ObservedRecordCommitQueue commitQueue() {
        return commitQueue;
    }

    public TopicPartition topicPartition() {
        return generation.topicPartition();
    }

    /**
     * The effective read decision from {@code kafkaLLD §5.1}. All three conditions must hold; none implies
     * another.
     */
    public boolean isReadable() {
        return outstandingRequest != null && !priorGenerationCleanupPending && lifecycleAllowsIntake;
    }

    public Optional<PartitionBatchRequestId> outstandingRequest() {
        return Optional.ofNullable(outstandingRequest);
    }

    /**
     * Records a batch request as outstanding.
     *
     * <p>Rejects a second request for the same generation rather than overwriting it: replay intake may hold
     * at most one outstanding request per partition generation, and that limit is only checkable if
     * violating it fails here ({@code kafkaLLD §5.3}).
     */
    public void requestBatch(PartitionBatchRequestId requestId) {
        Objects.requireNonNull(requestId, "requestId");
        if (!requestId.generation().equals(generation)) {
            throw new IllegalArgumentException(
                "batch request " + requestId + " does not belong to partition generation " + generation
            );
        }
        if (outstandingRequest != null) {
            throw new IllegalStateException(
                "a batch request is already outstanding for "
                    + generation
                    + ": existing="
                    + outstandingRequest
                    + ", rejected="
                    + requestId
            );
        }
        outstandingRequest = requestId;
    }

    /**
     * Clears the outstanding request because a batch is being delivered for it.
     *
     * @return the request the delivery answers
     */
    public PartitionBatchRequestId completeOutstandingRequest() {
        if (outstandingRequest == null) {
            throw new IllegalStateException(
                "records were returned for " + generation + " with no outstanding batch request"
            );
        }
        var completed = outstandingRequest;
        outstandingRequest = null;
        return completed;
    }

    /**
     * Prior-generation cleanup blocks reading without rejecting a request: the request stays outstanding and
     * the partition stays paused until cleanup finishes ({@code kafkaLLD §5.3}).
     */
    public void setPriorGenerationCleanupPending(boolean pending) {
        priorGenerationCleanupPending = pending;
    }

    public boolean isPriorGenerationCleanupPending() {
        return priorGenerationCleanupPending;
    }

    /** Ends intake for this generation permanently; revocation and shutdown never un-end it. */
    public void endIntake() {
        lifecycleAllowsIntake = false;
    }

    public boolean lifecycleAllowsIntake() {
        return lifecycleAllowsIntake;
    }

    public boolean isKafkaPaused() {
        return kafkaPaused;
    }

    /** Records what Kafka was told, so the owner can issue {@code pause}/{@code resume} only on a change. */
    public void setKafkaPaused(boolean paused) {
        kafkaPaused = paused;
    }

    public boolean hasPendingCommit() {
        return pendingCommit;
    }

    public void setPendingCommit(boolean pending) {
        pendingCommit = pending;
    }

    /** Counts a delivered record, for the retirement measurement {@code kafkaLLD §15.4} requires. */
    public void countRecordsRead(long count) {
        recordsRead += count;
    }

    public long recordsRead() {
        return recordsRead;
    }

    /**
     * Counts records whose contiguous prefix advanced, which is what a later commit will cover.
     *
     * <p>Counted as records rather than as an offset delta because they are not the same number: physical
     * offset gaps exist and {@code kafkaLLD §17.1} requires that they not block advancement, so offset
     * arithmetic would over-count by every gap.
     */
    public void countRecordsAwaitingCommit(long count) {
        recordsAwaitingCommit += count;
    }

    /**
     * Detaches the records a commit is about to cover, so the count travels with that submission.
     *
     * <p>Taken at submission rather than credited at acknowledgement, because records keep finishing while a
     * commit is in flight and those belong to the <em>next</em> position, not this one. Crediting whatever was
     * awaiting at acknowledgement time would report them committed although their position was never
     * acknowledged.
     */
    public long takeRecordsAwaitingCommit() {
        var taken = recordsAwaitingCommit;
        recordsAwaitingCommit = 0;
        return taken;
    }

    /**
     * Credits exactly the records the acknowledged operation covered.
     *
     * <p>Counted from acknowledgement rather than staging, because a staged position that never commits is
     * exactly the case the retirement measurement exists to reveal ({@code procCommit §9.5}).
     */
    public void creditRecordsCommitted(long recordsCovered) {
        recordsCommitted += recordsCovered;
    }

    /** Returns a failed submission's records to the awaiting pool, so a later commit still credits them. */
    public void restoreRecordsAwaitingCommit(long recordsCovered) {
        recordsAwaitingCommit += recordsCovered;
    }

    public long recordsCommitted() {
        return recordsCommitted;
    }

    @Override
    public String toString() {
        return "PartitionSourceState["
            + generation
            + " readable="
            + isReadable()
            + " request="
            + outstandingRequest
            + " cleanupPending="
            + priorGenerationCleanupPending
            + " intakeAllowed="
            + lifecycleAllowsIntake
            + " kafkaPaused="
            + kafkaPaused
            + " pendingCommit="
            + pendingCommit
            + "]";
    }
}
