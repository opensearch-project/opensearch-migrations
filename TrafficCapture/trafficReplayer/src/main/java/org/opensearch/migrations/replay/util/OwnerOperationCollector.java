/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.migrations.replay.util;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import org.opensearch.migrations.replay.lifecycle.OutstandingOperationRegistry;

import lombok.NonNull;

/**
 * Derives bounded aggregate and exact long-running detail from immutable owner-published snapshots.
 */
public final class OwnerOperationCollector {
    public static final Duration DEFAULT_LONG_RUNNING_THRESHOLD = Duration.ofSeconds(30);
    public static final int MAX_LONG_RUNNING_DETAILS = 16;

    // The warnings target compiler-generated members of this public diagnostic contract.
    @SuppressWarnings({"java:S100", "java:S1186"})
    public record Detail(
        @NonNull OutstandingOperationRegistry.Snapshot operation,
        @NonNull Duration elapsed
    ) {}

    // The warnings target compiler-generated members of this public diagnostic contract.
    @SuppressWarnings({"java:S100", "java:S1172", "java:S1186"})
    public record Snapshot(
        @NonNull Instant observedAt,
        int activeCount,
        int longRunningCount,
        @NonNull Map<OutstandingOperationRegistry.OperationType, Integer> activeByOperationType,
        @NonNull Map<OutstandingOperationRegistry.OperationType, Integer> longRunningByOperationType,
        @NonNull List<Detail> longRunningOperations
    ) {}

    private final Clock clock;
    private final Duration threshold;
    private final Supplier<List<OutstandingOperationRegistry.Snapshot>> snapshotSupplier;

    public OwnerOperationCollector(
        @NonNull Clock clock,
        @NonNull Supplier<List<OutstandingOperationRegistry.Snapshot>> snapshotSupplier
    ) {
        this(clock, DEFAULT_LONG_RUNNING_THRESHOLD, snapshotSupplier);
    }

    public OwnerOperationCollector(
        @NonNull Clock clock,
        @NonNull Duration threshold,
        @NonNull Supplier<List<OutstandingOperationRegistry.Snapshot>> snapshotSupplier
    ) {
        if (threshold.isZero() || threshold.isNegative()) {
            throw new IllegalArgumentException("threshold must be positive");
        }
        this.clock = clock;
        this.threshold = threshold;
        this.snapshotSupplier = snapshotSupplier;
    }

    public Snapshot collect() {
        var now = clock.instant();
        var snapshots = List.copyOf(snapshotSupplier.get());
        var longRunning = snapshots.stream()
            .map(snapshot -> new Detail(snapshot, elapsed(snapshot, now)))
            .filter(detail -> detail.elapsed().compareTo(threshold) >= 0)
            .sorted(
                Comparator.comparing(Detail::elapsed).reversed()
                    .thenComparing(detail -> detail.operation().ownerIdentity())
                    .thenComparingLong(detail -> detail.operation().operationId())
            )
            .toList();
        var activeByType = emptyOperationTypeCounts();
        snapshots.forEach(snapshot -> activeByType.merge(snapshot.operationType(), 1, Math::addExact));
        var longRunningByType = emptyOperationTypeCounts();
        longRunning.forEach(detail ->
            longRunningByType.merge(detail.operation().operationType(), 1, Math::addExact)
        );
        return new Snapshot(
            now,
            snapshots.size(),
            longRunning.size(),
            Map.copyOf(activeByType),
            Map.copyOf(longRunningByType),
            longRunning.stream().limit(MAX_LONG_RUNNING_DETAILS).toList()
        );
    }

    private static EnumMap<OutstandingOperationRegistry.OperationType, Integer>
    emptyOperationTypeCounts() {
        var counts = new EnumMap<
            OutstandingOperationRegistry.OperationType,
            Integer
        >(OutstandingOperationRegistry.OperationType.class);
        for (var operationType : OutstandingOperationRegistry.OperationType.values()) {
            counts.put(operationType, 0);
        }
        return counts;
    }

    private static Duration elapsed(OutstandingOperationRegistry.Snapshot snapshot, Instant now) {
        var elapsed = Duration.between(snapshot.startTime(), now);
        if (elapsed.isNegative()) {
            return Duration.ZERO;
        }
        return elapsed;
    }
}
