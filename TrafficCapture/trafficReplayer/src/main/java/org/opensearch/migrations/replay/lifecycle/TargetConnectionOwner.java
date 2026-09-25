/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.migrations.replay.lifecycle;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

import org.opensearch.migrations.replay.identity.CancellationDeadline;
import org.opensearch.migrations.replay.identity.ConnectionProcessingId;
import org.opensearch.migrations.replay.identity.PartitionGenerationId;
import org.opensearch.migrations.replay.identity.ReplayRequestId;
import org.opensearch.migrations.replay.lifecycle.OutstandingOperationRegistry.OperationType;
import org.opensearch.migrations.replay.lifecycle.OutstandingOperationRegistry.WaitReason;
import org.opensearch.migrations.replay.lifecycle.ReplayOutcomes.RequestPreparationCancelled;
import org.opensearch.migrations.replay.lifecycle.ReplayOutcomes.RequestPreparationReady;
import org.opensearch.migrations.replay.lifecycle.ReplayOutcomes.RequestPreparationResult;
import org.opensearch.migrations.replay.sink.TupleWriter;

import io.netty.channel.EventLoop;
import io.netty.util.concurrent.ScheduledFuture;
import lombok.NonNull;

/**
 * Event-loop-confined owner of one process-local target connection and its request registry.
 */
public final class TargetConnectionOwner<S, P extends AutoCloseable, R, F, T> {
    private static final Duration PREPARATION_LEAD = Duration.ofSeconds(1);

    public sealed interface ConnectionInput<S, F>
        permits AdmitReconstitutedRequest,
            AdmitCapturedClose,
            SourceResponseComplete,
            SourceResponseUnavailableForRetry,
            SourceResponseIncomplete,
            CapturedConnectionExpired,
            GracefulConnectionCancellation,
            ForceConnectionCancellation {

        ConnectionProcessingId connectionProcessingId();

        PartitionGenerationId partitionGenerationId();
    }

    public record AdmitReconstitutedRequest<S, F>(
        @NonNull ConnectionProcessingId connectionProcessingId,
        @NonNull PartitionGenerationId partitionGenerationId,
        @NonNull ReplayRequestId requestId,
        long capturedRequestOrdinal,
        @NonNull Instant requestFirstByteSourceTime,
        @NonNull S sourceRequest,
        @NonNull String activityIdentity
    ) implements ConnectionInput<S, F> {}

    public record AdmitCapturedClose<S, F>(
        @NonNull ConnectionProcessingId connectionProcessingId,
        @NonNull PartitionGenerationId partitionGenerationId,
        long capturedOrdinal,
        @NonNull Instant replayTime
    ) implements ConnectionInput<S, F> {}

    public record SourceResponseComplete<S, F>(
        @NonNull ConnectionProcessingId connectionProcessingId,
        @NonNull PartitionGenerationId partitionGenerationId,
        @NonNull ReplayRequestId requestId,
        @NonNull F response
    ) implements ConnectionInput<S, F> {}

    public record SourceResponseUnavailableForRetry<S, F>(
        @NonNull ConnectionProcessingId connectionProcessingId,
        @NonNull PartitionGenerationId partitionGenerationId,
        @NonNull ReplayRequestId requestId
    ) implements ConnectionInput<S, F> {}

    public record SourceResponseIncomplete<S, F>(
        @NonNull ConnectionProcessingId connectionProcessingId,
        @NonNull PartitionGenerationId partitionGenerationId,
        @NonNull ReplayRequestId requestId,
        @NonNull String reason
    ) implements ConnectionInput<S, F> {}

    public record CapturedConnectionExpired<S, F>(
        @NonNull ConnectionProcessingId connectionProcessingId,
        @NonNull PartitionGenerationId partitionGenerationId
    ) implements ConnectionInput<S, F> {}

    public record GracefulConnectionCancellation<S, F>(
        @NonNull ConnectionProcessingId connectionProcessingId,
        @NonNull PartitionGenerationId partitionGenerationId,
        @NonNull CancellationDeadline deadline,
        @NonNull CancellationException cause
    ) implements ConnectionInput<S, F> {}

    public record ForceConnectionCancellation<S, F>(
        @NonNull ConnectionProcessingId connectionProcessingId,
        @NonNull PartitionGenerationId partitionGenerationId,
        @NonNull CancellationException cause
    ) implements ConnectionInput<S, F> {}

    public sealed interface RequestAdmissionResult
        permits RequestAdmissionAccepted, RequestAdmissionRejected {}

    public record RequestAdmissionAccepted() implements RequestAdmissionResult {}

    public record RequestAdmissionRejected(
        @NonNull CancellationException cause
    ) implements RequestAdmissionResult {}

    public record InputApplied() {}

    public interface LifecycleSink {
        CompletionStage<Void> connectionRequestFinished(
            PartitionGenerationId partitionGenerationId,
            ConnectionProcessingId connectionProcessingId,
            ReplayRequestId requestId
        );

        CompletionStage<Void> requestProcessingFinished(
            PartitionGenerationId partitionGenerationId,
            ConnectionProcessingId connectionProcessingId,
            ReplayRequestId requestId
        );

        CompletionStage<Void> connectionOwnerFinished(
            PartitionGenerationId partitionGenerationId,
            ConnectionProcessingId connectionProcessingId
        );

        CompletionStage<Void> connectionCleanupFinished(
            PartitionGenerationId partitionGenerationId,
            ConnectionProcessingId connectionProcessingId
        );
    }

    @FunctionalInterface
    public interface FatalHandler {
        void onFatal(Error failure);
    }

    private enum SourceLifetime {
        OPEN,
        CAPTURED_CLOSE_ADMITTED,
        EXPIRED,
        CANCELLING,
        CLOSED
    }

    private enum PreparationReadiness {
        WAITING,
        READY,
        CANCELLED
    }

    private sealed interface AdmissionEntry
        permits RequestEntry, CloseEntry {

        long capturedOrdinal();

        Instant admissionTime();
    }

    private sealed interface ExecutionEntry
        permits RequestExecutionEntry, CloseEntry {

        long capturedOrdinal();

        Instant executionTime();
    }

    private static final class RequestEntry<S, P extends AutoCloseable, R, F, T>
        implements AdmissionEntry {
        private final ReplayRequestId requestId;
        private final long capturedOrdinal;
        private final Instant nominalTargetTime;
        private final RequestReplayOwner<S, P, R, F, T> owner;
        private boolean firstTargetWriteSubmitted;
        private boolean finalTargetWriteSubmitted;
        private boolean targetTurnStarted;
        private boolean connectionTurnFinished;
        private boolean connectionRequestFinishedSubmitted;

        private RequestEntry(
            ReplayRequestId requestId,
            long capturedOrdinal,
            Instant nominalTargetTime,
            RequestReplayOwner<S, P, R, F, T> owner
        ) {
            this.requestId = requestId;
            this.capturedOrdinal = capturedOrdinal;
            this.nominalTargetTime = nominalTargetTime;
            this.owner = owner;
        }

        @Override
        public long capturedOrdinal() {
            return capturedOrdinal;
        }

        @Override
        public Instant admissionTime() {
            return nominalTargetTime.minus(PREPARATION_LEAD);
        }
    }

    private static final class RequestExecutionEntry<S, P extends AutoCloseable, R, F, T>
        implements ExecutionEntry {
        private final RequestEntry<S, P, R, F, T> request;
        private PreparationReadiness readiness = PreparationReadiness.WAITING;

        private RequestExecutionEntry(RequestEntry<S, P, R, F, T> request) {
            this.request = request;
        }

        @Override
        public long capturedOrdinal() {
            return request.capturedOrdinal;
        }

        @Override
        public Instant executionTime() {
            return request.nominalTargetTime;
        }
    }

    private static final class CloseEntry implements AdmissionEntry, ExecutionEntry {
        private final long capturedOrdinal;
        private final Instant replayTime;

        private CloseEntry(long capturedOrdinal, Instant replayTime) {
            this.capturedOrdinal = capturedOrdinal;
            this.replayTime = replayTime;
        }

        @Override
        public long capturedOrdinal() {
            return capturedOrdinal;
        }

        @Override
        public Instant admissionTime() {
            return replayTime;
        }

        @Override
        public Instant executionTime() {
            return replayTime;
        }
    }

    private record PendingPermit<S, P extends AutoCloseable, R, F, T>(
        RequestEntry<S, P, R, F, T> request,
        TargetAttemptPermitProvider.Acquisition acquisition,
        OutstandingOperationRegistry.Registration registration,
        CompletableFuture<Void> retryDelivery
    ) {}

    private final ConnectionProcessingId connectionProcessingId;
    private final PartitionGenerationId partitionGenerationId;
    private final EventLoop eventLoop;
    private final Clock clock;
    private final LongSupplier nanoTime;
    private final Function<Instant, Instant> replayTimeMapper;
    private final RequestReplayOwner.RequestPreparer<S, P> preparer;
    private final RequestReplayOwner.RetryPolicy<R, F> retryPolicy;
    private final TargetChannelPort<P, R> targetChannel;
    private final TupleWriter<T> tupleWriter;
    private final RequestReplayOwner.TupleFactory<S, P, R, F, T> tupleFactory;
    private final RequestReplayOwner.ResourceReleaser<S, P, R, F> resourceReleaser;
    private final TargetAttemptPermitProvider permitProvider;
    private final LifecycleSink lifecycleSink;
    private final FatalHandler fatalHandler;
    private final OutstandingOperationRegistry.CountHook countHook;
    private final OutstandingOperationRegistry operations;
    private final Deque<AdmissionEntry> admissionQueue = new ArrayDeque<>();
    private final Deque<ExecutionEntry> executionQueue = new ArrayDeque<>();
    private final Map<ReplayRequestId, RequestEntry<S, P, R, F, T>> requestRegistry =
        new LinkedHashMap<>();
    private volatile List<RequestReplayOwner<S, P, R, F, T>> publishedRequestOwners =
        List.of();

    private SourceLifetime sourceLifetime = SourceLifetime.OPEN;
    private ScheduledFuture<?> admissionTimer;
    private ScheduledFuture<?> executionTimer;
    private RequestEntry<S, P, R, F, T> activeTurn;
    private PendingPermit<S, P, R, F, T> pendingPermit;
    private long lastCapturedOrdinal = Long.MIN_VALUE;
    private boolean targetChannelClosed;
    private boolean targetChannelClosePending;
    private boolean ownerFinishedSubmitted;

    public TargetConnectionOwner(
        @NonNull ConnectionProcessingId connectionProcessingId,
        @NonNull EventLoop eventLoop,
        @NonNull Clock clock,
        @NonNull LongSupplier nanoTime,
        @NonNull Function<Instant, Instant> replayTimeMapper,
        @NonNull RequestReplayOwner.RequestPreparer<S, P> preparer,
        @NonNull RequestReplayOwner.RetryPolicy<R, F> retryPolicy,
        @NonNull TargetChannelPort<P, R> targetChannel,
        @NonNull TupleWriter<T> tupleWriter,
        @NonNull RequestReplayOwner.TupleFactory<S, P, R, F, T> tupleFactory,
        @NonNull RequestReplayOwner.ResourceReleaser<S, P, R, F> resourceReleaser,
        @NonNull TargetAttemptPermitProvider permitProvider,
        @NonNull LifecycleSink lifecycleSink,
        @NonNull FatalHandler fatalHandler,
        @NonNull OutstandingOperationRegistry.CountHook countHook
    ) {
        this.connectionProcessingId = connectionProcessingId;
        this.partitionGenerationId = connectionProcessingId.generation();
        this.eventLoop = eventLoop;
        this.clock = clock;
        this.nanoTime = nanoTime;
        this.replayTimeMapper = replayTimeMapper;
        this.preparer = preparer;
        this.retryPolicy = retryPolicy;
        this.targetChannel = targetChannel;
        this.tupleWriter = tupleWriter;
        this.tupleFactory = tupleFactory;
        this.resourceReleaser = resourceReleaser;
        this.permitProvider = permitProvider;
        this.lifecycleSink = lifecycleSink;
        this.fatalHandler = fatalHandler;
        this.countHook = countHook;
        this.operations = new OutstandingOperationRegistry(
            "connection " + connectionProcessingId,
            eventLoop,
            clock,
            fatalHandler::onFatal,
            (operationType, typeCount, totalCount) -> {
                countHook.operationCountChanged(operationType, typeCount, totalCount);
                if (totalCount == 0) {
                    tryFinishOwner();
                }
            }
        );
    }

    public CompletionStage<RequestAdmissionResult> submit(
        @NonNull AdmitReconstitutedRequest<S, F> admission
    ) {
        return submitInput(admission, () -> applyRequestAdmission(admission));
    }

    public CompletionStage<InputApplied> submit(@NonNull AdmitCapturedClose<S, F> input) {
        return submitNonAdmissionInput(input);
    }

    public CompletionStage<InputApplied> submit(@NonNull SourceResponseComplete<S, F> input) {
        return submitNonAdmissionInput(input);
    }

    public CompletionStage<InputApplied> submit(
        @NonNull SourceResponseUnavailableForRetry<S, F> input
    ) {
        return submitNonAdmissionInput(input);
    }

    public CompletionStage<InputApplied> submit(@NonNull SourceResponseIncomplete<S, F> input) {
        return submitNonAdmissionInput(input);
    }

    public CompletionStage<InputApplied> submit(@NonNull CapturedConnectionExpired<S, F> input) {
        return submitNonAdmissionInput(input);
    }

    public CompletionStage<InputApplied> submit(
        @NonNull GracefulConnectionCancellation<S, F> input
    ) {
        return submitNonAdmissionInput(input);
    }

    public CompletionStage<InputApplied> submit(
        @NonNull ForceConnectionCancellation<S, F> input
    ) {
        return submitNonAdmissionInput(input);
    }

    private CompletionStage<InputApplied> submitNonAdmissionInput(
        ConnectionInput<S, F> input
    ) {
        return submitInput(input, () -> applyNonAdmissionInput(input));
    }

    private <V> CompletionStage<V> submitInput(
        ConnectionInput<S, F> input,
        Supplier<V> transition
    ) {
        var completion = new CompletableFuture<V>();
        try {
            eventLoop.execute(() -> {
                try {
                    requireOwnerThread();
                    completion.complete(transition.get());
                } catch (Throwable failure) {
                    completion.completeExceptionally(failure);
                    impossible(
                        "connection input " + input.getClass().getSimpleName(),
                        failure
                    );
                }
            });
        } catch (Throwable failure) {
            completion.completeExceptionally(failure);
            reportFatal("required connection-input submission", failure);
        }
        return completion.minimalCompletionStage();
    }

    public OutstandingOperationRegistry operations() {
        return operations;
    }

    public List<OutstandingOperationRegistry.Snapshot> activitySnapshot() {
        var snapshot = new ArrayList<>(operations.snapshots());
        for (var requestOwner : publishedRequestOwners) {
            snapshot.addAll(requestOwner.operations().snapshots());
        }
        return List.copyOf(snapshot);
    }

    int registeredRequestCount() {
        requireOwnerThread();
        return requestRegistry.size();
    }

    private InputApplied applyNonAdmissionInput(ConnectionInput<S, F> input) {
        requireOwnerThread();
        return switch (input) {
            case AdmitReconstitutedRequest<S, F> ignored ->
                throw new IllegalArgumentException(
                    "request admission requires the typed admission submission path"
                );
            case AdmitCapturedClose<S, F> close -> {
                applyCapturedClose(close);
                yield new InputApplied();
            }
            case SourceResponseComplete<S, F> complete -> {
                validateIdentity(complete);
                var request = routeRequest(complete.requestId());
                if (request != null) {
                    request.sourceResponseComplete(complete.response());
                }
                yield new InputApplied();
            }
            case SourceResponseUnavailableForRetry<S, F> unavailable -> {
                validateIdentity(unavailable);
                var request = routeRequest(unavailable.requestId());
                if (request != null) {
                    request.sourceResponseUnavailableForRetry();
                }
                yield new InputApplied();
            }
            case SourceResponseIncomplete<S, F> incomplete -> {
                validateIdentity(incomplete);
                var request = routeRequest(incomplete.requestId());
                if (request != null) {
                    request.sourceResponseIncomplete(incomplete.reason());
                }
                yield new InputApplied();
            }
            case CapturedConnectionExpired<S, F> expired -> {
                validateIdentity(expired);
                expireConnection();
                yield new InputApplied();
            }
            case GracefulConnectionCancellation<S, F> graceful -> {
                validateIdentity(graceful);
                gracefulCancel(graceful.deadline(), graceful.cause());
                yield new InputApplied();
            }
            case ForceConnectionCancellation<S, F> forced -> {
                validateIdentity(forced);
                forceCancel(forced.cause());
                yield new InputApplied();
            }
        };
    }

    private RequestAdmissionResult applyRequestAdmission(
        AdmitReconstitutedRequest<S, F> admission
    ) {
        if (!matchesIdentity(admission)) {
            throw new IllegalArgumentException(
                "request admission identity "
                    + admission.connectionProcessingId()
                    + " / "
                    + admission.partitionGenerationId()
                    + " does not match "
                    + connectionProcessingId
            );
        }
        if (sourceLifetime != SourceLifetime.OPEN) {
            return new RequestAdmissionRejected(
                rejectionCause(new IllegalStateException(
                    "connection is not accepting request admissions"
                ))
            );
        }
        if (!admission.requestId().connectionProcessingId().equals(connectionProcessingId)) {
            throw new IllegalArgumentException(
                "request belongs to " + admission.requestId().connectionProcessingId()
            );
        }
        if (admission.requestId().capturedRequestOrdinal()
            != admission.capturedRequestOrdinal()) {
            var failure = new IllegalArgumentException(
                "request identity ordinal "
                    + admission.requestId().capturedRequestOrdinal()
                    + " does not match admitted ordinal "
                    + admission.capturedRequestOrdinal()
            );
            throw failure;
        }
        if (requestRegistry.containsKey(admission.requestId())) {
            var failure = new IllegalStateException(
                "request is already registered: " + admission.requestId()
            );
            throw failure;
        }
        if (!acceptCapturedOrdinal(admission.capturedRequestOrdinal())) {
            var failure = new IllegalStateException(
                "captured ordinal "
                    + admission.capturedRequestOrdinal()
                    + " did not follow "
                    + lastCapturedOrdinal
            );
            throw failure;
        }
        var nominalTargetTime = Objects.requireNonNull(
            replayTimeMapper.apply(admission.requestFirstByteSourceTime()),
            "replay-time mapping returned no nominal target time"
        );
        var requestOwner = new RequestReplayOwner<>(
            partitionGenerationId,
            connectionProcessingId,
            admission.requestId(),
            nominalTargetTime,
            admission.sourceRequest(),
            eventLoop,
            clock,
            nanoTime,
            preparer,
            retryPolicy,
            targetChannel,
            tupleFactory,
            tupleWriter,
            resourceReleaser,
            new RequestCallbacks(),
            fatalHandler::onFatal,
            countHook
        );
        var entry = new RequestEntry<S, P, R, F, T>(
            admission.requestId(),
            admission.capturedRequestOrdinal(),
            nominalTargetTime,
            requestOwner
        );
        requestRegistry.put(admission.requestId(), entry);
        publishRequestOwners();
        admissionQueue.addLast(entry);
        scheduleAdmissionHead();
        return new RequestAdmissionAccepted();
    }

    private void applyCapturedClose(AdmitCapturedClose<S, F> close) {
        validateIdentity(close);
        if (sourceLifetime != SourceLifetime.OPEN) {
            impossible(
                "captured close admission",
                new IllegalStateException("source lifetime is " + sourceLifetime)
            );
            return;
        }
        if (!acceptCapturedOrdinal(close.capturedOrdinal())) {
            impossible(
                "captured close ordering",
                new IllegalStateException(
                    "captured ordinal "
                        + close.capturedOrdinal()
                        + " did not follow "
                        + lastCapturedOrdinal
                )
            );
            return;
        }
        sourceLifetime = SourceLifetime.CAPTURED_CLOSE_ADMITTED;
        admissionQueue.addLast(new CloseEntry(close.capturedOrdinal(), close.replayTime()));
        scheduleAdmissionHead();
    }

    private boolean acceptCapturedOrdinal(long candidate) {
        if (candidate < 0 || candidate <= lastCapturedOrdinal) {
            return false;
        }
        lastCapturedOrdinal = candidate;
        return true;
    }

    private void scheduleAdmissionHead() {
        requireOwnerThread();
        cancelAdmissionTimer();
        if (sourceLifetime == SourceLifetime.CANCELLING || admissionQueue.isEmpty()) {
            return;
        }
        var head = admissionQueue.peekFirst();
        var delay = nonNegativeDelay(clock.instant(), head.admissionTime());
        if (delay.isZero()) {
            promoteDueAdmissions();
            return;
        }
        try {
            admissionTimer = eventLoop.schedule(
                () -> runTransition("admission timer", this::promoteDueAdmissions),
                delay.toNanos(),
                TimeUnit.NANOSECONDS
            );
        } catch (Throwable failure) {
            impossible("admission timer submission", failure);
        }
    }

    private void promoteDueAdmissions() {
        requireOwnerThread();
        admissionTimer = null;
        while (!admissionQueue.isEmpty()
            && !admissionQueue.peekFirst().admissionTime().isAfter(clock.instant())) {
            var entry = admissionQueue.removeFirst();
            switch (entry) {
                case RequestEntry<?, ?, ?, ?, ?> request -> {
                    @SuppressWarnings("unchecked")
                    var typed = (RequestEntry<S, P, R, F, T>) request;
                    executionQueue.addLast(
                        new RequestExecutionEntry<S, P, R, F, T>(typed)
                    );
                    typed.owner.beginPreparation();
                }
                case CloseEntry close -> executionQueue.addLast(close);
            }
        }
        scheduleAdmissionHead();
        evaluateExecutionHead();
    }

    private void scheduleExecutionHead() {
        cancelExecutionTimer();
        if (executionQueue.isEmpty()
            || activeTurn != null
            || pendingPermit != null
            || sourceLifetime == SourceLifetime.CANCELLING) {
            return;
        }
        var head = executionQueue.peekFirst();
        var delay = nonNegativeDelay(clock.instant(), head.executionTime());
        if (delay.isZero()) {
            evaluateExecutionHead();
            return;
        }
        try {
            executionTimer = eventLoop.schedule(
                () -> runTransition("execution timer", this::evaluateExecutionHead),
                delay.toNanos(),
                TimeUnit.NANOSECONDS
            );
        } catch (Throwable failure) {
            impossible("execution timer submission", failure);
        }
    }

    private void evaluateExecutionHead() {
        requireOwnerThread();
        executionTimer = null;
        if (executionQueue.isEmpty()
            || activeTurn != null
            || pendingPermit != null
            || sourceLifetime == SourceLifetime.CANCELLING) {
            maybeCloseAfterSourceEnd();
            return;
        }
        var head = executionQueue.peekFirst();
        if (head.executionTime().isAfter(clock.instant())) {
            scheduleExecutionHead();
            return;
        }
        switch (head) {
            case RequestExecutionEntry<?, ?, ?, ?, ?> request -> {
                @SuppressWarnings("unchecked")
                var typed = (RequestExecutionEntry<S, P, R, F, T>) request;
                switch (typed.readiness) {
                    case WAITING -> {}
                    case READY -> {
                        activeTurn = typed.request;
                        acquirePermit(typed.request, null);
                    }
                    case CANCELLED -> {
                        executionQueue.removeFirst();
                        evaluateExecutionHead();
                    }
                }
            }
            case CloseEntry ignored ->
                closeTargetChannel();
        }
    }

    private void acquirePermit(
        RequestEntry<S, P, R, F, T> request,
        CompletableFuture<Void> retryDelivery
    ) {
        var registration = operations.register(
            partitionGenerationId,
            connectionProcessingId,
            request.requestId,
            OperationType.PERMIT_ACQUISITION,
            request.nominalTargetTime,
            WaitReason.WAITING_FOR_PERMIT
        );
        final TargetAttemptPermitProvider.Acquisition acquisition;
        try {
            acquisition = Objects.requireNonNull(
                permitProvider.acquire(request.requestId),
                "permit provider returned no acquisition"
            );
        } catch (Throwable failure) {
            impossible("permit acquisition submission", failure);
            return;
        }
        var expected = new PendingPermit<>(
            request,
            acquisition,
            registration,
            retryDelivery
        );
        pendingPermit = expected;
        acquisition.completion().whenComplete((result, failure) ->
            postPermitResult(expected, result, unwrap(failure))
        );
    }

    private void postPermitResult(
        PendingPermit<S, P, R, F, T> expected,
        TargetAttemptPermitProvider.AcquisitionResult result,
        Throwable failure
    ) {
        if (eventLoop.inEventLoop()) {
            applyPermitResult(expected, result, failure);
            return;
        }
        try {
            eventLoop.execute(() -> runTransition(
                "permit result",
                () -> applyPermitResult(expected, result, failure)
            ));
        } catch (Throwable submissionFailure) {
            if (result instanceof TargetAttemptPermitProvider.PermitAcquired acquired) {
                acquired.permit().close();
            }
            if (expected.retryDelivery() != null) {
                expected.retryDelivery().completeExceptionally(submissionFailure);
            }
            reportFatal("required permit delivery submission", submissionFailure);
        }
    }

    private void applyPermitResult(
        PendingPermit<S, P, R, F, T> expected,
        TargetAttemptPermitProvider.AcquisitionResult result,
        Throwable failure
    ) {
        requireOwnerThread();
        if (pendingPermit != expected) {
            if (result instanceof TargetAttemptPermitProvider.PermitAcquired acquired) {
                acquired.permit().close();
            }
            impossible(
                "permit result",
                new IllegalStateException("permit result did not match pending acquisition")
            );
            return;
        }
        pendingPermit = null;
        if (failure != null) {
            if (expected.retryDelivery() != null) {
                expected.retryDelivery().completeExceptionally(failure);
            }
            impossible("permit acquisition exceptional completion", failure);
            return;
        }
        if (result == null) {
            var missing = new NullPointerException("permit acquisition returned no result");
            if (expected.retryDelivery() != null) {
                expected.retryDelivery().completeExceptionally(missing);
            }
            impossible("permit acquisition completion", missing);
            return;
        }
        switch (result) {
            case TargetAttemptPermitProvider.PermitAcquired acquired -> {
                operations.complete(expected.registration());
                if (sourceLifetime == SourceLifetime.CANCELLING
                    && !expected.request.finalTargetWriteSubmitted) {
                    acquired.permit().close();
                } else {
                    expected.request.targetTurnStarted = true;
                    expected.request.owner.acceptAttemptPermit(acquired.permit());
                }
                if (expected.retryDelivery() != null) {
                    expected.retryDelivery().complete(null);
                }
            }
            case TargetAttemptPermitProvider.AcquisitionCancelled cancelled -> {
                operations.complete(expected.registration());
                if (expected.retryDelivery() != null) {
                    expected.retryDelivery().complete(null);
                }
                expected.request.owner.forceCancel(cancelled.cause());
            }
        }
    }

    private void closeTargetChannel() {
        if (targetChannelClosed || targetChannelClosePending) {
            return;
        }
        targetChannelClosePending = true;
        var registration = operations.register(
            partitionGenerationId,
            connectionProcessingId,
            null,
            OperationType.TARGET_CHANNEL_CLOSE,
            null,
            WaitReason.WAITING_FOR_CHANNEL_CLOSE
        );
        final CompletionStage<Void> close;
        try {
            close = Objects.requireNonNull(
                targetChannel.close(connectionProcessingId),
                "target channel returned no close completion"
            );
        } catch (Throwable failure) {
            impossible("target channel close submission", failure);
            return;
        }
        close.whenComplete((ignored, failure) ->
            postRequired(
                "target channel close completion",
                () -> {
                    if (failure != null) {
                        impossible("target channel close", unwrap(failure));
                        return;
                    }
                    targetChannelClosePending = false;
                    targetChannelClosed = true;
                    if (!executionQueue.isEmpty()
                        && executionQueue.peekFirst() instanceof CloseEntry) {
                        executionQueue.removeFirst();
                    }
                    if (sourceLifetime == SourceLifetime.CAPTURED_CLOSE_ADMITTED
                        || sourceLifetime == SourceLifetime.EXPIRED) {
                        sourceLifetime = SourceLifetime.CLOSED;
                    }
                    operations.complete(registration);
                    evaluateExecutionHead();
                    tryFinishOwner();
                }
            )
        );
    }

    private void expireConnection() {
        if (sourceLifetime != SourceLifetime.OPEN) {
            impossible(
                "captured connection expiration",
                new IllegalStateException("source lifetime is " + sourceLifetime)
            );
            return;
        }
        sourceLifetime = SourceLifetime.EXPIRED;
        maybeCloseAfterSourceEnd();
    }

    private void gracefulCancel(
        CancellationDeadline deadline,
        CancellationException cause
    ) {
        if (sourceLifetime == SourceLifetime.CLOSED) {
            return;
        }
        sourceLifetime = SourceLifetime.CANCELLING;
        cancelAdmissionTimer();
        cancelExecutionTimer();
        discardCloseEntries();
        for (var entry : List.copyOf(requestRegistry.values())) {
            entry.owner.gracefulCancel(deadline, cause);
        }
        if (pendingPermit != null
            && !pendingPermit.request().finalTargetWriteSubmitted) {
            pendingPermit.acquisition().cancel(cause);
        }
        maybeCloseAfterSourceEnd();
    }

    private void forceCancel(CancellationException cause) {
        sourceLifetime = SourceLifetime.CANCELLING;
        cancelAdmissionTimer();
        cancelExecutionTimer();
        discardCloseEntries();
        if (pendingPermit != null) {
            pendingPermit.acquisition().cancel(cause);
        }
        for (var entry : List.copyOf(requestRegistry.values())) {
            entry.owner.forceCancel(cause);
        }
        maybeCloseAfterSourceEnd();
    }

    private void discardCloseEntries() {
        admissionQueue.removeIf(CloseEntry.class::isInstance);
        executionQueue.removeIf(CloseEntry.class::isInstance);
    }

    private void maybeCloseAfterSourceEnd() {
        if ((sourceLifetime == SourceLifetime.CAPTURED_CLOSE_ADMITTED
            || sourceLifetime == SourceLifetime.EXPIRED
            || sourceLifetime == SourceLifetime.CANCELLING)
            && admissionQueue.isEmpty()
            && executionQueue.isEmpty()
            && activeTurn == null
            && pendingPermit == null) {
            closeTargetChannel();
        }
    }

    private void tryFinishOwner() {
        if (ownerFinishedSubmitted
            || sourceLifetime == SourceLifetime.OPEN
            || !targetChannelClosed
            || targetChannelClosePending
            || !admissionQueue.isEmpty()
            || !executionQueue.isEmpty()
            || activeTurn != null
            || pendingPermit != null
            || !requestRegistry.isEmpty()
            || admissionTimer != null
            || executionTimer != null
            || operations.activeCount() != 0) {
            return;
        }
        ownerFinishedSubmitted = true;
        requiredLifecycleDelivery(
            null,
            sourceLifetime == SourceLifetime.CANCELLING
                ? "connection cleanup completion"
                : "connection-owner completion",
            () -> sourceLifetime == SourceLifetime.CANCELLING
                ? lifecycleSink.connectionCleanupFinished(
                    partitionGenerationId,
                    connectionProcessingId
                )
                : lifecycleSink.connectionOwnerFinished(
                    partitionGenerationId,
                    connectionProcessingId
                ),
            () -> {}
        );
    }

    private RequestReplayOwner<S, P, R, F, T> routeRequest(ReplayRequestId requestId) {
        if (!requestId.connectionProcessingId().equals(connectionProcessingId)) {
            var failure = new IllegalArgumentException(
                "request belongs to " + requestId.connectionProcessingId()
            );
            impossible("source-response routing", failure);
            throw failure;
        }
        var request = requestRegistry.get(requestId);
        if (request == null) {
            var failure = new IllegalStateException(
                "no registered request for source-response input " + requestId
            );
            if (sourceLifetime == SourceLifetime.CANCELLING) {
                return null;
            }
            impossible("source-response routing", failure);
            throw failure;
        }
        return request.owner;
    }

    private void validateIdentity(ConnectionInput<S, F> input) {
        if (!matchesIdentity(input)) {
            var failure = new IllegalArgumentException(
                "input identity "
                    + input.connectionProcessingId()
                    + " / "
                    + input.partitionGenerationId()
                    + " does not match "
                    + connectionProcessingId
            );
            impossible("connection input identity", failure);
            throw failure;
        }
    }

    private boolean matchesIdentity(ConnectionInput<S, F> input) {
        return input.connectionProcessingId().equals(connectionProcessingId)
            && input.partitionGenerationId().equals(partitionGenerationId);
    }

    private void preparationFinished(
        ReplayRequestId requestId,
        RequestPreparationResult<?> result
    ) {
        var execution = findExecution(requestId);
        if (execution == null) {
            impossible(
                "preparation readiness",
                new IllegalStateException(
                    "request has no execution entry: " + requestId
                )
            );
            return;
        }
        if (execution.readiness != PreparationReadiness.WAITING) {
            impossible(
                "preparation readiness",
                new IllegalStateException(
                    "preparation readiness is " + execution.readiness
                )
            );
            return;
        }
        switch (result) {
            case RequestPreparationReady<?> ignored ->
                execution.readiness = PreparationReadiness.READY;
            case RequestPreparationCancelled<?> ignored ->
                execution.readiness = PreparationReadiness.CANCELLED;
        }
        evaluateExecutionHead();
    }

    private RequestExecutionEntry<S, P, R, F, T> findExecution(
        ReplayRequestId requestId
    ) {
        for (var entry : executionQueue) {
            if (entry instanceof RequestExecutionEntry<?, ?, ?, ?, ?> request
                && request.request.requestId.equals(requestId)) {
                @SuppressWarnings("unchecked")
                var typed = (RequestExecutionEntry<S, P, R, F, T>) request;
                return typed;
            }
        }
        return null;
    }

    private void firstTargetWriteSubmitted(ReplayRequestId requestId) {
        var request = requireRegistered(requestId);
        if (request.firstTargetWriteSubmitted) {
            impossible(
                "first target write",
                new IllegalStateException(
                    "first target write was submitted more than once"
                )
            );
            return;
        }
        request.firstTargetWriteSubmitted = true;
    }

    private void finalTargetWriteSubmitted(ReplayRequestId requestId) {
        var request = requireRegistered(requestId);
        if (!request.firstTargetWriteSubmitted) {
            impossible(
                "final target write",
                new IllegalStateException(
                    "final target write arrived before first target write"
                )
            );
            return;
        }
        if (request.finalTargetWriteSubmitted) {
            impossible(
                "final target write",
                new IllegalStateException(
                    "final target write was submitted more than once"
                )
            );
            return;
        }
        request.finalTargetWriteSubmitted = true;
    }

    private CompletionStage<Void> connectionTurnFinished(
        ReplayRequestId requestId,
        RequestReplayOwner.ConnectionTurnCompletion completion
    ) {
        var request = requireRegistered(requestId);
        if (!request.targetTurnStarted
            || activeTurn != request
            || executionQueue.isEmpty()
            || !(executionQueue.peekFirst()
                instanceof RequestExecutionEntry<?, ?, ?, ?, ?> execution)
            || execution.request != request) {
            var failure = new IllegalStateException(
                "request does not own the active connection turn: " + requestId
            );
            impossible("connection-turn completion", failure);
            return CompletableFuture.failedFuture(failure);
        }
        if (request.connectionTurnFinished) {
            var failure = new IllegalStateException(
                "connection-turn completion arrived twice for " + requestId
            );
            impossible("connection-turn completion", failure);
            return CompletableFuture.failedFuture(failure);
        }
        request.connectionTurnFinished = true;
        activeTurn = null;
        executionQueue.removeFirst();
        evaluateExecutionHead();
        if (completion
            == RequestReplayOwner.ConnectionTurnCompletion.CANCELLATION_ENDED_STARTED_TURN) {
            return CompletableFuture.completedFuture(null);
        }
        request.connectionRequestFinishedSubmitted = true;
        return requiredLifecycleDelivery(
            requestId,
            "connection-request completion",
            () -> lifecycleSink.connectionRequestFinished(
                partitionGenerationId,
                connectionProcessingId,
                requestId
            ),
            () -> {}
        );
    }

    private CompletionStage<Void> requestProcessingFinished(ReplayRequestId requestId) {
        var request = requireRegistered(requestId);
        if (!request.connectionTurnFinished
            || !request.connectionRequestFinishedSubmitted) {
            var failure = new IllegalStateException(
                "request processing completed before connection turn: " + requestId
            );
            impossible("request-processing completion", failure);
            return CompletableFuture.failedFuture(failure);
        }
        return requiredLifecycleDelivery(
            requestId,
            "request-processing completion",
            () -> lifecycleSink.requestProcessingFinished(
                partitionGenerationId,
                connectionProcessingId,
                requestId
            ),
            () -> {
                if (!requestRegistry.remove(requestId, request)) {
                    impossible(
                        "request registry removal",
                        new IllegalStateException(
                            "request disappeared before processing acceptance: " + requestId
                        )
                    );
                    return;
                }
                publishRequestOwners();
                maybeCloseAfterSourceEnd();
                tryFinishOwner();
            }
        );
    }

    private CompletionStage<Void> requestCleanupFinished(
        ReplayRequestId requestId,
        CancellationException cause
    ) {
        var request = requireRegistered(requestId);
        if (activeTurn == request && request.targetTurnStarted) {
            var failure = new IllegalStateException(
                "started target turn was not finished before request cleanup: " + requestId
            );
            impossible("request cleanup", failure);
            return CompletableFuture.failedFuture(failure);
        }
        admissionQueue.remove(request);
        executionQueue.removeIf(entry ->
            entry instanceof RequestExecutionEntry<?, ?, ?, ?, ?> execution
                && execution.request == request
        );
        if (activeTurn == request) {
            activeTurn = null;
        }
        if (!requestRegistry.remove(requestId, request)) {
            var failure = new IllegalStateException(
                "request disappeared before cleanup: " + requestId
            );
            impossible("request cleanup", failure);
            return CompletableFuture.failedFuture(failure);
        }
        publishRequestOwners();
        evaluateExecutionHead();
        maybeCloseAfterSourceEnd();
        tryFinishOwner();
        return CompletableFuture.completedFuture(null);
    }

    private CompletionStage<Void> requestAttemptPermit(ReplayRequestId requestId) {
        var request = requireRegistered(requestId);
        if (activeTurn != request || pendingPermit != null) {
            var failure = new IllegalStateException(
                "retry permit requested without sole active turn: " + requestId
            );
            impossible("retry permit request", failure);
            return CompletableFuture.failedFuture(failure);
        }
        var delivery = new CompletableFuture<Void>();
        acquirePermit(request, delivery);
        return delivery.minimalCompletionStage();
    }

    private void cancelAttemptPermit(
        ReplayRequestId requestId,
        CancellationException cause
    ) {
        if (pendingPermit != null
            && pendingPermit.request().requestId.equals(requestId)) {
            pendingPermit.acquisition().cancel(cause);
        }
    }

    private RequestEntry<S, P, R, F, T> requireRegistered(
        ReplayRequestId requestId
    ) {
        var request = requestRegistry.get(requestId);
        if (request == null) {
            var failure = new IllegalStateException(
                "request is not registered: " + requestId
            );
            impossible("request registry lookup", failure);
            throw failure;
        }
        return request;
    }

    private void publishRequestOwners() {
        publishedRequestOwners = requestRegistry.values()
            .stream()
            .map(entry -> entry.owner)
            .toList();
    }

    private CompletionStage<Void> requiredLifecycleDelivery(
        ReplayRequestId requestId,
        String operation,
        RequiredSubmission submission,
        Runnable afterAcceptance
    ) {
        var completion = new CompletableFuture<Void>();
        var registration = operations.register(
            partitionGenerationId,
            connectionProcessingId,
            requestId,
            OperationType.REQUIRED_DELIVERY,
            null,
            WaitReason.WAITING_FOR_RECEIVER
        );
        final CompletionStage<Void> acceptance;
        try {
            acceptance = Objects.requireNonNull(
                submission.submit(),
                operation + " returned no acceptance stage"
            );
        } catch (Throwable failure) {
            completion.completeExceptionally(failure);
            impossible(operation + " submission", failure);
            return completion.minimalCompletionStage();
        }
        acceptance.whenComplete((ignored, failure) ->
            postRequired(
                operation + " acceptance",
                () -> {
                    if (failure != null) {
                        var cause = unwrap(failure);
                        completion.completeExceptionally(cause);
                        impossible(operation + " acceptance", cause);
                        return;
                    }
                    afterAcceptance.run();
                    operations.complete(registration);
                    tryFinishOwner();
                    completion.complete(null);
                }
            )
        );
        return completion.minimalCompletionStage();
    }

    private void postRequired(String operation, Runnable transition) {
        if (eventLoop.inEventLoop()) {
            runTransition(operation, transition);
            return;
        }
        try {
            eventLoop.execute(() -> runTransition(operation, transition));
        } catch (Throwable failure) {
            reportFatal("required event-loop submission " + operation, failure);
        }
    }

    private void runTransition(String operation, Runnable transition) {
        try {
            requireOwnerThread();
            transition.run();
        } catch (Throwable failure) {
            impossible(operation, failure);
        }
    }

    private void cancelAdmissionTimer() {
        if (admissionTimer != null) {
            admissionTimer.cancel(false);
            admissionTimer = null;
        }
    }

    private void cancelExecutionTimer() {
        if (executionTimer != null) {
            executionTimer.cancel(false);
            executionTimer = null;
        }
    }

    private void requireOwnerThread() {
        if (!eventLoop.inEventLoop()) {
            throw new IllegalStateException(
                "connection owner for "
                    + connectionProcessingId
                    + " accessed outside its event loop"
            );
        }
    }

    private void impossible(String operation, Throwable cause) {
        reportFatal("impossible transition during " + operation, cause);
    }

    private void reportFatal(String operation, Throwable cause) {
        fatalHandler.onFatal(new Error(
            "Connection-owner failure for "
                + connectionProcessingId
                + ": "
                + operation,
            cause
        ));
    }

    private static Duration nonNegativeDelay(Instant now, Instant target) {
        return target.isAfter(now) ? Duration.between(now, target) : Duration.ZERO;
    }

    private static CancellationException rejectionCause(Throwable failure) {
        var cancellation = new CancellationException(
            "connection input was rejected: " + failure.getMessage()
        );
        cancellation.initCause(failure);
        return cancellation;
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

    private final class RequestCallbacks implements RequestReplayOwner.ConnectionCallbacks {
        @Override
        public CompletionStage<Void> preparationFinished(
            ReplayRequestId requestId,
            RequestPreparationResult<?> result
        ) {
            TargetConnectionOwner.this.preparationFinished(requestId, result);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletionStage<Void> requestAttemptPermit(ReplayRequestId requestId) {
            return TargetConnectionOwner.this.requestAttemptPermit(requestId);
        }

        @Override
        public void cancelAttemptPermit(
            ReplayRequestId requestId,
            CancellationException cause
        ) {
            TargetConnectionOwner.this.cancelAttemptPermit(requestId, cause);
        }

        @Override
        public CompletionStage<Void> firstTargetWriteSubmitted(ReplayRequestId requestId) {
            TargetConnectionOwner.this.firstTargetWriteSubmitted(requestId);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletionStage<Void> finalTargetWriteSubmitted(ReplayRequestId requestId) {
            TargetConnectionOwner.this.finalTargetWriteSubmitted(requestId);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletionStage<Void> connectionTurnFinished(
            ReplayRequestId requestId,
            RequestReplayOwner.ConnectionTurnCompletion completion
        ) {
            return TargetConnectionOwner.this.connectionTurnFinished(
                requestId,
                completion
            );
        }

        @Override
        public CompletionStage<Void> requestProcessingFinished(ReplayRequestId requestId) {
            return TargetConnectionOwner.this.requestProcessingFinished(requestId);
        }

        @Override
        public CompletionStage<Void> requestCleanupFinished(
            ReplayRequestId requestId,
            CancellationException cause
        ) {
            return TargetConnectionOwner.this.requestCleanupFinished(requestId, cause);
        }
    }

    @FunctionalInterface
    private interface RequiredSubmission {
        CompletionStage<Void> submit();
    }
}
