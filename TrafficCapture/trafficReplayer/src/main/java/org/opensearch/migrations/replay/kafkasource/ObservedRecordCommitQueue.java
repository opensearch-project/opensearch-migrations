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
 * Computes the committable prefix of records observed during one partition generation.
 *
 * <p>Records are registered in increasing poll order and may finish out of order as replay work completes.
 * A completion marks its entry, then removes only the consecutive completed entries at the deque head; the
 * last removed offset plus one becomes the next safe Kafka position. Physical offset gaps are harmless
 * because the algorithm follows records actually returned by Kafka rather than assuming every numeric
 * offset was observed.</p>
 *
 * <p>A protocol-violating record can instead be marked permanently commit-ineligible. It remains at the
 * head as a barrier even after later records settle, ensuring no commit skips the poison offset and a restart
 * sees the same failure. All transitions are confined to the Kafka owner thread and reject duplicates or
 * cross-generation identities.</p>
 */
public final class ObservedRecordCommitQueue {
    // The warnings target compiler-generated members of this public result contract.
    @SuppressWarnings({"java:S100", "java:S1186"})
    public record Completion(
        @NonNull List<KafkaRecordId> newlyContiguousRecords,
        @NonNull OptionalLong nextCommitOffset
    ) {}

    // The warnings target compiler-generated members of this public diagnostic contract.
    @SuppressWarnings({"java:S100", "java:S1172", "java:S1186"})
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
        private boolean commitIneligible;

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
        if (entry.commitIneligible) {
            throw new IllegalStateException("Kafka record is commit-ineligible: " + recordId);
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
        return (int) observedRecords.stream()
            .filter(entry -> !entry.completed && !entry.commitIneligible)
            .count();
    }

    public int completedBehindHeadCount() {
        ownerThreadGuard.requireOwnerThread();
        return (int) observedRecords.stream().filter(entry -> entry.completed).count();
    }

    public int commitIneligibleCount() {
        ownerThreadGuard.requireOwnerThread();
        return (int) observedRecords.stream().filter(entry -> entry.commitIneligible).count();
    }

    /**
     * Marks the poison record terminal without advancing the contiguous prefix.
     *
     * <p>The entry remains in the deque as the permanent head barrier: only
     * {@link #recordProcessingFinished(KafkaRecordId)} removes records, and a protocol violation must stay
     * uncommitted so restart encounters it again.
     */
    public void markCommitIneligible(@NonNull KafkaRecordId recordId) {
        ownerThreadGuard.requireOwnerThread();
        requireGeneration(recordId);
        var entry = recordsById.get(recordId);
        if (entry == null) {
            throw new IllegalStateException(
                "Kafka record was not observed in active generation " + generation + ": " + recordId
            );
        }
        if (entry.completed || entry.commitIneligible) {
            throw new IllegalStateException(
                "Kafka record cannot become commit-ineligible from its current state: " + recordId
            );
        }
        entry.commitIneligible = true;
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

    // Renaming this public diagnostic accessor would break existing callers and tests.
    @SuppressWarnings("java:S1845")
    public Snapshot snapshot() {
        ownerThreadGuard.requireOwnerThread();
        var head = observedRecords.peekFirst();
        return new Snapshot(
            generation,
            observedRecords.size(),
            (int) observedRecords.stream()
                .filter(entry -> !entry.completed && !entry.commitIneligible)
                .count(),
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
