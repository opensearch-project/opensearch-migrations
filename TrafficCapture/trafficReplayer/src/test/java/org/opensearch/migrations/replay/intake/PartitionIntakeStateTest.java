/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.migrations.replay.intake;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import org.opensearch.migrations.replay.identity.CapturedConnectionId;
import org.opensearch.migrations.replay.identity.ConnectionProcessingId;
import org.opensearch.migrations.replay.identity.KafkaRecordId;
import org.opensearch.migrations.replay.identity.PartitionBatchRequestId;
import org.opensearch.migrations.replay.identity.PartitionGenerationId;
import org.opensearch.migrations.replay.identity.ReplayRequestId;

import org.apache.kafka.common.TopicPartition;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Covers the record-accounting cases in {@code kafkaLLD §17.1} against {@code §6} and {@code §8}.
 *
 * <p>Drives the per-generation state rather than one record's tracker, because every property in
 * {@code §17.1} is about how several records and several operations interact.
 */
class PartitionIntakeStateTest {
    private static final PartitionGenerationId GENERATION =
        new PartitionGenerationId(new TopicPartition("traffic", 2), 3);
    private static final KafkaRecordId RECORD = new KafkaRecordId(GENERATION, 41);
    private static final ConnectionProcessingId LIFETIME = new ConnectionProcessingId(
        GENERATION,
        new CapturedConnectionId("writer", "connection"),
        0
    );
    private static final RecordAssociationId ASSEMBLY =
        new RecordAssociationId.RequestAssembly(LIFETIME, 7);
    private static final RecordAssociationId REQUEST =
        new RecordAssociationId.Request(new ReplayRequestId(LIFETIME, 7));

    @Test
    void duplicateAssociationIsIdempotentAndEveryDistinctAssociationMustFinish() {
        var completions = new ArrayList<KafkaRecordId>();
        var intake = ownerState(completions);

        intake.registerRecord(RECORD);
        intake.associate(RECORD, ASSEMBLY);
        intake.associate(RECORD, ASSEMBLY);
        intake.associate(RECORD, REQUEST);
        intake.closeRecordToNewAssociations(RECORD);

        Assertions.assertTrue(completions.isEmpty());

        intake.associationFinished(RECORD, ASSEMBLY);
        Assertions.assertTrue(completions.isEmpty());
        intake.associationFinished(RECORD, REQUEST);

        Assertions.assertEquals(List.of(RECORD), completions);
    }

    @Test
    void relabelAcrossClosedRecordsIsAtomicAndDoesNotEmitTransientCompletion() {
        var earlier = new KafkaRecordId(GENERATION, 40);
        var completions = new ArrayList<KafkaRecordId>();
        var intake = ownerState(completions);

        intake.registerRecord(earlier);
        intake.associate(earlier, ASSEMBLY);
        intake.closeRecordToNewAssociations(earlier);
        intake.registerRecord(RECORD);
        intake.associate(RECORD, ASSEMBLY);

        intake.relabelAll(ASSEMBLY, REQUEST);

        Assertions.assertTrue(completions.isEmpty(), "relabel must not create an empty-association gap");

        intake.closeRecordToNewAssociations(RECORD);
        intake.associationFinished(REQUEST);
        Assertions.assertEquals(List.of(earlier, RECORD), completions);
    }

    @Test
    void closedRecordWithoutAssociationsCompletesExactlyOnce() {
        var completions = new ArrayList<KafkaRecordId>();
        var intake = ownerState(completions);

        intake.registerRecord(RECORD);
        intake.closeRecordToNewAssociations(RECORD);

        Assertions.assertEquals(List.of(RECORD), completions);
        Assertions.assertThrows(
            IllegalStateException.class,
            () -> intake.closeRecordToNewAssociations(RECORD)
        );
        Assertions.assertEquals(List.of(RECORD), completions);
    }

    @Test
    void aRecordTrackerIsNotRetiredWhenTheRequiredSourceSubmissionIsRejected() {
        var activeTrackers = new AtomicInteger();
        var retiredTrackers = new AtomicInteger();
        var intake = new PartitionIntakeState(
            GENERATION,
            () -> true,
            ignored -> {
                throw new IllegalStateException("source queue rejected required submission");
            },
            activeTrackers::addAndGet,
            retiredTrackers::incrementAndGet
        );

        intake.registerRecord(RECORD);

        Assertions.assertThrows(
            IllegalStateException.class,
            () -> intake.closeRecordToNewAssociations(RECORD)
        );
        Assertions.assertEquals(1, activeTrackers.get());
        Assertions.assertEquals(0, retiredTrackers.get());
    }

    @Test
    void mutationOffOwnerThreadIsRejected() {
        var intake = new PartitionIntakeState(
            GENERATION,
            () -> false,
            ignored -> {},
            ignored -> {},
            () -> {}
        );

        Assertions.assertThrows(IllegalStateException.class, () -> intake.registerRecord(RECORD));
    }

    /**
     * {@code §17.1}: "A record containing two requests emits no completion after only one request finishes."
     *
     * <p>{@code §8.3} is the rule: {@code RequestProcessingFinished} for one request "removes only that
     * request's association from each contributing record". A record holding a pipelined pair is the case
     * where getting that wrong commits past a request that has not been replayed.
     */
    @Test
    void aRecordCarryingTwoRequestsEmitsNoCompletionWhenOnlyOneFinishes() {
        var completions = new ArrayList<KafkaRecordId>();
        var intake = ownerState(completions);
        var first = new RecordAssociationId.Request(new ReplayRequestId(LIFETIME, 7));
        var second = new RecordAssociationId.Request(new ReplayRequestId(LIFETIME, 8));

        intake.registerRecord(RECORD);
        intake.associate(RECORD, first);
        intake.associate(RECORD, second);
        intake.closeRecordToNewAssociations(RECORD);

        intake.associationFinished(first);

        Assertions.assertTrue(
            completions.isEmpty(),
            () -> "the record still carries request 8; completions: " + completions
        );

        intake.associationFinished(second);
        Assertions.assertEquals(List.of(RECORD), completions);
    }

    /**
     * {@code §17.1}: "A request spanning three records keeps all three unfinished until tuple durability."
     *
     * <p>Durability is what {@code §4.1} makes {@code RequestProcessingFinished} report, and {@code §9.2}
     * says "the request's record associations remain until" it arrives. Nothing else releases them, which is
     * why closing each record to new associations is asserted to change nothing here.
     */
    @Test
    void aRequestSpanningThreeRecordsKeepsAllThreeUnfinishedUntilItsTupleIsDurable() {
        var completions = new ArrayList<KafkaRecordId>();
        var intake = ownerState(completions);
        var spanned = List.of(
            new KafkaRecordId(GENERATION, 40),
            new KafkaRecordId(GENERATION, 41),
            new KafkaRecordId(GENERATION, 42)
        );

        spanned.forEach(recordId -> {
            intake.registerRecord(recordId);
            intake.associate(recordId, ASSEMBLY);
        });
        intake.relabelAll(ASSEMBLY, REQUEST);
        spanned.forEach(intake::closeRecordToNewAssociations);

        Assertions.assertTrue(
            completions.isEmpty(),
            () -> "closing a record to new associations is not durability; completions: " + completions
        );

        intake.associationFinished(REQUEST);

        Assertions.assertEquals(
            spanned,
            completions,
            "every record the request spanned finishes, and only once the tuple is durable"
        );
    }

    /**
     * {@code §17.1}: "A record contributing source-response bytes remains unfinished until the tuple is
     * durable."
     *
     * <p>Distinct from the spanning case above because this record carries no part of the request:
     * {@code §9.2} associates "the containing record" with the applicable {@code ReplayRequestId} for every
     * source-response observation, so a response-only record is held by a request it does not contain.
     */
    @Test
    void aRecordContributingOnlyResponseBytesStaysUnfinishedUntilTheTupleIsDurable() {
        var completions = new ArrayList<KafkaRecordId>();
        var intake = ownerState(completions);
        var requestRecord = new KafkaRecordId(GENERATION, 40);
        var responseRecord = new KafkaRecordId(GENERATION, 55);

        intake.registerRecord(requestRecord);
        intake.associate(requestRecord, ASSEMBLY);
        intake.relabelAll(ASSEMBLY, REQUEST);
        intake.closeRecordToNewAssociations(requestRecord);
        // A later record, after reconstitution, carrying only response bytes for that request.
        intake.registerRecord(responseRecord);
        intake.associate(responseRecord, REQUEST);
        intake.closeRecordToNewAssociations(responseRecord);

        Assertions.assertTrue(completions.isEmpty());

        intake.associationFinished(REQUEST);

        Assertions.assertEquals(List.of(requestRecord, responseRecord), completions);
    }

    /**
     * {@code §17.1}: "An incomplete request that expires without reconstitution releases its records without
     * creating a tuple."
     *
     * <p>{@code §8.2}: "When an incomplete request expires before reconstitution, replay intake removes its
     * source-request assembly associations. No tuple is required." The absence of a tuple is observable here
     * as the record never having carried a {@code Request} association at all — no {@code ReplayRequestId} was
     * ever allocated for it, so nothing downstream could have been asked to produce one.
     */
    @Test
    void anIncompleteRequestThatExpiresReleasesItsRecordsWithoutEverBecomingARequest() {
        var completions = new ArrayList<KafkaRecordId>();
        var intake = ownerState(completions);
        var earlier = new KafkaRecordId(GENERATION, 40);

        intake.registerRecord(earlier);
        intake.associate(earlier, ASSEMBLY);
        intake.closeRecordToNewAssociations(earlier);
        intake.registerRecord(RECORD);
        intake.associate(RECORD, ASSEMBLY);
        intake.closeRecordToNewAssociations(RECORD);
        Assertions.assertTrue(completions.isEmpty());

        intake.associationFinished(ASSEMBLY);

        Assertions.assertEquals(List.of(earlier, RECORD), completions);
        Assertions.assertThrows(
            IllegalStateException.class,
            () -> intake.associationFinished(REQUEST),
            "no ReplayRequestId was ever allocated, so no tuple can be owed for these records"
        );
    }

    /**
     * {@code §6} holds one state per local partition generation, so a record from another generation reaching
     * it is an invariant failure rather than a record to accommodate.
     *
     * <p>The two generations here differ only in {@code localSequence} — the same Kafka topic-partition,
     * reassigned. {@code §2} says that sequence "is allocated by the replayer each time Kafka assigns that
     * partition locally", and accepting the older one's records would let a revoked generation's work finish
     * against its successor's accounting.
     */
    @Test
    void aRecordFromAnotherGenerationOfTheSamePartitionIsRejected() {
        var intake = ownerState(new ArrayList<>());
        var predecessor = new PartitionGenerationId(GENERATION.topicPartition(), 2);

        Assertions.assertThrows(
            IllegalStateException.class,
            () -> intake.registerRecord(new KafkaRecordId(predecessor, 41))
        );
    }

    @Test
    void exactHeartbeatAndRestartFallbackUseTheirDistinctBrokerTimeThresholds() {
        var configuration = new PartitionIntakeState.BrokerTimeConfiguration(100, 10, 50);
        var exact = ownerState(new ArrayList<>(), configuration);

        Assertions.assertEquals(
            PartitionIntakeState.WriterTimeTransition.EXACT_HEARTBEAT_STARTED,
            exact.observeHeartbeat("writer", 100, 25, 90L)
        );
        Assertions.assertInstanceOf(
            PartitionIntakeState.ExactHeartbeat.class,
            exact.expirationReferenceFor("writer")
        );
        Assertions.assertTrue(exact.writersExpiredAt(209).isEmpty());
        Assertions.assertEquals(List.of("writer"), exact.writersExpiredAt(210));

        var fallback = ownerState(new ArrayList<>(), configuration);
        Assertions.assertEquals(
            PartitionIntakeState.WriterTimeTransition.FIRST_TRAFFIC_FALLBACK_STARTED,
            fallback.observeTrafficWriter("writer", 100)
        );
        Assertions.assertInstanceOf(
            PartitionIntakeState.FirstTrafficFallback.class,
            fallback.expirationReferenceFor("writer")
        );
        Assertions.assertTrue(fallback.writersExpiredAt(219).isEmpty());
        Assertions.assertEquals(List.of("writer"), fallback.writersExpiredAt(220));
    }

    @Test
    void firstHeartbeatReplacesFallbackAndOnlyTimelyHeartbeatsAdvanceExactReference() {
        var intake = ownerState(
            new ArrayList<>(),
            new PartitionIntakeState.BrokerTimeConfiguration(100, 10, 50)
        );
        intake.observeTrafficWriter("writer", 100);

        Assertions.assertEquals(
            PartitionIntakeState.WriterTimeTransition.FALLBACK_REPLACED_BY_HEARTBEAT,
            intake.observeHeartbeat("writer", 500, 25, 490L)
        );
        Assertions.assertEquals(
            new PartitionIntakeState.ExactHeartbeat(500),
            intake.expirationReferenceFor("writer")
        );
        Assertions.assertEquals(
            PartitionIntakeState.WriterTimeTransition.TIMELY_HEARTBEAT_ACCEPTED,
            intake.observeHeartbeat("writer", 599, 25, 590L)
        );
        Assertions.assertEquals(
            PartitionIntakeState.WriterTimeTransition.LATE_HEARTBEAT_IGNORED,
            intake.observeHeartbeat("writer", 699, 25, 690L)
        );
        Assertions.assertEquals(
            new PartitionIntakeState.ExactHeartbeat(599),
            intake.expirationReferenceFor("writer")
        );
    }

    @Test
    void higherOffsetBeyondBackwardSkewIsFatalBeforeBecomingTimeEvidence() {
        var intake = ownerState(
            new ArrayList<>(),
            new PartitionIntakeState.BrokerTimeConfiguration(100, 10, 50)
        );

        intake.observeLogAppendTime(100);
        intake.observeLogAppendTime(90);
        Assertions.assertEquals(100, intake.greatestObservedLogAppendTime());
        Assertions.assertThrows(
            PartitionIntakeState.BrokerTimeViolation.class,
            () -> intake.observeLogAppendTime(89)
        );
        Assertions.assertEquals(100, intake.greatestObservedLogAppendTime());
    }

    @Test
    void retryBoundaryIsIrreversibleAndFinishedRequestsNeverBecomeRetryReadySupply() {
        var intake = ownerState(
            new ArrayList<>(),
            new PartitionIntakeState.BrokerTimeConfiguration(100, 10, 50)
        );
        var crossingRequest = new ReplayRequestId(LIFETIME, 20);
        intake.registerRequest(crossingRequest, 100);

        Assertions.assertTrue(intake.resolveRetryBoundaries(149).isEmpty());
        intake.connectionRequestFinished(crossingRequest);
        Assertions.assertEquals(
            List.of(crossingRequest),
            intake.resolveRetryBoundaries(150)
        );
        Assertions.assertFalse(intake.requestCanCountAsRetryReadySupply(crossingRequest));
        Assertions.assertFalse(
            intake.sourceResponseCompleted(crossingRequest),
            "a later final response must not replace the retry input frozen at the first crossing"
        );
        Assertions.assertThrows(
            IllegalStateException.class,
            () -> intake.sourceResponseCompleted(crossingRequest),
            "final input is also accepted exactly once"
        );

        var fastRequest = new ReplayRequestId(LIFETIME, 21);
        intake.registerRequest(fastRequest, 200);
        Assertions.assertTrue(intake.sourceResponseCompleted(fastRequest));
        Assertions.assertTrue(intake.resolveRetryBoundaries(300).isEmpty());
    }

    @Test
    void retryReadySupplyCountsOnlyResolvedUnfinishedTargetTurnsAndLeavesExactlyOnce() {
        var supplyDeltas = new ArrayList<Integer>();
        var intake = ownerState(
            new ArrayList<>(),
            new PartitionIntakeState.BrokerTimeConfiguration(100, 10, 50),
            supplyDeltas
        );
        var fast = new ReplayRequestId(LIFETIME, 30);
        var slow = new ReplayRequestId(LIFETIME, 31);
        var finishedBeforeResolution = new ReplayRequestId(LIFETIME, 32);

        intake.registerRequest(fast, 100);
        Assertions.assertEquals(0, intake.retryReadyRequestSupplyCount());
        Assertions.assertTrue(intake.demandOpen(2), "an unresolved retry input is not supply");

        Assertions.assertTrue(intake.sourceResponseCompleted(fast));
        Assertions.assertEquals(1, intake.retryReadyRequestSupplyCount());
        Assertions.assertTrue(
            intake.demandOpen(2),
            "a fast complete response satisfies one slot before the retry boundary, not the whole target"
        );

        intake.registerRequest(slow, 200);
        Assertions.assertTrue(intake.resolveRetryBoundaries(249).isEmpty());
        Assertions.assertEquals(1, intake.retryReadyRequestSupplyCount());
        Assertions.assertEquals(List.of(slow), intake.resolveRetryBoundaries(250));
        Assertions.assertEquals(2, intake.retryReadyRequestSupplyCount());
        Assertions.assertFalse(intake.demandOpen(2), "unavailable is a resolved retry input and counts");

        intake.registerRequest(finishedBeforeResolution, 300);
        intake.targetSupplyFinishedOrCancelled(finishedBeforeResolution);
        Assertions.assertEquals(
            List.of(finishedBeforeResolution),
            intake.resolveRetryBoundaries(350)
        );
        Assertions.assertEquals(
            2,
            intake.retryReadyRequestSupplyCount(),
            "a late retry input cannot re-add a finished or cancelled target turn"
        );
        Assertions.assertFalse(
            intake.sourceResponseCompleted(finishedBeforeResolution),
            "the late final response cannot replace the frozen unavailable retry input"
        );
        Assertions.assertEquals(2, intake.retryReadyRequestSupplyCount());

        intake.connectionRequestFinished(fast);
        Assertions.assertEquals(1, intake.retryReadyRequestSupplyCount());
        Assertions.assertThrows(
            IllegalStateException.class,
            () -> intake.connectionRequestFinished(fast),
            "a finished request may leave supply only once"
        );
        intake.targetSupplyFinishedOrCancelled(slow);
        Assertions.assertEquals(0, intake.retryReadyRequestSupplyCount());
        Assertions.assertEquals(List.of(1, 1, -1, -1), supplyDeltas);

        intake.removeRequest(fast);
        intake.removeRequest(slow);
        intake.removeRequest(finishedBeforeResolution);
    }

    @Test
    void batchEntitlementsAreOrderedAndNoNewRequestIsAllocatedWhileOneIsApplying() {
        var intake = ownerState(new ArrayList<>());
        var explicitOne = intake.requestNextBatchIfNeeded(2).orElseThrow();

        Assertions.assertEquals(1, explicitOne.localSequence());
        Assertions.assertEquals(PartitionIntakeState.BootstrapBatchState.PENDING, intake.bootstrapBatchState());
        Assertions.assertEquals(PartitionIntakeState.RequestedBatchState.REQUESTED, intake.requestedBatchState());

        var bootstrap = new PartitionBatchRequestId(GENERATION, 0);
        var bootstrapEntitlement = intake.beginApplyingBatch(bootstrap);
        Assertions.assertEquals(PartitionIntakeState.BatchEntitlement.BOOTSTRAP, bootstrapEntitlement);
        Assertions.assertTrue(
            intake.requestNextBatchIfNeeded(2).isEmpty(),
            "the overlapping explicit request remains the sole intake-issued request"
        );
        intake.finishApplyingBatch(bootstrap, bootstrapEntitlement);

        var explicitEntitlement = intake.beginApplyingBatch(explicitOne);
        Assertions.assertEquals(PartitionIntakeState.BatchEntitlement.EXPLICIT, explicitEntitlement);
        Assertions.assertTrue(
            intake.requestNextBatchIfNeeded(2).isEmpty(),
            "the next request cannot be issued while the current batch is being applied"
        );
        intake.finishApplyingBatch(explicitOne, explicitEntitlement);

        var explicitTwo = intake.requestNextBatchIfNeeded(2).orElseThrow();
        Assertions.assertEquals(2, explicitTwo.localSequence());
        Assertions.assertThrows(
            IllegalStateException.class,
            () -> intake.beginApplyingBatch(explicitOne),
            "a delivered batch must match the one current entitlement"
        );
    }

    @Test
    void oneBatchMayOvershootTheSupplyTargetWithoutAnOwnershipCap() {
        var deltas = new ArrayList<Integer>();
        var intake = ownerState(
            new ArrayList<>(),
            new PartitionIntakeState.BrokerTimeConfiguration(30_000, 0, 5_000),
            deltas
        );

        for (int request = 0; request < 128; request++) {
            var requestId = new ReplayRequestId(LIFETIME, 100 + request);
            intake.registerRequest(requestId, request);
            intake.sourceResponseIncomplete(requestId);
        }

        Assertions.assertEquals(128, intake.retryReadyRequestSupplyCount());
        Assertions.assertFalse(intake.demandOpen(2));
        Assertions.assertEquals(
            128,
            deltas.stream().filter(delta -> delta == 1).count(),
            "every resolved unfinished request remains supply; no ownership cap truncates the batch"
        );
    }

    @Test
    void randomizedSharedRecordsWaitForEveryAssociatedRequest() {
        var random = new Random(0x5A4B_C0DEL);
        for (int trial = 0; trial < 200; trial++) {
            var completions = new ArrayList<KafkaRecordId>();
            var intake = ownerState(completions);
            var requests = new ArrayList<RecordAssociationId>();
            int requestCount = 2 + random.nextInt(5);
            for (int requestIndex = 0; requestIndex < requestCount; requestIndex++) {
                requests.add(new RecordAssociationId.Request(new ReplayRequestId(LIFETIME, requestIndex)));
            }

            var expectedAssociations = new LinkedHashMap<KafkaRecordId, Set<RecordAssociationId>>();
            int recordCount = 1 + random.nextInt(20);
            for (int recordIndex = 0; recordIndex < recordCount; recordIndex++) {
                var record = new KafkaRecordId(GENERATION, 100L + recordIndex);
                intake.registerRecord(record);

                var shuffledRequests = new ArrayList<>(requests);
                Collections.shuffle(shuffledRequests, random);
                int associationCount = recordIndex == 0
                    ? requestCount
                    : 1 + random.nextInt(requestCount);
                var associations = Set.copyOf(
                    shuffledRequests.subList(0, associationCount)
                );
                expectedAssociations.put(record, associations);
                associations.forEach(request -> intake.associate(record, request));
                intake.closeRecordToNewAssociations(record);
            }
            Assertions.assertTrue(completions.isEmpty());

            var completionOrder = new ArrayList<>(requests);
            Collections.shuffle(completionOrder, random);
            var finishedRequests = new HashSet<RecordAssociationId>();
            for (var request : completionOrder) {
                intake.associationFinished(request);
                finishedRequests.add(request);
                var expectedCompleted = expectedAssociations.entrySet()
                    .stream()
                    .filter(entry -> finishedRequests.containsAll(entry.getValue()))
                    .map(java.util.Map.Entry::getKey)
                    .collect(java.util.stream.Collectors.toSet());
                Assertions.assertEquals(expectedCompleted, new HashSet<>(completions));
                Assertions.assertEquals(
                    completions.size(),
                    new HashSet<>(completions).size(),
                    "a record completion may be emitted only once"
                );
            }
            Assertions.assertEquals(expectedAssociations.keySet(), new HashSet<>(completions));
        }
    }

    private static PartitionIntakeState ownerState(List<KafkaRecordId> completions) {
        return ownerState(
            completions,
            new PartitionIntakeState.BrokerTimeConfiguration(30_000, 0, 5_000)
        );
    }

    private static PartitionIntakeState ownerState(
        List<KafkaRecordId> completions,
        PartitionIntakeState.BrokerTimeConfiguration brokerTimeConfiguration
    ) {
        return ownerState(completions, brokerTimeConfiguration, new ArrayList<>());
    }

    private static PartitionIntakeState ownerState(
        List<KafkaRecordId> completions,
        PartitionIntakeState.BrokerTimeConfiguration brokerTimeConfiguration,
        List<Integer> supplyDeltas
    ) {
        return new PartitionIntakeState(
            GENERATION,
            () -> true,
            completions::add,
            ignored -> {},
            () -> {},
            brokerTimeConfiguration,
            supplyDeltas::add
        );
    }
}
