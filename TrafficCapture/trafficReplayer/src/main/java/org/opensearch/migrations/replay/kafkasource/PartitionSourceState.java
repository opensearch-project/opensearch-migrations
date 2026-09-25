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

    /**
     * Finished records carried by one staged or submitted commit position.
     *
     * <p>The categories are monotonic evidence. A pre-acceptance rejection moves only records never known
     * uncertain into {@code rejected}; an accepted operation with a non-acknowledged result moves all of its
     * coverage to {@code unknown}. Later failures cannot make an earlier unknown outcome certain.
     */
    public record CommitCoverage(long unsubmitted, long rejected, long unknown) {
        public static final CommitCoverage EMPTY = new CommitCoverage(0, 0, 0);

        public CommitCoverage {
            if (unsubmitted < 0 || rejected < 0 || unknown < 0) {
                throw new IllegalArgumentException("commit coverage counts must not be negative");
            }
        }

        public long total() {
            return unsubmitted + rejected + unknown;
        }

        public CommitCoverage plusUnsubmitted(long count) {
            if (count < 0) {
                throw new IllegalArgumentException("count must not be negative");
            }
            return new CommitCoverage(Math.addExact(unsubmitted, count), rejected, unknown);
        }

        public CommitCoverage plus(CommitCoverage other) {
            Objects.requireNonNull(other, "other");
            return new CommitCoverage(
                Math.addExact(unsubmitted, other.unsubmitted),
                Math.addExact(rejected, other.rejected),
                Math.addExact(unknown, other.unknown)
            );
        }

        public CommitCoverage afterRejectedBeforeAcceptance() {
            return new CommitCoverage(0, Math.addExact(unsubmitted, rejected), unknown);
        }

        public CommitCoverage afterUncertainOutcome() {
            return new CommitCoverage(0, 0, total());
        }
    }

    private final PartitionGenerationId generation;
    private final ObservedRecordCommitQueue commitQueue;
    private boolean assignmentBootstrapPending = true;
    private PartitionBatchRequestId outstandingRequest;
    private boolean priorGenerationCleanupPending;
    private boolean lifecycleAllowsIntake = true;
    private boolean kafkaPaused = true;
    private boolean pendingCommit;
    private long recordsRead;
    private long recordsCommitted;
    /** Finished records whose commit has not yet been acknowledged; credited when one is. */
    private CommitCoverage recordsAwaitingCommit = CommitCoverage.EMPTY;
    private CommitUncertainty commitUncertainty = CommitUncertainty.NONE_OBSERVED;

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
        return (assignmentBootstrapPending || outstandingRequest != null)
            && !priorGenerationCleanupPending
            && lifecycleAllowsIntake;
    }

    public boolean isAssignmentBootstrapPending() {
        return assignmentBootstrapPending;
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
     * Consumes the oldest source-local batch entitlement.
     *
     * <p>Assignment bootstrap is always first. One intake-issued explicit request may already overlap it,
     * but it remains outstanding until the following nonempty batch.
     */
    public PartitionBatchRequestId completeNextBatchEntitlement() {
        if (assignmentBootstrapPending) {
            assignmentBootstrapPending = false;
            return new PartitionBatchRequestId(generation, 0);
        }
        return completeOutstandingRequest();
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

    /**
     * Known uncertainty in this generation's credited commit count.
     *
     * <p>This is diagnostic only. {@code kafkaLLD §15.4}'s two retirement measurements remain authoritative,
     * and {@code NONE_OBSERVED} does not diagnose why a committed count is zero.
     *
     * <p><strong>Last observed, not current.</strong> Only an acknowledgement clears this, so a partition whose
     * unknown outcome is followed by repeated {@code RETRIABLE} results still reports
     * {@code SYNC_OUTCOME_UNKNOWN} at retirement, even though by then the operative reason for a zero count is
     * a broker that keeps refusing. That reading is conservative in the right direction — both say "do not treat
     * this zero as proven absence of progress" — but it is not a live status, and a value here is a reason to
     * look at the commit-outcome logs rather than a conclusion.
     */
    public enum CommitUncertainty {
        /** No unresolved or unknown commit outcome is currently known for this generation. */
        NONE_OBSERVED,
        /** An asynchronous submission was still unresolved at retirement; its callback arrives afterwards. */
        ASYNC_UNRESOLVED_AT_RETIREMENT,
        /** An accepted commit returned an unknown outcome; it may have reached the broker. */
        SYNC_OUTCOME_UNKNOWN
    }

    /**
     * Records an accepted commit whose broker outcome is unknown.
     *
     * <p>The retained method name predates G4's common async/sync accounting; callers must interpret the
     * state, not the historical method name.
     */
    public void markSyncCommitOutcomeUnknown() {
        commitUncertainty = CommitUncertainty.SYNC_OUTCOME_UNKNOWN;
    }

    /** Records that a submission was still unresolved when this generation retired. */
    public void markAsyncCommitUnresolved() {
        commitUncertainty = CommitUncertainty.ASYNC_UNRESOLVED_AT_RETIREMENT;
    }

    /**
     * Clears an earlier unknown outcome after an acknowledged retry covered the restored records.
     */
    public void clearCommitUncertainty() {
        commitUncertainty = CommitUncertainty.NONE_OBSERVED;
    }

    public CommitUncertainty commitUncertainty() {
        return commitUncertainty;
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
        recordsAwaitingCommit = recordsAwaitingCommit.plusUnsubmitted(count);
    }

    /**
     * Detaches the records a commit is about to cover, so the count travels with that submission.
     *
     * <p>Taken at submission rather than credited at acknowledgement, because records keep finishing while a
     * commit is in flight and those belong to the <em>next</em> position, not this one. Crediting whatever was
     * awaiting at acknowledgement time would report them committed although their position was never
     * acknowledged.
     */
    public CommitCoverage takeRecordsAwaitingCommit() {
        var taken = recordsAwaitingCommit;
        recordsAwaitingCommit = CommitCoverage.EMPTY;
        return taken;
    }

    /**
     * Credits exactly the records the acknowledged operation covered.
     *
     * <p>Counted from acknowledgement rather than staging, because a staged position that never commits is
     * exactly the case the retirement measurement exists to reveal ({@code procCommit §9.5}).
     *
     * <p>A submission still unresolved when the generation retires is therefore <strong>not</strong> counted.
     * Its outcome is genuinely unknown at that point, and under-reporting progress is the safe direction: the
     * measurement exists so that a run of zero-commit retirements is visible, and over-reporting would hide
     * exactly that.
     */
    public void creditRecordsCommitted(CommitCoverage recordsCovered) {
        recordsCommitted += recordsCovered.total();
    }

    /** Returns a pre-acceptance rejection without erasing any earlier uncertainty. */
    public void restoreRejectedRecords(CommitCoverage recordsCovered) {
        recordsAwaitingCommit =
            recordsAwaitingCommit.plus(recordsCovered.afterRejectedBeforeAcceptance());
    }

    /** Returns an accepted operation whose broker result is uncertain. */
    public void restoreUncertainRecords(CommitCoverage recordsCovered) {
        recordsAwaitingCommit = recordsAwaitingCommit.plus(recordsCovered.afterUncertainOutcome());
        markSyncCommitOutcomeUnknown();
    }

    public CommitCoverage recordsAwaitingCommit() {
        return recordsAwaitingCommit;
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
            + " bootstrapPending="
            + assignmentBootstrapPending
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
