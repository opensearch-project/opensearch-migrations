/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.migrations.replay.lifecycle;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Random;
import java.util.Set;

import org.opensearch.migrations.replay.lifecycle.ReplayIdentity.ConnectionSessionKey;
import org.opensearch.migrations.replay.lifecycle.ReplayIdentity.KafkaRecordId;
import org.opensearch.migrations.replay.lifecycle.ReplayIdentity.ReplayRequestId;
import org.opensearch.migrations.replay.lifecycle.ReplayIdentity.SourceConnectionKey;
import org.opensearch.migrations.replay.lifecycle.ReplayIdentity.SourceRequestAssemblyId;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class RecordWorkTrackerTest {
    private static final KafkaRecordId RECORD = new KafkaRecordId("traffic", 2, 41, 3);
    private static final ConnectionSessionKey SESSION = new ConnectionSessionKey(
        new SourceConnectionKey("writer", "connection"),
        0,
        3
    );
    private static final SourceRequestAssemblyId ASSEMBLY = new SourceRequestAssemblyId(SESSION, 7);
    private static final ReplayRequestId REQUEST = new ReplayRequestId(SESSION, 7);

    @Test
    void duplicateAssociationIsIdempotentAndEveryDistinctAssociationMustFinish() {
        var completions = new ArrayList<KafkaRecordId>();
        var tracker = ownerTracker(new ArrayList<>(), completions);

        tracker.register(RECORD);
        tracker.associate(RECORD, ASSEMBLY);
        tracker.associate(RECORD, ASSEMBLY);
        tracker.associate(RECORD, REQUEST);
        tracker.closeToNewAssociations(RECORD);

        Assertions.assertEquals(java.util.Set.of(ASSEMBLY, REQUEST), tracker.associations(RECORD));
        Assertions.assertFalse(tracker.completionEmitted(RECORD));

        tracker.associationFinished(RECORD, ASSEMBLY);
        Assertions.assertFalse(tracker.completionEmitted(RECORD));
        tracker.associationFinished(RECORD, REQUEST);

        Assertions.assertTrue(tracker.completionEmitted(RECORD));
        Assertions.assertEquals(List.of(RECORD), completions);
    }

    @Test
    void relabelAcrossClosedRecordsIsAtomicAndDoesNotEmitTransientCompletion() {
        var earlier = new KafkaRecordId("traffic", 2, 40, 3);
        var completions = new ArrayList<KafkaRecordId>();
        var tracker = ownerTracker(new ArrayList<>(), completions);

        tracker.register(earlier);
        tracker.associate(earlier, ASSEMBLY);
        tracker.closeToNewAssociations(earlier);
        tracker.register(RECORD);
        tracker.associate(RECORD, ASSEMBLY);

        tracker.relabelAll(ASSEMBLY, REQUEST);

        Assertions.assertEquals(java.util.Set.of(REQUEST), tracker.associations(earlier));
        Assertions.assertEquals(java.util.Set.of(REQUEST), tracker.associations(RECORD));
        Assertions.assertTrue(completions.isEmpty(), "relabel must not create an empty-association gap");

        tracker.closeToNewAssociations(RECORD);
        tracker.associationFinished(REQUEST);
        Assertions.assertEquals(List.of(earlier, RECORD), completions);
    }

    @Test
    void closedRecordWithoutAssociationsCompletesExactlyOnce() {
        var completions = new ArrayList<KafkaRecordId>();
        var tracker = ownerTracker(new ArrayList<>(), completions);

        tracker.register(RECORD);
        tracker.closeToNewAssociations(RECORD);

        Assertions.assertEquals(List.of(RECORD), completions);
        Assertions.assertThrows(
            IllegalStateException.class,
            () -> tracker.closeToNewAssociations(RECORD)
        );
        Assertions.assertEquals(List.of(RECORD), completions);
    }

    @Test
    void requestCompletionReturnsAsAnImmutableOwnerInput() {
        var submitted = new ArrayList<ReplayIntakeInput>();
        var completions = new ArrayList<KafkaRecordId>();
        var tracker = ownerTracker(submitted, completions);
        tracker.register(RECORD);
        tracker.associate(RECORD, REQUEST);
        tracker.closeToNewAssociations(RECORD);

        tracker.submitAssociationFinished(REQUEST);

        Assertions.assertEquals(1, submitted.size());
        var input = Assertions.assertInstanceOf(
            RecordWorkTracker.AssociationFinished.class,
            submitted.get(0)
        );
        Assertions.assertEquals(REQUEST, input.association());
        Assertions.assertTrue(completions.isEmpty());

        tracker.apply(input);
        Assertions.assertEquals(List.of(RECORD), completions);
    }

    @Test
    void mutationOffOwnerThreadIsRejected() {
        var tracker = new RecordWorkTracker(ignored -> {}, () -> false, ignored -> {});

        Assertions.assertThrows(IllegalStateException.class, () -> tracker.register(RECORD));
    }

    @Test
    void randomizedSharedRecordsWaitForEveryAssociatedRequest() {
        var random = new Random(0x5A4B_C0DEL);
        for (int trial = 0; trial < 200; trial++) {
            var completions = new ArrayList<KafkaRecordId>();
            var tracker = ownerTracker(new ArrayList<>(), completions);
            var requests = new ArrayList<ReplayRequestId>();
            int requestCount = 2 + random.nextInt(5);
            for (int requestIndex = 0; requestIndex < requestCount; requestIndex++) {
                requests.add(new ReplayRequestId(SESSION, requestIndex));
            }

            var expectedAssociations = new LinkedHashMap<KafkaRecordId, Set<ReplayRequestId>>();
            int recordCount = 1 + random.nextInt(20);
            for (int recordIndex = 0; recordIndex < recordCount; recordIndex++) {
                var record = new KafkaRecordId("traffic", 2, 100L + recordIndex, 3);
                tracker.register(record);

                var shuffledRequests = new ArrayList<>(requests);
                Collections.shuffle(shuffledRequests, random);
                int associationCount = recordIndex == 0
                    ? requestCount
                    : 1 + random.nextInt(requestCount);
                var associations = Set.copyOf(
                    shuffledRequests.subList(0, associationCount)
                );
                expectedAssociations.put(record, associations);
                associations.forEach(request -> tracker.associate(record, request));
                tracker.closeToNewAssociations(record);
            }
            Assertions.assertTrue(completions.isEmpty());

            var completionOrder = new ArrayList<>(requests);
            Collections.shuffle(completionOrder, random);
            var finishedRequests = new HashSet<ReplayRequestId>();
            for (var request : completionOrder) {
                tracker.associationFinished(request);
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

    private static RecordWorkTracker ownerTracker(
        List<ReplayIntakeInput> submitted,
        List<KafkaRecordId> completions
    ) {
        return new RecordWorkTracker(submitted::add, () -> true, completions::add);
    }
}
