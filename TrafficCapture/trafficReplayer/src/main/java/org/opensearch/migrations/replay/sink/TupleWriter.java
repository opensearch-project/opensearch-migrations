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
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import org.opensearch.migrations.replay.identity.ReplayRequestId;
import org.opensearch.migrations.replay.lifecycle.OutstandingOperationRegistry;
import org.opensearch.migrations.replay.lifecycle.OutstandingOperationRegistry.OperationType;
import org.opensearch.migrations.replay.lifecycle.OutstandingOperationRegistry.WaitReason;

import io.netty.channel.EventLoop;
import io.netty.util.concurrent.ScheduledFuture;
import lombok.NonNull;

/**
 * Owns physical retries for one logical {@link WriteTuple} link.
 *
 * <p>The writer is event-loop-confined. A bounded worker layer may place independent logical
 * writes on separate owners without changing the logical contract represented here.</p>
 */
public final class TupleWriter<T> {
    public record WriteTuple<T>(
        @NonNull ReplayRequestId requestId,
        @NonNull T tuple
    ) {}

    public sealed interface TupleWriteResult
        permits TupleDurable, TupleWriteCancelled {}

    public record TupleDurable() implements TupleWriteResult {}

    public record TupleWriteCancelled(
        @NonNull CancellationException cause
    ) implements TupleWriteResult {}

    @FunctionalInterface
    public interface PhysicalTupleSink<T> {
        CompletionStage<Void> write(T tuple);
    }

    @FunctionalInterface
    public interface FatalHandler {
        void onFatal(Error failure);
    }

    public interface LogicalWrite {
        CompletionStage<TupleWriteResult> completion();

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
    private final Duration retryDelay;
    private final PhysicalTupleSink<T> sink;
    private final Consumer<T> tupleReleaser;
    private final FatalHandler fatalHandler;
    private final OutstandingOperationRegistry operations;
    private final Map<ReplayRequestId, WriteOperation> active = new LinkedHashMap<>();

    public TupleWriter(
        @NonNull EventLoop eventLoop,
        @NonNull Clock clock,
        @NonNull Duration retryDelay,
        @NonNull PhysicalTupleSink<T> sink,
        @NonNull Consumer<T> tupleReleaser,
        @NonNull FatalHandler fatalHandler,
        @NonNull OutstandingOperationRegistry.CountHook countHook
    ) {
        if (retryDelay.isNegative()) {
            throw new IllegalArgumentException("retryDelay must not be negative");
        }
        this.eventLoop = eventLoop;
        this.clock = clock;
        this.retryDelay = retryDelay;
        this.sink = sink;
        this.tupleReleaser = tupleReleaser;
        this.fatalHandler = fatalHandler;
        this.operations = new OutstandingOperationRegistry(
            "tuple writer",
            eventLoop,
            clock,
            fatalHandler::onFatal,
            countHook
        );
    }

    public LogicalWrite write(@NonNull WriteTuple<T> input) {
        var operation = new WriteOperation(input);
        postRequired(
            "logical tuple write submission " + input.requestId(),
            () -> start(operation),
            failure -> operation.completion.completeExceptionally(failure)
        );
        return operation;
    }

    public OutstandingOperationRegistry operations() {
        return operations;
    }

    private void start(WriteOperation operation) {
        requireOwnerThread();
        var requestId = operation.input.requestId();
        if (active.putIfAbsent(requestId, operation) != null) {
            var failure = new IllegalStateException(
                "tuple write already active for " + requestId
            );
            operation.state = State.FAILED;
            operation.completion.completeExceptionally(failure);
            reportFatal("duplicate logical tuple write", failure);
            return;
        }
        operation.logicalRegistration = operations.register(
            requestId.connectionProcessingId().generation(),
            requestId.connectionProcessingId(),
            requestId,
            OperationType.TUPLE_DURABILITY,
            null,
            WaitReason.WAITING_FOR_TUPLE_DURABILITY
        );
        submitPhysicalWrite(operation);
    }

    private void submitPhysicalWrite(WriteOperation operation) {
        requireOwnerThread();
        if (operation.state == State.CANCELLED || operation.state == State.FAILED) {
            return;
        }
        operation.retryTimer = null;
        operation.state = State.WRITING;
        var requestId = operation.input.requestId();
        var physicalRegistration = operations.register(
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
                sink.write(operation.input.tuple()),
                "physical tuple sink returned no completion stage"
            );
        } catch (Throwable failure) {
            onPhysicalWriteComplete(operation, physicalRegistration, failure);
            return;
        }
        attempt.whenComplete((ignored, failure) ->
            postRequired(
                "physical tuple completion " + requestId,
                () -> onPhysicalWriteComplete(operation, physicalRegistration, unwrap(failure)),
                ignoredFailure -> {}
            )
        );
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
                    "unexpected physical tuple completion for " + operation.input.requestId()
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
        operation.state = State.WAITING_TO_RETRY;
        try {
            operation.retryTimer = eventLoop.schedule(
                () -> submitPhysicalWrite(operation),
                retryDelay.toNanos(),
                TimeUnit.NANOSECONDS
            );
            operations.complete(registration);
        } catch (Throwable schedulingFailure) {
            operation.state = State.FAILED;
            operation.completion.completeExceptionally(schedulingFailure);
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

    private void finish(WriteOperation operation, TupleWriteResult result) {
        active.remove(operation.input.requestId(), operation);
        if (operation.logicalRegistration != null) {
            operations.complete(operation.logicalRegistration);
            operation.logicalRegistration = null;
        }
        if (operation.released.compareAndSet(false, true)) {
            try {
                tupleReleaser.accept(operation.input.tuple());
            } catch (Throwable failure) {
                reportFatal("tuple ownership release", failure);
            }
        }
        operation.completion.complete(result);
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
        private final WriteTuple<T> input;
        private final CompletableFuture<TupleWriteResult> completion = new CompletableFuture<>();
        private final AtomicBoolean released = new AtomicBoolean();
        private State state = State.NEW;
        private OutstandingOperationRegistry.Registration logicalRegistration;
        private OutstandingOperationRegistry.Registration physicalRegistration;
        private ScheduledFuture<?> retryTimer;

        private WriteOperation(WriteTuple<T> input) {
            this.input = input;
        }

        @Override
        public CompletionStage<TupleWriteResult> completion() {
            return completion.minimalCompletionStage();
        }

        @Override
        public void cancel(@NonNull CancellationException cause) {
            postRequired(
                "logical tuple cancellation " + input.requestId(),
                () -> cancelOnOwner(this, cause),
                ignored -> {}
            );
        }
    }
}
