/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.migrations.replay.intake;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ThreadFactory;

import org.opensearch.migrations.replay.HttpMessageAndTimestamp;
import org.opensearch.migrations.replay.identity.CancellationGrace;
import org.opensearch.migrations.replay.identity.CapturedConnectionId;
import org.opensearch.migrations.replay.identity.ConnectionProcessingId;
import org.opensearch.migrations.replay.identity.KafkaRecordId;
import org.opensearch.migrations.replay.identity.PartitionGenerationId;
import org.opensearch.migrations.replay.identity.ReplayRequestId;
import org.opensearch.migrations.replay.kafkasource.ApplicationKafkaRecord;
import org.opensearch.migrations.replay.kafkasource.KafkaSourceInput;
import org.opensearch.migrations.replay.kafkasource.KafkaSourceInputQueue;
import org.opensearch.migrations.replay.lifecycle.OwnerThreadGuard;
import org.opensearch.migrations.trafficcapture.protos.CaptureRecord;
import org.opensearch.migrations.trafficcapture.protos.TrafficStream;
import org.opensearch.migrations.trafficcapture.protos.TrafficStreamUtils;

import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;

/**
 * Sole owner of replay-intake state and of applying source records — {@code kafkaLLD §3}, {@code §7}.
 *
 * <p>Runs one thread. Every input arrives through {@link ReplayIntakeInputQueue}, is removed only here, and is
 * applied completely before the next is removed ({@code §3}'s intake loop). Nothing else changes intake state,
 * which is what makes the maps in {@link PartitionIntakeState} safe without locks.
 *
 * <p>Failure is not a return value. An input that cannot be applied is an invariant failure, reported to the
 * process-failure boundary per {@code replayerLLD §4} rather than logged and skipped: intake is the only place
 * that knows which records are still owed, so a swallowed failure there is silent record loss.
 */
@Slf4j
public final class ReplayIntakeOwner {

    public enum InputKind {
        PARTITION_GENERATION_ASSIGNED,
        PARTITION_RECORD_BATCH,
        GRACEFUL_GENERATION_CANCELLATION,
        FORCE_GENERATION_CANCELLATION,
        FINALIZED_ARCHIVE_PARTITION_END,
        CONNECTION_REQUEST_FINISHED,
        REQUEST_PROCESSING_FINISHED,
        CONNECTION_OWNER_FINISHED,
        CONNECTION_CLEANUP_FINISHED
    }

    public interface Metrics {
        Metrics NOOP = new Metrics() {};

        default void ownerStarted() {}
        default void ownerStoppedAfterDraining() {}
        default void inputApplied(InputKind inputKind) {}
        default void recordApplied() {}
        default void activeRecordTrackersChanged(int delta) {}
        default void recordTrackerRetired() {}
        default void requestReconstituted() {}
        default void interimResponseObserved() {}
        default void responseCompleted(boolean keptAlive) {}
        default void responseIncomplete(SourceAssemblySink.IncompleteReason reason) {}
        default void retrySourceResponseCompleted() {}
        default void retrySourceResponseUnavailable() {}
        default void writerTimeTransition(PartitionIntakeState.WriterTimeTransition transition) {}
        default void sourceConnectionsExpired(int count) {}
        default void targetConnectionExpirationSent() {}
        default void brokerTimeViolation() {}
        default void batchRequested() {}
        default void batchEntitlementResolved(PartitionIntakeState.BatchEntitlement entitlement) {}
        default void retryReadySupplyChanged(int delta) {}
        default void demandEvaluated(boolean open) {}
        default void capturedCloseAccepted() {}
        default void captureProtocolViolation() {}
        default void recordBatchRejectedAfterProtocolViolation() {}
        default void generationGraceStarted(CancellationGrace grace) {}
        default void generationForceStarted() {}
        default void generationCleanupFinished() {}
        default void staleGenerationInputIgnored(InputKind inputKind) {}
    }

    @FunctionalInterface
    public interface RecordObserver {
        RecordObserver NOOP = record -> {};

        void beforeRecordApplied(ApplicationKafkaRecord record);
    }

    /** Where an invariant failure on the intake thread goes. {@code replayerLLD §4}'s process boundary. */
    public interface FatalHandler {
        void onFatal(Error failure);
    }

    private final ReplayIntakeInputQueue inputQueue;
    private final KafkaSourceInputQueue sourceInputs;
    private final SourceAssemblySink assemblySink;
    private final FatalHandler fatalHandler;
    private final Metrics metrics;
    private final RecordObserver recordObserver;
    private final PartitionIntakeState.BrokerTimeConfiguration brokerTimeConfiguration;
    private final int requestSupplyTarget;
    private final Thread ownerThread;
    private final OwnerThreadGuard ownerThreadGuard;
    private final CompletableFuture<Void> termination = new CompletableFuture<>();

    private final Map<PartitionGenerationId, PartitionIntakeState> partitions = new LinkedHashMap<>();
    /** The first poison record. Non-null means §16's replay-wide record-admission cutoff is latched. */
    private KafkaRecordId captureProtocolViolationRecord;
    private volatile boolean started;

    public ReplayIntakeOwner(
        @NonNull ReplayIntakeInputQueue inputQueue,
        @NonNull KafkaSourceInputQueue sourceInputs,
        @NonNull SourceAssemblySink assemblySink,
        @NonNull FatalHandler fatalHandler
    ) {
        this(inputQueue, sourceInputs, assemblySink, fatalHandler, Metrics.NOOP, RecordObserver.NOOP,
            new PartitionIntakeState.BrokerTimeConfiguration(30_000, 0, 5_000),
            2,
            runnable -> new Thread(runnable, "replay-intake-owner"));
    }

    public ReplayIntakeOwner(
        @NonNull ReplayIntakeInputQueue inputQueue,
        @NonNull KafkaSourceInputQueue sourceInputs,
        @NonNull SourceAssemblySink assemblySink,
        @NonNull FatalHandler fatalHandler,
        @NonNull Metrics metrics
    ) {
        this(inputQueue, sourceInputs, assemblySink, fatalHandler, metrics, RecordObserver.NOOP,
            new PartitionIntakeState.BrokerTimeConfiguration(30_000, 0, 5_000),
            2,
            runnable -> new Thread(runnable, "replay-intake-owner"));
    }

    public ReplayIntakeOwner(
        @NonNull ReplayIntakeInputQueue inputQueue,
        @NonNull KafkaSourceInputQueue sourceInputs,
        @NonNull SourceAssemblySink assemblySink,
        @NonNull FatalHandler fatalHandler,
        @NonNull Metrics metrics,
        @NonNull RecordObserver recordObserver
    ) {
        this(inputQueue, sourceInputs, assemblySink, fatalHandler, metrics, recordObserver,
            new PartitionIntakeState.BrokerTimeConfiguration(30_000, 0, 5_000),
            2,
            runnable -> new Thread(runnable, "replay-intake-owner"));
    }

    public ReplayIntakeOwner(
        @NonNull ReplayIntakeInputQueue inputQueue,
        @NonNull KafkaSourceInputQueue sourceInputs,
        @NonNull SourceAssemblySink assemblySink,
        @NonNull FatalHandler fatalHandler,
        @NonNull Metrics metrics,
        @NonNull RecordObserver recordObserver,
        @NonNull PartitionIntakeState.BrokerTimeConfiguration brokerTimeConfiguration
    ) {
        this(inputQueue, sourceInputs, assemblySink, fatalHandler, metrics, recordObserver,
            brokerTimeConfiguration, 2, runnable -> new Thread(runnable, "replay-intake-owner"));
    }

    public ReplayIntakeOwner(
        @NonNull ReplayIntakeInputQueue inputQueue,
        @NonNull KafkaSourceInputQueue sourceInputs,
        @NonNull SourceAssemblySink assemblySink,
        @NonNull FatalHandler fatalHandler,
        @NonNull Metrics metrics,
        @NonNull RecordObserver recordObserver,
        @NonNull PartitionIntakeState.BrokerTimeConfiguration brokerTimeConfiguration,
        int requestSupplyTarget
    ) {
        this(inputQueue, sourceInputs, assemblySink, fatalHandler, metrics, recordObserver,
            brokerTimeConfiguration, requestSupplyTarget,
            runnable -> new Thread(runnable, "replay-intake-owner"));
    }

    ReplayIntakeOwner(
        @NonNull ReplayIntakeInputQueue inputQueue,
        @NonNull KafkaSourceInputQueue sourceInputs,
        @NonNull SourceAssemblySink assemblySink,
        @NonNull FatalHandler fatalHandler,
        @NonNull Metrics metrics,
        @NonNull RecordObserver recordObserver,
        @NonNull PartitionIntakeState.BrokerTimeConfiguration brokerTimeConfiguration,
        int requestSupplyTarget,
        @NonNull ThreadFactory threadFactory
    ) {
        if (requestSupplyTarget <= 0) {
            throw new IllegalArgumentException("requestSupplyTarget must be positive");
        }
        this.inputQueue = inputQueue;
        this.sourceInputs = sourceInputs;
        this.metrics = metrics;
        this.recordObserver = recordObserver;
        this.brokerTimeConfiguration = brokerTimeConfiguration;
        this.requestSupplyTarget = requestSupplyTarget;
        this.assemblySink = new ObservedSourceAssemblySink(assemblySink);
        this.fatalHandler = fatalHandler;
        this.ownerThread = Objects.requireNonNull(threadFactory.newThread(this::runLoop));
        this.ownerThreadGuard = new OwnerThreadGuard(
            "replay intake owner",
            () -> Thread.currentThread() == ownerThread
        );
    }

    public void start() {
        if (started) {
            throw new IllegalStateException("replay-intake owner already started");
        }
        started = true;
        metrics.ownerStarted();
        ownerThread.start();
    }

    public CompletionStage<Void> termination() {
        return termination.minimalCompletionStage();
    }

    public boolean isOwnerThread() {
        return Thread.currentThread() == ownerThread;
    }

    /**
     * Applies one input synchronously for deterministic component fixtures.
     *
     * <p>Production construction always starts the owner and submits through {@link ReplayIntakeInputQueue}.
     * Guard-exempt by construction rather than by exception: while the owner thread has not been started,
     * nothing else can be touching this state.
     */
    public void applyOnCallingThread(@NonNull ReplayIntakeInput input) {
        if (started) {
            throw new IllegalStateException(
                "this owner is running its own thread; submit to its queue instead of applying inline"
            );
        }
        apply(input);
    }

    /**
     * The state for one generation, for a test that needs to read what applying a record did to it.
     *
     * <p>Read-only in effect: the state's own mutators require the owner thread.
     */
    public java.util.Optional<PartitionIntakeState> partitionState(@NonNull PartitionGenerationId generation) {
        return java.util.Optional.ofNullable(partitions.get(generation));
    }

    // ---------------------------------------------------------------- the loop

    private void runLoop() {
        try {
            while (true) {
                switch (inputQueue.takeEntry()) {
                    case ReplayIntakeInputQueue.SubmittedInput submitted -> {
                        try {
                            apply(submitted.input());
                            if (submitted.handled() != null) {
                                submitted.handled().complete(null);
                            }
                        } catch (Throwable failure) {
                            if (submitted.handled() != null) {
                                submitted.handled().completeExceptionally(failure);
                            }
                            throw failure;
                        }
                    }
                    case ReplayIntakeInputQueue.ProcessingFence fence ->
                        fence.handled().complete(null);
                    case ReplayIntakeInputQueue.StopAfterDraining ignored -> {
                        metrics.ownerStoppedAfterDraining();
                        termination.complete(null);
                        return;
                    }
                }
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            failOwner(new Error("Replay-intake owner was interrupted", interrupted));
        } catch (Throwable failure) {
            failOwner(new Error("Replay-intake owner failed unexpectedly", failure));
        } finally {
            inputQueue.closeNow();
        }
    }

    private void failOwner(Error failure) {
        termination.completeExceptionally(failure);
        fatalHandler.onFatal(failure);
    }

    /**
     * {@code §4.1}'s family, switched exhaustively with no {@code default}: adding a variant must break this
     * switch rather than being silently ignored ({@code replayerLLD §4}).
     */
    private void apply(ReplayIntakeInput input) {
        switch (input) {
            case ReplayIntakeInput.PartitionGenerationAssigned assigned -> applyGenerationAssigned(assigned);
            case ReplayIntakeInput.PartitionRecordBatch batch -> applyRecordBatch(batch);
            case RequestLifecycleInput.RequestProcessingFinished finished ->
                applyRequestProcessingFinished(finished);
            case ReplayIntakeInput.GracefulGenerationCancellation graceful ->
                applyGracefulGenerationCancellation(graceful);
            case ReplayIntakeInput.ForceGenerationCancellation forced ->
                applyForceGenerationCancellation(forced);
            case ReplayIntakeInput.ConnectionCleanupFinished finished ->
                applyConnectionCleanupFinished(finished);
            case ReplayIntakeInput.FinalizedArchivePartitionEnd finalized ->
                applyFinalizedArchivePartitionEnd(finalized);
            case RequestLifecycleInput.ConnectionRequestFinished finished ->
                applyConnectionRequestFinished(finished);
            case ReplayIntakeInput.ConnectionOwnerFinished finished ->
                applyConnectionOwnerFinished(finished);
        }
        metrics.inputApplied(inputKind(input));
        recomputeDemandAfterInput();
    }

    private static InputKind inputKind(ReplayIntakeInput input) {
        return switch (input) {
            case ReplayIntakeInput.PartitionGenerationAssigned ignored ->
                InputKind.PARTITION_GENERATION_ASSIGNED;
            case ReplayIntakeInput.PartitionRecordBatch ignored -> InputKind.PARTITION_RECORD_BATCH;
            case ReplayIntakeInput.GracefulGenerationCancellation ignored ->
                InputKind.GRACEFUL_GENERATION_CANCELLATION;
            case ReplayIntakeInput.ForceGenerationCancellation ignored ->
                InputKind.FORCE_GENERATION_CANCELLATION;
            case ReplayIntakeInput.FinalizedArchivePartitionEnd ignored ->
                InputKind.FINALIZED_ARCHIVE_PARTITION_END;
            case RequestLifecycleInput.ConnectionRequestFinished ignored ->
                InputKind.CONNECTION_REQUEST_FINISHED;
            case RequestLifecycleInput.RequestProcessingFinished ignored ->
                InputKind.REQUEST_PROCESSING_FINISHED;
            case ReplayIntakeInput.ConnectionOwnerFinished ignored ->
                InputKind.CONNECTION_OWNER_FINISHED;
            case ReplayIntakeInput.ConnectionCleanupFinished ignored ->
                InputKind.CONNECTION_CLEANUP_FINISHED;
        };
    }

    // ---------------------------------------------------------------- inputs

    /**
     * Creates the corresponding {@link PartitionIntakeState}.
     */
    private void applyGenerationAssigned(ReplayIntakeInput.PartitionGenerationAssigned assigned) {
        var generation = assigned.generation();
        var existing = partitions.putIfAbsent(generation, newPartitionState(generation));
        if (existing != null) {
            throw new IllegalStateException("partition generation was already assigned: " + generation);
        }
    }

    private PartitionIntakeState newPartitionState(PartitionGenerationId generation) {
        return new PartitionIntakeState(
            generation,
            this::isOwnerThreadOrUnstarted,
            this::submitRecordProcessingFinished,
            metrics::activeRecordTrackersChanged,
            metrics::recordTrackerRetired,
            brokerTimeConfiguration,
            metrics::retryReadySupplyChanged
        );
    }

    /**
     * {@code §4.1}: "Applies every record in order, closes that batch request, recomputes demand, and may
     * submit the next request."
     */
    private void applyRecordBatch(ReplayIntakeInput.PartitionRecordBatch batch) {
        var state = partitions.get(batch.requestId().generation());
        if (state == null || !state.acceptsRecordApplication()) {
            metrics.staleGenerationInputIgnored(InputKind.PARTITION_RECORD_BATCH);
            return;
        }
        var entitlement = state.beginApplyingBatch(batch.requestId());
        // §16: the first capture-protocol violation kills the whole replay. Completion and cleanup inputs may
        // still drain already-admitted work, but no queued batch from any partition may admit another record.
        if (captureProtocolViolationRecord != null) {
            metrics.recordBatchRejectedAfterProtocolViolation();
            state.finishApplyingBatch(batch.requestId(), entitlement);
            metrics.batchEntitlementResolved(entitlement);
            return;
        }
        try {
            for (var record : batch.records()) {
                if (!applyRecord(state, record)) {
                    break;
                }
            }
        } finally {
            state.finishApplyingBatch(batch.requestId(), entitlement);
            metrics.batchEntitlementResolved(entitlement);
        }
    }

    /**
     * {@code §4.1}: marks this request's processing complete for every record containing its observations.
     *
     * <p>{@code §8.3} is what makes that removal narrow — only this request's association leaves each record,
     * so a record shared with another request stays unfinished.
     */
    private void applyRequestProcessingFinished(
        RequestLifecycleInput.RequestProcessingFinished finished
    ) {
        var state = partitions.get(finished.generation());
        if (state == null || !state.hasRequest(finished.requestId())) {
            metrics.staleGenerationInputIgnored(InputKind.REQUEST_PROCESSING_FINISHED);
            return;
        }
        state.associationFinished(new RecordAssociationId.Request(finished.requestId()));
        state.removeRequest(finished.requestId());
    }

    private void applyConnectionRequestFinished(
        RequestLifecycleInput.ConnectionRequestFinished finished
    ) {
        var state = partitions.get(finished.generation());
        if (state == null
            || !state.applyConnectionRequestFinishedInput(finished.requestId())) {
            metrics.staleGenerationInputIgnored(InputKind.CONNECTION_REQUEST_FINISHED);
        }
    }

    private void applyGracefulGenerationCancellation(
        ReplayIntakeInput.GracefulGenerationCancellation graceful
    ) {
        var state = partitions.get(graceful.generation());
        if (state == null) {
            metrics.staleGenerationInputIgnored(InputKind.GRACEFUL_GENERATION_CANCELLATION);
            return;
        }
        var previousState = state.cancellationState();
        var plan = state.beginGracefulCancellation(graceful.grace());
        if (previousState == PartitionIntakeState.GenerationCancellationState.ACTIVE) {
            metrics.generationGraceStarted(graceful.grace());
        }
        plan.connectionOwners().forEach(connectionProcessingId ->
            assemblySink.onGracefulGenerationCancellation(connectionProcessingId, graceful.grace())
        );
        if (plan.cleanupComplete()) {
            finishGenerationCleanup(state);
        }
    }

    private void applyForceGenerationCancellation(
        ReplayIntakeInput.ForceGenerationCancellation forced
    ) {
        var state = partitions.get(forced.generation());
        if (state == null) {
            metrics.staleGenerationInputIgnored(InputKind.FORCE_GENERATION_CANCELLATION);
            return;
        }
        var previousState = state.cancellationState();
        var plan = state.beginForceCancellation();
        if (previousState != PartitionIntakeState.GenerationCancellationState.REVOCATION_FORCED) {
            metrics.generationForceStarted();
        }
        plan.connectionOwners().forEach(assemblySink::onForceGenerationCancellation);
        if (plan.cleanupComplete()) {
            finishGenerationCleanup(state);
        }
    }

    private void applyFinalizedArchivePartitionEnd(
        ReplayIntakeInput.FinalizedArchivePartitionEnd finalized
    ) {
        requireGeneration(finalized.generation()).resolveAllRetryBoundaries().forEach(requestId -> {
            assemblySink.onSourceResponseUnavailableForRetry(requestId);
            metrics.retrySourceResponseUnavailable();
        });
    }

    private void applyConnectionOwnerFinished(ReplayIntakeInput.ConnectionOwnerFinished finished) {
        var state = partitions.get(finished.generation());
        if (state == null) {
            metrics.staleGenerationInputIgnored(InputKind.CONNECTION_OWNER_FINISHED);
            return;
        }
        if (!finished.connectionProcessingId().generation().equals(state.generation())) {
            metrics.staleGenerationInputIgnored(InputKind.CONNECTION_OWNER_FINISHED);
            return;
        }
        if (state.cancellationState() == PartitionIntakeState.GenerationCancellationState.ACTIVE) {
            state.removeConnectionOwner(finished.connectionProcessingId());
            assemblySink.onConnectionOwnerFinished(finished.connectionProcessingId());
            return;
        }
        applyConnectionTerminalAcknowledgement(
            state,
            finished.connectionProcessingId(),
            InputKind.CONNECTION_OWNER_FINISHED
        );
    }

    private void applyConnectionCleanupFinished(
        ReplayIntakeInput.ConnectionCleanupFinished finished
    ) {
        var state = partitions.get(finished.generation());
        if (state == null) {
            metrics.staleGenerationInputIgnored(InputKind.CONNECTION_CLEANUP_FINISHED);
            return;
        }
        applyConnectionTerminalAcknowledgement(
            state,
            finished.connectionProcessingId(),
            InputKind.CONNECTION_CLEANUP_FINISHED
        );
    }

    private void applyConnectionTerminalAcknowledgement(
        PartitionIntakeState state,
        ConnectionProcessingId connectionProcessingId,
        InputKind inputKind
    ) {
        if (!connectionProcessingId.generation().equals(state.generation())) {
            metrics.staleGenerationInputIgnored(inputKind);
            return;
        }
        var acknowledgement = state.acknowledgeConnectionCleanup(connectionProcessingId);
        if (!acknowledgement.accepted()) {
            metrics.staleGenerationInputIgnored(inputKind);
            return;
        }
        assemblySink.onConnectionOwnerFinished(connectionProcessingId);
        if (acknowledgement.cleanupComplete()) {
            finishGenerationCleanup(state);
        }
    }

    private void finishGenerationCleanup(PartitionIntakeState state) {
        var generation = state.generation();
        state.discardCancelledGenerationBookkeeping();
        if (!partitions.remove(generation, state)) {
            throw new IllegalStateException("generation disappeared during cleanup: " + generation);
        }
        submitRequired(new KafkaSourceInput.GenerationCleanupFinished(generation));
        metrics.generationCleanupFinished();
    }

    /**
     * The ordinary end-of-input pass from {@code kafkaLLD §13}, over every active generation.
     */
    private void recomputeDemandAfterInput() {
        if (captureProtocolViolationRecord != null) {
            return;
        }
        for (var state : partitions.values()) {
            var demandOpen = state.demandOpen(requestSupplyTarget);
            metrics.demandEvaluated(demandOpen);
            state.requestNextBatchIfNeeded(requestSupplyTarget).ifPresent(requestId -> {
                submitRequired(new KafkaSourceInput.RequestNextPartitionBatch(requestId));
                metrics.batchRequested();
            });
        }
    }

    // ---------------------------------------------------------------- §7, one record

    /**
     * Applies one record in the exact order {@code §7} defines, steps 1 through 10.
     *
     * <p>The order is the contract, not an implementation detail: step 3 validates time before the record is
     * used as time evidence, step 4 resolves boundaries this record crosses before step 7 applies the payload
     * that crosses them, and step 2 creates the tracker before any observation can associate with it. Each is
     * a rule about what must not have happened yet.
     */
    // REBUILD-TRACE-START(G5,target): retain through the rebuild; remove in final pre-merge cleanup.
    // CapturedTrafficToHttpTransactionAccumulator.accept -> ReplayIntakeOwner.applyRecord
    // REBUILD-TRACE-END(G5,target)
    // REBUILD-TRACE-START(G6,target): retain through the rebuild; remove in final pre-merge cleanup.
    // CapturedTrafficToHttpTransactionAccumulator.addObservationToAccumulation(
    //     Accumulation,ITrafficStreamKey,TrafficObservation,KafkaRecordId) ->
    //     ReplayIntakeOwner.applyRecord
    // REBUILD-TRACE-END(G6,target)
    private boolean applyRecord(PartitionIntakeState state, ApplicationKafkaRecord record) {
        // 1. Validate that its generation is active for application.
        if (!state.generation().equals(record.recordId().generation())
            || !state.acceptsRecordApplication()) {
            throw new IllegalStateException(
                "record " + record.recordId() + " cannot apply to generation " + state.generation()
            );
        }
        // 2. Create its RecordWorkTracker.
        state.registerRecord(record.recordId());
        // 3. Validate partition LogAppendTime movement before using the record as time evidence.
        try {
            state.observeLogAppendTime(record.logAppendTimeMillis());
        } catch (PartitionIntakeState.BrokerTimeViolation violation) {
            metrics.brokerTimeViolation();
            throw violation;
        }
        // 4. Resolve retry-source-response boundaries crossed by this higher-offset record.
        state.resolveRetryBoundaries(record.logAppendTimeMillis()).forEach(requestId -> {
            assemblySink.onSourceResponseUnavailableForRetry(requestId);
            metrics.retrySourceResponseUnavailable();
        });
        // 5. Apply broker-time expiration that this record proves for existing writer state.
        state.writersExpiredAt(record.logAppendTimeMillis()).forEach(writerNodeId -> {
            var expiration = state.expireConnectionsForWriter(writerNodeId);
            metrics.sourceConnectionsExpired(expiration.expiredSourceConnections());
            expiration.targetOwnersToExpire().forEach(connectionProcessingId -> {
                assemblySink.onCapturedConnectionExpired(connectionProcessingId);
                metrics.targetConnectionExpirationSent();
            });
        });

        // 6. Decode CaptureRecord.payload. 7. Apply the recognized payload.
        recordObserver.beforeRecordApplied(record);
        if (!applyPayload(state, record)) {
            state.captureProtocolViolationAt(record.recordId());
            return false;
        }

        // 8. Close the tracker to new associations. 9. Emit completion if nothing is outstanding.
        state.closeRecordToNewAssociations(record.recordId());
        metrics.recordApplied();

        // 10. The enclosing input runs the demand pass after this complete batch, so a request can never be
        // issued while the current batch is still being applied.
        return true;
    }

    /**
     * {@code §7.1}'s exhaustive payload switch. No {@code default} branch and no trial decoding of several
     * protobuf types: the active {@code payload} field is the only record-type discriminator.
     */
    // REBUILD-TRACE-START(G5,target): retain through the rebuild; remove in final pre-merge cleanup.
    // CapturedTrafficToHttpTransactionAccumulator.accept -> ReplayIntakeOwner.applyPayload
    // REBUILD-TRACE-END(G5,target)
    private boolean applyPayload(PartitionIntakeState state, ApplicationKafkaRecord record) {
        var envelope = record.envelope();
        return switch (envelope.getPayloadCase()) {
            case TRAFFICSTREAM -> applyTrafficStream(state, record, envelope.getTrafficStream());
            case WRITERPARTITIONHEARTBEAT -> {
                var heartbeat = envelope.getWriterPartitionHeartbeat();
                metrics.writerTimeTransition(
                    state.observeHeartbeat(
                        heartbeat.getWriterNodeId(),
                        record.logAppendTimeMillis(),
                        heartbeat.getHeartbeatIntervalMillis(),
                        heartbeat.hasEmittedAtMillis() ? heartbeat.getEmittedAtMillis() : null
                    )
                );
                yield true;
            }
            // §7.1: "CaptureCapabilityProbe creates no writer or connection state." Its timestamp may still
            // serve as time evidence, which step 3 has already recorded.
            case CAPTURECAPABILITYPROBE -> true;
            case PAYLOAD_NOT_SET -> {
                submitProtocolViolation(record.recordId(), "CaptureRecord carries no recognized payload");
                yield false;
            }
        };
    }

    /**
     * Routes a {@code TrafficStream}'s observations to the connection lifetime they belong to, recording each
     * observation's record associations as it is applied.
     *
     * <p>Associations are recorded per observation rather than per record, which is what lets one record hold
     * several request identities ({@code §8.3}) — a record carrying the end of request <em>N</em> and the start
     * of <em>N+1</em> has exactly both.
     */
    // REBUILD-TRACE-START(G5,target): retain through the rebuild; remove in final pre-merge cleanup.
    // CapturedTrafficToHttpTransactionAccumulator.accept -> ReplayIntakeOwner.applyTrafficStream
    // REBUILD-TRACE-END(G5,target)
    private boolean applyTrafficStream(
        PartitionIntakeState state,
        ApplicationKafkaRecord record,
        TrafficStream trafficStream
    ) {
        metrics.writerTimeTransition(
            state.observeTrafficWriter(trafficStream.getNodeId(), record.logAppendTimeMillis())
        );
        var capturedConnectionId =
            new CapturedConnectionId(trafficStream.getNodeId(), trafficStream.getConnectionId());
        SourceConnectionState connection;
        try {
            connection = state.connectionFor(capturedConnectionId, trafficStream, assemblySink);
        } catch (SourceConnectionState.CaptureProtocolViolation violation) {
            submitProtocolViolation(record.recordId(), violation.getMessage());
            return false;
        }
        var recordContext = record.replayContext();
        try (var trafficContext = recordContext == null
            ? null
            : recordContext.createTrafficStreamContext(
                TrafficStreamUtils.getTrafficStreamIndex(trafficStream)
            )) {
            for (var observation : trafficStream.getSubStreamList()) {
                SourceConnectionState.ObservationOutcome outcome;
                try {
                    outcome = connection.apply(
                        observation,
                        record.recordId(),
                        record.logAppendTimeMillis(),
                        trafficContext
                    );
                } catch (SourceConnectionState.CaptureProtocolViolation violation) {
                    // §16: invalid capture input stops admission through the source rather than terminating here.
                    submitProtocolViolation(record.recordId(), violation.getMessage());
                    return false;
                }
                // Additions first: §8.2 forbids a transient gap, and one observation can both finish a request's
                // assembly identity and begin the next request's.
                outcome.associationsToAdd()
                    .forEach(association -> state.associate(record.recordId(), association));
                outcome.relabels()
                    .forEach(relabel -> state.relabelAll(relabel.from(), relabel.to()));
                outcome.associationsFinished().forEach(state::associationFinished);
                if (outcome.lifetimeEnded()) {
                    state.retireLifetime(connection);
                }
            }
        }
        return true;
    }

    // ---------------------------------------------------------------- outputs

    /** {@code §7} step 9, and the only input that may advance a commit position ({@code §4.2}). */
    private void submitRecordProcessingFinished(KafkaRecordId recordId) {
        submitRequired(new KafkaSourceInput.RecordProcessingFinished(recordId));
    }

    private void submitProtocolViolation(KafkaRecordId recordId, String diagnostic) {
        if (captureProtocolViolationRecord != null) {
            return;
        }
        captureProtocolViolationRecord = recordId;
        metrics.captureProtocolViolation();
        submitRequired(new KafkaSourceInput.CaptureProtocolViolationDetected(recordId, diagnostic));
    }

    private final class ObservedSourceAssemblySink implements SourceAssemblySink {
        private final SourceAssemblySink delegate;

        private ObservedSourceAssemblySink(SourceAssemblySink delegate) {
            this.delegate = delegate;
        }

        @Override
        // REBUILD-TRACE-START(G5,target): retain through the rebuild; remove in final pre-merge cleanup.
        // CapturedTrafficToHttpTransactionAccumulator.SpanWrappingAccumulationCallbacks.onRequestReceived ->
        //     ReplayIntakeOwner.ObservedSourceAssemblySink.onRequestReconstituted
        // REBUILD-TRACE-END(G5,target)
        public void onRequestReconstituted(
            ReplayRequestId replayRequestId,
            long capturedRequestOrdinal,
            HttpMessageAndTimestamp.Request request,
            Instant requestFirstByteSourceTime,
            Instant requestEndOfMessageSourceTime,
            long requestCompletingLogAppendTime
        ) {
            requireGeneration(replayRequestId.connectionProcessingId().generation())
                .registerRequest(replayRequestId, requestCompletingLogAppendTime);
            metrics.requestReconstituted();
            delegate.onRequestReconstituted(
                replayRequestId,
                capturedRequestOrdinal,
                request,
                requestFirstByteSourceTime,
                requestEndOfMessageSourceTime,
                requestCompletingLogAppendTime
            );
        }

        @Override
        public void onRequestReconstituted(
            HttpMessageAndTimestamp.Request request,
            Instant requestEndOfMessageSourceTime,
            long requestCompletingLogAppendTime,
            org.opensearch.migrations.replay.tracing.IReplayContexts.IRequestContext replayContext
        ) {
            var requestId = replayContext.getRequestId();
            requireGeneration(requestId.connectionProcessingId().generation())
                .registerRequest(requestId, requestCompletingLogAppendTime);
            delegate.onRequestReconstituted(
                request,
                requestEndOfMessageSourceTime,
                requestCompletingLogAppendTime,
                replayContext
            );
        }

        @Override
        public void onSourceInterimResponse(
            ReplayRequestId replayRequestId,
            HttpMessageAndTimestamp.InterimResponse interimResponse
        ) {
            delegate.onSourceInterimResponse(replayRequestId, interimResponse);
            metrics.interimResponseObserved();
        }

        @Override
        public void onSourceResponseComplete(
            ReplayRequestId replayRequestId,
            HttpMessageAndTimestamp.Response response,
            boolean keptAlive
        ) {
            var state = requireGeneration(replayRequestId.connectionProcessingId().generation());
            if (state.sourceResponseCompleted(replayRequestId)) {
                delegate.onRetrySourceResponseComplete(replayRequestId, response);
                metrics.retrySourceResponseCompleted();
            }
            delegate.onSourceResponseComplete(replayRequestId, response, keptAlive);
            metrics.responseCompleted(keptAlive);
        }

        @Override
        // REBUILD-TRACE-START(G5,target): retain through the rebuild; remove in final pre-merge cleanup.
        // CapturedTrafficToHttpTransactionAccumulator.SpanWrappingAccumulationCallbacks.onTrafficStreamsExpired ->
        //     ReplayIntakeOwner.ObservedSourceAssemblySink.onSourceResponseIncomplete
        // REBUILD-TRACE-END(G5,target)
        public void onSourceResponseIncomplete(
            ReplayRequestId replayRequestId,
            IncompleteReason reason
        ) {
            var state = requireGeneration(replayRequestId.connectionProcessingId().generation());
            if (state.sourceResponseIncomplete(replayRequestId)) {
                delegate.onSourceResponseUnavailableForRetry(replayRequestId);
                metrics.retrySourceResponseUnavailable();
            }
            delegate.onSourceResponseIncomplete(replayRequestId, reason);
            metrics.responseIncomplete(reason);
        }

        @Override
        // REBUILD-TRACE-START(G5,target): retain through the rebuild; remove in final pre-merge cleanup.
        // CapturedTrafficToHttpTransactionAccumulator.SpanWrappingAccumulationCallbacks.onConnectionClose ->
        //     ReplayIntakeOwner.ObservedSourceAssemblySink.onCapturedClose
        // REBUILD-TRACE-END(G5,target)
        public void onCapturedClose(
            ConnectionProcessingId connectionProcessingId,
            long capturedOrdinal,
            Instant closeTime
        ) {
            delegate.onCapturedClose(connectionProcessingId, capturedOrdinal, closeTime);
            metrics.capturedCloseAccepted();
        }

        @Override
        public void onConnectionOwnerFinished(ConnectionProcessingId connectionProcessingId) {
            delegate.onConnectionOwnerFinished(connectionProcessingId);
        }

        @Override
        public void onRetrySourceResponseComplete(
            ReplayRequestId replayRequestId,
            HttpMessageAndTimestamp.Response response
        ) {
            delegate.onRetrySourceResponseComplete(replayRequestId, response);
        }

        @Override
        public void onSourceResponseUnavailableForRetry(ReplayRequestId replayRequestId) {
            delegate.onSourceResponseUnavailableForRetry(replayRequestId);
        }

        @Override
        public void onCapturedConnectionExpired(ConnectionProcessingId connectionProcessingId) {
            delegate.onCapturedConnectionExpired(connectionProcessingId);
        }

        @Override
        public void onGracefulGenerationCancellation(
            ConnectionProcessingId connectionProcessingId,
            CancellationGrace grace
        ) {
            delegate.onGracefulGenerationCancellation(connectionProcessingId, grace);
        }

        @Override
        public void onForceGenerationCancellation(ConnectionProcessingId connectionProcessingId) {
            delegate.onForceGenerationCancellation(connectionProcessingId);
        }
    }

    /**
     * {@code §4.2}: "Required submission reports acceptance; the queue is not allowed to silently discard an
     * input." The queue reports refusal by throwing, which reaches the process-failure boundary through the
     * intake loop — the record this input concerns would otherwise never reach a disposition.
     */
    private void submitRequired(KafkaSourceInput input) {
        sourceInputs.submit(input);
    }

    private PartitionIntakeState requireGeneration(PartitionGenerationId generation) {
        var state = partitions.get(generation);
        if (state == null) {
            throw new IllegalStateException("no replay-intake state for generation " + generation);
        }
        return state;
    }

    /**
     * True on the owner thread, and also before the thread starts — a test drives the owner directly, and the
     * guard's question is "could anything else be touching this", which has only one answer while the one
     * thread that would is not running.
     */
    private boolean isOwnerThreadOrUnstarted() {
        return !started || isOwnerThread();
    }
}
