/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.migrations.replay.kafkasource;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import org.opensearch.migrations.replay.identity.KafkaRecordId;
import org.opensearch.migrations.replay.identity.PartitionBatchRequestId;
import org.opensearch.migrations.replay.identity.PartitionGenerationId;
import org.opensearch.migrations.replay.intake.ReplayIntakeInput;
import org.opensearch.migrations.replay.kafka.PumpedKafkaSource;
import org.opensearch.migrations.replay.lifecycle.ReplayIntakeInputQueue;
import org.opensearch.migrations.replay.tracing.KafkaSourceRootContext;
import org.opensearch.migrations.tracing.InMemoryInstrumentationBundle;
import org.opensearch.migrations.trafficcapture.protos.CaptureRecord;
import org.opensearch.migrations.trafficcapture.protos.TrafficStream;

import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Covers the Kafka-side cases in {@code kafkaLLD §17.4} and the exit properties Plan A names for G2, driving
 * {@link KafkaSourceOwner} through {@link PumpedKafkaSource}.
 *
 * <p>Deterministic throughout: no broker, no threads, no sleeps. The one property that cannot be shown this
 * way — that a wakeup shortens a genuinely blocking poll — is covered against a real broker in
 * {@code WakeupAgainstRealKafkaTest}.
 */
class KafkaSourceOwnerTest {

    private static final TopicPartition PARTITION_0 = new TopicPartition("traffic", 0);
    private static final TopicPartition PARTITION_1 = new TopicPartition("traffic", 1);
    private static final Duration GRACE = Duration.ofMillis(200);

    private final InMemoryInstrumentationBundle telemetry = new InMemoryInstrumentationBundle(false, false);
    private final AtomicLong clockNanos = new AtomicLong();
    private final ReplayIntakeInputQueue intakeInputs = new ReplayIntakeInputQueue();
    // REBUILD-LIMBO-NOTE(G3): becomes RootReplayerContext.
    private final KafkaSourceRootContext rootContext =
        new KafkaSourceRootContext(telemetry.openTelemetrySdk);
    private final WakeupController wakeupController = new WakeupController(() -> {}, rootContext);
    private final KafkaSourceInputQueue sourceInputs = new KafkaSourceInputQueue(wakeupController);

    @AfterEach
    void closeTelemetry() {
        telemetry.close();
    }

    private KafkaSourceOwner ownerFor(PumpedKafkaSource port) {
        return new KafkaSourceOwner(
            port, sourceInputs, intakeInputs, wakeupController, GRACE, clockNanos::get
        );
    }

    private static ApplicationKafkaRecord record(PartitionGenerationId generation, long offset) {
        var envelope = CaptureRecord.newBuilder()
            .setTrafficStream(TrafficStream.newBuilder().setConnectionId("c").setNumber(0).build())
            .build();
        return new ApplicationKafkaRecord(
            new KafkaRecordId(generation, offset), 1_000L + offset, envelope.getSerializedSize(), envelope
        );
    }

    private List<ReplayIntakeInput> drainIntake() {
        var drained = new java.util.ArrayList<ReplayIntakeInput>();
        while (intakeInputs.size() > 0) {
            try {
                drained.add(intakeInputs.take());
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return drained;
    }

    /**
     * Assigns through a poll, which is the only way it happens in production: Kafka delivers rebalance
     * callbacks from inside {@code poll()}, and the wakeup controller enforces that.
     */
    private void assignThroughPoll(
        KafkaSourceOwner owner,
        PumpedKafkaSource port,
        List<TopicPartition> partitions
    ) {
        port.scriptRebalanceDuringNextPoll(() -> owner.onPartitionsAssigned(partitions));
        owner.runOnce();
    }

    private PartitionGenerationId assignAndGetGeneration(
        KafkaSourceOwner owner,
        PumpedKafkaSource port,
        TopicPartition topicPartition
    ) {
        assignThroughPoll(owner, port, List.of(topicPartition));
        return owner.partitionState(topicPartition).orElseThrow().generation();
    }

    /** §17.4: {@code onPartitionsAssigned} pauses the complete resulting assignment before Kafka may fetch. */
    @Test
    void assignmentPausesEveryPartitionInTheResultingAssignmentNotJustTheNewOnes() {
        var port = new PumpedKafkaSource(List.of(PARTITION_0, PARTITION_1));
        var owner = ownerFor(port);

        // Only partition 0 is newly assigned, but Kafka does not preserve pause state across the change, so
        // partition 1 must be paused too.
        assignThroughPoll(owner, port, List.of(PARTITION_0));

        Assertions.assertTrue(port.isPaused(PARTITION_0), () -> "history: " + port.history());
        Assertions.assertTrue(port.isPaused(PARTITION_1), () -> "history: " + port.history());
        Assertions.assertTrue(
            port.history().containsAll(List.of("pause(traffic-0)", "pause(traffic-1)")),
            () -> "history: " + port.history()
        );
    }

    /** §17.4: a partition is paused before its returned batch is submitted to replay intake. */
    @Test
    void aPartitionIsPausedBeforeItsBatchReachesIntake() {
        var port = new PumpedKafkaSource(List.of(PARTITION_0));
        var owner = ownerFor(port);
        var generation = assignAndGetGeneration(owner, port, PARTITION_0);
        sourceInputs.submit(new KafkaSourceInput.RequestNextPartitionBatch(
            new PartitionBatchRequestId(generation, 1)));
        port.scriptPoll(Map.of(PARTITION_0, List.of(record(generation, 10))));
        port.clearHistory();

        owner.runOnce();

        var history = port.history();
        var resumeIndex = history.indexOf("resume(traffic-0)");
        var pollIndex = history.indexOf("poll->[traffic-0]");
        var pauseIndex = history.lastIndexOf("pause(traffic-0)");
        Assertions.assertTrue(resumeIndex >= 0 && pollIndex > resumeIndex, () -> "history: " + history);
        Assertions.assertTrue(
            pauseIndex > pollIndex,
            () -> "the partition was not paused after its records were returned; history: " + history
        );
        var batches = drainIntake().stream()
            .filter(ReplayIntakeInput.PartitionRecordBatch.class::isInstance)
            .count();
        Assertions.assertEquals(1, batches, "exactly one batch should reach intake");
        Assertions.assertTrue(
            port.isPaused(PARTITION_0),
            "the partition must still be paused once the batch has been submitted"
        );
    }

    /** §17.4: an empty poll leaves the outstanding batch request in place, and the partition resumed. */
    @Test
    void anEmptyPollCompletesNoRequestAndLeavesThePartitionReadable() {
        var port = new PumpedKafkaSource(List.of(PARTITION_0));
        var owner = ownerFor(port);
        var generation = assignAndGetGeneration(owner, port, PARTITION_0);
        var requestId = new PartitionBatchRequestId(generation, 1);
        sourceInputs.submit(new KafkaSourceInput.RequestNextPartitionBatch(requestId));
        port.scriptEmptyPoll();

        owner.runOnce();

        var state = owner.partitionState(PARTITION_0).orElseThrow();
        Assertions.assertEquals(requestId, state.outstandingRequest().orElseThrow(),
            "an empty poll must not consume the request");
        Assertions.assertTrue(state.isReadable());
        Assertions.assertFalse(port.isPaused(PARTITION_0), "the partition should stay resumed to keep polling");
        Assertions.assertTrue(drainIntake().stream()
            .noneMatch(ReplayIntakeInput.PartitionRecordBatch.class::isInstance),
            "an empty poll must not produce a batch");
    }

    /** §17.4: one poll can satisfy requests for several partitions, with no order defined between them. */
    @Test
    void onePollSatisfiesSeveralPartitionsIndependently() {
        var port = new PumpedKafkaSource(List.of(PARTITION_0, PARTITION_1));
        var owner = ownerFor(port);
        assignThroughPoll(owner, port, List.of(PARTITION_0, PARTITION_1));
        var generation0 = owner.partitionState(PARTITION_0).orElseThrow().generation();
        var generation1 = owner.partitionState(PARTITION_1).orElseThrow().generation();
        var request0 = new PartitionBatchRequestId(generation0, 1);
        var request1 = new PartitionBatchRequestId(generation1, 1);
        sourceInputs.submit(new KafkaSourceInput.RequestNextPartitionBatch(request0));
        sourceInputs.submit(new KafkaSourceInput.RequestNextPartitionBatch(request1));
        port.scriptPoll(Map.of(
            PARTITION_0, List.of(record(generation0, 10)),
            PARTITION_1, List.of(record(generation1, 20))
        ));

        owner.runOnce();

        var batches = drainIntake().stream()
            .filter(ReplayIntakeInput.PartitionRecordBatch.class::isInstance)
            .map(ReplayIntakeInput.PartitionRecordBatch.class::cast)
            .toList();
        Assertions.assertEquals(2, batches.size(), "one batch per satisfied request");
        Assertions.assertEquals(
            Set.of(request0, request1),
            batches.stream().map(ReplayIntakeInput.PartitionRecordBatch::requestId)
                .collect(java.util.stream.Collectors.toSet()),
            "each outstanding request gets its own batch; no order is defined between them"
        );
        Assertions.assertTrue(port.isPaused(PARTITION_0) && port.isPaused(PARTITION_1));
    }

    /**
     * Plan A G2 exit: one partition paused for prior-generation cleanup does not stall unrelated partitions.
     */
    @Test
    void aPartitionAwaitingCleanupDoesNotStallAnUnrelatedPartition() {
        var port = new PumpedKafkaSource(List.of(PARTITION_0, PARTITION_1));
        var owner = ownerFor(port);
        assignThroughPoll(owner, port, List.of(PARTITION_0, PARTITION_1));
        var generation0 = owner.partitionState(PARTITION_0).orElseThrow().generation();
        var generation1 = owner.partitionState(PARTITION_1).orElseThrow().generation();
        // Partition 0 is blocked on cleanup; partition 1 is not.
        owner.partitionState(PARTITION_0).orElseThrow().setPriorGenerationCleanupPending(true);
        sourceInputs.submit(new KafkaSourceInput.RequestNextPartitionBatch(
            new PartitionBatchRequestId(generation0, 1)));
        sourceInputs.submit(new KafkaSourceInput.RequestNextPartitionBatch(
            new PartitionBatchRequestId(generation1, 1)));
        port.scriptPoll(Map.of(
            PARTITION_0, List.of(record(generation0, 10)),
            PARTITION_1, List.of(record(generation1, 20))
        ));

        owner.runOnce();

        Assertions.assertTrue(port.isPaused(PARTITION_0), "cleanup-pending partition must stay paused");
        var batches = drainIntake().stream()
            .filter(ReplayIntakeInput.PartitionRecordBatch.class::isInstance)
            .map(ReplayIntakeInput.PartitionRecordBatch.class::cast)
            .toList();
        Assertions.assertEquals(1, batches.size(), () -> "history: " + port.history());
        Assertions.assertEquals(
            generation1,
            batches.get(0).requestId().generation(),
            "the unrelated partition must be delivered while the blocked one waits"
        );

        // Cleanup finishing clears only that reason, and then partition 0 reads.
        sourceInputs.submit(new KafkaSourceInput.GenerationCleanupFinished(generation0));
        port.scriptPoll(Map.of(PARTITION_0, List.of(record(generation0, 10))));
        owner.runOnce();

        Assertions.assertEquals(
            1,
            drainIntake().stream().filter(ReplayIntakeInput.PartitionRecordBatch.class::isInstance).count(),
            "the previously blocked partition should deliver once cleanup finishes"
        );
    }

    /** Plan A G2 exit: a poll failure is fatal, never an empty success. */
    @Test
    void aPollFailurePropagatesRatherThanBecomingAnEmptyPoll() {
        var port = new PumpedKafkaSource(List.of(PARTITION_0));
        var owner = ownerFor(port);
        var generation = assignAndGetGeneration(owner, port, PARTITION_0);
        sourceInputs.submit(new KafkaSourceInput.RequestNextPartitionBatch(
            new PartitionBatchRequestId(generation, 1)));
        var failure = new IllegalStateException("broker unreachable");
        port.scriptPollFailure(failure);

        var thrown = Assertions.assertThrows(IllegalStateException.class, owner::runOnce);

        Assertions.assertSame(failure, thrown, "the failure must reach the caller unchanged");
        Assertions.assertEquals(
            new PartitionBatchRequestId(generation, 1),
            owner.partitionState(PARTITION_0).orElseThrow().outstandingRequest().orElseThrow(),
            "a failed poll must not consume the outstanding request"
        );
    }

    /**
     * Plan A G2 exit: a commit attempted during revocation cannot hold the callback past the grace deadline.
     *
     * <p>Also the shape defect {@code D5} forbids — no blocking commit before any cancellation is delivered.
     * The ordering assertion is what distinguishes the two: graceful cancellation must reach intake first.
     */
    @Test
    void revocationDeliversCancellationBeforeCommittingAndReturnsAtTheDeadline() throws Exception {
        var port = new PumpedKafkaSource(List.of(PARTITION_0));
        var owner = ownerFor(port);
        var generation = assignAndGetGeneration(owner, port, PARTITION_0);
        sourceInputs.submit(new KafkaSourceInput.RequestNextPartitionBatch(
            new PartitionBatchRequestId(generation, 1)));
        port.scriptPoll(Map.of(PARTITION_0, List.of(record(generation, 10))));
        owner.runOnce();
        drainIntake();
        port.clearHistory();

        // Finish a record and let the normal loop commit it. That commit is legitimate -- it happens before
        // the poll, not inside a callback -- so flushing it here is what isolates the callback's own window.
        sourceInputs.submit(new KafkaSourceInput.RecordProcessingFinished(
            new KafkaRecordId(generation, 10)));
        owner.runOnce();
        Assertions.assertTrue(
            port.history().contains("commit{traffic-0=11}"),
            () -> "expected the ordinary loop commit before revocation; history: " + port.history()
        );
        drainIntake();
        port.clearHistory();

        // The deadline has already passed on the injected clock, so the callback takes its shortest path and
        // the test cannot hang however the ordering is wrong.
        clockNanos.set(GRACE.toNanos() * 2);
        port.scriptRebalanceDuringNextPoll(() -> owner.onPartitionsRevoked(List.of(PARTITION_0)));
        owner.runOnce();

        var intake = drainIntake();
        var gracefulIndex = indexOfType(intake, ReplayIntakeInput.GracefulGenerationCancellation.class);
        var forceIndex = indexOfType(intake, ReplayIntakeInput.ForceGenerationCancellation.class);
        Assertions.assertTrue(gracefulIndex >= 0, () -> "no graceful cancellation was sent: " + intake);
        Assertions.assertTrue(forceIndex > gracefulIndex,
            () -> "force cancellation must follow graceful, not replace it: " + intake);
        // D5 is a blocking commit at the top of the callback, before any cancellation has been delivered.
        // Nothing is staged now, so the callback must attempt no commit at all.
        Assertions.assertTrue(
            port.history().stream().noneMatch(call -> call.startsWith("commit")),
            () -> "the revocation callback attempted a commit with nothing staged, which is D5's shape;"
                + " history: " + port.history()
        );
        Assertions.assertTrue(
            owner.partitionState(PARTITION_0).isEmpty(),
            "revoked partition state should be gone once the callback returns"
        );
    }

    /** A commit staged before revocation is submitted as part of the wait, not before cancellation. */
    @Test
    void aCommitStagedDuringTheGraceIntervalIsSubmittedFromInsideTheWait() throws Exception {
        var port = new PumpedKafkaSource(List.of(PARTITION_0));
        var owner = ownerFor(port);
        var generation = assignAndGetGeneration(owner, port, PARTITION_0);
        sourceInputs.submit(new KafkaSourceInput.RequestNextPartitionBatch(
            new PartitionBatchRequestId(generation, 1)));
        port.scriptPoll(Map.of(PARTITION_0, List.of(record(generation, 10))));
        owner.runOnce();
        sourceInputs.submit(new KafkaSourceInput.RecordProcessingFinished(
            new KafkaRecordId(generation, 10)));
        port.clearHistory();
        drainIntake();

        // Applying the completion stages offset 11; the next loop iteration submits it.
        owner.runOnce();

        Assertions.assertTrue(
            port.history().contains("commit{traffic-0=11}"),
            () -> "the contiguous prefix should commit at offset+1; history: " + port.history()
        );
    }

    /**
     * A rejected commit tells intake nothing, and is reissued rather than abandoned while the partition is
     * still owned.
     *
     * <p>{@code kafkaLLD §5.7}'s no-retry rule is specifically that "after revocation, the old generation does
     * not retry or wait indefinitely for a commit" — it does not ask a still-owned partition to discard a
     * position it has already computed. Abandoning it would mean waiting for the next
     * {@code RecordProcessingFinished} to re-derive the same prefix, which head-of-line blocking can delay
     * indefinitely. Reissuing is paced by the loop: one attempt per iteration, each of which also polls.
     */
    @Test
    void aRejectedCommitTellsIntakeNothingAndIsReissuedOncePerIteration() {
        var port = new PumpedKafkaSource(List.of(PARTITION_0));
        var owner = ownerFor(port);
        var generation = assignAndGetGeneration(owner, port, PARTITION_0);
        sourceInputs.submit(new KafkaSourceInput.RequestNextPartitionBatch(
            new PartitionBatchRequestId(generation, 1)));
        port.scriptPoll(Map.of(PARTITION_0, List.of(record(generation, 10))));
        owner.runOnce();
        sourceInputs.submit(new KafkaSourceInput.RecordProcessingFinished(
            new KafkaRecordId(generation, 10)));
        port.scriptCommitOutcome(KafkaSourcePort.CommitOutcome.REJECTED);
        drainIntake();
        port.clearHistory();

        owner.runOnce();
        owner.runOnce();

        var commits = port.history().stream().filter(c -> c.startsWith("commit")).toList();
        Assertions.assertEquals(
            List.of("commit{traffic-0=11}", "commit{traffic-0=11}"),
            commits,
            () -> "expected one attempt per iteration, not a spin and not abandonment: " + port.history()
        );
        Assertions.assertTrue(drainIntake().isEmpty(), "a commit outcome must not travel back to intake");
        Assertions.assertTrue(
            owner.stagedCommitPosition(PARTITION_0).isPresent(),
            "the position stays staged while the partition is still owned"
        );
    }

    /**
     * A failed batch must not strand the partitions that were fine.
     *
     * <p>Three partitions stage a commit; the batch fails because one of them is no longer owned. The two
     * still owned keep their staged positions and commit on the next iteration, while the unowned one is
     * dropped — {@code kafkaLLD §5.7} forbids an old generation retrying. Without this, all three positions
     * would be discarded and only re-derived when the next {@code RecordProcessingFinished} advanced each
     * partition's prefix, which head-of-line blocking can delay indefinitely.
     */
    @Test
    void aFailedBatchRetainsStillOwnedPositionsAndDropsUnownedOnes() {
        var partition2 = new TopicPartition("traffic", 2);
        var port = new PumpedKafkaSource(List.of(PARTITION_0, PARTITION_1, partition2));
        var owner = ownerFor(port);
        assignThroughPoll(owner, port, List.of(PARTITION_0, PARTITION_1, partition2));

        var generations = new java.util.LinkedHashMap<TopicPartition, PartitionGenerationId>();
        var records = new java.util.LinkedHashMap<TopicPartition, List<ApplicationKafkaRecord>>();
        for (var topicPartition : List.of(PARTITION_0, PARTITION_1, partition2)) {
            var generation = owner.partitionState(topicPartition).orElseThrow().generation();
            generations.put(topicPartition, generation);
            sourceInputs.submit(new KafkaSourceInput.RequestNextPartitionBatch(
                new PartitionBatchRequestId(generation, 1)));
            records.put(topicPartition, List.of(record(generation, 10)));
        }
        port.scriptPoll(records);
        owner.runOnce();
        generations.forEach((topicPartition, generation) -> sourceInputs.submit(
            new KafkaSourceInput.RecordProcessingFinished(new KafkaRecordId(generation, 10))));
        drainIntake();

        // Partition 0 is revoked, which is what makes the batch fail. Revoking it also removes its state, so
        // the owner can tell it apart from the two it still holds.
        clockNanos.set(GRACE.toNanos() * 2);
        port.scriptRebalanceDuringNextPoll(() -> owner.onPartitionsRevoked(List.of(PARTITION_0)));
        port.scriptCommitOutcome(KafkaSourcePort.CommitOutcome.OWNERSHIP_ENDED_BEFORE_SUBMISSION);
        port.clearHistory();
        owner.runOnce();

        var failedAttempt = port.history().stream().filter(c -> c.startsWith("commit")).toList();
        Assertions.assertEquals(1, failedAttempt.size(), () -> "history: " + port.history());
        Assertions.assertTrue(
            owner.stagedCommitPosition(PARTITION_1).isPresent()
                && owner.stagedCommitPosition(partition2).isPresent(),
            "the still-owned partitions must keep their staged positions after a failed batch"
        );
        Assertions.assertTrue(
            owner.stagedCommitPosition(PARTITION_0).isEmpty(),
            "the revoked partition must be dropped, not retried"
        );

        // Next iteration: the retained positions are offered again, without the dropped one.
        port.scriptCommitOutcome(KafkaSourcePort.CommitOutcome.ACKNOWLEDGED);
        port.clearHistory();
        owner.runOnce();

        var retry = port.history().stream().filter(c -> c.startsWith("commit")).toList();
        Assertions.assertEquals(1, retry.size(), () -> "expected one retry commit: " + port.history());
        Assertions.assertTrue(
            retry.get(0).contains("traffic-1=11") && retry.get(0).contains("traffic-2=11"),
            () -> "both still-owned positions should be reissued: " + retry
        );
        Assertions.assertFalse(retry.get(0).contains("traffic-0"),
            () -> "the revoked partition must not reappear: " + retry);
        Assertions.assertTrue(
            owner.stagedCommitPosition(PARTITION_1).isEmpty()
                && owner.stagedCommitPosition(partition2).isEmpty(),
            "an acknowledged commit clears what it committed"
        );
    }

    private static int indexOfType(List<ReplayIntakeInput> inputs, Class<?> type) {
        for (var i = 0; i < inputs.size(); i++) {
            if (type.isInstance(inputs.get(i))) {
                return i;
            }
        }
        return -1;
    }
}
