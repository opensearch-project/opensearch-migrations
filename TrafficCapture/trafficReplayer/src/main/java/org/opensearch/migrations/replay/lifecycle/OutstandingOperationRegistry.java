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
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

import org.opensearch.migrations.replay.identity.ConnectionProcessingId;
import org.opensearch.migrations.replay.identity.KafkaRecordId;
import org.opensearch.migrations.replay.identity.PartitionGenerationId;
import org.opensearch.migrations.replay.identity.ReplayRequestId;

import io.netty.channel.EventLoop;
import lombok.NonNull;

/**
 * Tracks asynchronous work whose completion is required for a replay owner to make progress or terminate.
 *
 * <p>An operation is registered before submission and remains active until its typed result has been applied
 * on the owning event loop, not merely until an external future resolves. Stable operation ids and explicit
 * wait reasons make duplicate, stale, or missing completions detectable and identify whether the owner is
 * preparing, waiting on a permit, performing I/O, delaying a retry, or delivering a result.</p>
 *
 * <p>All mutation is event-loop confined, but an immutable snapshot is published for diagnostics on other
 * threads. Observability can therefore explain an apparently stuck owner without exposing its maps to races
 * or granting diagnostic code authority over lifecycle state. A small registry republishes on every change.
 * Rebuilding a large one on every change would make each operation cost the size of the registry, so a
 * large registry is only marked stale; it is rebuilt when read on its event loop, and a read from another
 * thread returns the last snapshot and schedules a rebuild for the next read.</p>
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

    public record Snapshot(
        long operationId,
        @NonNull String ownerIdentity,
        KafkaRecordId kafkaRecordId,
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
        KafkaRecordId kafkaRecordId,
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
                kafkaRecordId,
                partitionGenerationId,
                connectionProcessingId,
                replayRequestId,
                operationType,
                startTime,
                scheduledTargetTime,
                replacement
            );
        }

        Snapshot snapshot(
            long operationId,
            String ownerIdentity,
            KafkaRecordId kafkaRecordId
        ) {
            return new Snapshot(
                operationId,
                ownerIdentity,
                kafkaRecordId,
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
    private final KafkaRecordId defaultKafkaRecordId;
    private final EventLoop eventLoop;
    private final Clock clock;
    private final FatalHandler fatalHandler;
    private final Map<Long, Entry> active = new LinkedHashMap<>();
    static final int EAGER_PUBLISH_LIMIT = 32;

    private volatile List<Snapshot> publishedSnapshots = List.of();
    private volatile int activeCount;
    private volatile boolean snapshotStale;
    private final AtomicBoolean refreshScheduled = new AtomicBoolean();
    private long nextOperationId = 1;
    private boolean failed;

    public OutstandingOperationRegistry(
        @NonNull String ownerIdentity,
        @NonNull EventLoop eventLoop,
        @NonNull Clock clock,
        @NonNull FatalHandler fatalHandler
    ) {
        this(ownerIdentity, null, eventLoop, clock, fatalHandler);
    }

    public OutstandingOperationRegistry(
        @NonNull String ownerIdentity,
        KafkaRecordId defaultKafkaRecordId,
        @NonNull EventLoop eventLoop,
        @NonNull Clock clock,
        @NonNull FatalHandler fatalHandler
    ) {
        this.ownerIdentity = ownerIdentity;
        this.defaultKafkaRecordId = defaultKafkaRecordId;
        this.eventLoop = eventLoop;
        this.clock = clock;
        this.fatalHandler = fatalHandler;
    }

    public Registration register(
        @NonNull PartitionGenerationId partitionGenerationId,
        @NonNull ConnectionProcessingId connectionProcessingId,
        ReplayRequestId replayRequestId,
        @NonNull OperationType operationType,
        Instant scheduledTargetTime,
        @NonNull WaitReason waitReason
    ) {
        return register(
            defaultKafkaRecordId,
            partitionGenerationId,
            connectionProcessingId,
            replayRequestId,
            operationType,
            scheduledTargetTime,
            waitReason
        );
    }

    public Registration register(
        KafkaRecordId kafkaRecordId,
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
                kafkaRecordId,
                partitionGenerationId,
                connectionProcessingId,
                replayRequestId,
                operationType,
                clock.instant(),
                scheduledTargetTime,
                waitReason
            )
        );
        publish();
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
        publish();
    }

    public void complete(@NonNull Registration registration) {
        requireOwnerThread();
        var entry = requireEntry(registration, "apply typed completion");
        if (entry == null) {
            return;
        }
        active.remove(registration.operationId);
        publish();
    }

    public List<Snapshot> snapshots() {
        if (eventLoop.inEventLoop()) {
            refreshStaleSnapshot();
        } else if (snapshotStale && refreshScheduled.compareAndSet(false, true)) {
            try {
                eventLoop.execute(() -> {
                    refreshScheduled.set(false);
                    refreshStaleSnapshot();
                });
            } catch (RejectedExecutionException shuttingDown) {
                refreshScheduled.set(false);
            }
        }
        return publishedSnapshots;
    }

    public int activeCount() {
        return activeCount;
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

    private void publish() {
        activeCount = active.size();
        if (active.size() > EAGER_PUBLISH_LIMIT) {
            snapshotStale = true;
            return;
        }
        rebuildSnapshot();
    }

    private void refreshStaleSnapshot() {
        if (snapshotStale) {
            rebuildSnapshot();
        }
    }

    private void rebuildSnapshot() {
        snapshotStale = false;
        var snapshots = new ArrayList<Snapshot>(active.size());
        active.forEach((operationId, entry) ->
            snapshots.add(entry.snapshot(operationId, ownerIdentity, entry.kafkaRecordId()))
        );
        publishedSnapshots = List.copyOf(snapshots);
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
