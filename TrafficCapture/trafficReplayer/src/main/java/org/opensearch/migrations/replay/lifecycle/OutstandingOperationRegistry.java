/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.migrations.replay.lifecycle;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.opensearch.migrations.replay.identity.ConnectionProcessingId;
import org.opensearch.migrations.replay.identity.PartitionGenerationId;
import org.opensearch.migrations.replay.identity.ReplayRequestId;

import io.netty.channel.EventLoop;
import lombok.NonNull;

/**
 * Event-loop-confined registry for correctness-linked asynchronous operations.
 *
 * <p>Registration precedes submission. An entry remains registered until the owning event loop
 * applies the typed completion, including any required receiver handling. Immutable snapshots are
 * published for activity diagnostics without exposing mutable owner state.</p>
 */
public final class OutstandingOperationRegistry {
    public enum OperationType {
        REQUEST_PREPARATION,
        PERMIT_ACQUISITION,
        TARGET_ATTEMPT,
        TARGET_WRITE_MILESTONE,
        RETRY_SOURCE_RESPONSE_WAIT,
        RETRY_TIMER,
        FINAL_SOURCE_RESPONSE_WAIT,
        TUPLE_DURABILITY,
        PHYSICAL_TUPLE_WRITE,
        CANCELLATION_CLEANUP,
        REQUIRED_DELIVERY,
        TARGET_CHANNEL_CLOSE
    }

    public enum WaitReason {
        SUBMITTING,
        PREPARING,
        WAITING_FOR_PERMIT,
        WAITING_FOR_TARGET,
        WAITING_FOR_RETRY_SOURCE_RESPONSE,
        WAITING_FOR_RETRY_TIME,
        WAITING_FOR_FINAL_SOURCE_RESPONSE,
        WAITING_FOR_TUPLE_DURABILITY,
        WAITING_FOR_RECEIVER,
        WAITING_FOR_CHANNEL_TEARDOWN,
        WAITING_FOR_CHANNEL_CLOSE
    }

    @FunctionalInterface
    public interface FatalHandler {
        void onFatal(Error failure);
    }

    public interface CountHook {
        CountHook NOOP = new CountHook() {
            @Override
            public void operationCountChanged(OperationType type, int typeCount, int totalCount) {}
        };

        void operationCountChanged(OperationType type, int typeCount, int totalCount);
    }

    public record Snapshot(
        long operationId,
        @NonNull PartitionGenerationId partitionGenerationId,
        @NonNull ConnectionProcessingId connectionProcessingId,
        ReplayRequestId replayRequestId,
        @NonNull OperationType operationType,
        @NonNull Instant startTime,
        Instant scheduledTargetTime,
        @NonNull WaitReason waitReason
    ) {}

    public static final class Registration {
        private final OutstandingOperationRegistry registry;
        private final long operationId;

        private Registration(OutstandingOperationRegistry registry, long operationId) {
            this.registry = registry;
            this.operationId = operationId;
        }

        public long operationId() {
            return operationId;
        }
    }

    private record Entry(
        PartitionGenerationId partitionGenerationId,
        ConnectionProcessingId connectionProcessingId,
        ReplayRequestId replayRequestId,
        OperationType operationType,
        Instant startTime,
        Instant scheduledTargetTime,
        WaitReason waitReason
    ) {
        Entry withWaitReason(WaitReason replacement) {
            return new Entry(
                partitionGenerationId,
                connectionProcessingId,
                replayRequestId,
                operationType,
                startTime,
                scheduledTargetTime,
                replacement
            );
        }

        Snapshot snapshot(long operationId) {
            return new Snapshot(
                operationId,
                partitionGenerationId,
                connectionProcessingId,
                replayRequestId,
                operationType,
                startTime,
                scheduledTargetTime,
                waitReason
            );
        }
    }

    private final String ownerIdentity;
    private final EventLoop eventLoop;
    private final Clock clock;
    private final FatalHandler fatalHandler;
    private final CountHook countHook;
    private final Map<Long, Entry> active = new LinkedHashMap<>();
    private volatile List<Snapshot> publishedSnapshots = List.of();
    private long nextOperationId = 1;
    private boolean failed;

    public OutstandingOperationRegistry(
        @NonNull String ownerIdentity,
        @NonNull EventLoop eventLoop,
        @NonNull Clock clock,
        @NonNull FatalHandler fatalHandler,
        @NonNull CountHook countHook
    ) {
        this.ownerIdentity = ownerIdentity;
        this.eventLoop = eventLoop;
        this.clock = clock;
        this.fatalHandler = fatalHandler;
        this.countHook = countHook;
    }

    public Registration register(
        @NonNull PartitionGenerationId partitionGenerationId,
        @NonNull ConnectionProcessingId connectionProcessingId,
        ReplayRequestId replayRequestId,
        @NonNull OperationType operationType,
        Instant scheduledTargetTime,
        @NonNull WaitReason waitReason
    ) {
        requireOwnerThread();
        if (failed) {
            var failure = new IllegalStateException(
                "operation registry is failed for " + ownerIdentity
            );
            reportFatal("registration after fatal transition", failure);
            throw failure;
        }
        var operationId = nextOperationId++;
        active.put(
            operationId,
            new Entry(
                partitionGenerationId,
                connectionProcessingId,
                replayRequestId,
                operationType,
                clock.instant(),
                scheduledTargetTime,
                waitReason
            )
        );
        publish(operationType);
        return new Registration(this, operationId);
    }

    public void updateWaitReason(
        @NonNull Registration registration,
        @NonNull WaitReason waitReason
    ) {
        requireOwnerThread();
        var entry = requireEntry(registration, "update wait reason");
        if (entry == null) {
            return;
        }
        active.put(registration.operationId, entry.withWaitReason(waitReason));
        publish(null);
    }

    public void complete(@NonNull Registration registration) {
        requireOwnerThread();
        var entry = requireEntry(registration, "apply typed completion");
        if (entry == null) {
            return;
        }
        active.remove(registration.operationId);
        publish(entry.operationType);
    }

    public List<Snapshot> snapshots() {
        return publishedSnapshots;
    }

    public int activeCount() {
        return publishedSnapshots.size();
    }

    private Entry requireEntry(Registration registration, String operation) {
        if (registration.registry != this) {
            reportFatal(
                operation,
                new IllegalArgumentException(
                    "operation registration belongs to a different owner registry"
                )
            );
            return null;
        }
        var entry = active.get(registration.operationId);
        if (entry == null) {
            reportFatal(
                operation,
                new IllegalStateException(
                    "missing or duplicate completion for operation "
                        + registration.operationId
                )
            );
        }
        return entry;
    }

    private void publish(OperationType changedType) {
        var snapshots = new ArrayList<Snapshot>(active.size());
        active.forEach((operationId, entry) -> snapshots.add(entry.snapshot(operationId)));
        publishedSnapshots = List.copyOf(snapshots);
        if (changedType != null) {
            var typeCount = 0;
            for (var entry : active.values()) {
                if (entry.operationType == changedType) {
                    typeCount++;
                }
            }
            try {
                countHook.operationCountChanged(changedType, typeCount, active.size());
            } catch (Throwable failure) {
                reportFatal("fixed-cardinality operation count hook", failure);
            }
        }
    }

    private void requireOwnerThread() {
        if (!eventLoop.inEventLoop()) {
            var failure = new IllegalStateException(
                "operation registry for "
                    + ownerIdentity
                    + " accessed outside its event loop"
            );
            reportFatal("event-loop confinement", failure);
            throw failure;
        }
    }

    private void reportFatal(String operation, Throwable cause) {
        if (eventLoop.inEventLoop()) {
            failed = true;
        }
        fatalHandler.onFatal(new Error(
            "Outstanding-operation registry failure during "
                + operation
                + " for "
                + ownerIdentity,
            cause
        ));
    }
}
