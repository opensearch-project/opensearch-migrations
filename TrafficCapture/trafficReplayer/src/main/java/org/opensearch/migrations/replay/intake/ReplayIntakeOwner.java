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
        default void requestReconstituted() {}
        default void responseCompleted(boolean keptAlive) {}
        default void responseIncomplete(SourceAssemblySink.IncompleteReason reason) {}
        default void capturedCloseAccepted() {}
        default void captureProtocolViolation() {}
        default void recordBatchRejectedAfterProtocolViolation() {}
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
            runnable -> new Thread(runnable, "replay-intake-owner"));
    }

    ReplayIntakeOwner(
        @NonNull ReplayIntakeInputQueue inputQueue,
        @NonNull KafkaSourceInputQueue sourceInputs,
        @NonNull SourceAssemblySink assemblySink,
        @NonNull FatalHandler fatalHandler,
        @NonNull Metrics metrics,
        @NonNull RecordObserver recordObserver,
        @NonNull ThreadFactory threadFactory
    ) {
        this.inputQueue = inputQueue;
        this.sourceInputs = sourceInputs;
        this.metrics = metrics;
        this.recordObserver = recordObserver;
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
                    case ReplayIntakeInputQueue.SubmittedInput submitted -> apply(submitted.input());
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
            case ReplayIntakeInput.RequestProcessingFinished finished -> applyRequestProcessingFinished(finished);
            // REBUILD-LIMBO-NOTE(G8): §15.1 graceful cancellation, §15.2 force cancellation and §15.3's
            // cleanup tracker, which is what sends GenerationCleanupFinished back to the source.
            case ReplayIntakeInput.GracefulGenerationCancellation ignored -> unimplemented(input, "G8");
            case ReplayIntakeInput.ForceGenerationCancellation ignored -> unimplemented(input, "G8");
            case ReplayIntakeInput.ConnectionCleanupFinished ignored -> unimplemented(input, "G8");
            // REBUILD-LIMBO-NOTE(G6): §14's finite-input expiration rules, which need §10's writer time state.
            case ReplayIntakeInput.FinalizedArchivePartitionEnd ignored -> unimplemented(input, "G6");
            // REBUILD-LIMBO-NOTE(G7): §13's supply count is what this input changes.
            case ReplayIntakeInput.ConnectionRequestFinished ignored -> unimplemented(input, "G7");
            // REBUILD-LIMBO-NOTE(G5): removes the connection-owner mapping; §4.1 says it finishes no record.
            case ReplayIntakeInput.ConnectionOwnerFinished ignored -> unimplemented(input, "G5");
        }
        metrics.inputApplied(inputKind(input));
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
            case ReplayIntakeInput.ConnectionRequestFinished ignored ->
                InputKind.CONNECTION_REQUEST_FINISHED;
            case ReplayIntakeInput.RequestProcessingFinished ignored ->
                InputKind.REQUEST_PROCESSING_FINISHED;
            case ReplayIntakeInput.ConnectionOwnerFinished ignored ->
                InputKind.CONNECTION_OWNER_FINISHED;
            case ReplayIntakeInput.ConnectionCleanupFinished ignored ->
                InputKind.CONNECTION_CLEANUP_FINISHED;
        };
    }

    private void unimplemented(ReplayIntakeInput input, String milestone) {
        throw new IllegalStateException(
            "replay intake received " + input.getClass().getSimpleName() + ", which " + milestone
                + " implements; reaching it now means something was wired ahead of its handler"
        );
    }

    // ---------------------------------------------------------------- inputs

    /**
     * Creates the corresponding {@link PartitionIntakeState}.
     *
     * <p>REBUILD-LIMBO-NOTE(G7): {@code §4.1}'s ordinary end-of-input demand pass requires the supply count
     * and explicit batch-request state introduced by G7.
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
            this::submitRecordProcessingFinished
        );
    }

    /**
     * {@code §4.1}: "Applies every record in order, closes that batch request, recomputes demand, and may
     * submit the next request."
     */
    private void applyRecordBatch(ReplayIntakeInput.PartitionRecordBatch batch) {
        // §16: the first capture-protocol violation kills the whole replay. Completion and cleanup inputs may
        // still drain already-admitted work, but no queued batch from any partition may admit another record.
        if (captureProtocolViolationRecord != null) {
            metrics.recordBatchRejectedAfterProtocolViolation();
            return;
        }
        var state = requireGeneration(batch.requestId().generation());
        for (var record : batch.records()) {
            if (!applyRecord(state, record)) {
                break;
            }
        }
        // REBUILD-LIMBO-NOTE(G7): closing the batch request, recomputing demand and submitting the next
        // RequestNextPartitionBatch are §13's, and the partitionBatchState that makes "cannot request the next
        // batch before applying this one" checkable lives there too.
    }

    /**
     * {@code §4.1}: marks this request's processing complete for every record containing its observations.
     *
     * <p>{@code §8.3} is what makes that removal narrow — only this request's association leaves each record,
     * so a record shared with another request stays unfinished.
     */
    private void applyRequestProcessingFinished(ReplayIntakeInput.RequestProcessingFinished finished) {
        // REBUILD-LIMBO-NOTE(G8): cancellation can make a late completion stale after generation cleanup.
        // G8 adds the cancellation/cleanup state that decides whether to consume or ignore that late input.
        requireGeneration(finished.generation())
            .associationFinished(new RecordAssociationId.Request(finished.requestId()));
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
    private boolean applyRecord(PartitionIntakeState state, ApplicationKafkaRecord record) {
        // 1. Validate that its generation is active for application.
        // REBUILD-LIMBO-NOTE(G8): §15 cancellation state extends this check beyond identity equality: a
        // generation being cancelled is no longer active for new record application.
        if (!state.generation().equals(record.recordId().generation())) {
            throw new IllegalStateException(
                "record " + record.recordId() + " does not belong to generation " + state.generation()
            );
        }
        recordObserver.beforeRecordApplied(record);
        // 2. Create its RecordWorkTracker.
        state.registerRecord(record.recordId());
        // 3. Validate partition LogAppendTime movement before using the record as time evidence.
        state.observeLogAppendTime(record.logAppendTimeMillis());
        // 4. Resolve retry-source-response boundaries crossed by this higher-offset record.
        // 5. Apply broker-time expiration that this record proves for existing writer state.
        // REBUILD-LIMBO-NOTE(G6): steps 4 and 5. §11's boundary resolution must emit
        // SourceResponseUnavailableForRetry *before* step 7 applies the crossing payload, and §10.3's
        // expiration evaluation runs here so a later record can prove another writer's expiry.

        // 6. Decode CaptureRecord.payload. 7. Apply the recognized payload.
        if (!applyPayload(state, record)) {
            state.captureProtocolViolationAt(record.recordId());
            return false;
        }

        // 8. Close the tracker to new associations. 9. Emit completion if nothing is outstanding.
        state.closeRecordToNewAssociations(record.recordId());
        metrics.recordApplied();

        // 10. Recompute partition demand.
        // REBUILD-LIMBO-NOTE(G7): §13's recomputation, which has no count to recompute until that milestone.
        return true;
    }

    /**
     * {@code §7.1}'s exhaustive payload switch. No {@code default} branch and no trial decoding of several
     * protobuf types: the active {@code payload} field is the only record-type discriminator.
     */
    private boolean applyPayload(PartitionIntakeState state, ApplicationKafkaRecord record) {
        var envelope = record.envelope();
        return switch (envelope.getPayloadCase()) {
            case TRAFFICSTREAM -> applyTrafficStream(state, record, envelope.getTrafficStream());
            // REBUILD-LIMBO-NOTE(G6): §10.2's writer time state is what a heartbeat updates. Until then a
            // heartbeat is correctly a record with no associations, which §17.1 requires to complete
            // immediately -- so this branch is already right about record accounting and incomplete only
            // about time.
            case WRITERPARTITIONHEARTBEAT -> true;
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
    private boolean applyTrafficStream(
        PartitionIntakeState state,
        ApplicationKafkaRecord record,
        TrafficStream trafficStream
    ) {
        var capturedConnectionId =
            new CapturedConnectionId(trafficStream.getNodeId(), trafficStream.getConnectionId());
        var connection = state.connectionFor(capturedConnectionId, trafficStream, assemblySink);
        for (var observation : trafficStream.getSubStreamList()) {
            SourceConnectionState.ObservationOutcome outcome;
            try {
                outcome = connection.apply(observation, record.recordId(), record.logAppendTimeMillis());
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
        public void onRequestReconstituted(
            ReplayRequestId replayRequestId,
            long capturedRequestOrdinal,
            HttpMessageAndTimestamp.Request request,
            Instant sourceEventTime,
            long requestCompletingLogAppendTime
        ) {
            delegate.onRequestReconstituted(
                replayRequestId,
                capturedRequestOrdinal,
                request,
                sourceEventTime,
                requestCompletingLogAppendTime
            );
            metrics.requestReconstituted();
        }

        @Override
        public void onSourceResponseComplete(
            ReplayRequestId replayRequestId,
            HttpMessageAndTimestamp.Response response,
            boolean keptAlive
        ) {
            delegate.onSourceResponseComplete(replayRequestId, response, keptAlive);
            metrics.responseCompleted(keptAlive);
        }

        @Override
        public void onSourceResponseIncomplete(
            ReplayRequestId replayRequestId,
            IncompleteReason reason
        ) {
            delegate.onSourceResponseIncomplete(replayRequestId, reason);
            metrics.responseIncomplete(reason);
        }

        @Override
        public void onCapturedClose(ConnectionProcessingId connectionProcessingId, Instant closeTime) {
            delegate.onCapturedClose(connectionProcessingId, closeTime);
            metrics.capturedCloseAccepted();
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
