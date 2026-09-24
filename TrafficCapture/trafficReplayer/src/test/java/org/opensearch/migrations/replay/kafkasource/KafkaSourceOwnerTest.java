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
import org.opensearch.migrations.replay.intake.ReplayIntakeInputQueue;
import org.opensearch.migrations.replay.tracing.IKafkaConsumerContexts;
import org.opensearch.migrations.replay.tracing.RootReplayerContext;
import org.opensearch.migrations.testutils.CloseableLogSetup;
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

    private final InMemoryInstrumentationBundle telemetry = new InMemoryInstrumentationBundle(false, true);
    private final AtomicLong clockNanos = new AtomicLong();
    private final ReplayIntakeInputQueue intakeInputs = new ReplayIntakeInputQueue();
    private final RootReplayerContext rootContext =
        new RootReplayerContext(telemetry.openTelemetrySdk);
    /** Counts issuance, which is the only observable form of "a wakeup was not coalesced into a spent one". */
    private final java.util.concurrent.atomic.AtomicInteger wakeupsIssued =
        new java.util.concurrent.atomic.AtomicInteger();
    private final WakeupController wakeupController =
        new WakeupController(wakeupsIssued::incrementAndGet, rootContext);
    /**
     * Makes the next grace wait report its input only after the whole interval has elapsed.
     *
     * <p>Production's wait does this: {@code GraceIntervalWait.blockingOn} waits a slice sized by the time
     * remaining and returns true if input became available, so input arriving in that slice's last nanosecond
     * ends the wait with nothing left on the clock. A fake that only ever returns true with time to spare is
     * more permissive than the thing it stands in for, and puts "apply an input, then find no grace left" out
     * of reach.
     */
    private boolean nextWaitConsumesTheWholeInterval;
    private final KafkaSourceInputQueue sourceInputs = new KafkaSourceInputQueue(wakeupController);

    @AfterEach
    void closeTelemetry() {
        telemetry.close();
    }

    private KafkaSourceOwner ownerFor(PumpedKafkaSource port) {
        return new KafkaSourceOwner(
            port,
            sourceInputs,
            intakeInputs,
            wakeupController,
            GRACE,
            clockNanos::get,
            // A non-blocking wait that still honours the contract: false means the deadline has passed, so when
            // there is nothing to wait for it advances the injected clock to the deadline before saying so.
            // Returning false with time still on the clock would let a test claim it waited out the interval
            // when it did nothing of the kind -- the revocation test asserting that force cancellation follows
            // the grace period would have passed while cancelling immediately.
            deadline -> {
                // Deadline first, exactly as GraceIntervalWait.blockingOn does. Checking the queue first made
                // the fake more permissive than production and put "deadline passed with inputs still queued"
                // out of reach -- which is the state a commit-with-no-grace-left is reached from.
                if (deadline.hasPassed(clockNanos.get())) {
                    return false;
                }
                if (!sourceInputs.isEmpty()) {
                    if (nextWaitConsumesTheWholeInterval) {
                        nextWaitConsumesTheWholeInterval = false;
                        clockNanos.updateAndGet(now -> Math.max(now, deadline.monotonicDeadlineNanos()));
                    }
                    return true;
                }
                clockNanos.updateAndGet(now -> Math.max(now, deadline.monotonicDeadlineNanos()));
                return false;
            }
        );
    }

    /**
     * The fixture shares the owner's clock, so a scripted commit duration moves the owner's deadline. That is
     * what lets a grace-interval test reach its deadline without sleeping: time passes because a commit was
     * modelled as taking time, not because the test waited.
     */
    private PumpedKafkaSource pumpedSource(List<TopicPartition> partitions) {
        return new PumpedKafkaSource(partitions, clockNanos::addAndGet);
    }

    /** The port returns records without identity; the owner stamps the generation (kafkaLLD 5). */
    private static PolledKafkaRecord record(long offset) {
        var envelope = CaptureRecord.newBuilder()
            .setTrafficStream(TrafficStream.newBuilder().setConnectionId("c").setNumber(0).build())
            .build();
        return new PolledKafkaRecord(offset, 1_000L + offset, envelope.getSerializedSize(), envelope);
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
        var port = pumpedSource(List.of(PARTITION_0, PARTITION_1));
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
        var port = pumpedSource(List.of(PARTITION_0));
        var owner = ownerFor(port);
        var generation = assignAndGetGeneration(owner, port, PARTITION_0);
        sourceInputs.submit(new KafkaSourceInput.RequestNextPartitionBatch(
            new PartitionBatchRequestId(generation, 1)));
        port.scriptPoll(Map.of(PARTITION_0, List.of(record(10))));
        port.clearHistory();
        // Drained so the depth below starts at zero; assignment already put PartitionGenerationAssigned there.
        drainIntake();

        // One timeline across both sides. Each port call is stamped with how many inputs had reached intake at
        // that moment, so "paused before the batch reached intake" is a single ordered fact. Asserting the
        // port's history and the queue's contents separately would hold just as well with the two production
        // statements reversed, which is exactly the property. Sampling the queue from the port's listener keeps
        // this in the test rather than adding an observation hook to a production class.
        var events = new java.util.ArrayList<String>();
        port.onObservation(call -> events.add(call + " intake=" + intakeInputs.size()));

        owner.runOnce();

        Assertions.assertEquals(
            List.of("resume(traffic-0) intake=0", "poll->[traffic-0] intake=0", "pause(traffic-0) intake=0"),
            events,
            () -> "the partition must be paused while intake is still empty, so no further poll can fetch for"
                + " it while this batch is being applied; events: " + events
        );
        Assertions.assertEquals(
            1,
            drainIntake().stream().filter(ReplayIntakeInput.PartitionRecordBatch.class::isInstance).count(),
            "exactly one batch should reach intake, after the pause"
        );
        Assertions.assertTrue(
            port.isPaused(PARTITION_0),
            "the partition must still be paused once the batch has been submitted"
        );
    }

    /** §17.4: an empty poll leaves the outstanding batch request in place, and the partition resumed. */
    @Test
    void anEmptyPollCompletesNoRequestAndLeavesThePartitionReadable() {
        var port = pumpedSource(List.of(PARTITION_0));
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
        var port = pumpedSource(List.of(PARTITION_0, PARTITION_1));
        var owner = ownerFor(port);
        assignThroughPoll(owner, port, List.of(PARTITION_0, PARTITION_1));
        var generation0 = owner.partitionState(PARTITION_0).orElseThrow().generation();
        var generation1 = owner.partitionState(PARTITION_1).orElseThrow().generation();
        var request0 = new PartitionBatchRequestId(generation0, 1);
        var request1 = new PartitionBatchRequestId(generation1, 1);
        sourceInputs.submit(new KafkaSourceInput.RequestNextPartitionBatch(request0));
        sourceInputs.submit(new KafkaSourceInput.RequestNextPartitionBatch(request1));
        port.scriptPoll(Map.of(
            PARTITION_0, List.of(record(10)),
            PARTITION_1, List.of(record(20))
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
        var port = pumpedSource(List.of(PARTITION_0, PARTITION_1));
        var owner = ownerFor(port);
        assignThroughPoll(owner, port, List.of(PARTITION_0, PARTITION_1));
        var revokedGeneration0 = owner.partitionState(PARTITION_0).orElseThrow().generation();
        var generation1 = owner.partitionState(PARTITION_1).orElseThrow().generation();

        // Reached through the real revoke-then-reassign path rather than by setting the flag. Setting it
        // directly is what previously hid the gate being inert in production: beginGeneration derived
        // "was there a prior generation" from map membership, which is always false by the time it asks.
        port.scriptRebalanceDuringNextPoll(() -> owner.onPartitionsRevoked(List.of(PARTITION_0)));
        owner.runOnce();
        drainIntake();
        assignThroughPoll(owner, port, List.of(PARTITION_0));
        var generation0 = owner.partitionState(PARTITION_0).orElseThrow().generation();
        Assertions.assertNotEquals(revokedGeneration0, generation0, "reassignment must be a new generation");
        Assertions.assertTrue(
            owner.partitionState(PARTITION_0).orElseThrow().isPriorGenerationCleanupPending(),
            "a successor assigned while its predecessor's cleanup is outstanding must be gated"
        );
        drainIntake();
        port.clearHistory();
        sourceInputs.submit(new KafkaSourceInput.RequestNextPartitionBatch(
            new PartitionBatchRequestId(generation0, 1)));
        sourceInputs.submit(new KafkaSourceInput.RequestNextPartitionBatch(
            new PartitionBatchRequestId(generation1, 1)));
        port.scriptPoll(Map.of(
            PARTITION_0, List.of(record(10)),
            PARTITION_1, List.of(record(20))
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

        // The cleanup message names the generation that finished cleaning up -- the revoked one -- while the
        // gate lives on its successor. Matching them is the point: clearing by topic-partition alone would let
        // any generation's cleanup release any successor.
        sourceInputs.submit(new KafkaSourceInput.GenerationCleanupFinished(revokedGeneration0));
        port.scriptPoll(Map.of(PARTITION_0, List.of(record(10))));
        owner.runOnce();

        Assertions.assertEquals(
            1,
            drainIntake().stream().filter(ReplayIntakeInput.PartitionRecordBatch.class::isInstance).count(),
            "the previously blocked partition should deliver once cleanup finishes"
        );
    }

    /**
     * A cleanup completion for a generation the successor is not waiting on must not release its gate.
     *
     * <p>The gate was previously a boolean cleared by topic-partition, so any generation's cleanup released any
     * successor. That is unobservable while the gate is never set, which is why this case and the one above
     * have to be separate: the first proves the gate engages, this proves it discriminates.
     */
    @Test
    void aCleanupCompletionForADifferentGenerationDoesNotReleaseTheGate() {
        var port = pumpedSource(List.of(PARTITION_0));
        var owner = ownerFor(port);
        assignThroughPoll(owner, port, List.of(PARTITION_0));
        var firstGeneration = owner.partitionState(PARTITION_0).orElseThrow().generation();
        port.scriptRebalanceDuringNextPoll(() -> owner.onPartitionsRevoked(List.of(PARTITION_0)));
        owner.runOnce();
        assignThroughPoll(owner, port, List.of(PARTITION_0));
        var successor = owner.partitionState(PARTITION_0).orElseThrow();
        drainIntake();

        // A generation that never existed on this partition, standing in for a stale or duplicated message.
        sourceInputs.submit(new KafkaSourceInput.GenerationCleanupFinished(
            new PartitionGenerationId(PARTITION_0, 999)));
        owner.runOnce();

        Assertions.assertTrue(
            successor.isPriorGenerationCleanupPending(),
            "an unmatched cleanup completion must leave the gate closed"
        );

        sourceInputs.submit(new KafkaSourceInput.GenerationCleanupFinished(firstGeneration));
        owner.runOnce();

        Assertions.assertFalse(
            successor.isPriorGenerationCleanupPending(),
            "the matching cleanup completion must release it"
        );
    }

    /**
     * Cleanup can finish before the successor is assigned, because cleanup is fast and reassignment waits for a
     * poll. The successor must then not be gated at all.
     *
     * <p>Without this the obvious repair deadlocks: a gate set at assignment time on "was cleanup outstanding"
     * has nothing left to clear it, and the partition stays paused forever.
     */
    @Test
    void cleanupFinishingBeforeReassignmentLeavesTheSuccessorUngated() {
        var port = pumpedSource(List.of(PARTITION_0));
        var owner = ownerFor(port);
        assignThroughPoll(owner, port, List.of(PARTITION_0));
        var firstGeneration = owner.partitionState(PARTITION_0).orElseThrow().generation();
        port.scriptRebalanceDuringNextPoll(() -> owner.onPartitionsRevoked(List.of(PARTITION_0)));
        owner.runOnce();

        sourceInputs.submit(new KafkaSourceInput.GenerationCleanupFinished(firstGeneration));
        owner.runOnce();
        assignThroughPoll(owner, port, List.of(PARTITION_0));

        Assertions.assertFalse(
            owner.partitionState(PARTITION_0).orElseThrow().isPriorGenerationCleanupPending(),
            "a successor assigned after cleanup already finished must not be gated"
        );
    }

    /**
     * A commit callback that arrives after its generation was revoked and reassigned must change nothing.
     *
     * <p>This is the case a partition-keyed callback gets wrong in three ways at once: it would credit the
     * successor's retirement count for an operation the revoked generation submitted, re-stage a position the
     * successor never derived, and clear the successor's in-flight marker so a second operation could race it.
     * The fixture resolves commit callbacks <em>after</em> rebalance callbacks specifically so this is
     * reachable — resolving first made it impossible to express.
     */
    @Test
    void aCommitCallbackArrivingAfterReassignmentIsIgnored() {
        var port = pumpedSource(List.of(PARTITION_0));
        var owner = ownerFor(port);
        var firstGeneration = assignAndGetGeneration(owner, port, PARTITION_0);
        sourceInputs.submit(new KafkaSourceInput.RequestNextPartitionBatch(
            new PartitionBatchRequestId(firstGeneration, 1)));
        port.scriptPoll(Map.of(PARTITION_0, List.of(record(10))));
        owner.runOnce();
        sourceInputs.submit(new KafkaSourceInput.RecordProcessingFinished(
            new KafkaRecordId(firstGeneration, 10)));
        drainIntake();

        // Submits the commit for the first generation, then revokes and reassigns inside the same poll, so the
        // callback resolves against a partition whose generation has already been replaced.
        port.scriptRebalanceDuringNextPoll(() -> {
            owner.onPartitionsRevoked(List.of(PARTITION_0));
            owner.onPartitionsAssigned(List.of(PARTITION_0));
        });
        owner.runOnce();

        var successor = owner.partitionState(PARTITION_0).orElseThrow();
        Assertions.assertNotEquals(firstGeneration, successor.generation(), "expected a new generation");
        Assertions.assertEquals(
            0,
            successor.recordsCommitted(),
            "the successor must not be credited for the revoked generation's commit"
        );
        Assertions.assertTrue(
            owner.stagedCommitPosition(PARTITION_0).isEmpty(),
            "the revoked generation's position must not be re-staged onto its successor"
        );
    }

    /** §5.3: a batch request is refused once lifecycle state has permanently ended intake. */
    @Test
    void aBatchRequestIsRefusedAfterIntakeHasPermanentlyEnded() {
        var port = pumpedSource(List.of(PARTITION_0));
        var owner = ownerFor(port);
        var generation = assignAndGetGeneration(owner, port, PARTITION_0);
        var state = owner.partitionState(PARTITION_0).orElseThrow();
        state.endIntake();

        sourceInputs.submit(new KafkaSourceInput.RequestNextPartitionBatch(
            new PartitionBatchRequestId(generation, 1)));
        owner.runOnce();

        Assertions.assertTrue(
            state.outstandingRequest().isEmpty(),
            "a request accepted past the permanent boundary can never be satisfied, so it must be refused"
        );
        Assertions.assertFalse(state.isReadable(), "and the partition must stay unreadable");
    }

    /**
     * §17.4 case 18: a wakeup arriving between revocation and assignment postpones the assignment to a later
     * poll without losing it.
     *
     * <p>{@code §5.4}: "the assignment callback is postponed, not discarded". The wakeup lands after
     * {@code onPartitionsRevoked} has run and before {@code onPartitionsAssigned} does, which is the one
     * interleaving that can silently drop an assignment — the partition would then be owned by Kafka and
     * unknown to this owner, reading nothing forever.
     */
    @Test
    void aWakeupBetweenRevocationAndAssignmentPostponesTheAssignmentWithoutLosingIt() {
        var port = pumpedSource(List.of(PARTITION_0));
        var owner = ownerFor(port);
        var revokedGeneration = assignAndGetGeneration(owner, port, PARTITION_0);
        drainIntake();
        port.clearHistory();

        // The revoke half of the rebalance runs, then the poll is interrupted before the assign half.
        port.scriptRebalanceDuringNextPoll(() -> owner.onPartitionsRevoked(List.of(PARTITION_0)));
        port.scriptWakeupAfterNextRebalance();
        owner.runOnce();

        Assertions.assertTrue(
            owner.partitionState(PARTITION_0).isEmpty(),
            "the revoke half must have taken effect before the wakeup interrupted the poll"
        );

        // The next poll continues the rebalance, exactly as Kafka does.
        assignThroughPoll(owner, port, List.of(PARTITION_0));

        var successor = owner.partitionState(PARTITION_0)
            .orElseThrow(() -> new AssertionError("the assignment was lost rather than postponed"));
        Assertions.assertNotEquals(
            revokedGeneration,
            successor.generation(),
            "the postponed assignment must produce a new generation, not resurrect the revoked one"
        );
        Assertions.assertTrue(
            successor.isPriorGenerationCleanupPending(),
            "and it must still be gated on the revoked generation's cleanup"
        );
    }

    /**
     * A revocation commit does not overlap an asynchronous commit still in flight, and the records that
     * submission covered are credited exactly once.
     *
     * <p>An asynchronous submission cannot be taken back. Kafka guarantees its callback runs before the next
     * {@code commitSync} returns, so it resolves <em>during</em> any commit the revocation path issues — and
     * credits its own record count, which the second commit would then credit again. Forgetting it locally does
     * not cancel it; an earlier version of this code removed it from the in-flight map and called that a
     * reclaim, which double-counted. Nor can the callback be waited for, since it needs a poll that this
     * callback is preventing. So it is left to be the one operation, which is also what {@code §5.7}'s
     * one-at-a-time rule requires.
     *
     * <p>The fixture models Kafka's ordering guarantee, which is what makes the double-credit reachable here
     * rather than only in production.
     */
    @Test
    void revocationDoesNotOverlapAnInFlightCommitAndCreditsItOnce() throws Exception {
        var port = pumpedSource(List.of(PARTITION_0));
        var owner = ownerFor(port);
        var generation = assignAndGetGeneration(owner, port, PARTITION_0);
        sourceInputs.submit(new KafkaSourceInput.RequestNextPartitionBatch(
            new PartitionBatchRequestId(generation, 1)));
        port.scriptPoll(Map.of(PARTITION_0, List.of(record(10))));
        owner.runOnce();
        drainIntake();
        var state = owner.partitionState(PARTITION_0).orElseThrow();
        port.clearHistory();

        // One iteration does all three in the order production does: the completion stages a position,
        // submitLoopCommitIfEligible sends it asynchronously, and the poll that would resolve it instead
        // delivers the revocation first. The fixture runs rebalance callbacks before resolving commit
        // callbacks precisely so this state — a submission in flight while revocation begins — is reachable.
        sourceInputs.submit(new KafkaSourceInput.RecordProcessingFinished(
            new KafkaRecordId(generation, 10)));
        port.scriptRebalanceDuringNextPoll(() -> owner.onPartitionsRevoked(List.of(PARTITION_0)));
        owner.runOnce();

        var synchronousCommits = port.observations().stream()
            .filter(PumpedKafkaSource.CommitAttempted.class::isInstance)
            .toList();
        Assertions.assertTrue(
            synchronousCommits.isEmpty(),
            () -> "no synchronous commit may overlap the in-flight asynchronous one; history: " + port.history()
        );
        // Not credited: the submission's outcome is genuinely unknown at retirement, since its callback arrives
        // after the generation is gone, and crediting it would be a guess.
        Assertions.assertEquals(
            0,
            state.recordsCommitted(),
            () -> "an unresolved submission must not be credited; history: " + port.history()
        );
        // And therefore reported as a zero-commit retirement, which §15.4 and §9.5 require: they define exactly
        // two measurements and read zero as the stall signal. That conflates "committed nothing" with "outcome
        // unknown" -- a real gap, recorded as an open escalation in the register. The code follows the design as
        // written rather than inventing a third category, which is what red line 1 requires.
        Assertions.assertEquals(
            1,
            counterValue(IKafkaConsumerContexts.MetricNames.GENERATIONS_RETIRED_WITHOUT_COMMIT),
            "the design defines two measurements and reads zero as the signal, so this retirement reports zero"
        );
    }

    /** Sums a counter across its recorded points, so an assertion reads the value an operator would see. */
    private long counterValue(String metricName) {
        return telemetry.getFinishedMetrics().stream()
            .filter(metric -> metric.getName().equals(metricName))
            .flatMap(metric -> metric.getLongSumData().getPoints().stream())
            .mapToLong(point -> point.getValue())
            .sum();
    }

    /**
     * Asserts the operator-visible retirement diagnostic rather than only the mutable state that feeds it.
     */
    private static void assertRetirementLog(
        CloseableLogSetup logs,
        PartitionGenerationId generation,
        PartitionSourceState.CommitUncertainty uncertainty
    ) {
        var generationPrefix = "Retiring generation " + generation + ":";
        var uncertaintySuffix = "commitUncertainty=" + uncertainty;
        Assertions.assertTrue(
            logs.getLogEvents().stream()
                .anyMatch(message -> message.startsWith(generationPrefix) && message.endsWith(uncertaintySuffix)),
            () -> "expected retirement log for "
                + generation
                + " with "
                + uncertainty
                + "; logs="
                + logs.getLogEvents()
        );
    }

    /**
     * A generation that never became readable retires with no known commit uncertainty.
     *
     * <p>{@code kafkaLLD §15.4} explicitly requires this generation to report zero for both measurements, and
     * says the owner "draws no conclusion from them". So {@code NONE_OBSERVED} means only that no unresolved or
     * unknown outcome is known — it narrows the explanations for a zero without diagnosing one.
     */
    @Test
    void aGenerationThatNeverReadRetiresWithNoKnownUncertainty() throws Exception {
        try (var logs = new CloseableLogSetup(KafkaSourceOwner.class.getName())) {
            var port = pumpedSource(List.of(PARTITION_0));
            var owner = ownerFor(port);
            var generation = assignAndGetGeneration(owner, port, PARTITION_0);
            var state = owner.partitionState(PARTITION_0).orElseThrow();

            port.scriptRebalanceDuringNextPoll(() -> owner.onPartitionsRevoked(List.of(PARTITION_0)));
            owner.runOnce();

            Assertions.assertEquals(
                PartitionSourceState.CommitUncertainty.NONE_OBSERVED,
                state.commitUncertainty()
            );
            Assertions.assertEquals(0, state.recordsRead());
            Assertions.assertEquals(0, state.recordsCommitted());
            assertRetirementLog(logs, generation, PartitionSourceState.CommitUncertainty.NONE_OBSERVED);
        }
    }

    /**
     * An asynchronous submission still in flight when its generation retires is reported as such.
     *
     * <p>Its callback arrives after the generation is gone, so it cannot be credited — but it may well have
     * landed, which is why the zero it produces must not read as a stall.
     */
    @Test
    void anUnresolvedAsyncSubmissionIsReportedAtRetirement() throws Exception {
        try (var logs = new CloseableLogSetup(KafkaSourceOwner.class.getName())) {
            var port = pumpedSource(List.of(PARTITION_0));
            var owner = ownerFor(port);
            var generation = assignAndGetGeneration(owner, port, PARTITION_0);
            sourceInputs.submit(new KafkaSourceInput.RequestNextPartitionBatch(
                new PartitionBatchRequestId(generation, 1)));
            port.scriptPoll(Map.of(PARTITION_0, List.of(record(10))));
            owner.runOnce();
            drainIntake();
            var state = owner.partitionState(PARTITION_0).orElseThrow();

            // The completion stages a position, the loop submits it asynchronously, and the poll that would
            // resolve it delivers the revocation first -- so it is still in flight at retirement.
            sourceInputs.submit(new KafkaSourceInput.RecordProcessingFinished(
                new KafkaRecordId(generation, 10)));
            port.scriptRebalanceDuringNextPoll(() -> owner.onPartitionsRevoked(List.of(PARTITION_0)));
            owner.runOnce();

            Assertions.assertEquals(
                PartitionSourceState.CommitUncertainty.ASYNC_UNRESOLVED_AT_RETIREMENT,
                state.commitUncertainty()
            );
            Assertions.assertEquals(0, state.recordsCommitted());
            assertRetirementLog(
                logs, generation, PartitionSourceState.CommitUncertainty.ASYNC_UNRESOLVED_AT_RETIREMENT
            );
        }
    }

    /**
     * A synchronous revocation commit returning an unknown outcome is reported at retirement.
     *
     * <p>This is the case the in-flight check cannot see, because a synchronous submission never enters
     * {@code inFlightCommitPositions} — so it has to be marked explicitly. Removing that one line fails this
     * test and nothing else.
     */
    @Test
    void aSynchronousUnknownOutcomeIsReportedAtRetirement() throws Exception {
        try (var logs = new CloseableLogSetup(KafkaSourceOwner.class.getName())) {
            var port = pumpedSource(List.of(PARTITION_0));
            var owner = ownerFor(port);
            var generation = assignAndGetGeneration(owner, port, PARTITION_0);
            sourceInputs.submit(new KafkaSourceInput.RequestNextPartitionBatch(
                new PartitionBatchRequestId(generation, 1)));
            port.scriptPoll(Map.of(PARTITION_0, List.of(record(10))));
            owner.runOnce();
            drainIntake();
            var state = owner.partitionState(PARTITION_0).orElseThrow();

            port.scriptCommitOutcome(KafkaSourcePort.CommitOutcome.OUTCOME_UNKNOWN);
            port.scriptRebalanceDuringNextPoll(() -> {
                sourceInputs.submit(new KafkaSourceInput.RecordProcessingFinished(
                    new KafkaRecordId(generation, 10)));
                owner.onPartitionsRevoked(List.of(PARTITION_0));
            });
            owner.runOnce();

            Assertions.assertEquals(
                PartitionSourceState.CommitUncertainty.SYNC_OUTCOME_UNKNOWN,
                state.commitUncertainty(),
                () -> "history: " + port.history()
            );
            Assertions.assertEquals(0, state.recordsCommitted());
            assertRetirementLog(logs, generation, PartitionSourceState.CommitUncertainty.SYNC_OUTCOME_UNKNOWN);
        }
    }

    /**
     * An unknown outcome is a property of the operation, so it marks every partition in the batch — including
     * one that is not being revoked.
     *
     * <p>{@code kafkaLLD §5.7}: the outcomes "describe the operation, not individual partitions", and the client
     * reports one result without saying which positions were recorded. Marking only the revoked partition would
     * be exactly the per-partition inference §5.7 forbids.
     */
    @Test
    void aBatchedUnknownOutcomeMarksEveryPartitionInTheOperation() throws Exception {
        var port = pumpedSource(List.of(PARTITION_0, PARTITION_1));
        var owner = ownerFor(port);
        assignThroughPoll(owner, port, List.of(PARTITION_0, PARTITION_1));
        var generation0 = owner.partitionState(PARTITION_0).orElseThrow().generation();
        var generation1 = owner.partitionState(PARTITION_1).orElseThrow().generation();
        sourceInputs.submit(new KafkaSourceInput.RequestNextPartitionBatch(
            new PartitionBatchRequestId(generation0, 1)));
        sourceInputs.submit(new KafkaSourceInput.RequestNextPartitionBatch(
            new PartitionBatchRequestId(generation1, 1)));
        port.scriptPoll(Map.of(
            PARTITION_0, List.of(record(10)),
            PARTITION_1, List.of(record(20))
        ));
        owner.runOnce();
        drainIntake();
        var revokedState = owner.partitionState(PARTITION_0).orElseThrow();
        var retainedState = owner.partitionState(PARTITION_1).orElseThrow();

        port.scriptCommitOutcome(KafkaSourcePort.CommitOutcome.OUTCOME_UNKNOWN);
        port.scriptRebalanceDuringNextPoll(() -> {
            sourceInputs.submit(new KafkaSourceInput.RecordProcessingFinished(
                new KafkaRecordId(generation0, 10)));
            sourceInputs.submit(new KafkaSourceInput.RecordProcessingFinished(
                new KafkaRecordId(generation1, 20)));
            owner.onPartitionsRevoked(List.of(PARTITION_0));
        });
        owner.runOnce();

        Assertions.assertEquals(
            PartitionSourceState.CommitUncertainty.SYNC_OUTCOME_UNKNOWN,
            revokedState.commitUncertainty(),
            "the revoked partition was in the operation"
        );
        Assertions.assertEquals(
            PartitionSourceState.CommitUncertainty.SYNC_OUTCOME_UNKNOWN,
            retainedState.commitUncertainty(),
            "and so was the retained one, which the operation-level result covers just as much"
        );
    }

    /**
     * A retained partition's uncertainty clears once an acknowledged retry covers the records it restored.
     *
     * <p>The revoked partition cannot retry — `§5.7` has it discard rather than re-offer — so only the retained
     * one recovers. That asymmetry is the point: the diagnostic is not sticky for a partition that goes on to
     * prove its progress.
     */
    @Test
    void anAcknowledgedRetryClearsARetainedPartitionsUncertainty() throws Exception {
        try (var logs = new CloseableLogSetup(KafkaSourceOwner.class.getName())) {
            var port = pumpedSource(List.of(PARTITION_0, PARTITION_1));
            var owner = ownerFor(port);
            assignThroughPoll(owner, port, List.of(PARTITION_0, PARTITION_1));
            var generation0 = owner.partitionState(PARTITION_0).orElseThrow().generation();
            var generation1 = owner.partitionState(PARTITION_1).orElseThrow().generation();
            sourceInputs.submit(new KafkaSourceInput.RequestNextPartitionBatch(
                new PartitionBatchRequestId(generation0, 1)));
            sourceInputs.submit(new KafkaSourceInput.RequestNextPartitionBatch(
                new PartitionBatchRequestId(generation1, 1)));
            port.scriptPoll(Map.of(
                PARTITION_0, List.of(record(10)),
                PARTITION_1, List.of(record(20))
            ));
            owner.runOnce();
            drainIntake();
            var retainedState = owner.partitionState(PARTITION_1).orElseThrow();

            port.scriptCommitOutcome(KafkaSourcePort.CommitOutcome.OUTCOME_UNKNOWN);
            port.scriptRebalanceDuringNextPoll(() -> {
                sourceInputs.submit(new KafkaSourceInput.RecordProcessingFinished(
                    new KafkaRecordId(generation0, 10)));
                sourceInputs.submit(new KafkaSourceInput.RecordProcessingFinished(
                    new KafkaRecordId(generation1, 20)));
                owner.onPartitionsRevoked(List.of(PARTITION_0));
            });
            owner.runOnce();
            Assertions.assertEquals(
                PartitionSourceState.CommitUncertainty.SYNC_OUTCOME_UNKNOWN,
                retainedState.commitUncertainty(),
                "precondition: the retained partition starts uncertain"
            );

            // The unknown outcome re-staged its position and restored its records, so an acknowledgement of the
            // re-offered position covers them and the earlier uncertainty no longer explains anything.
            port.scriptCommitOutcome(KafkaSourcePort.CommitOutcome.ACKNOWLEDGED);
            owner.runOnce();

            Assertions.assertEquals(
                PartitionSourceState.CommitUncertainty.NONE_OBSERVED,
                retainedState.commitUncertainty()
            );
            Assertions.assertEquals(1, retainedState.recordsCommitted());

            port.scriptRebalanceDuringNextPoll(() -> owner.onPartitionsRevoked(List.of(PARTITION_1)));
            owner.runOnce();
            assertRetirementLog(logs, generation1, PartitionSourceState.CommitUncertainty.NONE_OBSERVED);
        }
    }

    /**
     * Cleanup completing <em>during</em> the grace interval must not leave a successor paused forever.
     *
     * <p>Intake sends {@code GenerationCleanupFinished} as soon as its tracker empties, which for a generation
     * with little in flight is immediately — so the completion can arrive inside the grace wait. The obligation
     * is therefore registered before graceful cancellation is submitted. Registering it at retirement instead
     * discarded such a completion as unmatched and then recorded the obligation anyway, so the successor waited
     * on a cleanup already reported and never sent again: a permanently paused partition, with no test failing.
     */
    @Test
    void cleanupCompletingDuringGraceDoesNotStrandTheSuccessor() throws Exception {
        var port = pumpedSource(List.of(PARTITION_0));
        var owner = ownerFor(port);
        var revokedGeneration = assignAndGetGeneration(owner, port, PARTITION_0);
        drainIntake();

        // Delivered from inside the callback, which is exactly when intake would send it.
        port.scriptRebalanceDuringNextPoll(() -> {
            sourceInputs.submit(new KafkaSourceInput.GenerationCleanupFinished(revokedGeneration));
            owner.onPartitionsRevoked(List.of(PARTITION_0));
        });
        owner.runOnce();

        assignThroughPoll(owner, port, List.of(PARTITION_0));
        var successor = owner.partitionState(PARTITION_0).orElseThrow();

        Assertions.assertNotEquals(revokedGeneration, successor.generation());
        Assertions.assertFalse(
            successor.isPriorGenerationCleanupPending(),
            "the predecessor's cleanup was reported during the grace interval, so the successor must not be"
                + " gated on a message that will never be sent again"
        );
    }


    /**
     * `§5.7`: "at most one commit operation is in flight at a time" — an invariant about the <em>operation</em>,
     * which retiring its partitions must not appear to satisfy.
     *
     * <p>The guard used to be derived from the per-partition in-flight map, which `retireGeneration` empties. So
     * when every partition of an outstanding operation was revoked, the map emptied while the operation was
     * still unresolved and the next loop submitted a second one — the broker-spam the section's rationale names.
     * A successor generation is what provides the second staged position here, which is why the predecessor's
     * cleanup is reported: otherwise the successor stays gated and never reads.
     */
    @Test
    void retiringEveryPartitionOfAnUnresolvedOperationDoesNotAdmitASecondCommit() throws Exception {
        var port = pumpedSource(List.of(PARTITION_0));
        var owner = ownerFor(port);
        var revoked = assignAndGetGeneration(owner, port, PARTITION_0);
        sourceInputs.submit(new KafkaSourceInput.RequestNextPartitionBatch(
            new PartitionBatchRequestId(revoked, 1)));
        port.scriptPoll(Map.of(PARTITION_0, List.of(record(10))));
        owner.runOnce();
        drainIntake();

        // The operation covers this partition alone, and is held unresolved for the rest of the test.
        sourceInputs.submit(new KafkaSourceInput.RecordProcessingFinished(new KafkaRecordId(revoked, 10)));
        port.scriptNeverResolveAsyncCommits();
        port.scriptRebalanceDuringNextPoll(() -> {
            // Cleanup reported inside the callback, so the successor is not gated and can read.
            sourceInputs.submit(new KafkaSourceInput.GenerationCleanupFinished(revoked));
            owner.onPartitionsRevoked(List.of(PARTITION_0));
        });
        owner.runOnce();
        drainIntake();

        assignThroughPoll(owner, port, List.of(PARTITION_0));
        var successor = owner.partitionState(PARTITION_0).orElseThrow();
        Assertions.assertFalse(successor.isPriorGenerationCleanupPending(), "precondition: successor can read");
        sourceInputs.submit(new KafkaSourceInput.RequestNextPartitionBatch(
            new PartitionBatchRequestId(successor.generation(), 1)));
        port.scriptPoll(Map.of(PARTITION_0, List.of(record(11))));
        owner.runOnce();
        sourceInputs.submit(new KafkaSourceInput.RecordProcessingFinished(
            new KafkaRecordId(successor.generation(), 11)));
        port.clearHistory();

        owner.runOnce();

        Assertions.assertTrue(
            port.history().stream().noneMatch(call -> call.startsWith("commitAsync")),
            () -> "a second operation was submitted while the first was still unresolved; history: "
                + port.history()
        );
    }


    /**
     * `procCommit:1308`: "If the generation has no unfinished work, the callback returns immediately."
     *
     * <p>The early return is what the design states. Skipping force cancellation is <strong>not</strong> — §9.2
     * step 7 makes accepting it what releases the callback — so this asserts both that the interval was not
     * waited out and that the submission still happened. An earlier version asserted the skip, which was a
     * design-silent question decided in code.
     */
    @Test
    void aGenerationWhoseCleanupCompletesEarlyReturnsWithoutWaitingOutTheInterval() throws Exception {
        var port = pumpedSource(List.of(PARTITION_0));
        var owner = ownerFor(port);
        var generation = assignAndGetGeneration(owner, port, PARTITION_0);
        drainIntake();
        var clockAtEntry = clockNanos.get();

        port.scriptRebalanceDuringNextPoll(() -> {
            sourceInputs.submit(new KafkaSourceInput.GenerationCleanupFinished(generation));
            owner.onPartitionsRevoked(List.of(PARTITION_0));
        });
        owner.runOnce();

        Assertions.assertTrue(
            clockNanos.get() - clockAtEntry < GRACE.toNanos(),
            () -> "the callback waited out the interval although cleanup was already reported; the wait advanced"
                + " the clock by " + (clockNanos.get() - clockAtEntry) + "ns of " + GRACE.toNanos()
        );
        var intake = drainIntake();
        Assertions.assertTrue(
            intake.stream().anyMatch(ReplayIntakeInput.GracefulGenerationCancellation.class::isInstance),
            () -> "graceful cancellation is what tells intake to stop admitting; " + intake
        );
        Assertions.assertTrue(
            intake.stream().anyMatch(ReplayIntakeInput.ForceGenerationCancellation.class::isInstance),
            () -> "force cancellation is still submitted; nothing in the design says an early return skips it; "
                + intake
        );
    }

    /** Plan A G2 exit: a poll failure is fatal, never an empty success. */
    @Test
    void aPollFailurePropagatesRatherThanBecomingAnEmptyPoll() {
        var port = pumpedSource(List.of(PARTITION_0));
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
        var port = pumpedSource(List.of(PARTITION_0));
        var owner = ownerFor(port);
        var generation = assignAndGetGeneration(owner, port, PARTITION_0);
        sourceInputs.submit(new KafkaSourceInput.RequestNextPartitionBatch(
            new PartitionBatchRequestId(generation, 1)));
        port.scriptPoll(Map.of(PARTITION_0, List.of(record(10))));
        owner.runOnce();
        drainIntake();
        port.clearHistory();

        // Finish a record and let the normal loop commit it. That commit is legitimate -- it happens before
        // the poll, not inside a callback -- so flushing it here is what isolates the callback's own window.
        sourceInputs.submit(new KafkaSourceInput.RecordProcessingFinished(
            new KafkaRecordId(generation, 10)));
        owner.runOnce();
        Assertions.assertTrue(
            port.history().contains("commitAsync{traffic-0=11}"),
            () -> "expected the ordinary loop commit before revocation; history: " + port.history()
        );
        drainIntake();
        port.clearHistory();

        // Nothing is staged and nothing is queued, so the callback waits out the interval and commits nothing.
        // That wait is the production mechanism rather than a sleep-and-hope: the assertion below fails if a
        // commit happens, so the interval passing is what is being measured, not assumed.
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

    /**
     * How the grace wait ended is observable, because the callback returns either way.
     *
     * <p>The pair of counters is what makes the grace ceiling tunable, which is why it is a command-line option:
     * every revocation ending early says the ceiling exceeds what the work needs, and every one reaching the
     * deadline says revocations are held for their whole interval. A run cannot tell those apart from the
     * outside — both end with the generation retired and force cancellation submitted.
     */
    @Test
    void howTheGraceWaitEndedIsCounted() throws Exception {
        var port = pumpedSource(List.of(PARTITION_0, PARTITION_1));
        var owner = ownerFor(port);
        assignThroughPoll(owner, port, List.of(PARTITION_0, PARTITION_1));
        var cleanEarly = owner.partitionState(PARTITION_0).orElseThrow().generation();

        // Cleanup reported from inside the callback, so the wait returns before the deadline.
        port.scriptRebalanceDuringNextPoll(() -> {
            sourceInputs.submit(new KafkaSourceInput.GenerationCleanupFinished(cleanEarly));
            owner.onPartitionsRevoked(List.of(PARTITION_0));
        });
        owner.runOnce();

        // Nothing reports cleanup for this one, so its wait runs the interval out.
        port.scriptRebalanceDuringNextPoll(() -> owner.onPartitionsRevoked(List.of(PARTITION_1)));
        owner.runOnce();

        Assertions.assertEquals(
            1,
            counterValue(IKafkaConsumerContexts.MetricNames.REVOCATIONS_CLEANED_BEFORE_DEADLINE),
            "the revocation whose cleanup arrived during the wait must be counted as ending early"
        );
        Assertions.assertEquals(
            1,
            counterValue(IKafkaConsumerContexts.MetricNames.REVOCATIONS_REACHING_DEADLINE),
            "the revocation that waited out its interval must be counted separately, or the pair says nothing"
        );
    }

    /**
     * A revocation must not take a retained partition's batch request down with it.
     *
     * <p>The grace wait is a downstream push and the rest of the processing model continues around it: a
     * request that arrives during the wait for a partition the rebalance is <em>keeping</em> is handled as it
     * normally would be, and is resumed and polled once the callback returns. A request for the partition
     * leaving is dropped instead, which is decidable rather than guessed because every input carries its
     * generation ({@code PartitionBatchRequestId}, {@code §2}).
     *
     * <p>The dropping half is proved by {@code aBatchRequestIsRefusedAfterIntakeHasPermanentlyEnded}, not here:
     * removing the ended-intake guard leaves this test passing, because the revoked generation retires moments
     * later and can no longer be resumed whether it accepted the request or not. This test is evidence about
     * the partition that stays.
     */
    @Test
    void aRetainedPartitionsBatchRequestSurvivesAnotherPartitionsRevocation() throws Exception {
        var port = pumpedSource(List.of(PARTITION_0, PARTITION_1));
        var owner = ownerFor(port);
        assignThroughPoll(owner, port, List.of(PARTITION_0, PARTITION_1));
        var leaving = owner.partitionState(PARTITION_0).orElseThrow().generation();
        var retained = owner.partitionState(PARTITION_1).orElseThrow().generation();
        port.clearHistory();

        port.scriptRebalanceDuringNextPoll(() -> {
            // Submitted from inside the callback, so both are applied while the grace wait is running.
            sourceInputs.submit(new KafkaSourceInput.RequestNextPartitionBatch(
                new PartitionBatchRequestId(leaving, 1)));
            sourceInputs.submit(new KafkaSourceInput.RequestNextPartitionBatch(
                new PartitionBatchRequestId(retained, 1)));
            owner.onPartitionsRevoked(List.of(PARTITION_0));
        });

        owner.runOnce();

        Assertions.assertTrue(
            owner.partitionState(PARTITION_0).isEmpty(),
            "the revoked generation retires, taking its dropped request with it"
        );
        Assertions.assertTrue(
            owner.partitionState(PARTITION_1).orElseThrow().outstandingRequest().isPresent(),
            "the retained partition's request must survive a revocation of a different partition"
        );

        // The next iteration is where the surviving request becomes a resume and a poll.
        port.scriptPoll(Map.of(PARTITION_1, List.of(record(20))));
        owner.runOnce();

        Assertions.assertTrue(
            port.history().contains("resume(" + PARTITION_1 + ")"),
            () -> "the retained partition should have been resumed for its request; history: " + port.history()
        );
        Assertions.assertFalse(
            port.history().contains("resume(" + PARTITION_0 + ")"),
            () -> "a partition in limbo must never be resumed for a dropped request; history: "
                + port.history()
        );
    }

    /**
     * A commit must not be started once the grace interval is gone.
     *
     * <p>{@code kafkaLLD §5.7}: "a commit that cannot finish within the remaining grace must not be started."
     * The window is real rather than theoretical — the wait returns because input arrived, which can be the
     * last nanosecond of the interval, and applying that input takes time of its own. Starting anyway also
     * charges every submitted partition an unknown outcome it did not earn, including partitions the rebalance
     * is retaining, which is what the second assertion holds.
     */
    @Test
    void noCommitIsStartedOnceTheGraceIntervalIsGone() throws Exception {
        var port = pumpedSource(List.of(PARTITION_0, PARTITION_1));
        var owner = ownerFor(port);
        assignThroughPoll(owner, port, List.of(PARTITION_0, PARTITION_1));
        var revoked = owner.partitionState(PARTITION_0).orElseThrow().generation();
        var retained = owner.partitionState(PARTITION_1).orElseThrow().generation();
        sourceInputs.submit(new KafkaSourceInput.RequestNextPartitionBatch(
            new PartitionBatchRequestId(revoked, 1)));
        sourceInputs.submit(new KafkaSourceInput.RequestNextPartitionBatch(
            new PartitionBatchRequestId(retained, 1)));
        port.scriptPoll(Map.of(
            PARTITION_0, List.of(record(10)),
            PARTITION_1, List.of(record(20))
        ));
        owner.runOnce();
        drainIntake();
        // Nothing is staged when the callback is entered, which is load-bearing: a position staged earlier
        // would be committed by the attempt made *before* the wait, with the whole interval still remaining,
        // and this test would then be asserting about that commit instead of the one after the wait.
        port.clearHistory();

        // An unknown outcome is what a commit given no time actually returns, so scripting it is what makes the
        // spurious-uncertainty consequence observable rather than hypothetical.
        port.scriptCommitOutcome(KafkaSourcePort.CommitOutcome.OUTCOME_UNKNOWN);
        nextWaitConsumesTheWholeInterval = true;
        port.scriptRebalanceDuringNextPoll(() -> {
            sourceInputs.submit(new KafkaSourceInput.RecordProcessingFinished(
                new KafkaRecordId(retained, 20)));
            owner.onPartitionsRevoked(List.of(PARTITION_0));
        });

        owner.runOnce();

        Assertions.assertTrue(
            port.history().stream().noneMatch(call -> call.startsWith("commitSync")),
            () -> "the grace interval was spent by the wait, so no commit may be started; history: "
                + port.history()
        );
        Assertions.assertEquals(
            PartitionSourceState.CommitUncertainty.NONE_OBSERVED,
            owner.partitionState(PARTITION_1).orElseThrow().commitUncertainty(),
            "a retained partition must not be charged an unknown outcome by a commit that never ran"
        );
    }

    /**
     * A wakeup interrupting the loop's asynchronous submission must not strand the one-in-flight slot.
     *
     * <p>{@code kafkaLLD §5.7} allows "at most one commit operation ... in flight at a time" and the slot is
     * released when a submission resolves. An asynchronous submission interrupted before it registers a callback
     * has no callback to resolve it, so the owner resolves it — {@code §5.7} makes a wakeup-interrupted commit
     * an unknown outcome, and an unknown outcome keeps a still-owned partition's position and offers it again.
     * Left unresolved the source never commits again for the life of the process.
     */
    @Test
    void aWakeupInterruptingTheLoopSubmissionReleasesTheInFlightSlot() {
        var port = pumpedSource(List.of(PARTITION_0));
        var owner = ownerFor(port);
        var generation = assignAndGetGeneration(owner, port, PARTITION_0);
        sourceInputs.submit(new KafkaSourceInput.RequestNextPartitionBatch(
            new PartitionBatchRequestId(generation, 1)));
        port.scriptPoll(Map.of(PARTITION_0, List.of(record(10))));
        owner.runOnce();
        drainIntake();
        port.clearHistory();

        port.scriptCommitAsyncWakeup();
        sourceInputs.submit(new KafkaSourceInput.RecordProcessingFinished(
            new KafkaRecordId(generation, 10)));

        owner.runOnce();
        owner.runOnce();

        Assertions.assertEquals(
            List.of("commitAsync{traffic-0=11}", "commitAsync{traffic-0=11}"),
            port.history().stream().filter(call -> call.startsWith("commitAsync")).toList(),
            "the interrupted submission must resolve, or the slot it holds blocks every later commit"
        );
    }

    /**
     * A wakeup that interrupts the revocation commit must not silence the wakeup the callback owes.
     *
     * <p>{@code kafkaLLD §5.4} has a submission during callback handling issued "when callback handling
     * finishes". The commit throws {@code WakeupException} for the wakeup that was outstanding when the poll
     * began, which spends it; if the controller still believes that wakeup is outstanding, the callback's exit
     * coalesces the deferred one into it and issues nothing. The next poll then waits out its whole timeout
     * with an input already queued.
     */
    @Test
    void aWakeupThatInterruptsTheRevocationCommitDoesNotSwallowTheCallbacksOwnWakeup() throws Exception {
        var port = pumpedSource(List.of(PARTITION_0));
        var owner = ownerFor(port);
        var generation = assignAndGetGeneration(owner, port, PARTITION_0);
        sourceInputs.submit(new KafkaSourceInput.RequestNextPartitionBatch(
            new PartitionBatchRequestId(generation, 1)));
        port.scriptPoll(Map.of(PARTITION_0, List.of(record(10))));
        owner.runOnce();
        drainIntake();
        port.clearHistory();
        wakeupsIssued.set(0);

        port.scriptCommitSyncWakeup();
        // Submitted from inside the commit, so this input's wakeup is necessarily deferred and can only be
        // issued on callback exit -- which is the moment that reads the flag the absorption clears.
        port.onObservation(call -> {
            if (call.startsWith("commitSync")) {
                sourceInputs.submit(new KafkaSourceInput.GenerationCleanupFinished(generation));
            }
        });
        port.scriptRebalanceDuringNextPoll(() -> {
            // Submitted while the poll is in progress and before the callback begins, so this one is issued
            // immediately and is the wakeup the commit goes on to absorb.
            sourceInputs.submit(new KafkaSourceInput.RecordProcessingFinished(
                new KafkaRecordId(generation, 10)));
            owner.onPartitionsRevoked(List.of(PARTITION_0));
        });

        owner.runOnce();

        Assertions.assertTrue(
            port.history().stream().anyMatch(call -> call.startsWith("commitSync")),
            () -> "precondition: the interrupted commit must have been attempted; history: " + port.history()
        );
        Assertions.assertEquals(
            2,
            wakeupsIssued.get(),
            "the wakeup deferred during the callback must be issued on its exit; it was coalesced into the one"
                + " the interrupted commit had already consumed"
        );
    }

    /**
     * Plan A G2 exit, the half nothing reached before: a commit staged while the grace interval is running is
     * submitted from inside the wait, synchronously, under a bound no larger than the interval that is left.
     *
     * <p>The assertion is on the <em>bound the owner passed</em>, not on elapsed time. An unbounded commit
     * would be a 60-second stall of every partition this consumer holds plus the whole group's rebalance, and
     * the deadline handed to the call is the only thing preventing it — so asserting the value makes a missing
     * bound a failure, where asserting wall-clock duration would pass against a fast broker with no bound at
     * all.
     *
     * <p>The test this replaced carried this name and never called {@code onPartitionsRevoked}; it asserted an
     * ordinary loop commit instead.
     */
    @Test
    void aCommitStagedDuringTheGraceIntervalIsSubmittedFromInsideTheWaitUnderABound() throws Exception {
        var port = pumpedSource(List.of(PARTITION_0));
        var owner = ownerFor(port);
        var generation = assignAndGetGeneration(owner, port, PARTITION_0);
        sourceInputs.submit(new KafkaSourceInput.RequestNextPartitionBatch(
            new PartitionBatchRequestId(generation, 1)));
        port.scriptPoll(Map.of(PARTITION_0, List.of(record(10))));
        owner.runOnce();
        drainIntake();
        port.clearHistory();

        // The completion is submitted from inside the callback, so the position stages during the grace wait
        // rather than before it. Submitting it earlier would let the ordinary loop commit it and prove nothing.
        // The scripted duration is what ends the wait: the commit "takes" the whole interval, so the deadline
        // passes because modelled work consumed it, not because the test slept.
        port.scriptCommitDuration(GRACE);
        port.scriptRebalanceDuringNextPoll(() -> {
            sourceInputs.submit(new KafkaSourceInput.RecordProcessingFinished(
                new KafkaRecordId(generation, 10)));
            owner.onPartitionsRevoked(List.of(PARTITION_0));
        });

        owner.runOnce();

        var boundedCommits = port.observations().stream()
            .filter(PumpedKafkaSource.CommitAttempted.class::isInstance)
            .map(PumpedKafkaSource.CommitAttempted.class::cast)
            .toList();
        Assertions.assertEquals(
            1,
            boundedCommits.size(),
            () -> "the position staged during the wait should be committed exactly once from inside it;"
                + " history: " + port.history()
        );
        Assertions.assertEquals(Map.of(PARTITION_0, 11L), boundedCommits.get(0).nextPositions());
        Assertions.assertTrue(
            boundedCommits.get(0).bound().compareTo(GRACE) <= 0,
            () -> "the commit must be bounded by the grace remaining, was " + boundedCommits.get(0).bound()
        );
        Assertions.assertTrue(
            port.history().stream().noneMatch(call -> call.startsWith("commitAsync")),
            () -> "a commit inside a rebalance callback must be synchronous, since an async callback needs a"
                + " later poll this generation never sees; history: " + port.history()
        );
        Assertions.assertTrue(
            owner.partitionState(PARTITION_0).isEmpty(),
            "the callback must still return and retire the generation"
        );
    }

    /** A commit staged before revocation is submitted by the ordinary loop, asynchronously. */
    @Test
    void aCommitStagedBeforeRevocationIsSubmittedByTheOrdinaryLoop() throws Exception {
        var port = pumpedSource(List.of(PARTITION_0));
        var owner = ownerFor(port);
        var generation = assignAndGetGeneration(owner, port, PARTITION_0);
        sourceInputs.submit(new KafkaSourceInput.RequestNextPartitionBatch(
            new PartitionBatchRequestId(generation, 1)));
        port.scriptPoll(Map.of(PARTITION_0, List.of(record(10))));
        owner.runOnce();
        sourceInputs.submit(new KafkaSourceInput.RecordProcessingFinished(
            new KafkaRecordId(generation, 10)));
        port.clearHistory();
        drainIntake();

        // Applying the completion stages offset 11; the next loop iteration submits it.
        owner.runOnce();

        Assertions.assertTrue(
            port.history().contains("commitAsync{traffic-0=11}"),
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
        var port = pumpedSource(List.of(PARTITION_0));
        var owner = ownerFor(port);
        var generation = assignAndGetGeneration(owner, port, PARTITION_0);
        sourceInputs.submit(new KafkaSourceInput.RequestNextPartitionBatch(
            new PartitionBatchRequestId(generation, 1)));
        port.scriptPoll(Map.of(PARTITION_0, List.of(record(10))));
        owner.runOnce();
        sourceInputs.submit(new KafkaSourceInput.RecordProcessingFinished(
            new KafkaRecordId(generation, 10)));
        port.scriptCommitOutcome(KafkaSourcePort.CommitOutcome.RETRIABLE);
        drainIntake();
        port.clearHistory();

        owner.runOnce();
        owner.runOnce();

        var commits = port.history().stream().filter(c -> c.startsWith("commit")).toList();
        Assertions.assertEquals(
            List.of("commitAsync{traffic-0=11}", "commitAsync{traffic-0=11}"),
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
        var port = pumpedSource(List.of(PARTITION_0, PARTITION_1, partition2));
        var owner = ownerFor(port);
        assignThroughPoll(owner, port, List.of(PARTITION_0, PARTITION_1, partition2));

        var generations = new java.util.LinkedHashMap<TopicPartition, PartitionGenerationId>();
        var records = new java.util.LinkedHashMap<TopicPartition, List<PolledKafkaRecord>>();
        for (var topicPartition : List.of(PARTITION_0, PARTITION_1, partition2)) {
            var generation = owner.partitionState(topicPartition).orElseThrow().generation();
            generations.put(topicPartition, generation);
            sourceInputs.submit(new KafkaSourceInput.RequestNextPartitionBatch(
                new PartitionBatchRequestId(generation, 1)));
            records.put(topicPartition, List.of(record(10)));
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
        port.scriptCommitOutcome(KafkaSourcePort.CommitOutcome.GENERATION_STALE);
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
