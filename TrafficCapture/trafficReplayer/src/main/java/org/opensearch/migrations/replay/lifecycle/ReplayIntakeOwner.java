/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.migrations.replay.lifecycle;

// REBUILD-LIMBO(G11) -- nothing in this file is live yet. Javadoc is left outside the marked
// regions so it needs no escaping and keeps its blame; it documents code that is not compiled.
// Resolve each region to dead, keep, or refactor deliberately. If a member is deleted, delete its
// javadoc with it. See AGENTS.md section 8a.

// REBUILD-LIMBO-START(G11)
/*

import java.io.EOFException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ThreadFactory;
import java.util.function.Supplier;

import org.opensearch.migrations.replay.CapturedTrafficToHttpTransactionAccumulator;
import org.opensearch.migrations.replay.lifecycle.ReplayIdentity.PartitionGenerationId;
import org.opensearch.migrations.replay.lifecycle.ReplayIdentity.ReplayRequestId;
import org.opensearch.migrations.replay.tracing.ITrafficSourceContexts;
import org.opensearch.migrations.replay.traffic.source.ITrafficCaptureSource;
import org.opensearch.migrations.replay.traffic.source.SourceInput;

import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.common.TopicPartition;

*/
// REBUILD-LIMBO-END(G11)
/**
 * Sole owner of replay-intake mutable state and source-record application.
 */
// REBUILD-LIMBO-START(G11)
/*
@Slf4j
public final class ReplayIntakeOwner {
    @FunctionalInterface
    public interface FatalHandler {
        void onFatal(Error failure);
    }

    public sealed interface Input extends ReplayIntakeInput permits
        StartSourceRead,
        SourceReadCompleted,
        SourceReadFailed,
        RegisterRequestGeneration,
        StopReading,
        CloseAccumulator,
        Fence,
        StopOwner {}

    public record StartSourceRead(
        @NonNull ITrafficCaptureSource source,
        @NonNull CapturedTrafficToHttpTransactionAccumulator accumulator,
        @NonNull Supplier<ITrafficSourceContexts.IReadChunkContext> contextSupplier,
        @NonNull CompletableFuture<Void> completion
    ) implements Input {}

    record SourceReadCompleted(
        long sequence,
        @NonNull List<SourceInput> inputs
    ) implements Input {}

    record SourceReadFailed(long sequence, @NonNull Throwable failure) implements Input {}

    public record RegisterRequestGeneration(
        @NonNull PartitionGenerationId partitionGenerationId,
        @NonNull ReplayRequestId requestId,
        boolean kafkaBacked
    ) implements Input {}

    public record StopReading(@NonNull CompletableFuture<Void> completion) implements Input {}

    public record CloseAccumulator(@NonNull CompletableFuture<Void> completion) implements Input {}

    public record Fence() implements Input {}

    public record StopOwner(@NonNull CompletableFuture<Void> completion) implements Input {}

    private final ReplayIntakeInputQueue inputQueue;
    private final FatalHandler fatalHandler;
    private final Thread ownerThread;
    private final OwnerThreadGuard ownerThreadGuard;
    private final CompletableFuture<Void> termination = new CompletableFuture<>();

    private TargetAttemptPermitProvider permitPool;
    private ReplayProgressController progressController;
    private RecordWorkTracker recordWorkTracker;
    private static final class RequestGenerationState {
        private final PartitionGenerationId expected;
        private final boolean kafkaBacked;
        private boolean connectionRequestFinished;

        private RequestGenerationState(PartitionGenerationId expected, boolean kafkaBacked) {
            this.expected = expected;
            this.kafkaBacked = kafkaBacked;
        }
    }

    private final Map<ReplayRequestId, RequestGenerationState> requestGenerations =
        new LinkedHashMap<>();
    private ITrafficCaptureSource source;
    private CapturedTrafficToHttpTransactionAccumulator accumulator;
    private Supplier<ITrafficSourceContexts.IReadChunkContext> contextSupplier;
    private CompletableFuture<Void> readingCompletion;
    private CompletableFuture<List<SourceInput>> currentRead;
    private final List<CompletableFuture<Void>> stopReadingCompletions = new ArrayList<>();
    private long currentReadSequence;
    private boolean reading;
    private boolean stopReading;
    private boolean running = true;
    private volatile boolean started;

    public ReplayIntakeOwner(@NonNull FatalHandler fatalHandler) {
        this(fatalHandler, runnable -> new Thread(runnable, "replay-intake-owner"));
    }

    ReplayIntakeOwner(@NonNull FatalHandler fatalHandler, @NonNull ThreadFactory threadFactory) {
        this.inputQueue = new ReplayIntakeInputQueue();
        this.fatalHandler = fatalHandler;
        this.ownerThread = Objects.requireNonNull(threadFactory.newThread(this::runLoop));
        this.ownerThreadGuard = new OwnerThreadGuard(
            "replay intake owner",
            () -> Thread.currentThread() == ownerThread
        );
    }

    public void configureOwnedComponents(
        @NonNull TargetAttemptPermitProvider permitPool,
        @NonNull ReplayProgressController progressController
    ) {
        if (started) {
            throw new IllegalStateException("replay-intake components must be configured before start");
        }
        this.permitPool = permitPool;
        this.progressController = progressController;
    }

    public void configureOwnedComponents(
        @NonNull TargetAttemptPermitProvider permitPool,
        @NonNull ReplayProgressController progressController,
        @NonNull RecordWorkTracker recordWorkTracker
    ) {
        configureOwnedComponents(permitPool, progressController);
        this.recordWorkTracker = recordWorkTracker;
    }

    public void start() {
        if (started) {
            throw new IllegalStateException("replay-intake owner already started");
        }
        requireConfigured();
        started = true;
        ownerThread.start();
    }

    public CompletionStage<Void> startReading(
        @NonNull ITrafficCaptureSource source,
        @NonNull CapturedTrafficToHttpTransactionAccumulator accumulator,
        @NonNull Supplier<ITrafficSourceContexts.IReadChunkContext> contextSupplier
    ) {
        var completion = new CompletableFuture<Void>();
        if (!submitRequired(new StartSourceRead(source, accumulator, contextSupplier, completion))) {
            completion.completeExceptionally(new IllegalStateException("replay-intake owner is not accepting input"));
        }
        return completion.minimalCompletionStage();
    }

    public CompletionStage<Void> stopReading() {
        var completion = new CompletableFuture<Void>();
        if (!submitRequired(new StopReading(completion))) {
            completion.completeExceptionally(new IllegalStateException("replay-intake owner is not accepting input"));
        }
        return completion.minimalCompletionStage();
    }

    public CompletionStage<Void> closeAccumulator() {
        var completion = new CompletableFuture<Void>();
        if (!submitRequired(new CloseAccumulator(completion))) {
            completion.completeExceptionally(new IllegalStateException("replay-intake owner is not accepting input"));
        }
        return completion.minimalCompletionStage();
    }

*/
// REBUILD-LIMBO-END(G11)
    /**
     * Completes after every replay-intake input already queued by the caller or by an earlier
     * owner transition has been applied.
     */
// REBUILD-LIMBO-START(G11)
/*
    public CompletionStage<Void> fence() {
        return submitRequiredHandled(new Fence());
    }

    public CompletionStage<Void> closePermits(@NonNull CancellationException cause) {
        return permitPool.close(cause);
    }

    public CompletionStage<Void> stopOwner() {
        if (termination.isDone()) {
            return termination();
        }
        var completion = new CompletableFuture<Void>();
        if (!submitRequired(new StopOwner(completion))) {
            completion.completeExceptionally(new IllegalStateException("replay-intake owner is not accepting input"));
        }
        return completion.minimalCompletionStage();
    }

    public CompletionStage<Void> termination() {
        return termination.minimalCompletionStage();
    }

    public boolean isOwnerThread() {
        return Thread.currentThread() == ownerThread;
    }

    public boolean submitRequired(@NonNull ReplayIntakeInput input) {
        if (!started || !inputQueue.submit(input)) {
            reportRejectedSubmission(input);
            return false;
        }
        return true;
    }

    public CompletionStage<Void> submitRequiredHandled(@NonNull ReplayIntakeInput input) {
        var handled = new CompletableFuture<Void>();
        if (!started || !inputQueue.submit(input, handled)) {
            var rejection = new IllegalStateException(
                "replay-intake owner rejected " + input.getClass().getSimpleName()
            );
            handled.completeExceptionally(rejection);
            reportRejectedSubmission(input);
        }
        return handled.minimalCompletionStage();
    }

    private void reportRejectedSubmission(ReplayIntakeInput input) {
        fatalHandler.onFatal(new Error(
            "Required replay-intake input submission was rejected: " + input.getClass().getSimpleName()
        ));
    }

    private void runLoop() {
        try {
            while (running) {
                var queued = inputQueue.take();
                try {
                    apply(queued.input());
                    if (queued.handled() != null) {
                        queued.handled().complete(null);
                    }
                } catch (Throwable t) {
                    if (queued.handled() != null) {
                        queued.handled().completeExceptionally(t);
                    }
                    throw t;
                }
            }
            termination.complete(null);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            failOwner(new Error("Replay-intake owner was interrupted", e));
        } catch (Throwable t) {
            failOwner(new Error("Replay-intake owner failed unexpectedly", t));
        } finally {
            inputQueue.close();
        }
    }

    private void apply(ReplayIntakeInput input) {
        ownerThreadGuard.requireOwnerThread();
        switch (input) {
            case Input ownerInput -> applyOwnerInput(ownerInput);
            case TargetAttemptPermitProvider.Input permitInput -> permitPool.apply(permitInput);
            case ReplayProgressController.Input progressInput -> progressController.apply(progressInput);
            case RecordWorkTracker.Input trackerInput ->
                Objects.requireNonNull(recordWorkTracker, "recordWorkTracker").apply(trackerInput);
            case RequestLifecycleInput requestInput -> applyRequestLifecycleInput(requestInput);
        }
    }

    private void applyRequestLifecycleInput(RequestLifecycleInput input) {
        var generationState = requireExpectedGeneration(input);
        validateKafkaAssociations(input.requestId(), generationState);
        switch (input) {
            case RequestLifecycleInput.ConnectionRequestFinished finished -> {
                if (generationState.connectionRequestFinished) {
                    throw new IllegalStateException(
                        "duplicate ConnectionRequestFinished for "
                            + finished.requestId()
                    );
                }
                generationState.connectionRequestFinished = true;
                // S10 consumes this milestone for retry-ready demand. S5 establishes the typed
                // owner crossing and exactly-once connection ordering boundary.
            }
            case RequestLifecycleInput.RequestProcessingFinished finished -> {
                if (!generationState.connectionRequestFinished) {
                    throw new IllegalStateException(
                        "RequestProcessingFinished arrived without ConnectionRequestFinished for "
                            + finished.requestId()
                    );
                }
                if (generationState.kafkaBacked) {
                    Objects.requireNonNull(recordWorkTracker, "recordWorkTracker")
                        .associationFinished(finished.requestId());
                }
                requestGenerations.remove(finished.requestId());
            }
        }
    }

    private RequestGenerationState requireExpectedGeneration(RequestLifecycleInput input) {
        var generationState = requestGenerations.get(input.requestId());
        if (generationState == null) {
            throw new IllegalStateException(
                input.getClass().getSimpleName()
                    + " arrived without source generation registration for "
                    + input.requestId()
            );
        }
        if (!generationState.expected.equals(input.partitionGenerationId())) {
            throw new IllegalStateException(
                "request lifecycle partition-generation mismatch for "
                    + input.requestId()
                    + "; source registration="
                    + generationState.expected
                    + ", received="
                    + input.partitionGenerationId()
            );
        }
        return generationState;
    }

    private void registerRequestGeneration(RegisterRequestGeneration registration) {
        if (registration.partitionGenerationId().localSequence()
            != registration.requestId().session().sourceGeneration()) {
            throw new IllegalStateException(
                "request source generation mismatch for " + registration.requestId()
            );
        }
        var generationState = new RequestGenerationState(
            registration.partitionGenerationId(),
            registration.kafkaBacked()
        );
        if (requestGenerations.putIfAbsent(registration.requestId(), generationState) != null) {
            throw new IllegalStateException(
                "request source generation was already registered: " + registration.requestId()
            );
        }
        try {
            validateKafkaAssociations(registration.requestId(), generationState);
        } catch (Throwable failure) {
            requestGenerations.remove(registration.requestId());
            throw failure;
        }
    }

    private void validateKafkaAssociations(
        ReplayRequestId requestId,
        RequestGenerationState generationState
    ) {
        if (!generationState.kafkaBacked) {
            if (recordWorkTracker != null && !recordWorkTracker.recordsFor(requestId).isEmpty()) {
                throw new IllegalStateException(
                    "Non-Kafka request unexpectedly has Kafka record associations: " + requestId
                );
            }
            return;
        }
        var tracker = Objects.requireNonNull(recordWorkTracker, "recordWorkTracker");
        var records = tracker.recordsFor(requestId);
        if (records.isEmpty()) {
            throw new IllegalStateException(
                "Kafka-backed request has no record association: " + requestId
            );
        }
        records.forEach(recordId -> {
            var associatedGeneration = new PartitionGenerationId(
                new TopicPartition(recordId.topic(), recordId.partition()),
                recordId.sourceGeneration()
            );
            if (!generationState.expected.equals(associatedGeneration)) {
                throw new IllegalStateException(
                    "Kafka record association generation mismatch for "
                        + requestId
                        + "; source registration="
                        + generationState.expected
                        + ", record="
                        + recordId
                );
            }
        });
    }

    private void applyOwnerInput(Input input) {
        switch (input) {
            case StartSourceRead start -> beginReading(start);
            case SourceReadCompleted completed -> applySourceBatch(completed);
            case SourceReadFailed failed -> applySourceFailure(failed);
            case RegisterRequestGeneration registration -> registerRequestGeneration(registration);
            case StopReading stop -> applyStopReading(stop);
            case CloseAccumulator close -> applyCloseAccumulator(close);
            case Fence ignored -> {}
            case StopOwner stop -> applyStopOwner(stop);
        }
    }

    private void beginReading(StartSourceRead start) {
        if (reading || readingCompletion != null) {
            throw new IllegalStateException("source reading already started");
        }
        source = start.source();
        accumulator = start.accumulator();
        contextSupplier = start.contextSupplier();
        readingCompletion = start.completion();
        reading = true;
        requestNextSourceRead();
    }

    private void requestNextSourceRead() {
        ownerThreadGuard.requireOwnerThread();
        if (stopReading) {
            finishReading(null);
            return;
        }
        var sequence = ++currentReadSequence;
        currentRead = new CompletableFuture<>();
        try {
            currentRead = Objects.requireNonNull(
                source.readNextTrafficStreamChunk(contextSupplier),
                "source returned a null read stage"
            );
        } catch (Throwable t) {
            submitRequired(new SourceReadFailed(sequence, t));
            return;
        }
        currentRead.whenComplete((inputs, failure) -> {
            try {
                if (failure == null) {
                    submitRequired(new SourceReadCompleted(sequence, List.copyOf(inputs)));
                } else {
                    submitRequired(new SourceReadFailed(sequence, unwrap(failure)));
                }
            } catch (Throwable t) {
                submitRequired(new SourceReadFailed(sequence, t));
            }
        });
    }

    private void applySourceBatch(SourceReadCompleted completed) {
        requireCurrentRead(completed.sequence());
        currentRead = null;
        var batchStart = System.nanoTime();
        completed.inputs().forEach(accumulator::accept);
        var batchDurationMillis = (System.nanoTime() - batchStart) / 1_000_000;
        if (batchDurationMillis > 5_000) {
            log.atWarn()
                .setMessage("Replay intake applied {} source inputs in {}ms")
                .addArgument(completed.inputs()::size)
                .addArgument(batchDurationMillis)
                .log();
        }
        requestNextSourceRead();
    }

    private void applySourceFailure(SourceReadFailed failed) {
        requireCurrentRead(failed.sequence());
        currentRead = null;
        if (failed.failure() instanceof CancellationException && stopReading) {
            finishReading(null);
        } else if (failed.failure() instanceof EOFException) {
            log.atWarn().setCause(failed.failure()).setMessage("Reached the end of captured source input").log();
            finishReading(null);
        } else {
            finishReading(failed.failure());
        }
    }

    private void applyStopReading(StopReading stop) {
        stopReading = true;
        if (!reading) {
            stop.completion().complete(null);
            return;
        }
        stopReadingCompletions.add(stop.completion());
        var read = currentRead;
        if (read != null) {
            read.cancel(true);
        } else {
            finishReading(null);
        }
    }

    private void applyCloseAccumulator(CloseAccumulator close) {
        try {
            if (accumulator != null) {
                accumulator.close();
            }
            close.completion().complete(null);
        } catch (Throwable t) {
            close.completion().completeExceptionally(t);
            if (t instanceof Error error) {
                throw error;
            }
            throw new IllegalStateException("Could not close replay-intake accumulator", t);
        }
    }

    private void applyStopOwner(StopOwner stop) {
        if (reading) {
            throw new IllegalStateException("cannot stop replay-intake owner while source reading is active");
        }
        running = false;
        inputQueue.close();
        stop.completion().complete(null);
    }

    private void finishReading(Throwable failure) {
        if (!reading) {
            return;
        }
        reading = false;
        if (failure == null) {
            readingCompletion.complete(null);
            stopReadingCompletions.forEach(completion -> completion.complete(null));
        } else {
            readingCompletion.completeExceptionally(failure);
            stopReadingCompletions.forEach(
                completion -> completion.completeExceptionally(failure)
            );
        }
        stopReadingCompletions.clear();
    }

    private void requireCurrentRead(long sequence) {
        if (!reading || currentRead == null || sequence != currentReadSequence) {
            throw new IllegalStateException(
                "source read result does not match the active request: received="
                    + sequence
                    + ", active="
                    + currentReadSequence
            );
        }
    }

    private void failOwner(Error failure) {
        finishReading(failure);
        termination.completeExceptionally(failure);
        fatalHandler.onFatal(failure);
    }

    private void requireConfigured() {
        Objects.requireNonNull(permitPool, "permitPool");
        Objects.requireNonNull(progressController, "progressController");
    }

    private static Throwable unwrap(Throwable failure) {
        var current = failure;
        while ((current instanceof CompletionException
            || current instanceof java.util.concurrent.ExecutionException)
            && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

}

*/
// REBUILD-LIMBO-END(G11)