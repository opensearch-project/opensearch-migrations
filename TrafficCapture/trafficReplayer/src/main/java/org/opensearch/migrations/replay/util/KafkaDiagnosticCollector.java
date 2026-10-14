/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.migrations.replay.util;

import java.util.Optional;
import java.util.function.Supplier;

import org.opensearch.migrations.replay.identity.KafkaRecordId;
import org.opensearch.migrations.replay.kafkasource.KafkaSourceOwner;

import lombok.NonNull;

/**
 * Joins a context-owned Kafka record identity to Kafka owner state through immutable publications only.
 */
public final class KafkaDiagnosticCollector {
    // The warnings target compiler-generated members of this public diagnostic contract.
    @SuppressWarnings({"java:S100", "java:S1172", "java:S1186"})
    public record Snapshot(
        long uncertainPartitionCount,
        @NonNull Optional<KafkaSourceOwner.RecordDiagnostic> oldestRecord
    ) {}

    private final Supplier<KafkaSourceOwner.DiagnosticSnapshot> snapshotSupplier;

    public KafkaDiagnosticCollector(
        @NonNull Supplier<KafkaSourceOwner.DiagnosticSnapshot> snapshotSupplier
    ) {
        this.snapshotSupplier = snapshotSupplier;
    }

    public Snapshot collect(Optional<KafkaRecordId> oldestRecordId) {
        var sourceSnapshot = snapshotSupplier.get();
        return new Snapshot(
            sourceSnapshot.uncertainPartitionCount(),
            oldestRecordId.map(recordId ->
                sourceSnapshot.record(recordId).orElseGet(() ->
                    new KafkaSourceOwner.RecordDiagnostic(
                        recordId,
                        KafkaSourceOwner.RecordDiagnosticState.UNKNOWN
                    )
                )
            )
        );
    }
}
