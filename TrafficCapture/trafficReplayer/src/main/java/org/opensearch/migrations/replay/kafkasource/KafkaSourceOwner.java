/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.migrations.replay.kafkasource;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.LongSupplier;

import org.opensearch.migrations.replay.identity.CancellationDeadline;
import org.opensearch.migrations.replay.identity.KafkaRecordId;
import org.opensearch.migrations.replay.identity.PartitionGenerationId;
import org.opensearch.migrations.replay.intake.ReplayIntakeInput;
import org.opensearch.migrations.replay.intake.ReplayIntakeInputQueue;

import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.WakeupException;

/**
 * Owns Kafka reading, per-partition demand, and commit authority. Runs on the dedicated Kafka thread and
 * holds all of its state there. Defined by {@code kafkaLLD §5}, with the loop in {@code §3}.
 *
 * <p>Only this class invokes the Kafka commit API, and it is the only place a commit position is computed —
 * as a contiguous prefix of the records this consumer actually observed. No request, accumulator, retry
 * policy, or target result may commit ({@code kafkaLLD §5.6}).
 */
@Slf4j
public final class KafkaSourceOwner {

    public enum AbandonmentCause {
        REJECTED,
        UNKNOWN,
        UNSUBMITTED
    }

    public enum CommitResolution {
        ACKNOWLEDGED,
        RETRIABLE,
        GENERATION_STALE,
        OUTCOME_UNKNOWN,
        REJECTED,
        LATE_CALLBACK,
        DUPLICATE_IGNORED
    }

    public interface Metrics {
        Metrics NOOP = new Metrics() {};

        default void recordsRead(long count) {}
        default void recordsCommitted(long count) {}
        default void recordsCancelled(long count) {}
        default void recordsAbandonedAtRevocation(AbandonmentCause cause, long count) {}
        default void recordsCommitIneligible(long count) {}
        default void recordsOutstandingChanged(long delta) {}
        default void commitAttemptRejected() {}
        default void commitResolved(CommitResolution resolution) {}
    }

    private final KafkaSourcePort port;
    private final KafkaSourceInputQueue sourceInputs;
    private final ReplayIntakeInputQueue intakeInputs;
    private final WakeupController wakeupController;
    private final Duration cancellationGrace;
    private final LongSupplier monotonicNanos;
    private final GraceIntervalWait graceWait;
    private final Metrics metrics;

    private final Map<TopicPartition, PartitionSourceState> partitions = new LinkedHashMap<>();
    private final Map<TopicPartition, Long> stagedCommitPositions = new LinkedHashMap<>();
    /**
     * The one accepted asynchronous operation that has not resolved.
     *
     * <p>The identity and its resolution latch travel together. A callback can race a thrown
     * {@link WakeupException}, and a late callback from that operation can arrive while a newer operation is
     * active. Only resolving this exact identity may clear the slot; resolving it twice is diagnostic and
     * changes no state.
     */
    private CommitOperation inFlightCommitOperation;
    private long nextCommitOperationId;
    /**
     * Generations revoked or lost whose cleanup replay intake has not yet reported.
     *
     * <p>A set per partition rather than a boolean, for two reasons. Map membership cannot answer the question:
     * the previous generation's state is removed before its successor is created, so "was there a prior
     * generation" is always false by the time a successor could ask. And {@code GenerationCleanupFinished} can
     * arrive <em>before</em> the successor is assigned, since cleanup is fast and reassignment waits for a
     * poll — a boolean set at assignment time would then never be cleared, pausing the partition forever.
     */
    private final Map<TopicPartition, Set<PartitionGenerationId>> cleanupOutstanding = new LinkedHashMap<>();
    private long nextGenerationSequence;

    public KafkaSourceOwner(
        KafkaSourcePort port,
        KafkaSourceInputQueue sourceInputs,
        ReplayIntakeInputQueue intakeInputs,
        WakeupController wakeupController,
        Duration cancellationGrace,
        LongSupplier monotonicNanos,
        GraceIntervalWait graceWait
    ) {
        this(
            port,
            sourceInputs,
            intakeInputs,
            wakeupController,
            cancellationGrace,
            monotonicNanos,
            graceWait,
            Metrics.NOOP
        );
    }

    public KafkaSourceOwner(
        KafkaSourcePort port,
        KafkaSourceInputQueue sourceInputs,
        ReplayIntakeInputQueue intakeInputs,
        WakeupController wakeupController,
        Duration cancellationGrace,
        LongSupplier monotonicNanos,
        GraceIntervalWait graceWait,
        Metrics metrics
    ) {
        this.port = Objects.requireNonNull(port, "port");
        this.sourceInputs = Objects.requireNonNull(sourceInputs, "sourceInputs");
        this.intakeInputs = Objects.requireNonNull(intakeInputs, "intakeInputs");
        this.wakeupController = Objects.requireNonNull(wakeupController, "wakeupController");
        this.cancellationGrace = Objects.requireNonNull(cancellationGrace, "cancellationGrace");
        this.monotonicNanos = Objects.requireNonNull(monotonicNanos, "monotonicNanos");
        this.graceWait = Objects.requireNonNull(graceWait, "graceWait");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
        if (cancellationGrace.isNegative()) {
            throw new IllegalArgumentException("cancellationGrace must not be negative");
        }
    }

    /**
     * One iteration of the {@code kafkaLLD §3} loop. The order is the design's and is not interchangeable:
     * inputs are applied before new demand is acted on, commits are submitted before a poll that a wakeup
     * might interrupt, and resume happens last so it reflects every state change this iteration made.
     */
    public void runOnce() {
        applyQueuedInputs();
        submitLoopCommitIfEligible();
        applyPauseAndResumeDecisions();
        pollAndDeliver();
    }

    // ---------------------------------------------------------------- queued source inputs

    private void applyQueuedInputs() {
        for (var input : sourceInputs.drain()) {
            apply(input);
        }
    }

    /**
     * Exhaustive over {@link KafkaSourceInput} with no {@code default}, so adding a variant breaks here
     * rather than being silently ignored ({@code kafkaLLD §4.2}).
     */
    private void apply(KafkaSourceInput input) {
        switch (input) {
            case KafkaSourceInput.RequestNextPartitionBatch request -> applyBatchRequest(request);
            case KafkaSourceInput.RecordProcessingFinished finished -> applyRecordFinished(finished);
            case KafkaSourceInput.GenerationCleanupFinished cleanup -> applyCleanupFinished(cleanup);
            case KafkaSourceInput.CaptureProtocolViolationDetected violation ->
                applyProtocolViolation(violation);
        }
    }

    /**
     * Accepts a batch request only under the three conditions {@code kafkaLLD §5.3} names: the generation is
     * current, no earlier request for it is outstanding, and lifecycle state has not permanently ended intake.
     *
     * <p>The third is checked here and not left to {@link PartitionSourceState#isReadable()}. That would keep
     * the partition paused and so happen to be harmless, but it rests the whole "no reading past the permanent
     * boundary" invariant on one check where the design specifies two — and it records a request against a
     * generation that can never satisfy it. Prior-generation cleanup is deliberately <em>not</em> in this list:
     * §5.3 has it delay reading without rejecting the request.
     */
    private void applyBatchRequest(KafkaSourceInput.RequestNextPartitionBatch request) {
        var state = currentStateFor(request.generation());
        if (state.isEmpty()) {
            // A stale-generation request is dropped rather than applied to a successor assignment, which is
            // why every input carries its generation (kafkaLLD §4.2).
            log.atDebug().setMessage("Ignoring batch request for inactive generation {}")
                .addArgument(request::generation).log();
            return;
        }
        if (!state.get().lifecycleAllowsIntake()) {
            log.atDebug().setMessage("Ignoring batch request for {}: intake has permanently ended")
                .addArgument(request::generation).log();
            return;
        }
        state.get().requestBatch(request.requestId());
    }

    private void applyRecordFinished(KafkaSourceInput.RecordProcessingFinished finished) {
        var state = partitions.get(finished.generation().topicPartition());
        if (state == null || !state.generation().equals(finished.generation())) {
            // Late completion for a cleaned-up generation is diagnostic only (kafkaLLD §5.6).
            log.atDebug().setMessage("Ignoring completion for inactive generation {}")
                .addArgument(finished::generation).log();
            return;
        }
        var completion = state.commitQueue().recordProcessingFinished(finished.recordId());
        // Counted as records, not as an offset delta: physical offset gaps exist and §17.1 requires that they
        // not block advancement, so offset arithmetic would over-count by every gap.
        state.countRecordsAwaitingCommit(completion.newlyContiguousRecords().size());
        completion.nextCommitOffset().ifPresent(nextOffset ->
            stageCommitPosition(finished.generation().topicPartition(), nextOffset)
        );
    }

    /**
     * Clears the cleanup gate for the generation that finished, and only for it.
     *
     * <p>The message names the <em>old</em> generation while the gate lives on its successor, so the two are
     * matched through {@link #cleanupOutstanding} rather than by topic-partition alone. Clearing by partition
     * would let a cleanup for generation N−2 release a successor still waiting on N−1.
     *
     * <p>Clears only the cleanup reason. A successor stays paused if it has no request outstanding or if
     * lifecycle state still prohibits intake — the reasons do not alias ({@code kafkaLLD §5.1}).
     */
    private void applyCleanupFinished(KafkaSourceInput.GenerationCleanupFinished cleanup) {
        var topicPartition = cleanup.generation().topicPartition();
        var outstanding = cleanupOutstanding.get(topicPartition);
        if (outstanding == null || !outstanding.remove(cleanup.generation())) {
            log.atDebug().setMessage("Cleanup completion for {} matched no generation awaiting cleanup")
                .addArgument(cleanup::generation).log();
            return;
        }
        if (outstanding.isEmpty()) {
            cleanupOutstanding.remove(topicPartition);
            var successor = partitions.get(topicPartition);
            if (successor != null) {
                successor.setPriorGenerationCleanupPending(false);
            }
        }
    }

    private void applyProtocolViolation(KafkaSourceInput.CaptureProtocolViolationDetected violation) {
        // Blocks commits at and past the violating offset so a restart stops at the same record rather than
        // skipping it. Ending intake is what stops later records being admitted.
        currentStateFor(violation.generation()).ifPresent(state -> {
            state.endIntake();
            state.commitQueue().markCommitIneligible(violation.recordId());
            metrics.recordsCommitIneligible(1);
            metrics.recordsOutstandingChanged(-1);
            // A position already staged is necessarily before the violating record, because only
            // RecordProcessingFinished advances the prefix. Preserve that valid progress; the ineligible entry
            // remains in the deque and blocks every position at or beyond itself.
        });
        // An already-retired generation has no commit state left to classify. Preserve the same bounded
        // protocol-fatal path as an active generation without mutating a successor generation's accounting.
        throw new CaptureProtocolViolation(violation);
    }

    private void stageCommitPosition(TopicPartition topicPartition, long nextPosition) {
        stagedCommitPositions.merge(topicPartition, nextPosition, Math::max);
    }

    // ---------------------------------------------------------------- commit

    /**
     * Submits one asynchronous commit for every staged position, if none is already in flight.
     *
     * <p>Asynchronous because a synchronous commit here blocks for as long as the client retries internally —
     * up to {@code default.api.timeout.ms} — while this owner does not poll, which can exceed
     * {@code max.poll.interval.ms} and provoke a rebalance. That is {@code D5}'s mechanism in the ordinary
     * path rather than the revocation path, and {@code kafkaLLD §5.7} resolves it by making the loop's
     * submission asynchronous. The loop polls every iteration and a poll is what delivers the callback.
     *
     * <p>One operation at a time, so the source cannot spam the broker ({@code procCommit §9.4}) and a
     * partition that advances mid-flight stages the newer position rather than racing the old one.
     */
    private void submitLoopCommitIfEligible() {
        if (stagedCommitPositions.isEmpty() || inFlightCommitOperation != null) {
            return;
        }
        var submitted = detachForSubmission();
        var operation = new CommitOperation(nextCommitOperationId++, submitted);
        inFlightCommitOperation = operation;
        wakeupController.enterProtectedOperation();
        try {
            var submission = port.commitAsync(
                positionsOf(submitted),
                outcome -> resolveCommitOperation(operation, outcome, false)
            );
            if (!submission.accepted()) {
                resolveCommitOperation(operation, submission.rejectionOutcome(), true);
            }
        } catch (WakeupException absorbedByTheSubmission) {
            // §5.4 leaves this owner as the only interpreter of a wakeup, which is why the adapter propagates it
            // rather than classifying it. Resolving it here is what releases the one-in-flight slot §5.7 permits
            // only one of; a propagating exception would strand that slot for the life of the process, and a
            // submission interrupted before it registered a callback has nothing else that can resolve it.
            //
            // The outcome is the conservative reading rather than a stated rule: §5.7 names a wakeup as an
            // unknown outcome for a *bounded synchronous* commit, and says nothing about an interrupted
            // asynchronous submission. Unknown is right either way — the operation may already have reached the
            // broker — and it keeps a still-owned partition's position and re-offers it.
            wakeupController.onWakeupAbsorbedByProtectedOperation();
            resolveCommitOperation(operation, KafkaSourcePort.CommitOutcome.OUTCOME_UNKNOWN, false);
        } finally {
            wakeupController.leaveProtectedOperation();
        }
    }

    /**
     * Removes the staged positions and records, per partition, the generation they belong to and how many
     * records they cover.
     *
     * <p>The generation is what makes a callback resolvable. Without it a callback that arrives after the
     * partition was revoked and reassigned would be indistinguishable from one for the current generation, and
     * would credit or re-stage against a successor that never submitted it.
     */
    private Map<TopicPartition, SubmittedPosition> detachForSubmission() {
        var submitted = new LinkedHashMap<TopicPartition, SubmittedPosition>();
        stagedCommitPositions.forEach((topicPartition, position) -> {
            var state = partitions.get(topicPartition);
            if (state == null) {
                return;
            }
            submitted.put(
                topicPartition,
                new SubmittedPosition(state.generation(), position, state.takeRecordsAwaitingCommit())
            );
        });
        stagedCommitPositions.clear();
        return submitted;
    }

    private static Map<TopicPartition, Long> positionsOf(Map<TopicPartition, SubmittedPosition> submitted) {
        var positions = new LinkedHashMap<TopicPartition, Long>();
        submitted.forEach((topicPartition, detail) -> positions.put(topicPartition, detail.nextPosition()));
        return positions;
    }

    /**
     * One partition's position in a submitted commit, with the generation that submitted it and how many
     * records it covers.
     */
    private record SubmittedPosition(
        PartitionGenerationId generation,
        long nextPosition,
        PartitionSourceState.CommitCoverage recordsCovered
    ) {}

    /**
     * One operation-level identity and latch.
     *
     * <p>Owner-thread confinement makes an atomic unnecessary. The identity is still essential: a callback for
     * operation N may arrive after a wakeup resolved N and operation N+1 was submitted. Only N may observe its
     * latch, and it can never clear N+1's in-flight slot.
     */
    private static final class CommitOperation {
        private final long operationId;
        private final Map<TopicPartition, SubmittedPosition> submitted;
        private boolean resolved;

        private CommitOperation(long operationId, Map<TopicPartition, SubmittedPosition> submitted) {
            this.operationId = operationId;
            this.submitted = Map.copyOf(submitted);
        }

        private boolean claimResolution() {
            if (resolved) {
                return false;
            }
            resolved = true;
            return true;
        }
    }

    /**
     * Resolves a submitted commit. Runs on the Kafka thread, delivered from inside a later {@code poll()}.
     *
     * <p>Staged positions are credited on acknowledgement, never on attempt. An unresolved failure re-stages
     * every partition this consumer still owns under the same generation and drops the rest: committing a
     * position that did in fact commit is idempotent, which is what makes re-offering sound while a batch's
     * partial application stays unobservable, whereas abandoning it would hold that partition's progress until
     * its prefix next advances — which work blocked behind an unfinished head can delay without bound.
     */
    private void resolveCommitOperation(
        CommitOperation operation,
        KafkaSourcePort.CommitOutcome outcome,
        boolean rejectedWithoutBrokerMovement
    ) {
        if (!operation.claimResolution()) {
            metrics.commitResolved(CommitResolution.DUPLICATE_IGNORED);
            log.atDebug().setMessage("Ignoring duplicate resolution for commit operation {}: {}")
                .addArgument(operation.operationId).addArgument(outcome).log();
            return;
        }
        if (inFlightCommitOperation == operation) {
            inFlightCommitOperation = null;
        }
        if (rejectedWithoutBrokerMovement) {
            metrics.commitAttemptRejected();
            metrics.commitResolved(CommitResolution.REJECTED);
        } else {
            metrics.commitResolved(resolutionOf(outcome));
        }
        operation.submitted.forEach((topicPartition, detail) -> {
            var state = partitions.get(topicPartition);
            var currentGeneration = state != null && state.generation().equals(detail.generation());

            if (!currentGeneration) {
                // kafkaLLD §5.7's LATE_CALLBACK: diagnostic only. It changes no state -- crediting a successor
                // for an operation the revoked generation submitted would inflate its retirement count, and
                // re-staging would offer a successor a position it never derived.
                wakeupController.countLateCommitCallback();
                metrics.commitResolved(CommitResolution.LATE_CALLBACK);
                log.atDebug()
                    .setMessage("Late commit resolution for retired generation {} at {}: {}; ignoring")
                    .addArgument(detail::generation).addArgument(detail::nextPosition).addArgument(outcome)
                    .log();
                return;
            }

            if (outcome == KafkaSourcePort.CommitOutcome.ACKNOWLEDGED) {
                state.creditRecordsCommitted(detail.recordsCovered());
                metrics.recordsCommitted(detail.recordsCovered().total());
                metrics.recordsOutstandingChanged(-detail.recordsCovered().total());
                // A still-owned partition restores records after an unknown outcome. An acknowledgement of the
                // re-offered position covers those restored records, so the earlier uncertainty no longer
                // explains its retirement count.
                state.clearCommitUncertainty();
                return;
            }

            // Nothing is reported to intake, which finished its record-processing decision before sending
            // RecordProcessingFinished (kafkaLLD §5.7).
            if (rejectedWithoutBrokerMovement) {
                state.restoreRejectedRecords(detail.recordsCovered());
            } else {
                state.restoreUncertainRecords(detail.recordsCovered());
            }
            if (stillOwnsForCommit(topicPartition)) {
                stageCommitPosition(topicPartition, detail.nextPosition());
            } else {
                log.atInfo().setMessage("Dropping commit position {} for {}: {} and no longer committable")
                    .addArgument(detail::nextPosition).addArgument(topicPartition).addArgument(outcome).log();
            }
        });
    }

    private static CommitResolution resolutionOf(KafkaSourcePort.CommitOutcome outcome) {
        return switch (outcome) {
            case ACKNOWLEDGED -> CommitResolution.ACKNOWLEDGED;
            case RETRIABLE -> CommitResolution.RETRIABLE;
            case GENERATION_STALE -> CommitResolution.GENERATION_STALE;
            case OUTCOME_UNKNOWN -> CommitResolution.OUTCOME_UNKNOWN;
            case LATE_CALLBACK -> CommitResolution.LATE_CALLBACK;
        };
    }

    /**
     * Whether this partition may still be offered a commit position.
     *
     * <p>Map membership alone is not enough: during {@code onPartitionsRevoked} the entry is still present but
     * the generation is already revoked, and {@code kafkaLLD §5.7} has revocation beginning when the callback
     * is entered rather than when it returns. Re-offering there cannot succeed — a stale generation's commit is
     * rejected identically however often it is sent — and it consumes the interval force cancellation needs.
     */
    private boolean stillOwnsForCommit(TopicPartition topicPartition) {
        var state = partitions.get(topicPartition);
        return state != null && state.lifecycleAllowsIntake();
    }

    // ---------------------------------------------------------------- pause and resume

    private void applyPauseAndResumeDecisions() {
        for (var state : partitions.values()) {
            var shouldBePaused = !state.isReadable();
            if (shouldBePaused == state.isKafkaPaused()) {
                continue;
            }
            if (shouldBePaused) {
                port.pause(state.topicPartition());
            } else {
                port.resume(state.topicPartition());
            }
            state.setKafkaPaused(shouldBePaused);
        }
    }

    // ---------------------------------------------------------------- poll and deliver

    /**
     * Polls once and delivers what it returned.
     *
     * <p>{@code WakeupException} is caught here and only here. It is not a failure: it is the answer to a
     * wakeup this owner's own queue asked for, and {@code kafkaLLD §5.4} makes this loop the one controlled
     * boundary that may read it as "stop waiting and inspect the source-input queue". The adapter deliberately
     * propagates it rather than converting it to an empty result, because an empty result is indistinguishable
     * from a partition with nothing to read — so the boundary has to exist here or a routine queued input
     * unwinds the iteration.
     */
    private void pollAndDeliver() {
        Map<TopicPartition, List<PolledKafkaRecord>> polled;
        wakeupController.enterPoll(!sourceInputs.isEmpty());
        try {
            polled = port.poll();
        } catch (WakeupException wokenToInspectTheQueue) {
            log.atTrace().setMessage("Poll woken to inspect the source-input queue").log();
            return;
        } finally {
            // Consumed whether poll returned records, threw WakeupException, or failed, so an unconsumed wakeup
            // cannot land on the next Kafka operation.
            wakeupController.leavePollAndConsumeWakeup();
        }
        polled.forEach(this::deliver);
    }

    private void deliver(TopicPartition topicPartition, List<PolledKafkaRecord> records) {
        if (records.isEmpty()) {
            // An empty poll completes no request; the partition stays resumed and the request outstanding.
            return;
        }
        var state = partitions.get(topicPartition);
        if (state == null) {
            throw new IllegalStateException("poll returned records for unassigned partition " + topicPartition);
        }
        // Paused before the batch reaches intake, so another poll cannot fetch more for this partition while
        // the current batch is still being applied (kafkaLLD §5.3).
        if (!state.isKafkaPaused()) {
            port.pause(topicPartition);
            state.setKafkaPaused(true);
        }
        var requestId = state.completeOutstandingRequest();
        // Stamped here, by the only component that knows the generation. The adapter returns raw records
        // precisely so it needs no generation map of its own to keep in step (kafkaLLD §5).
        var stamped = records.stream()
            .map(raw -> new ApplicationKafkaRecord(
                new KafkaRecordId(state.generation(), raw.offset()),
                raw.logAppendTimeMillis(),
                raw.serializedSizeBytes(),
                raw.envelope()
            ))
            .toList();
        stamped.forEach(record -> state.commitQueue().register(record.recordId()));
        state.countRecordsRead(stamped.size());
        metrics.recordsRead(stamped.size());
        metrics.recordsOutstandingChanged(stamped.size());
        submitRequired(new ReplayIntakeInput.PartitionRecordBatch(requestId, stamped));
    }

    // ---------------------------------------------------------------- rebalance callbacks

    /**
     * Pauses the whole resulting assignment before Kafka may fetch from it, because Kafka does not preserve
     * pause state across an assignment change ({@code kafkaLLD §5.2}). Partitions are resumed later, by
     * {@link #runOnce()}, and only those with an outstanding request and no other reason to stay paused.
     */
    public void onPartitionsAssigned(Collection<TopicPartition> assigned) {
        wakeupController.enterRebalanceCallback();
        try {
            for (var topicPartition : port.assignment()) {
                port.pause(topicPartition);
                var existing = partitions.get(topicPartition);
                if (existing != null) {
                    existing.setKafkaPaused(true);
                }
            }
            for (var topicPartition : assigned) {
                if (partitions.containsKey(topicPartition)) {
                    // Retained continuously: same generation, but its pause state was just reapplied.
                    continue;
                }
                beginGeneration(topicPartition);
            }
        } finally {
            wakeupController.leaveRebalanceCallback();
        }
    }

    private void beginGeneration(TopicPartition topicPartition) {
        var generation = new PartitionGenerationId(topicPartition, nextGenerationSequence++);
        var state = new PartitionSourceState(generation);
        // A newly assigned generation cannot reuse mutable state from the previous one, so both the state and
        // the commit queue are replaced rather than reset (kafkaLLD §5.2).
        partitions.put(topicPartition, state);
        stagedCommitPositions.remove(topicPartition);
        // Gated on whether any earlier generation of this partition is still cleaning up, which map membership
        // cannot answer: the predecessor's state was removed when it was revoked, before this point.
        if (cleanupOutstanding.containsKey(topicPartition)) {
            state.setPriorGenerationCleanupPending(true);
        }
        submitRequired(new ReplayIntakeInput.PartitionGenerationAssigned(generation));
    }

    /**
     * The bounded-quiescence sequence from {@code kafkaLLD §15.1} and {@code procCommit §9.2}.
     *
     * <p>What this deliberately does <strong>not</strong> do is issue a blocking commit before any
     * cancellation has been delivered. That specific shape is defect {@code D5}: it can exceed
     * {@code max.poll.interval.ms} and trigger a rebalance from inside a rebalance. Commits still happen
     * here — they arrive as inputs while the callback waits, and are submitted from that wait.
     *
     * <p>The callback returns when force cancellation is <em>accepted</em> by replay intake, which proves
     * intake will observe it unless the process fails. It does not wait for cleanup, which continues
     * afterwards while the successor generation stays paused.
     */
    public void onPartitionsRevoked(Collection<TopicPartition> revoked) throws InterruptedException {
        wakeupController.enterRebalanceCallback();
        try {
            var generations = new ArrayList<PartitionGenerationId>();
            for (var topicPartition : revoked) {
                var state = partitions.get(topicPartition);
                if (state == null) {
                    continue;
                }
                state.endIntake();
                generations.add(state.generation());
            }
            if (generations.isEmpty()) {
                return;
            }

            // Registered before graceful cancellation is submitted, not after the wait. Cleanup can complete
            // *during* the grace interval -- intake sends GenerationCleanupFinished as soon as its tracker
            // empties, which for a generation with little in flight is immediately -- and a completion arriving
            // before its obligation was recorded used to be discarded as unmatched. The generation was then
            // registered anyway when it retired, so a successor waited on a cleanup already reported and never
            // sent again: a permanently paused partition.
            generations.forEach(generation -> cleanupOutstanding
                .computeIfAbsent(generation.topicPartition(), ignored -> new LinkedHashSet<>())
                .add(generation));

            var deadlineNanos = monotonicNanos.getAsLong() + cancellationGrace.toNanos();
            var deadline = new CancellationDeadline(deadlineNanos);
            generations.forEach(generation -> submitRequired(
                new ReplayIntakeInput.GracefulGenerationCancellation(generation, deadline)
            ));

            wakeupController.recordGraceWaitEnded(
                awaitGraceDeadlineProcessingInputs(deadline, generations)
            );

            // §4.1: "The Kafka source therefore submits it unconditionally, including when the grace wait
            // returned early because every revoked generation had already reported cleanup." Cancelling the
            // generation's work is this source's obligation and a successor assignment of the same partition
            // waits on it, while whether anything remains to cancel is intake's to determine -- and an
            // already-cleaned delivery is inert there, so predicting that answer here would only hold up the
            // successor to save a message that changes nothing.
            generations.forEach(generation -> submitRequired(
                new ReplayIntakeInput.ForceGenerationCancellation(generation)
            ));
            revoked.forEach(this::retireGeneration);
        } finally {
            wakeupController.leaveRebalanceCallback();
        }
    }

    /**
     * Drops a generation's local state and records what it achieved, per {@code kafkaLLD §15.4}.
     *
     * <p>The measurement is taken for every retiring generation, including one that committed nothing and one
     * that never became readable. A generation missing from the measurement is indistinguishable from one that
     * committed nothing, which is precisely the case it exists to reveal ({@code procCommit §9.5}).
     */
    private void retireGeneration(TopicPartition topicPartition) {
        var state = partitions.remove(topicPartition);
        // The old generation must not go on offering a commit: kafkaLLD §5.7 has it neither retrying nor
        // waiting, with the next assigned position deciding redelivery instead.
        stagedCommitPositions.remove(topicPartition);
        if (state == null) {
            return;
        }
        var activeSubmission = inFlightCommitOperation == null
            ? null
            : inFlightCommitOperation.submitted.get(topicPartition);
        if (activeSubmission != null && !activeSubmission.generation().equals(state.generation())) {
            activeSubmission = null;
        }
        // Not registered here. Registration happens before graceful cancellation is submitted, so that a
        // completion arriving during the grace interval matches an obligation that already exists; re-adding
        // here would resurrect an obligation that completion had already discharged.
        // The diagnostic qualifies the two measurements without interpreting them. NONE_OBSERVED means only
        // that no unresolved or unknown commit outcome is known; kafkaLLD §15.4 leaves conclusions about a zero
        // count to the observer.
        if (activeSubmission != null) {
            state.markAsyncCommitUnresolved();
        }
        recordRetirementConservation(state, activeSubmission);
        log.atInfo()
            .setMessage("Retiring generation {}: committed {} of {} records read, commitUncertainty={}")
            .addArgument(state::generation)
            .addArgument(state::recordsCommitted)
            .addArgument(state::recordsRead)
            .addArgument(state::commitUncertainty)
            .log();
        wakeupController.recordGenerationRetired(
            state.generation().toString(),
            state.recordsCommitted(),
            state.recordsRead()
        );
    }

    private void recordRetirementConservation(
        PartitionSourceState state,
        SubmittedPosition activeSubmission
    ) {
        var staged = state.recordsAwaitingCommit();
        var completedBehindHead = state.commitQueue().completedBehindHeadCount();
        var cancelled = state.commitQueue().unfinishedCount();
        var commitIneligible = state.commitQueue().commitIneligibleCount();
        var activeUnknown = activeSubmission == null ? 0 : activeSubmission.recordsCovered().total();

        var abandonedRejected = staged.rejected();
        var abandonedUnknown = Math.addExact(staged.unknown(), activeUnknown);
        var abandonedUnsubmitted = Math.addExact(staged.unsubmitted(), completedBehindHead);
        var accounted = Math.addExact(
            Math.addExact(state.recordsCommitted(), commitIneligible),
            Math.addExact(
                Math.addExact(cancelled, abandonedRejected),
                Math.addExact(abandonedUnknown, abandonedUnsubmitted)
            )
        );
        if (accounted != state.recordsRead()) {
            throw new IllegalStateException(
                "record conservation failed while retiring "
                    + state.generation()
                    + ": read="
                    + state.recordsRead()
                    + ", accounted="
                    + accounted
            );
        }

        recordAbandonment(AbandonmentCause.REJECTED, abandonedRejected);
        recordAbandonment(AbandonmentCause.UNKNOWN, abandonedUnknown);
        recordAbandonment(AbandonmentCause.UNSUBMITTED, abandonedUnsubmitted);
        if (cancelled > 0) {
            metrics.recordsCancelled(cancelled);
        }
        var newlyTerminal = Math.addExact(
            cancelled,
            Math.addExact(abandonedRejected, Math.addExact(abandonedUnknown, abandonedUnsubmitted))
        );
        if (newlyTerminal > 0) {
            metrics.recordsOutstandingChanged(-newlyTerminal);
        }
    }

    private void recordAbandonment(AbandonmentCause cause, long count) {
        if (count > 0) {
            metrics.recordsAbandonedAtRevocation(cause, count);
        }
    }

    /**
     * Processes commit and lifecycle inputs while waiting out the grace interval. The wait is signalled by
     * queue submission rather than by a Kafka wakeup, which this callback is protected from receiving.
     *
     * <p>No record batch is admitted here, so a batch request arriving during the wait is applied to state
     * but produces no poll.
     */
    private boolean awaitGraceDeadlineProcessingInputs(
        CancellationDeadline deadline,
        List<PartitionGenerationId> revokedGenerations
    ) throws InterruptedException {
        // The wait is injected, not called on the queue directly. kafkaLLD §15.1 requires one monotonic source
        // for the deadline, and no arrangement of durations achieves that with a raw Object.wait: waiting always
        // elapses in real time, so "the wait expired" and "the deadline passed" would be two different clocks
        // answering one question. GraceIntervalWait returns only when the deadline's own clock says so.
        // Attempted once before waiting, not only after an input arrives. Positions can already be staged when
        // revocation begins -- a prefix that advanced earlier in this iteration, or one reclaimed from an
        // abandoned async submission -- and `procCommit §9.2` step 5 permits attempting them without making the
        // attempt conditional on further inputs. Only reaching this inside the loop meant that with no new input
        // during the interval, a staged position was never committed at all.
        submitRevocationCommit(deadline);
        if (allCleanupReported(revokedGenerations)) {
            return true;
        }
        while (graceWait.awaitInputUntil(deadline)) {
            applyQueuedInputs();
            submitRevocationCommit(deadline);
            // procCommit:1308: "If the generation has no unfinished work, the callback returns immediately."
            // Cleanup completion is how that becomes observable -- intake sends it once its tracker empties --
            // so waiting out the rest of the interval after it arrives holds the whole consumer, and the
            // group's rebalance, for nothing.
            if (allCleanupReported(revokedGenerations)) {
                return true;
            }
        }
        return false;
    }

    /** True once no revoked generation is still awaiting cleanup, which is procCommit:1308's condition. */
    private boolean allCleanupReported(List<PartitionGenerationId> revokedGenerations) {
        return revokedGenerations.stream().noneMatch(generation -> {
            var outstanding = cleanupOutstanding.get(generation.topicPartition());
            return outstanding != null && outstanding.contains(generation);
        });
    }

    /**
     * Commits from inside the revocation callback, bounded by what is left of the grace interval.
     *
     * <p>Synchronous, because an asynchronous callback needs a later {@code poll()} and this generation is gone
     * before one happens. Bounded, because the callback is holding the whole consumer: nothing is polled for any
     * partition while it runs, the group's rebalance waits on it, and overrunning
     * {@code max.poll.interval.ms} — which is also the rebalance timeout — fences the member and converts this
     * graceful revocation into a lost one, discarding every staged position.
     *
     * <p>Attempted however little grace remains. The bound is what confines the call to the deadline, so a
     * sliver is a small budget rather than a hazard, and declining to try would discard a position a fast
     * round-trip could still have committed — a revoked generation's position is discarded anyway once the
     * callback returns, so attempting is never the worse choice.
     */
    private void submitRevocationCommit(CancellationDeadline deadline) {
        if (stagedCommitPositions.isEmpty()) {
            return;
        }
        if (inFlightCommitOperation != null) {
            // §5.7 allows one commit operation at a time, and an asynchronous one cannot be taken back: Kafka
            // guarantees its callback runs before the next commitSync returns, so it resolves *during* any
            // commit issued here — crediting its own record count, which this one would then credit again.
            // Forgetting it locally does not cancel it. Waiting for it is not possible either, since its
            // callback needs a poll that this callback is preventing, so the only sound choice is to let it be
            // the operation that decides. Its positions are not re-offered: §5.7 has a revoked generation
            // discard rather than retry, and the next assigned position decides redelivery.
            log.atDebug().setMessage("Not attempting a revocation commit while {} is in flight")
                .addArgument(() -> inFlightCommitOperation.submitted.keySet()).log();
            return;
        }
        // Attempted however little grace is left. The bound is what keeps the call inside the deadline, so a
        // sliver of remaining time is a small budget rather than a hazard -- and skipping the attempt would
        // discard a position that a fast round-trip could still have committed, since a revoked generation's
        // position is discarded either way once the callback returns.
        var remainingNanos = deadline.remainingNanos(monotonicNanos.getAsLong());
        if (remainingNanos <= 0) {
            // §5.7: "a commit that cannot finish within the remaining grace must not be started". Zero is the
            // absence of a remainder rather than a minimum, so this does not reintroduce the floor the owner
            // ruled out -- and starting here charges every submitted partition a spurious unknown outcome,
            // including partitions that are being retained.
            return;
        }
        var submitted = detachForSubmission();
        var operation = new CommitOperation(nextCommitOperationId++, submitted);
        wakeupController.enterProtectedOperation();
        KafkaSourcePort.CommitOutcome outcome;
        try {
            outcome = port.commitSync(positionsOf(submitted), Duration.ofNanos(remainingNanos));
        } catch (WakeupException absorbedByTheCommit) {
            // §5.7 classifies a wakeup-interrupted commit as an unknown outcome. Recording the absorption is
            // what keeps the callback's exit able to issue its deferred wakeup: the commit spent the one that
            // was outstanding, and a controller that still believes it is outstanding issues nothing.
            wakeupController.onWakeupAbsorbedByProtectedOperation();
            outcome = KafkaSourcePort.CommitOutcome.OUTCOME_UNKNOWN;
        } finally {
            wakeupController.leaveProtectedOperation();
        }
        // The synchronous call was accepted by the client. A failed batched operation may have applied some
        // positions, so no returned outcome proves the broker position stayed put. The common resolution path
        // restores that uncertain coverage and marks each still-live generation accordingly.
        resolveCommitOperation(operation, outcome, false);
    }

    /**
     * Revocation without the chance to commit or cancel gracefully. Local state is dropped; the next assigned
     * position decides redelivery.
     */
    public void onPartitionsLost(Collection<TopicPartition> lost) {
        wakeupController.enterRebalanceCallback();
        try {
            for (var topicPartition : lost) {
                var state = partitions.get(topicPartition);
                if (state != null) {
                    state.endIntake();
                    // Registered before force cancellation is submitted, for the same reason the revocation
                    // path does: intake may report cleanup complete before this method returns, and a
                    // completion with no obligation recorded yet would be discarded as unmatched.
                    cleanupOutstanding
                        .computeIfAbsent(topicPartition, ignored -> new LinkedHashSet<>())
                        .add(state.generation());
                    submitRequired(
                        new ReplayIntakeInput.ForceGenerationCancellation(state.generation())
                    );
                }
                retireGeneration(topicPartition);
            }
        } finally {
            wakeupController.leaveRebalanceCallback();
        }
    }

    // ---------------------------------------------------------------- observation

    public Optional<PartitionSourceState> partitionState(TopicPartition topicPartition) {
        return Optional.ofNullable(partitions.get(topicPartition));
    }

    public Optional<Long> stagedCommitPosition(TopicPartition topicPartition) {
        return Optional.ofNullable(stagedCommitPositions.get(topicPartition));
    }

    private Optional<PartitionSourceState> currentStateFor(PartitionGenerationId generation) {
        var state = partitions.get(generation.topicPartition());
        return state != null && state.generation().equals(generation) ? Optional.of(state) : Optional.empty();
    }

    /**
     * Submits an input replay intake must receive, and ends the process if it is refused.
     *
     * <p>{@code kafkaLLD §4.1} is explicit: "An input submission needed for correctness must report
     * acceptance. Queue rejection or an unexpected failure to submit is process-fatal." Every submission this
     * owner makes is in that class. A dropped {@code PartitionRecordBatch} strands records already registered
     * in the commit queue, so that partition's committed position never advances again; a dropped
     * {@code ForceGenerationCancellation} is worse, because {@code §15.2} makes accepting it the thing that
     * licenses {@code onPartitionsRevoked} to return and discard the generation's state — intake would then
     * never learn the generation ended.
     *
     * <p>Thrown rather than routed to an injected handler, matching {@link CaptureProtocolViolation}: the
     * supervisor and the fatal ladder are {@code G9}'s, and a second escalation mechanism here would be a
     * temporary concept in the core model.
     */
    private void submitRequired(ReplayIntakeInput input) {
        if (!intakeInputs.submit(input)) {
            throw new RequiredSubmissionRejected(input);
        }
    }

    /** A submission {@code kafkaLLD §4.1} requires to be accepted was refused. Ends the process. */
    public static final class RequiredSubmissionRejected extends IllegalStateException {
        RequiredSubmissionRejected(ReplayIntakeInput input) {
            super("replay intake refused a required input: " + input);
        }
    }

    /** Ends the process path for invalid capture input; carries the offending record for diagnostics. */
    public static final class CaptureProtocolViolation extends IllegalStateException {
        private final transient KafkaRecordId recordId;

        CaptureProtocolViolation(KafkaSourceInput.CaptureProtocolViolationDetected detected) {
            super("capture protocol violation at " + detected.recordId() + ": " + detected.diagnostic());
            this.recordId = detected.recordId();
        }

        public KafkaRecordId recordId() {
            return recordId;
        }
    }
}
