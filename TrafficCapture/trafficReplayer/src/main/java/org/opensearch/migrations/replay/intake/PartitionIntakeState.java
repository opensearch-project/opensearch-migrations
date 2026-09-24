/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.migrations.replay.intake;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import org.opensearch.migrations.replay.identity.KafkaRecordId;
import org.opensearch.migrations.replay.identity.PartitionGenerationId;
import org.opensearch.migrations.replay.lifecycle.OwnerThreadGuard;

import lombok.NonNull;

/**
 * Replay intake's state for one local partition generation — {@code kafkaLLD §6}.
 *
 * <p>One of these exists per {@link PartitionGenerationId}, created when {@code PartitionGenerationAssigned}
 * arrives and dropped whole when the generation's cleanup completes. That lifetime is why the record
 * trackers and their reverse index live here rather than in a longer-lived component: nothing belonging to a
 * revoked generation may outlive it, and a successor generation of the same partition starts empty.
 *
 * <p>Every map is changed only by the replay-intake thread, which {@link OwnerThreadGuard} enforces on each
 * mutator. A record completing on a Netty loop instead would race the Kafka source's commit prefix.
 *
 * <p>REBUILD-LIMBO-NOTE(G6): {@code §6} also lists {@code writerTimeStateByWriterNodeId} and
 * {@code unresolvedRetryBoundaries}. Both need types {@code §10.2} and {@code §11} define, and the
 * {@code §10.1} fatal backward-skew check belongs with them; {@link #observeLogAppendTime} is where it lands.
 * <p>REBUILD-LIMBO-NOTE(G7): {@code §6}'s {@code retryReadyRequestSupplyCount} and
 * {@code partitionBatchState = idle | requested | applying}, which are {@code §13}'s demand model.
 * <p>REBUILD-LIMBO-NOTE(G8): {@code §6}'s {@code cancellationState} and {@code GenerationCleanupTracker},
 * which are {@code §15.2} and {@code §15.3}.
 */
public final class PartitionIntakeState {

    private final PartitionGenerationId generation;
    private final OwnerThreadGuard ownerThreadGuard;
    /** Emits {@code RecordProcessingFinished} for a record whose work is done ({@code §7} step 9). */
    private final Consumer<KafkaRecordId> recordCompletionSink;

    private final Map<KafkaRecordId, RecordWorkTracker> recordTrackersByKafkaRecordId =
        new LinkedHashMap<>();
    /**
     * Which records each operation still holds.
     *
     * <p>Not named by {@code §6}, but {@code §8.3} requires the operation it serves: completion for one
     * request "removes only that request's association from each contributing record", and a request's
     * records are not otherwise enumerable. Kept exactly consistent with the forward map — an entry is
     * removed as soon as its last record drops the association, so a present key always means real
     * outstanding work.
     */
    private final Map<RecordAssociationId, LinkedHashSet<KafkaRecordId>> recordsByAssociation =
        new LinkedHashMap<>();

    private long greatestObservedLogAppendTime = Long.MIN_VALUE;

    public PartitionIntakeState(
        @NonNull PartitionGenerationId generation,
        @NonNull BooleanSupplier currentThreadIsOwner,
        @NonNull Consumer<KafkaRecordId> recordCompletionSink
    ) {
        this.generation = generation;
        this.ownerThreadGuard = new OwnerThreadGuard("replay intake " + generation, currentThreadIsOwner);
        this.recordCompletionSink = recordCompletionSink;
    }

    public PartitionGenerationId generation() {
        return generation;
    }

    // ------------------------------------------------------------------ record work tracking

    /** {@code §7} step 2: one tracker per accepted record, created before any observation is applied. */
    public void registerRecord(@NonNull KafkaRecordId recordId) {
        ownerThreadGuard.requireOwnerThread();
        requireSameGeneration(recordId);
        if (recordTrackersByKafkaRecordId.putIfAbsent(recordId, new RecordWorkTracker(recordId)) != null) {
            throw new IllegalStateException("Kafka record was already registered: " + recordId);
        }
    }

    public void associate(
        @NonNull KafkaRecordId recordId,
        @NonNull RecordAssociationId association
    ) {
        ownerThreadGuard.requireOwnerThread();
        var tracker = requireTracker(recordId);
        if (tracker.associate(association)) {
            addReverseAssociation(association, recordId);
        }
    }

    /**
     * Moves an incomplete assembly's associations to the request identity allocated for it
     * ({@code §8.2}, {@code §9.1} step 3).
     *
     * <p>Atomic with respect to completion: {@code §8.2} says relabeling "does not decrement the tracker and
     * create a gap", so no record is left with an empty association set part-way through. Each record gains
     * the new association before losing the old one, and closed records are relabeled too — a record that has
     * already stopped accepting new work still carries this request until its tuple is durable.
     */
    public void relabelAll(
        @NonNull RecordAssociationId oldAssociation,
        @NonNull RecordAssociationId newAssociation
    ) {
        ownerThreadGuard.requireOwnerThread();
        var associatedRecords = recordsByAssociation.get(oldAssociation);
        if (associatedRecords == null || associatedRecords.isEmpty()) {
            throw new IllegalStateException("No Kafka record has association " + oldAssociation);
        }
        if (oldAssociation.equals(newAssociation)) {
            return;
        }
        for (var recordId : List.copyOf(associatedRecords)) {
            var tracker = requireTracker(recordId);
            if (tracker.adoptRelabelled(newAssociation)) {
                addReverseAssociation(newAssociation, recordId);
            }
            tracker.removeAssociation(oldAssociation);
            removeReverseAssociation(oldAssociation, recordId);
        }
    }

    /**
     * {@code §8.3}: removes one operation's association from every record that carries it, and completes
     * each record that has nothing else outstanding.
     */
    public void associationFinished(@NonNull RecordAssociationId association) {
        ownerThreadGuard.requireOwnerThread();
        var associatedRecords = recordsByAssociation.get(association);
        if (associatedRecords == null || associatedRecords.isEmpty()) {
            throw new IllegalStateException("No Kafka record has unfinished association " + association);
        }
        List.copyOf(associatedRecords).forEach(recordId -> associationFinished(recordId, association));
    }

    /** The one-record case, for an operation that a single record contributed to. */
    public void associationFinished(
        @NonNull KafkaRecordId recordId,
        @NonNull RecordAssociationId association
    ) {
        ownerThreadGuard.requireOwnerThread();
        var tracker = requireTracker(recordId);
        tracker.removeAssociation(association);
        removeReverseAssociation(association, recordId);
        emitCompletionIfEligible(tracker);
    }

    /** {@code §7} steps 8 and 9: close the record, then complete it if nothing is outstanding. */
    public void closeRecordToNewAssociations(@NonNull KafkaRecordId recordId) {
        ownerThreadGuard.requireOwnerThread();
        var tracker = requireTracker(recordId);
        tracker.closeToNewAssociations();
        emitCompletionIfEligible(tracker);
    }

    public Set<RecordAssociationId> associations(@NonNull KafkaRecordId recordId) {
        ownerThreadGuard.requireOwnerThread();
        return requireTracker(recordId).associations();
    }

    public boolean recordCompletionEmitted(@NonNull KafkaRecordId recordId) {
        ownerThreadGuard.requireOwnerThread();
        return requireTracker(recordId).completionEmitted();
    }

    public List<KafkaRecordId> recordsFor(@NonNull RecordAssociationId association) {
        ownerThreadGuard.requireOwnerThread();
        return List.copyOf(recordsByAssociation.getOrDefault(association, new LinkedHashSet<>()));
    }

    // ------------------------------------------------------------------ broker time

    /**
     * {@code §6}'s {@code greatestObservedLogAppendTime}, advanced by {@code §7} step 3.
     *
     * <p>REBUILD-LIMBO-NOTE(G6): {@code §10.1}'s rule that a higher-offset record more than {@code S} below
     * this value is process-fatal is checked here, before any payload is applied.
     */
    public void observeLogAppendTime(long logAppendTimeMillis) {
        ownerThreadGuard.requireOwnerThread();
        greatestObservedLogAppendTime = Math.max(greatestObservedLogAppendTime, logAppendTimeMillis);
    }

    public long greatestObservedLogAppendTime() {
        ownerThreadGuard.requireOwnerThread();
        return greatestObservedLogAppendTime;
    }

    // ------------------------------------------------------------------ internals

    private void requireSameGeneration(KafkaRecordId recordId) {
        if (!generation.equals(recordId.generation())) {
            throw new IllegalStateException(
                "Kafka record " + recordId + " does not belong to generation " + generation
            );
        }
    }

    private RecordWorkTracker requireTracker(KafkaRecordId recordId) {
        var tracker = recordTrackersByKafkaRecordId.get(recordId);
        if (tracker == null) {
            throw new IllegalStateException("Unknown Kafka record: " + recordId);
        }
        return tracker;
    }

    private void addReverseAssociation(RecordAssociationId association, KafkaRecordId recordId) {
        recordsByAssociation.computeIfAbsent(association, ignored -> new LinkedHashSet<>()).add(recordId);
    }

    private void removeReverseAssociation(RecordAssociationId association, KafkaRecordId recordId) {
        var associatedRecords = recordsByAssociation.get(association);
        if (associatedRecords == null || !associatedRecords.remove(recordId)) {
            throw new IllegalStateException(
                "Missing reverse record association for " + association + " and " + recordId
            );
        }
        if (associatedRecords.isEmpty()) {
            recordsByAssociation.remove(association);
        }
    }

    private void emitCompletionIfEligible(RecordWorkTracker tracker) {
        if (tracker.claimCompletion()) {
            recordCompletionSink.accept(tracker.recordId());
        }
    }
}
