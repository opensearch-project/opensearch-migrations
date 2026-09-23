/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.migrations.replay.kafkasource;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;

import org.opensearch.migrations.replay.identity.KafkaRecordId;
import org.opensearch.migrations.replay.identity.PartitionGenerationId;
import org.opensearch.migrations.replay.lifecycle.OwnerThreadGuard;

import lombok.NonNull;

/**
 * Kafka-owner state for one partition generation's records in observed poll order.
 *
 * <p>Physical Kafka offsets may contain gaps. Commit eligibility therefore advances through the
 * deque of records actually observed by this consumer, never through assumed numeric offsets.
 */
public final class ObservedRecordCommitQueue {
    public record Completion(
        @NonNull List<KafkaRecordId> newlyContiguousRecords,
        @NonNull OptionalLong nextCommitOffset
    ) {}

    public record Snapshot(
        @NonNull PartitionGenerationId generation,
        int size,
        int unfinishedCount,
        @NonNull Optional<KafkaRecordId> headRecord,
        long greatestObservedOffset
    ) {}

    private static final class Entry {
        private final KafkaRecordId recordId;
        private boolean completed;

        private Entry(KafkaRecordId recordId) {
            this.recordId = recordId;
        }
    }

    private final PartitionGenerationId generation;
    private final OwnerThreadGuard ownerThreadGuard =
        new OwnerThreadGuard("observed record commit queue");
    private final Deque<Entry> observedRecords = new ArrayDeque<>();
    private final Map<KafkaRecordId, Entry> recordsById = new LinkedHashMap<>();
    private long greatestObservedOffset = -1;

    public ObservedRecordCommitQueue(@NonNull PartitionGenerationId generation) {
        this.generation = generation;
        ownerThreadGuard.guard(() -> {}).run();
    }

    public PartitionGenerationId generation() {
        return generation;
    }

    public void register(@NonNull KafkaRecordId recordId) {
        ownerThreadGuard.requireOwnerThread();
        requireGeneration(recordId);
        if (recordsById.containsKey(recordId)) {
            throw new IllegalStateException("Kafka record was already observed: " + recordId);
        }
        if (recordId.offset() <= greatestObservedOffset) {
            throw new IllegalStateException(
                "Kafka records must be registered in observed order for "
                    + generation
                    + ": prior="
                    + greatestObservedOffset
                    + ", next="
                    + recordId.offset()
            );
        }
        var entry = new Entry(recordId);
        observedRecords.addLast(entry);
        recordsById.put(recordId, entry);
        greatestObservedOffset = recordId.offset();
    }

    public Completion recordProcessingFinished(@NonNull KafkaRecordId recordId) {
        ownerThreadGuard.requireOwnerThread();
        requireGeneration(recordId);
        var entry = recordsById.get(recordId);
        if (entry == null) {
            throw new IllegalStateException(
                "Kafka record was not observed in active generation " + generation + ": " + recordId
            );
        }
        if (entry.completed) {
            throw new IllegalStateException("Kafka record completion was submitted twice: " + recordId);
        }
        entry.completed = true;

        var contiguous = new ArrayList<KafkaRecordId>();
        while (!observedRecords.isEmpty() && observedRecords.peekFirst().completed) {
            var completed = observedRecords.removeFirst();
            recordsById.remove(completed.recordId);
            contiguous.add(completed.recordId);
        }
        var nextCommitOffset = contiguous.isEmpty()
            ? OptionalLong.empty()
            : OptionalLong.of(contiguous.get(contiguous.size() - 1).offset() + 1);
        return new Completion(List.copyOf(contiguous), nextCommitOffset);
    }

    public int size() {
        ownerThreadGuard.requireOwnerThread();
        return observedRecords.size();
    }

    public boolean isEmpty() {
        ownerThreadGuard.requireOwnerThread();
        return observedRecords.isEmpty();
    }

    public int unfinishedCount() {
        ownerThreadGuard.requireOwnerThread();
        return (int) observedRecords.stream().filter(entry -> !entry.completed).count();
    }

    public OptionalLong headOffset() {
        ownerThreadGuard.requireOwnerThread();
        var head = observedRecords.peekFirst();
        return head == null ? OptionalLong.empty() : OptionalLong.of(head.recordId.offset());
    }

    public long greatestObservedOffset() {
        ownerThreadGuard.requireOwnerThread();
        return greatestObservedOffset;
    }

    public boolean hasAlreadyObserved(long offset) {
        ownerThreadGuard.requireOwnerThread();
        return greatestObservedOffset >= offset;
    }

    public Snapshot snapshot() {
        ownerThreadGuard.requireOwnerThread();
        var head = observedRecords.peekFirst();
        return new Snapshot(
            generation,
            observedRecords.size(),
            (int) observedRecords.stream().filter(entry -> !entry.completed).count(),
            head == null ? Optional.empty() : Optional.of(head.recordId),
            greatestObservedOffset
        );
    }

    private void requireGeneration(KafkaRecordId recordId) {
        if (!recordId.generation().equals(generation)) {
            throw new IllegalArgumentException(
                "Kafka record "
                    + recordId
                    + " does not belong to partition generation "
                    + generation
            );
        }
    }
}
