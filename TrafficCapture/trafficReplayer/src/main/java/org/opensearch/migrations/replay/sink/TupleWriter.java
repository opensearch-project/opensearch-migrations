/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.migrations.replay.sink;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.DoubleSupplier;

import org.opensearch.migrations.replay.identity.PartitionGenerationId;
import org.opensearch.migrations.replay.identity.ReplayRequestId;
import org.opensearch.migrations.replay.lifecycle.OutstandingOperationRegistry;
import org.opensearch.migrations.replay.lifecycle.OutstandingOperationRegistry.OperationType;
import org.opensearch.migrations.replay.lifecycle.OutstandingOperationRegistry.WaitReason;
import org.opensearch.migrations.replay.tracing.IReplayContexts;

import io.netty.channel.EventLoop;
import io.netty.util.concurrent.ScheduledFuture;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;

/**
 * Converts one logical replay tuple into a durable sink result despite transient physical-write failures.
 *
 * <p>On its event loop, the writer transforms each tuple once, treats an intentional transform drop as a
 * durable logical result, or submits the transformed value to the physical sink. Failed physical writes are
 * retried after policy-controlled delays while the same logical operation remains registered; success ends
 * the operation only when the sink's completion stage confirms durability.</p>
 *
 * <p>Cancellation stops pending retry timers and returns a typed cancelled result, while fatal transformation
 * or state-machine failures remain exceptional. Tuple resources are released exactly once at the logical
 * terminal boundary. During generation grace, reference-counted eager flushing reduces the chance that
 * buffered durable output outlives the bounded revocation window.</p>
 */
@Slf4j
public final class TupleWriter<T> {
    public static final Duration DEFAULT_INITIAL_RETRY_DELAY =
        Duration.ofMillis(100);
    public static final Duration DEFAULT_MAXIMUM_RETRY_DELAY =
        Duration.ofSeconds(5);

    public record WriteTuple<T>(
        @NonNull IReplayContexts.ITupleHandlingContext replayContext,
        @NonNull T tuple
    ) {
        public ReplayRequestId requestId() {
            return replayContext.getRequestId();
        }
    }

    public sealed interface TupleWriteResult
        permits TupleDurable, TupleWriteCancelled {}

    public record TupleDurable() implements TupleWriteResult {}

    public record TupleWriteCancelled(
        @NonNull CancellationException cause
    ) implements TupleWriteResult {}

    public interface PhysicalTupleSink<T> {
        CompletionStage<Void> write(
            IReplayContexts.ITupleHandlingContext replayContext,
            T tuple
        );

        void flush();

        /**
         * Whether {@link #write} has taken everything it needs from the tuple by the time it returns, and
         * owns retrying its own durability from there. Such a sink lets the writer drop the tuple, and the
         * request its payloads, while durability is still pending, so memory held for tuples awaiting an
         * upload is ids and futures rather than request and response bytes. A write that later fails cannot
         * be retried, because nothing is left to rewrite.
         */
        default boolean copiesTupleOnWrite() {
            return false;
        }
    }

    public sealed interface TupleTransformation<T>
        permits TransformedTuple, TupleDropped {}

    public record TransformedTuple<T>(
        @NonNull T tuple
    ) implements TupleTransformation<T> {}

    public record TupleDropped<T>() implements TupleTransformation<T> {}

    @FunctionalInterface
    public interface TupleTransformer<T> {
        TupleTransformation<T> transform(
            IReplayContexts.ITupleHandlingContext replayContext,
            T tuple
        );
    }

    @FunctionalInterface
    public interface FatalHandler {
        void onFatal(Error failure);
    }

    @FunctionalInterface
    public interface RetryDelayPolicy {
        Duration delayAfterFailure(int failedAttemptCount);
    }

    public enum FailureReason {
        OWNER_SUBMISSION_REJECTED,
        DUPLICATE_REQUEST,
        TRANSFORMATION,
        RETRY_SCHEDULING,
        FAILED_AFTER_HANDOFF
    }

    public interface Metrics {
        void writeAccepted();

        void retryScheduled();

        void writeDurable(Duration latency);

        void writeCancelled();

        void terminalFailure(FailureReason reason);
    }

    public interface LogicalWrite {
        CompletionStage<TupleWriteResult> completion();

        /**
         * Completes once nothing the tuple was built from is needed any more: the sink copied the tuple, or
         * the tuple was dropped. Durability may still be pending. Never completes for a sink that needs the
         * tuple until it is durable.
         */
        default CompletionStage<Void> handedOff() {
            return new CompletableFuture<>();
        }

        void cancel(CancellationException cause);
    }

    private enum State {
        NEW,
        WRITING,
        WAITING_TO_RETRY,
        DURABLE,
        CANCELLED,
        FAILED
    }

    private final EventLoop eventLoop;
    private final Clock clock;
    private final RetryDelayPolicy retryDelayPolicy;
    private final TupleTransformer<T> transformer;
    private final PhysicalTupleSink<T> sink;
    private final Consumer<T> tupleReleaser;
    private final FatalHandler fatalHandler;
    private final Metrics metrics;
    private final OutstandingOperationRegistry operations;
    private final Map<ReplayRequestId, WriteOperation> active = new LinkedHashMap<>();
    private final Map<PartitionGenerationId, Integer> eagerFlushReferences =
        new LinkedHashMap<>();

    public TupleWriter(
        @NonNull EventLoop eventLoop,
        @NonNull Clock clock,
        @NonNull Duration retryDelay,
        @NonNull PhysicalTupleSink<T> sink,
        @NonNull Consumer<T> tupleReleaser,
        @NonNull FatalHandler fatalHandler,
        @NonNull Metrics metrics
    ) {
        this(
            eventLoop,
            clock,
            retryDelay,
            (replayContext, tuple) -> new TransformedTuple<>(tuple),
            sink,
            tupleReleaser,
            fatalHandler,
            metrics
        );
    }

    public TupleWriter(
        @NonNull EventLoop eventLoop,
        @NonNull Clock clock,
        @NonNull RetryDelayPolicy retryDelayPolicy,
        @NonNull PhysicalTupleSink<T> sink,
        @NonNull Consumer<T> tupleReleaser,
        @NonNull FatalHandler fatalHandler,
        @NonNull Metrics metrics
    ) {
        this(
            eventLoop,
            clock,
            retryDelayPolicy,
            (replayContext, tuple) -> new TransformedTuple<>(tuple),
            sink,
            tupleReleaser,
            fatalHandler,
            metrics
        );
    }

    public TupleWriter(
        @NonNull EventLoop eventLoop,
        @NonNull Clock clock,
        @NonNull Duration retryDelay,
        @NonNull TupleTransformer<T> transformer,
        @NonNull PhysicalTupleSink<T> sink,
        @NonNull Consumer<T> tupleReleaser,
        @NonNull FatalHandler fatalHandler,
        @NonNull Metrics metrics
    ) {
        this(
            eventLoop,
            clock,
            fixedRetryDelay(retryDelay),
            transformer,
            sink,
            tupleReleaser,
            fatalHandler,
            metrics
        );
    }

    public TupleWriter(
        @NonNull EventLoop eventLoop,
        @NonNull Clock clock,
        @NonNull RetryDelayPolicy retryDelayPolicy,
        @NonNull TupleTransformer<T> transformer,
        @NonNull PhysicalTupleSink<T> sink,
        @NonNull Consumer<T> tupleReleaser,
        @NonNull FatalHandler fatalHandler,
        @NonNull Metrics metrics
    ) {
        this.eventLoop = eventLoop;
        this.clock = clock;
        this.retryDelayPolicy = retryDelayPolicy;
        this.transformer = transformer;
        this.sink = sink;
        this.tupleReleaser = tupleReleaser;
        this.fatalHandler = fatalHandler;
        this.metrics = metrics;
        this.operations = new OutstandingOperationRegistry(
            "tuple writer",
            eventLoop,
            clock,
            fatalHandler::onFatal
        );
    }

    public static RetryDelayPolicy fixedRetryDelay(@NonNull Duration retryDelay) {
        if (retryDelay.isNegative()) {
            throw new IllegalArgumentException("retryDelay must not be negative");
        }
        return ignored -> retryDelay;
    }

    public static RetryDelayPolicy randomizedExponentialRetryDelay(
        @NonNull Duration initialDelay,
        @NonNull Duration maximumDelay,
        @NonNull DoubleSupplier randomFraction
    ) {
        if (initialDelay.isZero() || initialDelay.isNegative()) {
            throw new IllegalArgumentException("initialDelay must be positive");
        }
        if (maximumDelay.compareTo(initialDelay) < 0) {
            throw new IllegalArgumentException(
                "maximumDelay must be greater than or equal to initialDelay"
            );
        }
        var initialNanos = initialDelay.toNanos();
        var maximumNanos = maximumDelay.toNanos();
        return failedAttemptCount -> {
            if (failedAttemptCount < 1) {
                throw new IllegalArgumentException(
                    "failedAttemptCount must be positive"
                );
            }
            var upperBoundNanos = initialNanos;
            for (var attempt = 1;
                 attempt < failedAttemptCount && upperBoundNanos < maximumNanos;
                 attempt++) {
                upperBoundNanos = upperBoundNanos > maximumNanos / 2
                    ? maximumNanos
                    : upperBoundNanos * 2;
            }
            var fraction = randomFraction.getAsDouble();
            if (!(fraction >= 0.0 && fraction < 1.0)) {
                throw new IllegalStateException(
                    "randomFraction must return a value in [0.0, 1.0)"
                );
            }
            return Duration.ofNanos((long) (upperBoundNanos * fraction));
        };
    }

    public LogicalWrite write(@NonNull WriteTuple<T> input) {
        var operation = new WriteOperation(input);
        metrics.writeAccepted();
        log.atDebug()
            .setMessage("event=tuple_write_submitted request={}")
            .addArgument(input::requestId)
            .log();
        postRequired(
            "logical tuple write submission " + input.requestId(),
            () -> start(operation),
            failure -> {
                operation.state = State.FAILED;
                operation.completion.completeExceptionally(failure);
                operation.recordTerminalFailure(FailureReason.OWNER_SUBMISSION_REJECTED);
            }
        );
        return operation;
    }

    public OutstandingOperationRegistry operations() {
        return operations;
    }

    /** Enters eager-flush operation for one connection served during generation grace. */
    public void enterGrace(@NonNull PartitionGenerationId generation) {
        postRequired(
            "tuple grace entry " + generation,
            () -> {
                var references = eagerFlushReferences.get(generation);
                eagerFlushReferences.put(
                    generation,
                    references == null ? 1 : Math.addExact(references, 1)
                );
                if (references == null) {
                    flushSink();
                }
            },
            ignored -> {}
        );
    }

    /** Leaves eager-flush operation after one connection owner finishes generation grace. */
    public void leaveGrace(@NonNull PartitionGenerationId generation) {
        postRequired(
            "tuple grace exit " + generation,
            () -> {
                var references = eagerFlushReferences.get(generation);
                if (references == null) {
                    throw new IllegalStateException(
                        "tuple writer has no grace reference for " + generation
                    );
                }
                if (references == 1) {
                    eagerFlushReferences.remove(generation);
                } else {
                    eagerFlushReferences.put(generation, references - 1);
                }
            },
            ignored -> {}
        );
    }

    private void start(WriteOperation operation) {
        requireOwnerThread();
        var requestId = operation.requestId;
        if (active.putIfAbsent(requestId, operation) != null) {
            var failure = new IllegalStateException(
                "tuple write already active for " + requestId
            );
            operation.state = State.FAILED;
            operation.completion.completeExceptionally(failure);
            operation.recordTerminalFailure(FailureReason.DUPLICATE_REQUEST);
            reportFatal("duplicate logical tuple write", failure);
            return;
        }
        operation.logicalRegistration = operations.register(
            operation.replayContext
                .getLogicalEnclosingScope()
                .getLogicalEnclosingScope()
                .getLogicalEnclosingScope()
                .getRecordId(),
            requestId.connectionProcessingId().generation(),
            requestId.connectionProcessingId(),
            requestId,
            OperationType.TUPLE_DURABILITY,
            null,
            WaitReason.WAITING_FOR_TUPLE_DURABILITY
        );
        final TupleTransformation<T> transformed;
        try {
            transformed = Objects.requireNonNull(
                transformer.transform(
                    operation.replayContext,
                    operation.tuple
                ),
                "tuple transformer returned no result"
            );
        } catch (Throwable failure) {
            failTransformation(operation, failure);
            return;
        }
        switch (transformed) {
            case TransformedTuple<T> emitted -> {
                operation.transformedTuple = emitted.tuple();
                submitPhysicalWrite(operation);
            }
            case TupleDropped<T> ignored -> {
                operation.state = State.DURABLE;
                handOff(operation);
                finish(operation, new TupleDurable());
            }
        }
    }

    private void submitPhysicalWrite(WriteOperation operation) {
        requireOwnerThread();
        if (operation.state == State.CANCELLED || operation.state == State.FAILED) {
            return;
        }
        operation.retryTimer = null;
        operation.state = State.WRITING;
        var requestId = operation.requestId;
        var physicalRegistration = operations.register(
            operation.replayContext
                .getLogicalEnclosingScope()
                .getLogicalEnclosingScope()
                .getLogicalEnclosingScope()
                .getRecordId(),
            requestId.connectionProcessingId().generation(),
            requestId.connectionProcessingId(),
            requestId,
            OperationType.PHYSICAL_TUPLE_WRITE,
            null,
            WaitReason.SUBMITTING
        );
        operation.physicalRegistration = physicalRegistration;

        final CompletionStage<Void> attempt;
        try {
            attempt = Objects.requireNonNull(
                sink.write(
                    operation.replayContext,
                    operation.transformedTuple
                ),
                "physical tuple sink returned no completion stage"
            );
            if (eagerFlushReferences.containsKey(
                operation.requestId.connectionProcessingId().generation()
            )) {
                flushSink();
            }
        } catch (Throwable failure) {
            onPhysicalWriteComplete(operation, physicalRegistration, failure);
            return;
        }
        if (sink.copiesTupleOnWrite()) {
            handOff(operation);
        }
        attempt.whenComplete((ignored, failure) ->
            postRequired(
                "physical tuple completion " + requestId,
                () -> onPhysicalWriteComplete(operation, physicalRegistration, unwrap(failure)),
                ignoredFailure -> {}
            )
        );
    }

    private void flushSink() {
        requireOwnerThread();
        try {
            sink.flush();
        } catch (Throwable failure) {
            reportFatal("tuple sink flush", failure);
        }
    }

    private void onPhysicalWriteComplete(
        WriteOperation operation,
        OutstandingOperationRegistry.Registration registration,
        Throwable failure
    ) {
        requireOwnerThread();
        if (operation.physicalRegistration != registration) {
            if (operation.state == State.CANCELLED) {
                return;
            }
            reportFatal(
                "duplicate or stale physical tuple completion",
                new IllegalStateException(
                    "unexpected physical tuple completion for " + operation.requestId
                )
            );
            return;
        }
        operation.physicalRegistration = null;
        if (operation.state == State.CANCELLED) {
            return;
        }
        if (failure == null) {
            operation.state = State.DURABLE;
            operations.complete(registration);
            finish(operation, new TupleDurable());
            return;
        }
        if (operation.handedOffToSink) {
            // The sink took ownership and then gave up; nothing remains to rewrite. The record stays
            // uncommitted, so a successor process re-delivers and re-replays it.
            operation.state = State.FAILED;
            operations.complete(registration);
            active.remove(operation.requestId, operation);
            if (operation.logicalRegistration != null) {
                operations.complete(operation.logicalRegistration);
                operation.logicalRegistration = null;
            }
            operation.completion.completeExceptionally(failure);
            operation.recordTerminalFailure(FailureReason.FAILED_AFTER_HANDOFF);
            reportFatal("tuple durability after the sink took ownership", failure);
            return;
        }
        operation.state = State.WAITING_TO_RETRY;
        try {
            operation.failedAttemptCount++;
            var retryDelay = Objects.requireNonNull(
                retryDelayPolicy.delayAfterFailure(operation.failedAttemptCount),
                "tuple retry delay policy returned null"
            );
            if (retryDelay.isNegative()) {
                throw new IllegalStateException(
                    "tuple retry delay policy returned a negative delay"
                );
            }
            operation.retryTimer = eventLoop.schedule(
                () -> submitPhysicalWrite(operation),
                retryDelay.toNanos(),
                TimeUnit.NANOSECONDS
            );
            operations.complete(registration);
            metrics.retryScheduled();
            if (operation.failedAttemptCount == 1) {
                log.atWarn()
                    .setMessage(
                        "event=tuple_durability_degraded request={} state=retrying "
                            + "failed_attempts={} retry_delay_ms={}"
                    )
                    .addArgument(() -> operation.requestId)
                    .addArgument(() -> operation.failedAttemptCount)
                    .addArgument(retryDelay::toMillis)
                    .log();
            } else {
                log.atDebug()
                    .setMessage(
                        "event=tuple_write_retried request={} failed_attempts={} retry_delay_ms={}"
                    )
                    .addArgument(() -> operation.requestId)
                    .addArgument(() -> operation.failedAttemptCount)
                    .addArgument(retryDelay::toMillis)
                    .log();
            }
        } catch (Throwable schedulingFailure) {
            operation.state = State.FAILED;
            operation.completion.completeExceptionally(schedulingFailure);
            operation.recordTerminalFailure(FailureReason.RETRY_SCHEDULING);
            reportFatal("tuple retry timer submission", schedulingFailure);
        }
    }

    private void cancelOnOwner(WriteOperation operation, CancellationException cause) {
        requireOwnerThread();
        if (operation.state == State.DURABLE
            || operation.state == State.CANCELLED
            || operation.state == State.FAILED) {
            return;
        }
        operation.state = State.CANCELLED;
        if (operation.retryTimer != null) {
            operation.retryTimer.cancel(false);
            operation.retryTimer = null;
        }
        if (operation.physicalRegistration != null) {
            operations.complete(operation.physicalRegistration);
            operation.physicalRegistration = null;
        }
        finish(operation, new TupleWriteCancelled(cause));
    }

    private void failTransformation(WriteOperation operation, Throwable failure) {
        operation.state = State.FAILED;
        active.remove(operation.requestId, operation);
        if (operation.logicalRegistration != null) {
            operations.complete(operation.logicalRegistration);
            operation.logicalRegistration = null;
        }
        releaseCandidate(operation);
        operation.completion.completeExceptionally(failure);
        operation.recordTerminalFailure(FailureReason.TRANSFORMATION);
        reportFatal("tuple transformation", failure);
    }

    private void finish(WriteOperation operation, TupleWriteResult result) {
        active.remove(operation.requestId, operation);
        if (operation.logicalRegistration != null) {
            operations.complete(operation.logicalRegistration);
            operation.logicalRegistration = null;
        }
        releaseCandidate(operation);
        operation.completion.complete(result);
        switch (result) {
            case TupleDurable ignored ->
                operation.recordDurable();
            case TupleWriteCancelled ignored ->
                operation.recordCancelled();
        }
    }

    private void releaseCandidate(WriteOperation operation) {
        if (operation.released.compareAndSet(false, true)) {
            try {
                tupleReleaser.accept(operation.tuple);
                if (operation.transformedTuple != null
                    && operation.transformedTuple != operation.tuple) {
                    tupleReleaser.accept(operation.transformedTuple);
                }
            } catch (Throwable failure) {
                reportFatal("tuple ownership release", failure);
            }
        }
    }

    /** Drops the tuple once nothing needs it, and tells the request it may drop what it was built from. */
    private void handOff(WriteOperation operation) {
        if (operation.handedOffToSink) {
            return;
        }
        operation.handedOffToSink = true;
        releaseCandidate(operation);
        operation.tuple = null;
        operation.transformedTuple = null;
        operation.handedOff.complete(null);
    }

    private void postRequired(
        String operation,
        Runnable transition,
        Consumer<Throwable> rejectionHandler
    ) {
        if (eventLoop.inEventLoop()) {
            runTransition(operation, transition);
            return;
        }
        try {
            eventLoop.execute(() -> runTransition(operation, transition));
        } catch (Throwable failure) {
            rejectionHandler.accept(failure);
            reportFatal(operation, failure);
        }
    }

    private void runTransition(String operation, Runnable transition) {
        try {
            requireOwnerThread();
            transition.run();
        } catch (Throwable failure) {
            reportFatal(operation, failure);
        }
    }

    private void requireOwnerThread() {
        if (!eventLoop.inEventLoop()) {
            throw new IllegalStateException("tuple writer accessed outside its event loop");
        }
    }

    private void reportFatal(String operation, Throwable cause) {
        fatalHandler.onFatal(new Error(
            "Tuple-writer failure during " + operation,
            cause
        ));
    }

    private static Throwable unwrap(Throwable failure) {
        var current = failure;
        while (current != null
            && (current instanceof java.util.concurrent.CompletionException
                || current instanceof java.util.concurrent.ExecutionException)
            && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    private final class WriteOperation implements LogicalWrite {
        private final IReplayContexts.ITupleHandlingContext replayContext;
        private final ReplayRequestId requestId;
        /** Cleared on hand-off, so a tuple awaiting durability is not retained by its write. */
        private T tuple;
        private boolean handedOffToSink;
        private final CompletableFuture<Void> handedOff = new CompletableFuture<>();
        private final CompletableFuture<TupleWriteResult> completion = new CompletableFuture<>();
        private final AtomicBoolean released = new AtomicBoolean();
        private final AtomicBoolean metricCompleted = new AtomicBoolean();
        private final Instant acceptedAt;
        private State state = State.NEW;
        private OutstandingOperationRegistry.Registration logicalRegistration;
        private OutstandingOperationRegistry.Registration physicalRegistration;
        private ScheduledFuture<?> retryTimer;
        private int failedAttemptCount;
        private T transformedTuple;

        private WriteOperation(WriteTuple<T> input) {
            this.replayContext = input.replayContext();
            this.requestId = input.requestId();
            this.tuple = input.tuple();
            this.acceptedAt = clock.instant();
        }

        private void recordDurable() {
            if (metricCompleted.compareAndSet(false, true)) {
                var latency = Duration.between(acceptedAt, clock.instant());
                metrics.writeDurable(latency);
                log.atDebug()
                    .setMessage(
                        "event=tuple_write_durable request={} retries={} latency_ms={}"
                    )
                    .addArgument(() -> requestId)
                    .addArgument(() -> failedAttemptCount)
                    .addArgument(latency::toMillis)
                    .log();
                if (failedAttemptCount > 0) {
                    log.atInfo()
                        .setMessage(
                            "event=tuple_durability_recovered request={} retries={} latency_ms={}"
                        )
                        .addArgument(() -> requestId)
                        .addArgument(() -> failedAttemptCount)
                        .addArgument(latency::toMillis)
                        .log();
                }
            }
        }

        private void recordCancelled() {
            if (metricCompleted.compareAndSet(false, true)) {
                metrics.writeCancelled();
                log.atDebug()
                    .setMessage("event=tuple_write_cancelled request={}")
                    .addArgument(() -> requestId)
                    .log();
            }
        }

        private void recordTerminalFailure(FailureReason reason) {
            if (metricCompleted.compareAndSet(false, true)) {
                metrics.terminalFailure(reason);
                log.atError()
                    .setMessage(
                        "event=tuple_durability_degraded request={} state=terminal reason={}"
                    )
                    .addArgument(() -> requestId)
                    .addArgument(() -> reason.name().toLowerCase(java.util.Locale.ROOT))
                    .log();
            }
        }

        @Override
        public CompletionStage<TupleWriteResult> completion() {
            return completion.minimalCompletionStage();
        }

        @Override
        public CompletionStage<Void> handedOff() {
            return handedOff.minimalCompletionStage();
        }

        @Override
        public void cancel(@NonNull CancellationException cause) {
            postRequired(
                "logical tuple cancellation " + requestId,
                () -> cancelOnOwner(this, cause),
                ignored -> {}
            );
        }
    }
}
