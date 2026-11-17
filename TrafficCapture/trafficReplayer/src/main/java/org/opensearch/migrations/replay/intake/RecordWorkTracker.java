/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.migrations.replay.intake;

import java.util.LinkedHashSet;

import org.opensearch.migrations.replay.identity.KafkaRecordId;

import lombok.NonNull;

/**
 * Tracks when one observed Kafka record is safe to report as fully processed.
 *
 * <p>While the record is being applied, intake attaches every logical operation that depends on any of its
 * observations. The tracker is then closed to new associations, and each operation removes its association
 * when its durable lifecycle milestone is reached. The record becomes complete only when both conditions
 * hold: application has ended and no association remains.</p>
 *
 * <p>A completion latch makes that transition exactly-once. This is more than local bookkeeping: emitting
 * completion twice or before all dependencies finish could let the Kafka source advance its contiguous
 * commit prefix past data that replay still needs.</p>
 */
final class RecordWorkTracker {

    private final KafkaRecordId recordId;
    private final LinkedHashSet<RecordAssociationId> associationsByOperationId = new LinkedHashSet<>();
    private boolean openForNewAssociations = true;
    private boolean completionEmitted;

    RecordWorkTracker(@NonNull KafkaRecordId recordId) {
        this.recordId = recordId;
    }

    KafkaRecordId recordId() {
        return recordId;
    }

    /**
     * @return true if the association is new to this record, which is what tells the caller to add a
     *         reverse-index entry. {@code §8.1}: "For one record and one operation identity, replay intake
     *         creates at most one association even when several observations in the record contribute to
     *         that operation."
     */
    boolean associate(@NonNull RecordAssociationId association) {
        if (!openForNewAssociations) {
            throw new IllegalStateException("Kafka record is closed to new associations: " + recordId);
        }
        if (completionEmitted) {
            throw new IllegalStateException("Kafka record already emitted completion: " + recordId);
        }
        return associationsByOperationId.add(association);
    }

    /**
     * Adds an association to a record that may already be closed, which is what {@code §8.2}'s relabel
     * needs: the record is mid-transition rather than accepting new work, and refusing here would be the
     * gap that rule forbids.
     */
    boolean adoptRelabelled(@NonNull RecordAssociationId association) {
        if (completionEmitted) {
            throw new IllegalStateException("Kafka record already emitted completion: " + recordId);
        }
        return associationsByOperationId.add(association);
    }

    void removeAssociation(@NonNull RecordAssociationId association) {
        if (!associationsByOperationId.remove(association)) {
            throw new IllegalStateException(
                "Kafka record " + recordId + " has no unfinished association " + association
            );
        }
    }

    void closeToNewAssociations() {
        if (!openForNewAssociations) {
            throw new IllegalStateException("Kafka record was already closed: " + recordId);
        }
        openForNewAssociations = false;
    }

    /**
     * {@code §8.3}'s completion predicate, and the only place {@code completionEmitted} is set.
     *
     * <p>Emitting twice would give the Kafka source authority to advance its commit prefix past a record
     * still in use, so the latch is part of the predicate rather than a caller's responsibility.
     *
     * @return true if this call is the one that becomes {@code RecordProcessingFinished}
     */
    boolean claimCompletion() {
        if (openForNewAssociations || !associationsByOperationId.isEmpty() || completionEmitted) {
            return false;
        }
        completionEmitted = true;
        return true;
    }

    @Override
    public String toString() {
        return "record " + recordId + (openForNewAssociations ? " open" : " closed")
            + " awaiting " + associationsByOperationId;
    }
}
