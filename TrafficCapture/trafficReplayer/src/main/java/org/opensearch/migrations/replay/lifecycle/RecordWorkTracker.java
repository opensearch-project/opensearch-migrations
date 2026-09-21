/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.migrations.replay.lifecycle;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import org.opensearch.migrations.replay.lifecycle.ReplayIdentity.KafkaRecordId;
import org.opensearch.migrations.replay.lifecycle.ReplayIdentity.RecordAssociationId;

import lombok.NonNull;

/**
 * Intake-owned whole-record association state.
 *
 * <p>Each registered Kafka record has an independent association set, close flag, and one-shot
 * completion latch. Completion is evidence only until the S4b commit-authority cutover.
 */
public final class RecordWorkTracker {
    public sealed interface Input extends ReplayIntakeInput permits AssociationFinished {}

    public record AssociationFinished(@NonNull RecordAssociationId association) implements Input {}

    private static final class TrackedRecord {
        private final KafkaRecordId id;
        private final LinkedHashSet<RecordAssociationId> associations = new LinkedHashSet<>();
        private boolean closedToNewAssociations;
        private boolean completionEmitted;

        private TrackedRecord(KafkaRecordId id) {
            this.id = id;
        }
    }

    private final Consumer<ReplayIntakeInput> ownerInputSink;
    private final Consumer<KafkaRecordId> completionListener;
    private final OwnerThreadGuard ownerThreadGuard;
    private final Map<KafkaRecordId, TrackedRecord> records = new LinkedHashMap<>();
    private final Map<RecordAssociationId, LinkedHashSet<KafkaRecordId>> recordsByAssociation =
        new LinkedHashMap<>();
    private final List<KafkaRecordId> completedRecords = new ArrayList<>();

    public RecordWorkTracker(
        @NonNull Consumer<ReplayIntakeInput> ownerInputSink,
        @NonNull BooleanSupplier currentThreadIsOwner,
        @NonNull Consumer<KafkaRecordId> completionListener
    ) {
        this.ownerInputSink = ownerInputSink;
        this.completionListener = completionListener;
        this.ownerThreadGuard = new OwnerThreadGuard("record work tracker", currentThreadIsOwner);
    }

    public void register(@NonNull KafkaRecordId recordId) {
        ownerThreadGuard.requireOwnerThread();
        if (records.putIfAbsent(recordId, new TrackedRecord(recordId)) != null) {
            throw new IllegalStateException("Kafka record was already registered: " + recordId);
        }
    }

    public void associate(
        @NonNull KafkaRecordId recordId,
        @NonNull RecordAssociationId association
    ) {
        ownerThreadGuard.requireOwnerThread();
        var record = requireRecord(recordId);
        if (record.closedToNewAssociations) {
            throw new IllegalStateException("Kafka record is closed to new associations: " + recordId);
        }
        if (record.completionEmitted) {
            throw new IllegalStateException("Kafka record already emitted completion: " + recordId);
        }
        if (record.associations.add(association)) {
            recordsByAssociation.computeIfAbsent(association, ignored -> new LinkedHashSet<>())
                .add(recordId);
        }
    }

    public void relabel(
        @NonNull KafkaRecordId recordId,
        @NonNull RecordAssociationId oldAssociation,
        @NonNull RecordAssociationId newAssociation
    ) {
        ownerThreadGuard.requireOwnerThread();
        var record = requireRecord(recordId);
        if (!record.associations.contains(oldAssociation)) {
            throw new IllegalStateException(
                "Kafka record " + recordId + " has no association " + oldAssociation
            );
        }
        if (oldAssociation.equals(newAssociation)) {
            return;
        }
        record.associations.remove(oldAssociation);
        removeReverseAssociation(oldAssociation, recordId);
        if (record.associations.add(newAssociation)) {
            recordsByAssociation.computeIfAbsent(newAssociation, ignored -> new LinkedHashSet<>())
                .add(recordId);
        }
    }

    public void relabelAll(
        @NonNull RecordAssociationId oldAssociation,
        @NonNull RecordAssociationId newAssociation
    ) {
        ownerThreadGuard.requireOwnerThread();
        var associatedRecords = recordsByAssociation.get(oldAssociation);
        if (associatedRecords == null || associatedRecords.isEmpty()) {
            throw new IllegalStateException("No Kafka record has association " + oldAssociation);
        }
        List.copyOf(associatedRecords).forEach(
            recordId -> relabel(recordId, oldAssociation, newAssociation)
        );
    }

    /**
     * Removes one record/operation association. This method deliberately returns no completion
     * authority; the one-shot listener is emitted internally when the closed record becomes empty.
     */
    public void associationFinished(
        @NonNull KafkaRecordId recordId,
        @NonNull RecordAssociationId association
    ) {
        ownerThreadGuard.requireOwnerThread();
        var record = requireRecord(recordId);
        if (!record.associations.remove(association)) {
            throw new IllegalStateException(
                "Kafka record " + recordId + " has no unfinished association " + association
            );
        }
        removeReverseAssociation(association, recordId);
        emitCompletionIfEligible(record);
    }

    public void associationFinished(@NonNull RecordAssociationId association) {
        ownerThreadGuard.requireOwnerThread();
        var associatedRecords = recordsByAssociation.get(association);
        if (associatedRecords == null || associatedRecords.isEmpty()) {
            throw new IllegalStateException("No Kafka record has unfinished association " + association);
        }
        List.copyOf(associatedRecords).forEach(
            recordId -> associationFinished(recordId, association)
        );
    }

    /**
     * Returns tuple/request completion to the intake owner without blocking the completing thread.
     */
    public void submitAssociationFinished(@NonNull RecordAssociationId association) {
        ownerInputSink.accept(new AssociationFinished(association));
    }

    public void closeToNewAssociations(@NonNull KafkaRecordId recordId) {
        ownerThreadGuard.requireOwnerThread();
        var record = requireRecord(recordId);
        if (record.closedToNewAssociations) {
            throw new IllegalStateException("Kafka record was already closed: " + recordId);
        }
        record.closedToNewAssociations = true;
        emitCompletionIfEligible(record);
    }

    public void apply(@NonNull Input input) {
        ownerThreadGuard.requireOwnerThread();
        switch (input) {
            case AssociationFinished finished -> associationFinished(finished.association());
        }
    }

    public Set<RecordAssociationId> associations(@NonNull KafkaRecordId recordId) {
        ownerThreadGuard.requireOwnerThread();
        return Set.copyOf(requireRecord(recordId).associations);
    }

    public boolean completionEmitted(@NonNull KafkaRecordId recordId) {
        ownerThreadGuard.requireOwnerThread();
        return requireRecord(recordId).completionEmitted;
    }

    public List<KafkaRecordId> completedRecords() {
        ownerThreadGuard.requireOwnerThread();
        return List.copyOf(completedRecords);
    }

    public Collection<KafkaRecordId> recordsFor(@NonNull RecordAssociationId association) {
        ownerThreadGuard.requireOwnerThread();
        return List.copyOf(recordsByAssociation.getOrDefault(association, new LinkedHashSet<>()));
    }

    private TrackedRecord requireRecord(KafkaRecordId recordId) {
        var record = records.get(recordId);
        if (record == null) {
            throw new IllegalStateException("Unknown Kafka record: " + recordId);
        }
        return record;
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

    private void emitCompletionIfEligible(TrackedRecord record) {
        if (!record.closedToNewAssociations
            || !record.associations.isEmpty()
            || record.completionEmitted) {
            return;
        }
        record.completionEmitted = true;
        completedRecords.add(record.id);
        completionListener.accept(record.id);
    }
}
