/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.migrations.replay.intake;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ThreadFactory;

import org.opensearch.migrations.replay.identity.CapturedConnectionId;
import org.opensearch.migrations.replay.identity.KafkaRecordId;
import org.opensearch.migrations.replay.identity.PartitionGenerationId;
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

    /** Where an invariant failure on the intake thread goes. {@code replayerLLD §4}'s process boundary. */
    public interface FatalHandler {
        void onFatal(Error failure);
    }

    private final ReplayIntakeInputQueue inputQueue;
    private final KafkaSourceInputQueue sourceInputs;
    private final SourceAssemblySink assemblySink;
    private final FatalHandler fatalHandler;
    private final Thread ownerThread;
    private final OwnerThreadGuard ownerThreadGuard;
    private final CompletableFuture<Void> termination = new CompletableFuture<>();

    private final Map<PartitionGenerationId, PartitionIntakeState> partitions = new LinkedHashMap<>();
    private boolean running = true;
    private volatile boolean started;

    public ReplayIntakeOwner(
        @NonNull ReplayIntakeInputQueue inputQueue,
        @NonNull KafkaSourceInputQueue sourceInputs,
        @NonNull SourceAssemblySink assemblySink,
        @NonNull FatalHandler fatalHandler
    ) {
        this(inputQueue, sourceInputs, assemblySink, fatalHandler,
            runnable -> new Thread(runnable, "replay-intake-owner"));
    }

    ReplayIntakeOwner(
        @NonNull ReplayIntakeInputQueue inputQueue,
        @NonNull KafkaSourceInputQueue sourceInputs,
        @NonNull SourceAssemblySink assemblySink,
        @NonNull FatalHandler fatalHandler,
        @NonNull ThreadFactory threadFactory
    ) {
        this.inputQueue = inputQueue;
        this.sourceInputs = sourceInputs;
        this.assemblySink = assemblySink;
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
        ownerThread.start();
    }

    public CompletionStage<Void> termination() {
        return termination.minimalCompletionStage();
    }

    public boolean isOwnerThread() {
        return Thread.currentThread() == ownerThread;
    }

    /**
     * Applies one input on the calling thread, for a caller that drives this owner instead of starting it.
     *
     * <p>There is one correctness model either way: the same inputs applied in the same order by one thread.
     * What {@link #start()} adds is a queue between the submitter and the applier, which a single-threaded
     * caller does not need — {@code --mode dump-http} reads a topic and applies each batch in turn, and would
     * otherwise need a barrier to know when intake had finished one. The design's barrier is
     * {@code RequestNextPartitionBatch} ({@code §4.2}), whose submission is {@code §13}'s and therefore G7's.
     *
     * <p>Guard-exempt by construction rather than by exception: the thread guard asks whether anything else
     * could be touching this state, and while the owner thread has not been started, nothing can.
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
     * <p>Package-private, and read-only in effect: the state's own mutators require the owner thread.
     */
    public java.util.Optional<PartitionIntakeState> partitionState(@NonNull PartitionGenerationId generation) {
        return java.util.Optional.ofNullable(partitions.get(generation));
    }

    // ---------------------------------------------------------------- the loop

    private void runLoop() {
        try {
            while (running) {
                apply(inputQueue.take());
            }
            termination.complete(null);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            failOwner(new Error("Replay-intake owner was interrupted", interrupted));
        } catch (Throwable failure) {
            failOwner(new Error("Replay-intake owner failed unexpectedly", failure));
        } finally {
            inputQueue.close();
        }
    }

    private void failOwner(Error failure) {
        running = false;
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
    }

    private void unimplemented(ReplayIntakeInput input, String milestone) {
        throw new IllegalStateException(
            "replay intake received " + input.getClass().getSimpleName() + ", which " + milestone
                + " implements; reaching it now means something was wired ahead of its handler"
        );
    }

    // ---------------------------------------------------------------- inputs

    /** {@code §4.1}: creates the corresponding {@link PartitionIntakeState} and nothing else. */
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
        var state = requireGeneration(batch.requestId().generation());
        batch.records().forEach(record -> applyRecord(state, record));
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
    private void applyRecord(PartitionIntakeState state, ApplicationKafkaRecord record) {
        // 1. Validate that its generation is active for application.
        if (!state.generation().equals(record.recordId().generation())) {
            throw new IllegalStateException(
                "record " + record.recordId() + " does not belong to generation " + state.generation()
            );
        }
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
        applyPayload(state, record);

        // 8. Close the tracker to new associations. 9. Emit completion if nothing is outstanding.
        state.closeRecordToNewAssociations(record.recordId());

        // 10. Recompute partition demand.
        // REBUILD-LIMBO-NOTE(G7): §13's recomputation, which has no count to recompute until that milestone.
    }

    /**
     * {@code §7.1}'s exhaustive payload switch. No {@code default} branch and no trial decoding of several
     * protobuf types: the active {@code payload} field is the only record-type discriminator.
     */
    private void applyPayload(PartitionIntakeState state, ApplicationKafkaRecord record) {
        var envelope = record.envelope();
        switch (envelope.getPayloadCase()) {
            case TRAFFICSTREAM -> applyTrafficStream(state, record, envelope.getTrafficStream());
            // REBUILD-LIMBO-NOTE(G6): §10.2's writer time state is what a heartbeat updates. Until then a
            // heartbeat is correctly a record with no associations, which §17.1 requires to complete
            // immediately -- so this branch is already right about record accounting and incomplete only
            // about time.
            case WRITERPARTITIONHEARTBEAT -> {
            }
            // §7.1: "CaptureCapabilityProbe creates no writer or connection state." Its timestamp may still
            // serve as time evidence, which step 3 has already recorded.
            case CAPTURECAPABILITYPROBE -> {
            }
            case PAYLOAD_NOT_SET -> submitProtocolViolation(
                record.recordId(),
                "CaptureRecord carries no recognized payload"
            );
        }
    }

    /**
     * Routes a {@code TrafficStream}'s observations to the connection lifetime they belong to, recording each
     * observation's record associations as it is applied.
     *
     * <p>Associations are recorded per observation rather than per record, which is what lets one record hold
     * several request identities ({@code §8.3}) — a record carrying the end of request <em>N</em> and the start
     * of <em>N+1</em> has exactly both.
     */
    private void applyTrafficStream(
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
                return;
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

    // ---------------------------------------------------------------- outputs

    /** {@code §7} step 9, and the only input that may advance a commit position ({@code §4.2}). */
    private void submitRecordProcessingFinished(KafkaRecordId recordId) {
        submitRequired(new KafkaSourceInput.RecordProcessingFinished(recordId));
    }

    private void submitProtocolViolation(KafkaRecordId recordId, String diagnostic) {
        submitRequired(new KafkaSourceInput.CaptureProtocolViolationDetected(recordId, diagnostic));
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
