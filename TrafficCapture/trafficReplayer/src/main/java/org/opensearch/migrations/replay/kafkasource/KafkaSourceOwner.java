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
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.LongSupplier;

import org.opensearch.migrations.replay.identity.CancellationDeadline;
import org.opensearch.migrations.replay.identity.KafkaRecordId;
import org.opensearch.migrations.replay.identity.PartitionGenerationId;
import org.opensearch.migrations.replay.intake.ReplayIntakeInput;
import org.opensearch.migrations.replay.lifecycle.ReplayIntakeInputQueue;

import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.common.TopicPartition;

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

    private final KafkaSourcePort port;
    private final KafkaSourceInputQueue sourceInputs;
    private final ReplayIntakeInputQueue intakeInputs;
    private final WakeupController wakeupController;
    private final Duration cancellationGrace;
    private final LongSupplier monotonicNanos;

    private final Map<TopicPartition, PartitionSourceState> partitions = new LinkedHashMap<>();
    private final Map<TopicPartition, Long> stagedCommitPositions = new LinkedHashMap<>();
    private long nextGenerationSequence;

    public KafkaSourceOwner(
        KafkaSourcePort port,
        KafkaSourceInputQueue sourceInputs,
        ReplayIntakeInputQueue intakeInputs,
        WakeupController wakeupController,
        Duration cancellationGrace,
        LongSupplier monotonicNanos
    ) {
        this.port = Objects.requireNonNull(port, "port");
        this.sourceInputs = Objects.requireNonNull(sourceInputs, "sourceInputs");
        this.intakeInputs = Objects.requireNonNull(intakeInputs, "intakeInputs");
        this.wakeupController = Objects.requireNonNull(wakeupController, "wakeupController");
        this.cancellationGrace = Objects.requireNonNull(cancellationGrace, "cancellationGrace");
        this.monotonicNanos = Objects.requireNonNull(monotonicNanos, "monotonicNanos");
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
        submitEligibleCommits();
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

    private void applyBatchRequest(KafkaSourceInput.RequestNextPartitionBatch request) {
        var state = currentStateFor(request.generation());
        if (state.isEmpty()) {
            // A stale-generation request is dropped rather than applied to a successor assignment, which is
            // why every input carries its generation (kafkaLLD §4.2).
            log.atDebug().setMessage("Ignoring batch request for inactive generation {}")
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
        completion.nextCommitOffset().ifPresent(nextOffset ->
            stagedCommitPositions.put(finished.generation().topicPartition(), nextOffset)
        );
    }

    private void applyCleanupFinished(KafkaSourceInput.GenerationCleanupFinished cleanup) {
        // Clears only the cleanup reason. A successor stays paused if it has no request outstanding or if
        // lifecycle state still prohibits intake -- the reasons do not alias (kafkaLLD §5.1).
        var state = partitions.get(cleanup.generation().topicPartition());
        if (state != null) {
            state.setPriorGenerationCleanupPending(false);
        }
    }

    private void applyProtocolViolation(KafkaSourceInput.CaptureProtocolViolationDetected violation) {
        // Blocks commits at and past the violating offset so a restart stops at the same record rather than
        // skipping it. Ending intake is what stops later records being admitted.
        var state = partitions.get(violation.generation().topicPartition());
        if (state != null) {
            state.endIntake();
        }
        stagedCommitPositions.remove(violation.generation().topicPartition());
        throw new CaptureProtocolViolation(violation);
    }

    // ---------------------------------------------------------------- commit

    /**
     * Attempts one commit for every staged position, and keeps the staged entries a failure did not resolve.
     *
     * <p>Staged positions are removed on success, not before the attempt. Clearing them up front loses the
     * progress of every partition in a failed batch, including the ones the broker accepted — and because a
     * position is only re-derived when the next {@code RecordProcessingFinished} advances a partition's
     * contiguous prefix, a partition blocked behind an unfinished head could wait arbitrarily long to be
     * offered again.
     *
     * <p>Re-staging is safe on both counts that matter. Committing a position that did in fact commit is
     * idempotent, which is what makes it sound despite a failed batch's partial application being
     * unobservable. And it is not the retry {@code kafkaLLD §5.7} forbids: that prohibition is specifically
     * that "after revocation, the old generation does not retry or wait indefinitely for a commit", so
     * partitions whose generation is no longer current are dropped here rather than re-staged.
     *
     * <p>Paced by the loop rather than spinning: one attempt per iteration, and every iteration also polls.
     */
    private void submitEligibleCommits() {
        if (stagedCommitPositions.isEmpty()) {
            return;
        }
        var attempted = Map.copyOf(stagedCommitPositions);
        wakeupController.enterProtectedOperation();
        KafkaSourcePort.CommitOutcome outcome;
        try {
            outcome = port.commit(attempted);
        } finally {
            wakeupController.leaveProtectedOperation();
        }

        if (outcome == KafkaSourcePort.CommitOutcome.ACKNOWLEDGED) {
            attempted.forEach(stagedCommitPositions::remove);
            return;
        }

        // Nothing is reported to intake, which finished its record-processing decision before sending
        // RecordProcessingFinished (kafkaLLD §5.7).
        attempted.forEach((topicPartition, position) -> {
            if (!isCurrentlyOwned(topicPartition)) {
                stagedCommitPositions.remove(topicPartition);
                log.atInfo().setMessage("Dropping staged commit for {} at {}: no longer owned")
                    .addArgument(topicPartition).addArgument(position).log();
            }
        });
        log.atWarn().setMessage("Commit was {} for {}; retaining {} still-owned position(s) for the next attempt")
            .addArgument(outcome)
            .addArgument(attempted::keySet)
            .addArgument(stagedCommitPositions::size)
            .log();
    }

    /**
     * True while this partition is assigned under the generation the staged position belongs to. A successor
     * generation gets its own staged position, so a stale one must not ride along with it.
     */
    private boolean isCurrentlyOwned(TopicPartition topicPartition) {
        return partitions.containsKey(topicPartition);
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

    private void pollAndDeliver() {
        Map<TopicPartition, List<ApplicationKafkaRecord>> polled;
        wakeupController.enterPoll();
        try {
            polled = port.poll();
        } finally {
            // Consumed whether poll returned records or threw WakeupException, so an unconsumed wakeup cannot
            // land on the next commit.
            wakeupController.leavePollAndConsumeWakeup();
        }
        polled.forEach(this::deliver);
    }

    private void deliver(TopicPartition topicPartition, List<ApplicationKafkaRecord> records) {
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
        records.forEach(record -> state.commitQueue().register(record.recordId()));
        intakeInputs.submit(new ReplayIntakeInput.PartitionRecordBatch(requestId, records));
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
        var hadPriorGeneration = partitions.containsKey(topicPartition);
        partitions.put(topicPartition, state);
        stagedCommitPositions.remove(topicPartition);
        if (hadPriorGeneration) {
            state.setPriorGenerationCleanupPending(true);
        }
        intakeInputs.submit(new ReplayIntakeInput.PartitionGenerationAssigned(
            generation,
            port.committedPosition(topicPartition).orElse(0L)
        ));
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

            var deadlineNanos = monotonicNanos.getAsLong() + cancellationGrace.toNanos();
            var deadline = new CancellationDeadline(deadlineNanos);
            generations.forEach(generation -> intakeInputs.submit(
                new ReplayIntakeInput.GracefulGenerationCancellation(generation, deadline)
            ));

            awaitGraceDeadlineProcessingInputs(deadline);

            // Accepting force cancellation is what releases the callback (procCommit §9.2 step 7).
            generations.forEach(generation -> intakeInputs.submit(
                new ReplayIntakeInput.ForceGenerationCancellation(generation)
            ));
            revoked.forEach(topicPartition -> {
                partitions.remove(topicPartition);
                // The old generation must not go on offering a commit: kafkaLLD §5.7 has it neither retrying
                // nor waiting, with the next assigned position deciding redelivery instead.
                stagedCommitPositions.remove(topicPartition);
            });
        } finally {
            wakeupController.leaveRebalanceCallback();
        }
    }

    /**
     * Processes commit and lifecycle inputs while waiting out the grace interval. The wait is signalled by
     * queue submission rather than by a Kafka wakeup, which this callback is protected from receiving.
     *
     * <p>No record batch is admitted here, so a batch request arriving during the wait is applied to state
     * but produces no poll.
     */
    private void awaitGraceDeadlineProcessingInputs(CancellationDeadline deadline) throws InterruptedException {
        while (!deadline.hasPassed(monotonicNanos.getAsLong())) {
            if (!sourceInputs.awaitInput(deadline.monotonicDeadlineNanos())) {
                return;
            }
            applyQueuedInputs();
            submitEligibleCommits();
        }
    }

    /**
     * Revocation without the chance to commit or cancel gracefully. Local state is dropped; the next assigned
     * position decides redelivery.
     */
    public void onPartitionsLost(Collection<TopicPartition> lost) {
        wakeupController.enterRebalanceCallback();
        try {
            for (var topicPartition : lost) {
                var state = partitions.remove(topicPartition);
                stagedCommitPositions.remove(topicPartition);
                if (state != null) {
                    intakeInputs.submit(
                        new ReplayIntakeInput.ForceGenerationCancellation(state.generation())
                    );
                }
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
