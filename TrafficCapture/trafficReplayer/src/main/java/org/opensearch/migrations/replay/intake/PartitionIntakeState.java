/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.migrations.replay.intake;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

import org.opensearch.migrations.replay.identity.CapturedConnectionId;
import org.opensearch.migrations.replay.identity.ConnectionProcessingId;
import org.opensearch.migrations.replay.identity.KafkaRecordId;
import org.opensearch.migrations.replay.identity.PartitionBatchRequestId;
import org.opensearch.migrations.replay.identity.PartitionGenerationId;
import org.opensearch.migrations.replay.identity.ReplayRequestId;
import org.opensearch.migrations.trafficcapture.protos.TrafficStream;
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
 * <p>REBUILD-LIMBO-NOTE(G8): {@code §6}'s {@code cancellationState} and {@code GenerationCleanupTracker},
 * which are {@code §15.2} and {@code §15.3}.
 */
public final class PartitionIntakeState {

    public record BrokerTimeConfiguration(
        long heartbeatExpirationMillis,
        long maximumBackwardSkewMillis,
        long sourceResponseRetryWindowMillis
    ) {
        public BrokerTimeConfiguration {
            if (heartbeatExpirationMillis <= 0) {
                throw new IllegalArgumentException("heartbeatExpirationMillis must be positive");
            }
            if (maximumBackwardSkewMillis < 0) {
                throw new IllegalArgumentException("maximumBackwardSkewMillis must not be negative");
            }
            if (sourceResponseRetryWindowMillis <= 0) {
                throw new IllegalArgumentException("sourceResponseRetryWindowMillis must be positive");
            }
            Math.addExact(heartbeatExpirationMillis, maximumBackwardSkewMillis);
            Math.addExact(
                heartbeatExpirationMillis,
                Math.multiplyExact(2, maximumBackwardSkewMillis)
            );
        }
    }

    public sealed interface ExpirationReference
        permits ExactHeartbeat, FirstTrafficFallback {

        long logAppendTimeMillis();
    }

    public record ExactHeartbeat(long logAppendTimeMillis) implements ExpirationReference {}

    public record FirstTrafficFallback(long logAppendTimeMillis) implements ExpirationReference {}

    public enum WriterTimeTransition {
        NONE,
        EXACT_HEARTBEAT_STARTED,
        FIRST_TRAFFIC_FALLBACK_STARTED,
        FALLBACK_REPLACED_BY_HEARTBEAT,
        TIMELY_HEARTBEAT_ACCEPTED,
        LATE_HEARTBEAT_IGNORED
    }

    public enum BootstrapBatchState {
        PENDING,
        APPLYING,
        CONSUMED
    }

    public enum RequestedBatchState {
        IDLE,
        REQUESTED,
        APPLYING
    }

    public enum BatchEntitlement {
        BOOTSTRAP,
        EXPLICIT
    }

    public record WriterExpirationResult(
        int expiredSourceConnections,
        List<ConnectionProcessingId> targetOwnersToExpire
    ) {
        public WriterExpirationResult {
            targetOwnersToExpire = List.copyOf(targetOwnersToExpire);
        }
    }

    private enum RetryInputState {
        UNRESOLVED,
        COMPLETE,
        UNAVAILABLE
    }

    private enum FinalInputState {
        UNRESOLVED,
        COMPLETE,
        INCOMPLETE
    }

    private enum TargetSupplyState {
        PRESENT,
        FINISHED_OR_CANCELLED
    }

    private static final class RequestIntakeState {
        private final long requestCompletingLogAppendTime;
        private RetryInputState retryInput = RetryInputState.UNRESOLVED;
        private FinalInputState finalInput = FinalInputState.UNRESOLVED;
        private TargetSupplyState targetSupply = TargetSupplyState.PRESENT;
        private boolean countedAsRetryReadySupply;

        private RequestIntakeState(long requestCompletingLogAppendTime) {
            this.requestCompletingLogAppendTime = requestCompletingLogAppendTime;
        }
    }

    private static final class WriterPartitionTimeState {
        private ExpirationReference reference;
        private long heartbeatIntervalMillis;
        private Long emittedAtMillis;

        private WriterPartitionTimeState(
            ExpirationReference reference,
            long heartbeatIntervalMillis,
            Long emittedAtMillis
        ) {
            this.reference = reference;
            this.heartbeatIntervalMillis = heartbeatIntervalMillis;
            this.emittedAtMillis = emittedAtMillis;
        }
    }

    public static final class BrokerTimeViolation extends IllegalStateException {
        private BrokerTimeViolation(String message) {
            super(message);
        }
    }

    private final PartitionGenerationId generation;
    private final OwnerThreadGuard ownerThreadGuard;
    private final BrokerTimeConfiguration brokerTimeConfiguration;
    /** Emits {@code RecordProcessingFinished} for a record whose work is done ({@code §7} step 9). */
    private final Consumer<KafkaRecordId> recordCompletionSink;
    /**
     * REBUILD-LIMBO-NOTE(G8): generation cleanup decrements this for every tracker it removes without ordinary
     * completion, so the process-wide gauge remains balanced when a generation is cancelled.
     */
    private final IntConsumer activeRecordTrackersChanged;
    private final Runnable recordTrackerRetired;
    private final IntConsumer retryReadySupplyChanged;

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

    /**
     * {@code §6}: this "points only to the current source-assembly lifetime". A fresh lifetime for the same
     * captured connection replaces the value here and does not touch the lifetime it replaced.
     *
     * <p>An explicitly closed lifetime keeps its entry, because that entry is the terminal cutoff
     * {@code §9.3} needs: {@link #connectionFor} rejects a later {@code TrafficStream} for the same captured
     * identity instead of opening a successor. The cutoff therefore lasts exactly as long as the lifetime
     * state it points at and is never a separate or durable identity tombstone.
     *
     * <p>{@code ConnectionOwnerFinished} drops a closed lifetime's entry here together with its
     * {@link #activeConnectionProcessingById} entry, which is where post-close rejection for that captured
     * identity stops.
     */
    private final Map<CapturedConnectionId, SourceConnectionState>
        activeSourceConnectionsByCapturedConnectionId = new LinkedHashMap<>();
    /**
     * {@code §6}: "Older expired {@code ConnectionProcessingId} values may remain here while their target and
     * tuple work finishes." An expired lifetime's entry therefore outlives its captured-identity mapping.
     *
     * <p>Removed on {@code ConnectionOwnerFinished} ({@code §4.1}), which reports that the owner has nothing
     * left.
     */
    private final Map<ConnectionProcessingId, SourceConnectionState> activeConnectionProcessingById =
        new LinkedHashMap<>();
    private final Map<String, WriterPartitionTimeState> writerTimeStateByWriterNodeId =
        new LinkedHashMap<>();
    private final Map<ReplayRequestId, RequestIntakeState> requestStateByReplayRequestId =
        new LinkedHashMap<>();
    private final LinkedHashSet<ReplayRequestId> unresolvedRetryBoundaries =
        new LinkedHashSet<>();
    private int retryReadyRequestSupplyCount;
    private BootstrapBatchState bootstrapBatchState = BootstrapBatchState.PENDING;
    private RequestedBatchState requestedBatchState = RequestedBatchState.IDLE;
    private PartitionBatchRequestId requestedBatchRequestId;
    private long nextBatchRequestLocalSequence = 1;
    private long nextConnectionLocalSequence;

    private long greatestObservedLogAppendTime = Long.MIN_VALUE;
    private KafkaRecordId captureProtocolViolationRecord;

    public PartitionIntakeState(
        @NonNull PartitionGenerationId generation,
        @NonNull BooleanSupplier currentThreadIsOwner,
        @NonNull Consumer<KafkaRecordId> recordCompletionSink,
        @NonNull IntConsumer activeRecordTrackersChanged,
        @NonNull Runnable recordTrackerRetired
    ) {
        this(
            generation,
            currentThreadIsOwner,
            recordCompletionSink,
            activeRecordTrackersChanged,
            recordTrackerRetired,
            new BrokerTimeConfiguration(30_000, 0, 5_000),
            ignored -> {}
        );
    }

    public PartitionIntakeState(
        @NonNull PartitionGenerationId generation,
        @NonNull BooleanSupplier currentThreadIsOwner,
        @NonNull Consumer<KafkaRecordId> recordCompletionSink,
        @NonNull IntConsumer activeRecordTrackersChanged,
        @NonNull Runnable recordTrackerRetired,
        @NonNull BrokerTimeConfiguration brokerTimeConfiguration
    ) {
        this(
            generation,
            currentThreadIsOwner,
            recordCompletionSink,
            activeRecordTrackersChanged,
            recordTrackerRetired,
            brokerTimeConfiguration,
            ignored -> {}
        );
    }

    public PartitionIntakeState(
        @NonNull PartitionGenerationId generation,
        @NonNull BooleanSupplier currentThreadIsOwner,
        @NonNull Consumer<KafkaRecordId> recordCompletionSink,
        @NonNull IntConsumer activeRecordTrackersChanged,
        @NonNull Runnable recordTrackerRetired,
        @NonNull BrokerTimeConfiguration brokerTimeConfiguration,
        @NonNull IntConsumer retryReadySupplyChanged
    ) {
        this.generation = generation;
        this.ownerThreadGuard = new OwnerThreadGuard("replay intake " + generation, currentThreadIsOwner);
        this.recordCompletionSink = recordCompletionSink;
        this.activeRecordTrackersChanged = activeRecordTrackersChanged;
        this.recordTrackerRetired = recordTrackerRetired;
        this.brokerTimeConfiguration = brokerTimeConfiguration;
        this.retryReadySupplyChanged = retryReadySupplyChanged;
    }

    public PartitionGenerationId generation() {
        return generation;
    }

    // ------------------------------------------------------------------ demand and batch entitlement

    public int retryReadyRequestSupplyCount() {
        ownerThreadGuard.requireOwnerThread();
        return retryReadyRequestSupplyCount;
    }

    public BootstrapBatchState bootstrapBatchState() {
        ownerThreadGuard.requireOwnerThread();
        return bootstrapBatchState;
    }

    public RequestedBatchState requestedBatchState() {
        ownerThreadGuard.requireOwnerThread();
        return requestedBatchState;
    }

    public Optional<PartitionBatchRequestId> requestedBatchRequestId() {
        ownerThreadGuard.requireOwnerThread();
        return Optional.ofNullable(requestedBatchRequestId);
    }

    public boolean demandOpen(int requestSupplyTarget) {
        ownerThreadGuard.requireOwnerThread();
        requirePositiveRequestSupplyTarget(requestSupplyTarget);
        return retryReadyRequestSupplyCount < requestSupplyTarget;
    }

    /**
     * Allocates one explicit request only while demand is open and no earlier explicit request remains.
     */
    public Optional<PartitionBatchRequestId> requestNextBatchIfNeeded(int requestSupplyTarget) {
        ownerThreadGuard.requireOwnerThread();
        if (!demandOpen(requestSupplyTarget) || requestedBatchState != RequestedBatchState.IDLE) {
            return Optional.empty();
        }
        var requestId = new PartitionBatchRequestId(generation, nextBatchRequestLocalSequence++);
        requestedBatchRequestId = requestId;
        requestedBatchState = RequestedBatchState.REQUESTED;
        return Optional.of(requestId);
    }

    /**
     * Opens the matching entitlement before any record in the delivered batch is applied.
     */
    public BatchEntitlement beginApplyingBatch(@NonNull PartitionBatchRequestId requestId) {
        ownerThreadGuard.requireOwnerThread();
        requireSameGeneration(requestId.generation());
        if (requestId.localSequence() == 0) {
            if (bootstrapBatchState != BootstrapBatchState.PENDING) {
                throw new IllegalStateException(
                    "bootstrap batch cannot begin from " + bootstrapBatchState + " for " + generation
                );
            }
            bootstrapBatchState = BootstrapBatchState.APPLYING;
            return BatchEntitlement.BOOTSTRAP;
        }
        if (requestedBatchState != RequestedBatchState.REQUESTED
            || !requestId.equals(requestedBatchRequestId)) {
            throw new IllegalStateException(
                "explicit batch " + requestId + " does not match requested state "
                    + requestedBatchState + " " + requestedBatchRequestId
            );
        }
        requestedBatchState = RequestedBatchState.APPLYING;
        return BatchEntitlement.EXPLICIT;
    }

    /**
     * Closes the entitlement only after every record in the delivered batch has been applied.
     */
    public void finishApplyingBatch(
        @NonNull PartitionBatchRequestId requestId,
        @NonNull BatchEntitlement entitlement
    ) {
        ownerThreadGuard.requireOwnerThread();
        requireSameGeneration(requestId.generation());
        switch (entitlement) {
            case BOOTSTRAP -> {
                if (requestId.localSequence() != 0
                    || bootstrapBatchState != BootstrapBatchState.APPLYING) {
                    throw new IllegalStateException(
                        "bootstrap batch " + requestId + " cannot finish from " + bootstrapBatchState
                    );
                }
                bootstrapBatchState = BootstrapBatchState.CONSUMED;
            }
            case EXPLICIT -> {
                if (requestedBatchState != RequestedBatchState.APPLYING
                    || !requestId.equals(requestedBatchRequestId)) {
                    throw new IllegalStateException(
                        "explicit batch " + requestId + " cannot finish from "
                            + requestedBatchState + " " + requestedBatchRequestId
                    );
                }
                requestedBatchState = RequestedBatchState.IDLE;
                requestedBatchRequestId = null;
            }
        }
    }

    // ------------------------------------------------------------------ record work tracking

    /** {@code §7} step 2: one tracker per accepted record, created before any observation is applied. */
    public void registerRecord(@NonNull KafkaRecordId recordId) {
        ownerThreadGuard.requireOwnerThread();
        requireSameGeneration(recordId);
        if (recordTrackersByKafkaRecordId.putIfAbsent(recordId, new RecordWorkTracker(recordId)) != null) {
            throw new IllegalStateException("Kafka record was already registered: " + recordId);
        }
        activeRecordTrackersChanged.accept(1);
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

    // ------------------------------------------------------------------ source connections

    /**
     * The lifetime that should receive this {@code TrafficStream}'s observations, creating one if the captured
     * connection has none open.
     *
     * <p>{@code §2}: {@code ConnectionProcessingId.localSequence} "is allocated whenever replay intake begins
     * fresh process-local source assembly for a captured connection. It distinguishes a later fresh lifetime
     * from an expired lifetime whose target or tuple work is still finishing." So an expired captured
     * connection gets a new sequence rather than rejoining the old one — {@code §17.2} requires the two
     * to "coexist without sharing state or messages", and sharing an identity is the one way they could not.
     *
     * <p>An explicit close admits no successor while its existing process-local lifetime remains retained:
     * a later {@code TrafficStream} for the same captured identity is a protocol violation rather than fresh
     * reconstruction.
     */
    // REBUILD-TRACE-START(G5,target): retain through the rebuild; remove in final pre-merge cleanup.
    // CapturedTrafficToHttpTransactionAccumulator.createInitialAccumulation ->
    //     PartitionIntakeState.connectionFor
    // REBUILD-TRACE-END(G5,target)
    public SourceConnectionState connectionFor(
        @NonNull CapturedConnectionId capturedConnectionId,
        @NonNull TrafficStream firstStream,
        @NonNull SourceAssemblySink sink
    ) {
        ownerThreadGuard.requireOwnerThread();
        var existing = activeSourceConnectionsByCapturedConnectionId.get(capturedConnectionId);
        if (existing != null) {
            if (existing.lifetime() == SourceConnectionState.Lifetime.EXPLICITLY_CLOSED) {
                throw new SourceConnectionState.CaptureProtocolViolation(
                    "TrafficStream for " + capturedConnectionId + " arrived after CloseObservation"
                );
            }
            if (existing.lifetime() == SourceConnectionState.Lifetime.OPEN) {
                return existing;
            }
        }
        var lifetime = new SourceConnectionState(
            new ConnectionProcessingId(generation, capturedConnectionId, nextConnectionLocalSequence++),
            firstStream,
            sink
        );
        activeSourceConnectionsByCapturedConnectionId.put(capturedConnectionId, lifetime);
        activeConnectionProcessingById.put(lifetime.connectionProcessingId(), lifetime);
        return lifetime;
    }

    /**
     * Frees a captured connection for fresh reconstruction once its lifetime expired.
     *
     * <p>Only if the mapping still points at that lifetime: a fresh lifetime may already have replaced it, and
     * {@code §6} says replacing "does not mutate the old lifetime" — the converse holds too, so an old
     * lifetime ending must not unmap its successor.
     *
     * <p>An explicitly closed lifetime keeps its mapping, which carries {@code §9.3}'s process-local terminal
     * cutoff past the record that held the close. G5 removes both retained references when the connection
     * owner reports it has no remaining work.
     */
    public void retireLifetime(@NonNull SourceConnectionState endedLifetime) {
        ownerThreadGuard.requireOwnerThread();
        if (endedLifetime.lifetime() == SourceConnectionState.Lifetime.EXPLICITLY_CLOSED) {
            return;
        }
        var capturedConnectionId = endedLifetime.connectionProcessingId().capturedConnectionId();
        activeSourceConnectionsByCapturedConnectionId.remove(capturedConnectionId, endedLifetime);
    }

    public Optional<SourceConnectionState> lifetimeOf(@NonNull ConnectionProcessingId id) {
        ownerThreadGuard.requireOwnerThread();
        return Optional.ofNullable(activeConnectionProcessingById.get(id));
    }

    public void removeConnectionOwner(@NonNull ConnectionProcessingId id) {
        ownerThreadGuard.requireOwnerThread();
        requireSameGeneration(id);
        var lifetime = activeConnectionProcessingById.remove(id);
        if (lifetime == null) {
            throw new IllegalStateException("No active connection owner for " + id);
        }
        activeSourceConnectionsByCapturedConnectionId.remove(id.capturedConnectionId(), lifetime);
    }

    public void registerRequest(
        @NonNull ReplayRequestId requestId,
        long requestCompletingLogAppendTime
    ) {
        ownerThreadGuard.requireOwnerThread();
        requireSameGeneration(requestId.connectionProcessingId());
        if (requestStateByReplayRequestId.putIfAbsent(
            requestId,
            new RequestIntakeState(requestCompletingLogAppendTime)
        ) != null) {
            throw new IllegalStateException("Replay request was already registered: " + requestId);
        }
        unresolvedRetryBoundaries.add(requestId);
    }

    /**
     * Freezes a complete response for final tuple output and, when still unresolved, for retry policy too.
     *
     * @return true when the retry receiver must receive this response
     */
    public boolean sourceResponseCompleted(@NonNull ReplayRequestId requestId) {
        ownerThreadGuard.requireOwnerThread();
        var request = requireRequest(requestId);
        if (request.finalInput != FinalInputState.UNRESOLVED) {
            throw new IllegalStateException("Final source response was already supplied for " + requestId);
        }
        request.finalInput = FinalInputState.COMPLETE;
        if (request.retryInput == RetryInputState.UNRESOLVED) {
            resolveRetryInput(requestId, request, RetryInputState.COMPLETE);
            return true;
        }
        return false;
    }

    /**
     * Freezes an incomplete final response and resolves a still-open retry input as unavailable.
     *
     * @return true when the retry receiver must receive unavailable
     */
    public boolean sourceResponseIncomplete(@NonNull ReplayRequestId requestId) {
        ownerThreadGuard.requireOwnerThread();
        var request = requireRequest(requestId);
        if (request.finalInput != FinalInputState.UNRESOLVED) {
            throw new IllegalStateException("Final source response was already supplied for " + requestId);
        }
        request.finalInput = FinalInputState.INCOMPLETE;
        if (request.retryInput == RetryInputState.UNRESOLVED) {
            resolveRetryInput(requestId, request, RetryInputState.UNAVAILABLE);
            return true;
        }
        return false;
    }

    /**
     * Irreversibly freezes every retry boundary crossed by this record before its payload is applied.
     */
    public List<ReplayRequestId> resolveRetryBoundaries(long recordLogAppendTimeMillis) {
        ownerThreadGuard.requireOwnerThread();
        var resolved = new ArrayList<ReplayRequestId>();
        for (var requestId : List.copyOf(unresolvedRetryBoundaries)) {
            var request = requireRequest(requestId);
            if (reaches(
                    recordLogAppendTimeMillis,
                    request.requestCompletingLogAppendTime,
                    brokerTimeConfiguration.sourceResponseRetryWindowMillis()
                )) {
                resolveRetryInput(requestId, request, RetryInputState.UNAVAILABLE);
                resolved.add(requestId);
            }
        }
        return List.copyOf(resolved);
    }

    /** A finalized archive has no later timestamp, but it still proves that no retry response can arrive. */
    public List<ReplayRequestId> resolveAllRetryBoundaries() {
        ownerThreadGuard.requireOwnerThread();
        var resolved = new ArrayList<ReplayRequestId>();
        for (var requestId : List.copyOf(unresolvedRetryBoundaries)) {
            resolveRetryInput(
                requestId,
                requireRequest(requestId),
                RetryInputState.UNAVAILABLE
            );
            resolved.add(requestId);
        }
        return List.copyOf(resolved);
    }

    public void connectionRequestFinished(@NonNull ReplayRequestId requestId) {
        targetSupplyFinishedOrCancelled(requestId);
    }

    /**
     * Removes a request from target supply exactly once. G8 invokes the same transition for cancellation.
     */
    public void targetSupplyFinishedOrCancelled(@NonNull ReplayRequestId requestId) {
        ownerThreadGuard.requireOwnerThread();
        var request = requireRequest(requestId);
        if (request.targetSupply == TargetSupplyState.FINISHED_OR_CANCELLED) {
            throw new IllegalStateException("Target supply already finished or cancelled: " + requestId);
        }
        request.targetSupply = TargetSupplyState.FINISHED_OR_CANCELLED;
        if (request.countedAsRetryReadySupply) {
            request.countedAsRetryReadySupply = false;
            changeRetryReadySupply(-1);
        }
    }

    public boolean requestCanCountAsRetryReadySupply(@NonNull ReplayRequestId requestId) {
        ownerThreadGuard.requireOwnerThread();
        var request = requireRequest(requestId);
        return request.targetSupply == TargetSupplyState.PRESENT
            && request.retryInput != RetryInputState.UNRESOLVED;
    }

    public void removeRequest(@NonNull ReplayRequestId requestId) {
        ownerThreadGuard.requireOwnerThread();
        var request = requireRequest(requestId);
        if (request.targetSupply != TargetSupplyState.FINISHED_OR_CANCELLED) {
            throw new IllegalStateException(
                "Request processing finished before target supply ended: " + requestId
            );
        }
        if (request.countedAsRetryReadySupply) {
            throw new IllegalStateException(
                "Request processing finished while still counted as retry-ready supply: " + requestId
            );
        }
        requestStateByReplayRequestId.remove(requestId);
        unresolvedRetryBoundaries.remove(requestId);
    }

    // ------------------------------------------------------------------ broker time

    /**
     * {@code §10.1}: validates a higher-offset record before it authorizes any time decision or payload.
     */
    public void observeLogAppendTime(long logAppendTimeMillis) {
        ownerThreadGuard.requireOwnerThread();
        if (greatestObservedLogAppendTime != Long.MIN_VALUE
            && exceedsByMoreThan(
                greatestObservedLogAppendTime,
                logAppendTimeMillis,
                brokerTimeConfiguration.maximumBackwardSkewMillis()
            )) {
            throw new BrokerTimeViolation(
                "Kafka LogAppendTime moved backward by more than "
                    + brokerTimeConfiguration.maximumBackwardSkewMillis()
                    + "ms in "
                    + generation.topicPartition()
                    + ": greatest="
                    + greatestObservedLogAppendTime
                    + ", current="
                    + logAppendTimeMillis
            );
        }
        greatestObservedLogAppendTime = Math.max(greatestObservedLogAppendTime, logAppendTimeMillis);
    }

    public long greatestObservedLogAppendTime() {
        ownerThreadGuard.requireOwnerThread();
        return greatestObservedLogAppendTime;
    }

    public WriterTimeTransition observeTrafficWriter(
        @NonNull String writerNodeId,
        long logAppendTimeMillis
    ) {
        ownerThreadGuard.requireOwnerThread();
        if (writerTimeStateByWriterNodeId.containsKey(writerNodeId)) {
            return WriterTimeTransition.NONE;
        }
        writerTimeStateByWriterNodeId.put(
            writerNodeId,
            new WriterPartitionTimeState(
                new FirstTrafficFallback(logAppendTimeMillis),
                0,
                null
            )
        );
        return WriterTimeTransition.FIRST_TRAFFIC_FALLBACK_STARTED;
    }

    public WriterTimeTransition observeHeartbeat(
        @NonNull String writerNodeId,
        long logAppendTimeMillis,
        long heartbeatIntervalMillis,
        Long emittedAtMillis
    ) {
        ownerThreadGuard.requireOwnerThread();
        var state = writerTimeStateByWriterNodeId.get(writerNodeId);
        if (state == null) {
            writerTimeStateByWriterNodeId.put(
                writerNodeId,
                new WriterPartitionTimeState(
                    new ExactHeartbeat(logAppendTimeMillis),
                    heartbeatIntervalMillis,
                    emittedAtMillis
                )
            );
            return WriterTimeTransition.EXACT_HEARTBEAT_STARTED;
        }
        if (state.reference instanceof FirstTrafficFallback) {
            state.reference = new ExactHeartbeat(logAppendTimeMillis);
            state.heartbeatIntervalMillis = heartbeatIntervalMillis;
            state.emittedAtMillis = emittedAtMillis;
            return WriterTimeTransition.FALLBACK_REPLACED_BY_HEARTBEAT;
        }
        var exact = (ExactHeartbeat) state.reference;
        if (!reaches(
            logAppendTimeMillis,
            exact.logAppendTimeMillis(),
            brokerTimeConfiguration.heartbeatExpirationMillis()
        )) {
            state.reference = new ExactHeartbeat(logAppendTimeMillis);
            state.heartbeatIntervalMillis = heartbeatIntervalMillis;
            state.emittedAtMillis = emittedAtMillis;
            return WriterTimeTransition.TIMELY_HEARTBEAT_ACCEPTED;
        }
        return WriterTimeTransition.LATE_HEARTBEAT_IGNORED;
    }

    public List<String> writersExpiredAt(long recordLogAppendTimeMillis) {
        ownerThreadGuard.requireOwnerThread();
        var expired = new ArrayList<String>();
        writerTimeStateByWriterNodeId.forEach((writerNodeId, state) -> {
            var threshold = switch (state.reference) {
                case ExactHeartbeat ignored -> Math.addExact(
                    brokerTimeConfiguration.heartbeatExpirationMillis(),
                    brokerTimeConfiguration.maximumBackwardSkewMillis()
                );
                case FirstTrafficFallback ignored -> Math.addExact(
                    brokerTimeConfiguration.heartbeatExpirationMillis(),
                    Math.multiplyExact(2, brokerTimeConfiguration.maximumBackwardSkewMillis())
                );
            };
            if (reaches(recordLogAppendTimeMillis, state.reference.logAppendTimeMillis(), threshold)) {
                expired.add(writerNodeId);
            }
        });
        return List.copyOf(expired);
    }

    /**
     * Expires every current lifetime for one writer and returns owners that need the matching target command.
     */
    // REBUILD-TRACE-START(G6,target): retain through the rebuild; remove in final pre-merge cleanup.
    // ExpiringKeyQueue.expireItemsBefore -> PartitionIntakeState.expireConnectionsForWriter
    // REBUILD-TRACE-END(G6,target)
    public WriterExpirationResult expireConnectionsForWriter(@NonNull String writerNodeId) {
        ownerThreadGuard.requireOwnerThread();
        var ownersToExpire = new ArrayList<ConnectionProcessingId>();
        var expiredSourceConnections = 0;
        var lifetimes = List.copyOf(activeSourceConnectionsByCapturedConnectionId.values());
        for (var lifetime : lifetimes) {
            if (lifetime.lifetime() != SourceConnectionState.Lifetime.OPEN
                || !lifetime.connectionProcessingId().capturedConnectionId().writerNodeId().equals(writerNodeId)) {
                continue;
            }
            var hasConnectionOwner = lifetime.hasConnectionOwner();
            var outcome = lifetime.expire();
            expiredSourceConnections++;
            if (!outcome.associationsToAdd().isEmpty() || !outcome.relabels().isEmpty()) {
                throw new IllegalStateException("Expiration created new record work for " + lifetime);
            }
            outcome.associationsFinished().forEach(this::associationFinished);
            retireLifetime(lifetime);
            if (hasConnectionOwner) {
                ownersToExpire.add(lifetime.connectionProcessingId());
            }
        }
        return new WriterExpirationResult(expiredSourceConnections, ownersToExpire);
    }

    ExpirationReference expirationReferenceFor(String writerNodeId) {
        ownerThreadGuard.requireOwnerThread();
        var state = writerTimeStateByWriterNodeId.get(writerNodeId);
        return state == null ? null : state.reference;
    }

    /**
     * Latches the first record whose payload violates the capture protocol.
     *
     * <p>{@code §16} leaves that record unfinished. This state retains the per-generation poison location;
     * {@link ReplayIntakeOwner} owns the replay-wide cutoff that rejects later batches from every partition.
     */
    public void captureProtocolViolationAt(@NonNull KafkaRecordId recordId) {
        ownerThreadGuard.requireOwnerThread();
        requireSameGeneration(recordId);
        if (captureProtocolViolationRecord == null) {
            captureProtocolViolationRecord = recordId;
        }
    }

    // ------------------------------------------------------------------ internals

    private void resolveRetryInput(
        ReplayRequestId requestId,
        RequestIntakeState request,
        RetryInputState resolvedState
    ) {
        if (resolvedState == RetryInputState.UNRESOLVED) {
            throw new IllegalArgumentException("retry input must resolve to complete or unavailable");
        }
        if (request.retryInput != RetryInputState.UNRESOLVED) {
            throw new IllegalStateException("Retry input was already resolved for " + requestId);
        }
        request.retryInput = resolvedState;
        unresolvedRetryBoundaries.remove(requestId);
        if (request.targetSupply == TargetSupplyState.PRESENT
            && !request.countedAsRetryReadySupply) {
            request.countedAsRetryReadySupply = true;
            changeRetryReadySupply(1);
        }
    }

    private void changeRetryReadySupply(int delta) {
        var changed = Math.addExact(retryReadyRequestSupplyCount, delta);
        if (changed < 0) {
            throw new IllegalStateException(
                "retry-ready request supply became negative for " + generation
            );
        }
        retryReadyRequestSupplyCount = changed;
        retryReadySupplyChanged.accept(delta);
    }

    private static void requirePositiveRequestSupplyTarget(int requestSupplyTarget) {
        if (requestSupplyTarget <= 0) {
            throw new IllegalArgumentException("requestSupplyTarget must be positive");
        }
    }

    private void requireSameGeneration(PartitionGenerationId otherGeneration) {
        if (!generation.equals(otherGeneration)) {
            throw new IllegalStateException(
                "Partition generation " + otherGeneration + " does not match " + generation
            );
        }
    }

    private void requireSameGeneration(KafkaRecordId recordId) {
        if (!generation.equals(recordId.generation())) {
            throw new IllegalStateException(
                "Kafka record " + recordId + " does not belong to generation " + generation
            );
        }
    }

    private void requireSameGeneration(ConnectionProcessingId connectionProcessingId) {
        if (!generation.equals(connectionProcessingId.generation())) {
            throw new IllegalStateException(
                "Connection " + connectionProcessingId + " does not belong to generation " + generation
            );
        }
    }

    private RequestIntakeState requireRequest(ReplayRequestId requestId) {
        requireSameGeneration(requestId.connectionProcessingId());
        var request = requestStateByReplayRequestId.get(requestId);
        if (request == null) {
            throw new IllegalStateException("No replay-intake request state for " + requestId);
        }
        return request;
    }

    private static boolean reaches(long observed, long reference, long threshold) {
        if (observed < reference) {
            return false;
        }
        if (reference > Long.MAX_VALUE - threshold) {
            return false;
        }
        return observed >= reference + threshold;
    }

    private static boolean exceedsByMoreThan(long greater, long lesser, long permittedDifference) {
        if (greater <= lesser) {
            return false;
        }
        if (lesser > Long.MAX_VALUE - permittedDifference) {
            return false;
        }
        return greater > lesser + permittedDifference;
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
            if (!recordTrackersByKafkaRecordId.remove(tracker.recordId(), tracker)) {
                throw new IllegalStateException("Completed Kafka record tracker was not registered: " + tracker);
            }
            activeRecordTrackersChanged.accept(-1);
            recordTrackerRetired.run();
        }
    }
}
