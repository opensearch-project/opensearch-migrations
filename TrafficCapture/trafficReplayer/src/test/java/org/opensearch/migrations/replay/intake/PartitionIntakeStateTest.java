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

import org.opensearch.migrations.replay.identity.CapturedConnectionId;
import org.opensearch.migrations.replay.identity.ConnectionProcessingId;
import org.opensearch.migrations.replay.identity.KafkaRecordId;
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

        Assertions.assertEquals(java.util.Set.of(ASSEMBLY, REQUEST), intake.associations(RECORD));
        Assertions.assertFalse(intake.recordCompletionEmitted(RECORD));

        intake.associationFinished(RECORD, ASSEMBLY);
        Assertions.assertFalse(intake.recordCompletionEmitted(RECORD));
        intake.associationFinished(RECORD, REQUEST);

        Assertions.assertTrue(intake.recordCompletionEmitted(RECORD));
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

        Assertions.assertEquals(java.util.Set.of(REQUEST), intake.associations(earlier));
        Assertions.assertEquals(java.util.Set.of(REQUEST), intake.associations(RECORD));
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
    void mutationOffOwnerThreadIsRejected() {
        var intake = new PartitionIntakeState(GENERATION, () -> false, ignored -> {});

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

        Assertions.assertEquals(Set.of(second), intake.associations(RECORD));
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
        return new PartitionIntakeState(GENERATION, () -> true, completions::add);
    }
}

