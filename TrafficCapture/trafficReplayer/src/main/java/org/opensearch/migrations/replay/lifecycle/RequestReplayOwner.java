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
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

import org.opensearch.migrations.replay.identity.CancellationDeadline;
import org.opensearch.migrations.replay.identity.ConnectionProcessingId;
import org.opensearch.migrations.replay.identity.PartitionGenerationId;
import org.opensearch.migrations.replay.identity.ReplayRequestId;
import org.opensearch.migrations.replay.datatypes.HttpRequestTransformationStatus;
import org.opensearch.migrations.replay.lifecycle.OutstandingOperationRegistry.OperationType;
import org.opensearch.migrations.replay.lifecycle.OutstandingOperationRegistry.WaitReason;
import org.opensearch.migrations.replay.lifecycle.ReplayOutcomes.RequestPreparationCancelled;
import org.opensearch.migrations.replay.lifecycle.ReplayOutcomes.RequestPreparationReady;
import org.opensearch.migrations.replay.lifecycle.ReplayOutcomes.RequestPreparationResult;
import org.opensearch.migrations.replay.lifecycle.ReplayOutcomes.RetryDecision;
import org.opensearch.migrations.replay.lifecycle.ReplayOutcomes.TargetAttemptOutcome;
import org.opensearch.migrations.replay.sink.TupleWriter;
import org.opensearch.migrations.replay.sink.TupleWriter.TupleWriteCancelled;
import org.opensearch.migrations.replay.sink.TupleWriter.TupleWriteResult;
import org.opensearch.migrations.replay.tracing.IReplayContexts;

import io.netty.channel.EventLoop;
import io.netty.util.concurrent.ScheduledFuture;
import lombok.NonNull;

// REBUILD-TRACE-START(G5,target): retain through the rebuild; remove in final pre-merge cleanup.
// HttpJsonTransformingConsumer.<init>(..., IReplayerHttpTransactionContext)
//     -> RequestReplayOwner.beginPreparation [create request transformation context].
// HttpJsonTransformingConsumer.finalizeRequest
//     -> RequestReplayOwner.applyPreparationResult [close request transformation context].
// HttpJsonTransformingConsumer.finalizeDeferredSigning
//     -> RequestReplayOwner.applyPreparationResult [close request transformation context].
// HttpJsonTransformingConsumer.finalizeNormalPath
//     -> RequestReplayOwner.applyPreparationResult [close request transformation context].
// HttpJsonTransformingConsumer.redriveWithoutTransformation
//     -> RequestReplayOwner.applyPreparationResult [close request transformation context].
// REBUILD-TRACE-END(G5,target)

/**
 * Exhaustive event-loop-confined lifecycle owner for one replay request.
 */
public final class RequestReplayOwner<S, P extends AutoCloseable, R, F, T> {
    public sealed interface RetrySourceResponse<F>
        permits CompleteSourceResponseForRetry, SourceResponseUnavailableForRetry {}

    public record CompleteSourceResponseForRetry<F>(
        @NonNull F response
    ) implements RetrySourceResponse<F> {}

    public record SourceResponseUnavailableForRetry<F>()
        implements RetrySourceResponse<F> {}

    public sealed interface FinalSourceResponse<F>
        permits CompleteFinalSourceResponse, IncompleteFinalSourceResponse {}

    public record CompleteFinalSourceResponse<F>(
        @NonNull F response,
        boolean keptAlive
    ) implements FinalSourceResponse<F> {}

    public record IncompleteFinalSourceResponse<F>(
        @NonNull String reason
    ) implements FinalSourceResponse<F> {}

    public enum ConnectionTurnCompletion {
        TARGET_WORK_FINISHED,
        CANCELLATION_ENDED_STARTED_TURN
    }

    public record RequestResult<S, P, R, F>(
        @NonNull ReplayRequestId requestId,
        @NonNull S sourceRequest,
        P preparedRequest,
        @NonNull HttpRequestTransformationStatus transformationStatus,
        @NonNull List<TargetAttemptOutcome<R>> targetAttemptHistory,
        TargetAttemptOutcome.TargetResponseObtained<R> terminalTargetResponse,
        @NonNull FinalSourceResponse<F> finalSourceResponse
    ) {
        public RequestResult {
            targetAttemptHistory = List.copyOf(targetAttemptHistory);
            if (transformationStatus.isCompleted() || transformationStatus.isError()) {
                Objects.requireNonNull(preparedRequest, "replayable request has no prepared request");
                Objects.requireNonNull(terminalTargetResponse, "replayable request has no target response");
            } else if (transformationStatus.isSkipped()) {
                if (preparedRequest != null || terminalTargetResponse != null || !targetAttemptHistory.isEmpty()) {
                    throw new IllegalArgumentException(
                        "skipped request must have no prepared request, target response, or attempts"
                    );
                }
            } else {
                throw new IllegalArgumentException(
                    "tuple input requires a completed, fallback-error, or skipped transformation status"
                );
            }
        }
    }

    public interface PreparationOperation<P> {
        CompletionStage<RequestPreparationResult<P>> completion();

        /**
         * Requests cancellation. The operation must settle {@link #completion()} exactly once with
         * {@link RequestPreparationCancelled} carrying this cause.
         */
        void cancel(CancellationException cause);
    }

    @FunctionalInterface
    public interface RequestPreparer<S, P> {
        PreparationOperation<P> begin(
            ReplayRequestId requestId,
            S sourceRequest,
            IReplayContexts.IRequestTransformationContext replayContext
        );
    }

    public interface RetryPolicy<R, F> {
        boolean requiresSourceResponse(R targetResponse);

        RetryDecision decide(R targetResponse, RetrySourceResponse<F> sourceResponse);

        Duration retryDelay(int completedAttemptCount);
    }

    @FunctionalInterface
    public interface TupleFactory<S, P, R, F, T> {
        T create(
            IReplayContexts.ITupleHandlingContext replayContext,
            RequestResult<S, P, R, F> result
        );
    }

    public interface ResourceReleaser<S, P, R, F> {
        void releaseSourceRequest(S sourceRequest);

        void releasePreparedRequest(P preparedRequest) throws Exception;

        void releaseTargetResponse(R targetResponse);

        void releaseSourceResponse(F sourceResponse);
    }

    public interface ConnectionCallbacks {
        CompletionStage<Void> preparationFinished(
            ReplayRequestId requestId,
            RequestPreparationResult<?> result
        );

        CompletionStage<Void> requestAttemptPermit(ReplayRequestId requestId);

        void cancelAttemptPermit(ReplayRequestId requestId, CancellationException cause);

        CompletionStage<Void> firstTargetWriteSubmitted(ReplayRequestId requestId);

        CompletionStage<Void> finalTargetWriteSubmitted(ReplayRequestId requestId);

        CompletionStage<Void> connectionTurnFinished(
            ReplayRequestId requestId,
            ConnectionTurnCompletion completion
        );

        CompletionStage<Void> requestProcessingFinished(ReplayRequestId requestId);

        CompletionStage<Void> requestCleanupFinished(
            ReplayRequestId requestId,
            CancellationException cause
        );
    }

    @FunctionalInterface
    public interface FatalHandler {
        void onFatal(Error failure);
    }

    private sealed interface PreparationState<P>
        permits PreparationState.Admitted,
            PreparationState.Preparing,
            PreparationState.Ready,
            PreparationState.Cancelled {

        record Admitted<P>() implements PreparationState<P> {}

        record Preparing<P>(
            PreparationOperation<P> operation,
            OutstandingOperationRegistry.Registration registration,
            IReplayContexts.IRequestTransformationContext replayContext
        ) implements PreparationState<P> {}

        record Ready<P>(
            RequestPreparationReady<P> preparation
        ) implements PreparationState<P> {}

        record Cancelled<P>(CancellationException cause) implements PreparationState<P> {}
    }

    private sealed interface TargetServerState<R>
        permits TargetServerState.NotStarted,
            TargetServerState.WaitingForPermit,
            TargetServerState.StartingAttempt,
            TargetServerState.AttemptInProgress,
            TargetServerState.AbortingAttempt,
            TargetServerState.WaitingForRetrySourceResponse,
            TargetServerState.WaitingForRetryTime,
            TargetServerState.Finished,
            TargetServerState.Filtered,
            TargetServerState.Cancelled {

        record NotStarted<R>() implements TargetServerState<R> {}

        record WaitingForPermit<R>() implements TargetServerState<R> {}

        record StartingAttempt<R>(
            int attemptNumber,
            TargetAttemptPermitProvider.Permit permit,
            OutstandingOperationRegistry.Registration registration
        ) implements TargetServerState<R> {}

        record AttemptInProgress<R>(
            int attemptNumber,
            TargetAttemptPermitProvider.Permit permit,
            TargetChannelPort.Attempt<R> attempt,
            OutstandingOperationRegistry.Registration registration
        ) implements TargetServerState<R> {}

        record AbortingAttempt<R>(
            int attemptNumber,
            TargetAttemptPermitProvider.Permit permit,
            TargetChannelPort.Attempt<R> attempt,
            OutstandingOperationRegistry.Registration attemptRegistration,
            OutstandingOperationRegistry.Registration abortRegistration
        ) implements TargetServerState<R> {}

        record WaitingForRetrySourceResponse<R>(
            TargetAttemptOutcome.TargetResponseObtained<R> response,
            OutstandingOperationRegistry.Registration registration
        ) implements TargetServerState<R> {}

        record WaitingForRetryTime<R>(
            ScheduledFuture<?> timer,
            OutstandingOperationRegistry.Registration registration
        ) implements TargetServerState<R> {}

        record Finished<R>(
            TargetAttemptOutcome.TargetResponseObtained<R> response
        ) implements TargetServerState<R> {}

        record Filtered<R>(
            HttpRequestTransformationStatus status
        ) implements TargetServerState<R> {}

        record Cancelled<R>(CancellationException cause) implements TargetServerState<R> {}
    }

    private sealed interface RetrySourceState<F>
        permits RetrySourceState.Unresolved,
            RetrySourceState.Complete,
            RetrySourceState.Unavailable {

        record Unresolved<F>() implements RetrySourceState<F> {}

        record Complete<F>(F response) implements RetrySourceState<F> {}

        record Unavailable<F>() implements RetrySourceState<F> {}
    }

    private sealed interface FinalSourceState<F>
        permits FinalSourceState.Unresolved,
            FinalSourceState.Complete,
            FinalSourceState.Incomplete {

        record Unresolved<F>() implements FinalSourceState<F> {}

        record Complete<F>(F response, boolean keptAlive) implements FinalSourceState<F> {}

        record Incomplete<F>(String reason) implements FinalSourceState<F> {}
    }

    private sealed interface TupleState
        permits TupleState.NotReady,
            TupleState.Writing,
            TupleState.Durable,
            TupleState.Cancelled {

        record NotReady() implements TupleState {}

        record Writing(
            TupleWriter.LogicalWrite write,
            OutstandingOperationRegistry.Registration registration
        ) implements TupleState {}

        record Durable() implements TupleState {}

        record Cancelled(CancellationException cause) implements TupleState {}
    }

    private sealed interface CancellationState
        permits CancellationState.Active,
            CancellationState.Graceful,
            CancellationState.Forced {

        record Active() implements CancellationState {}

        record Graceful(CancellationDeadline deadline) implements CancellationState {}

        record Forced(CancellationException cause) implements CancellationState {}
    }

    private enum MilestoneState {
        NOT_SUBMITTED,
        SUBMITTED,
        ACCEPTED
    }

    private final Instant nominalTargetTime;
    private final S sourceRequest;
    private final IReplayContexts.IRequestContext replayContext;
    private final EventLoop eventLoop;
    private final Clock clock;
    private final LongSupplier nanoTime;
    private final RequestPreparer<S, P> preparer;
    private final RetryPolicy<R, F> retryPolicy;
    private final TargetChannelPort<P, R> targetChannel;
    private final TupleFactory<S, P, R, F, T> tupleFactory;
    private final TupleWriter<T> tupleWriter;
    private final ResourceReleaser<S, P, R, F> resourceReleaser;
    private final ConnectionCallbacks callbacks;
    private final FatalHandler fatalHandler;
    private final OutstandingOperationRegistry operations;
    private final List<TargetAttemptOutcome<R>> attemptHistory = new ArrayList<>();

    private PreparationState<P> preparationState = new PreparationState.Admitted<>();
    private TargetServerState<R> targetServerState = new TargetServerState.NotStarted<>();
    private RetrySourceState<F> retrySourceState = new RetrySourceState.Unresolved<>();
    private FinalSourceState<F> finalSourceState = new FinalSourceState.Unresolved<>();
    private TupleState tupleState = new TupleState.NotReady();
    private CancellationState cancellationState = new CancellationState.Active();
    private MilestoneState turnMilestone = MilestoneState.NOT_SUBMITTED;
    private MilestoneState processingMilestone = MilestoneState.NOT_SUBMITTED;
    private MilestoneState cleanupMilestone = MilestoneState.NOT_SUBMITTED;
    private ScheduledFuture<?> gracefulDeadlineTimer;
    private OutstandingOperationRegistry.Registration retryPermitRegistration;
    private OutstandingOperationRegistry.Registration finalSourceWaitRegistration;
    private IReplayContexts.IScheduledContext retryTimerContext;
    private int nextAttemptNumber = 1;
    private int firstWriteAttempt;
    private int finalWriteAttempt;
    private boolean resourcesReleased;
    private boolean replayContextClosed;
    private IReplayContexts.ITargetRequestContext targetAttemptContext;
    private IReplayContexts.ITupleHandlingContext tupleContext;

    RequestReplayOwner(
        @NonNull Instant nominalTargetTime,
        @NonNull S sourceRequest,
        @NonNull IReplayContexts.IRequestContext replayContext,
        @NonNull EventLoop eventLoop,
        @NonNull Clock clock,
        @NonNull LongSupplier nanoTime,
        @NonNull RequestPreparer<S, P> preparer,
        @NonNull RetryPolicy<R, F> retryPolicy,
        @NonNull TargetChannelPort<P, R> targetChannel,
        @NonNull TupleFactory<S, P, R, F, T> tupleFactory,
        @NonNull TupleWriter<T> tupleWriter,
        @NonNull ResourceReleaser<S, P, R, F> resourceReleaser,
        @NonNull ConnectionCallbacks callbacks,
        @NonNull FatalHandler fatalHandler,
        @NonNull OutstandingOperationRegistry.CountHook countHook
    ) {
        this.nominalTargetTime = nominalTargetTime;
        this.sourceRequest = sourceRequest;
        this.replayContext = replayContext;
        this.eventLoop = eventLoop;
        this.clock = clock;
        this.nanoTime = nanoTime;
        this.preparer = preparer;
        this.retryPolicy = retryPolicy;
        this.targetChannel = targetChannel;
        this.tupleFactory = tupleFactory;
        this.tupleWriter = tupleWriter;
        this.resourceReleaser = resourceReleaser;
        this.callbacks = callbacks;
        this.fatalHandler = fatalHandler;
        this.operations = new OutstandingOperationRegistry(
            "request " + requestId(),
            eventLoop,
            clock,
            fatalHandler::onFatal,
            countHook
        );
    }

    ReplayRequestId requestId() {
        return replayContext.getRequestId();
    }

    private ConnectionProcessingId connectionProcessingId() {
        return replayContext.getConnectionProcessingId();
    }

    private PartitionGenerationId partitionGenerationId() {
        return connectionProcessingId().generation();
    }

    OutstandingOperationRegistry operations() {
        return operations;
    }

    boolean firstTargetWriteWasSubmitted() {
        requireOwnerThread();
        return firstWriteAttempt != 0;
    }

    boolean finalTargetWriteWasSubmitted() {
        requireOwnerThread();
        return finalWriteAttempt != 0;
    }

    void beginPreparation() {
        requireOwnerThread();
        if (!(preparationState instanceof PreparationState.Admitted<P>)) {
            impossible(
                "begin preparation",
                new IllegalStateException(
                    "preparation is " + preparationState.getClass().getSimpleName()
                )
            );
            return;
        }
        var registration = operations.register(
            partitionGenerationId(),
            connectionProcessingId(),
            requestId(),
            OperationType.REQUEST_PREPARATION,
            nominalTargetTime,
            WaitReason.PREPARING
        );
        final PreparationOperation<P> operation;
        var transformationContext = replayContext.createTransformationContext();
        try {
            operation = Objects.requireNonNull(
                preparer.begin(requestId(), sourceRequest, transformationContext),
                "request preparer returned no operation"
            );
            preparationState = new PreparationState.Preparing<>(
                operation,
                registration,
                transformationContext
            );
        } catch (Throwable failure) {
            transformationContext.close();
            impossible("request preparation submission", failure);
            return;
        }
        final CompletionStage<RequestPreparationResult<P>> completion;
        try {
            completion = Objects.requireNonNull(
                operation.completion(),
                "request preparation returned no completion stage"
            );
        } catch (Throwable failure) {
            transformationContext.close();
            impossible("request preparation completion", failure);
            return;
        }
        completion.whenComplete((result, failure) ->
            postRequired(
                "request preparation result",
                () -> applyPreparationResult(registration, result, unwrap(failure)),
                rejection -> {
                    transformationContext.close();
                    releaseLatePreparation(result);
                }
            )
        );
    }

    void acceptAttemptPermit(TargetAttemptPermitProvider.Permit permit) {
        requireOwnerThread();
        if (!permit.requestId().equals(requestId())) {
            permit.close();
            impossible(
                "attempt permit delivery",
                new IllegalArgumentException("permit belongs to " + permit.requestId())
            );
            return;
        }
        if (cancellationState instanceof CancellationState.Forced forced) {
            permit.close();
            targetServerState = new TargetServerState.Cancelled<>(forced.cause());
            tryEmitCleanup();
            return;
        }
        if (!(targetServerState instanceof TargetServerState.NotStarted<R>)
            && !(targetServerState instanceof TargetServerState.WaitingForPermit<R>)) {
            permit.close();
            impossible(
                "attempt permit delivery",
                new IllegalStateException(
                    "target server state is "
                        + targetServerState.getClass().getSimpleName()
                )
            );
            return;
        }
        startAttempt(permit);
    }

    void beginFilteredTurn() {
        requireOwnerThread();
        if (!(preparationState instanceof PreparationState.Ready<P> ready)
            || !ready.preparation().transformationStatus().isSkipped()
            || !(targetServerState instanceof TargetServerState.NotStarted<R>)) {
            impossible(
                "filtered target turn",
                new IllegalStateException("request is not ready for a filtered target turn")
            );
            return;
        }
        targetServerState = new TargetServerState.Filtered<>(
            ready.preparation().transformationStatus()
        );
        emitConnectionTurnFinished(ConnectionTurnCompletion.TARGET_WORK_FINISHED);
        tryStartTuple();
    }

    void sourceResponseComplete(F response, boolean keptAlive) {
        requireOwnerThread();
        if (finalSourceState instanceof FinalSourceState.Unresolved<F>) {
            finalSourceState = new FinalSourceState.Complete<>(response, keptAlive);
        } else {
            impossible(
                "complete final source response",
                new IllegalStateException("final source response was already supplied")
            );
            return;
        }
        if (retrySourceState instanceof RetrySourceState.Unresolved<F>) {
            retrySourceState = new RetrySourceState.Complete<>(response);
        }
        sourceResponseChanged();
    }

    void sourceResponseUnavailableForRetry() {
        requireOwnerThread();
        if (!(retrySourceState instanceof RetrySourceState.Unresolved<F>)) {
            impossible(
                "source response unavailable for retry",
                new IllegalStateException("retry source response was already supplied")
            );
            return;
        }
        retrySourceState = new RetrySourceState.Unavailable<>();
        sourceResponseChanged();
    }

    void sourceResponseIncomplete(String reason) {
        requireOwnerThread();
        if (finalSourceState instanceof FinalSourceState.Unresolved<F>) {
            finalSourceState = new FinalSourceState.Incomplete<>(reason);
        } else {
            impossible(
                "incomplete final source response",
                new IllegalStateException("final source response was already supplied")
            );
            return;
        }
        if (retrySourceState instanceof RetrySourceState.Unresolved<F>) {
            retrySourceState = new RetrySourceState.Unavailable<>();
        }
        sourceResponseChanged();
    }

    void gracefulCancel(CancellationDeadline deadline, CancellationException cause) {
        requireOwnerThread();
        switch (cancellationState) {
            case CancellationState.Active ignored ->
                cancellationState = new CancellationState.Graceful(deadline);
            case CancellationState.Graceful graceful -> {
                if (graceful.deadline().remainingNanos(nanoTime.getAsLong())
                    <= deadline.remainingNanos(nanoTime.getAsLong())) {
                    return;
                }
                cancellationState = new CancellationState.Graceful(deadline);
            }
            case CancellationState.Forced ignored -> {
                return;
            }
        }
        if (!finalTargetWriteWasSubmitted() && !wasIntentionallyFiltered()) {
            forceCancel(cause);
            return;
        }
        scheduleGracefulDeadline(deadline, cause);
    }

    private boolean wasIntentionallyFiltered() {
        return targetServerState instanceof TargetServerState.Filtered<R>
            || (preparationState instanceof PreparationState.Ready<P> ready
                && ready.preparation().transformationStatus().isSkipped());
    }

    void forceCancel(CancellationException cause) {
        requireOwnerThread();
        if (cancellationState instanceof CancellationState.Forced) {
            return;
        }
        if (tupleState instanceof TupleState.Durable) {
            cancelGracefulDeadline();
            tryEmitProcessingFinished();
            return;
        }
        cancellationState = new CancellationState.Forced(cause);
        cancelGracefulDeadline();
        cancelPreparation(cause);
        cancelTargetWork(cause);
        cancelTuple(cause);
        cancelFinalSourceWait();
        tryEmitCleanup();
    }

    private void applyPreparationResult(
        OutstandingOperationRegistry.Registration registration,
        RequestPreparationResult<P> result,
        Throwable failure
    ) {
        requireOwnerThread();
        var matchingPreparation =
            preparationState instanceof PreparationState.Preparing<P> preparing
                && preparing.registration() == registration
                    ? preparing
                    : null;
        if (failure != null) {
            if (matchingPreparation != null) {
                matchingPreparation.replayContext().close();
            }
            impossible("request preparation exceptional completion", failure);
            return;
        }
        if (result == null) {
            if (matchingPreparation != null) {
                matchingPreparation.replayContext().close();
            }
            impossible(
                "request preparation completion",
                new NullPointerException("request preparation completed without a result")
            );
            return;
        }
        if (matchingPreparation == null) {
            releaseLatePreparation(result);
            if (!(preparationState instanceof PreparationState.Cancelled<P>)) {
                impossible(
                    "request preparation completion",
                    new IllegalStateException(
                        "preparation is " + preparationState.getClass().getSimpleName()
                    )
                );
            }
            return;
        }
        matchingPreparation.replayContext().close();
        switch (result) {
            case RequestPreparationReady<P> ready -> {
                if (cancellationState instanceof CancellationState.Forced forced) {
                    preparationState = new PreparationState.Ready<>(ready);
                    operations.complete(registration);
                    closePreparedOnly();
                    preparationState = new PreparationState.Cancelled<>(forced.cause());
                    tryEmitCleanup();
                    return;
                }
                preparationState = new PreparationState.Ready<>(ready);
                deliverRequired(
                    registration,
                    "preparation readiness",
                    () -> callbacks.preparationFinished(requestId(), result),
                    this::tryStartTuple
                );
            }
            case RequestPreparationCancelled<P> cancelled -> {
                preparationState = new PreparationState.Cancelled<>(cancelled.cause());
                operations.complete(registration);
                if (!(cancellationState instanceof CancellationState.Forced)) {
                    impossible(
                        "unexpected preparation cancellation",
                        cancelled.cause()
                    );
                    return;
                }
                tryEmitCleanup();
            }
        }
    }

    private void startAttempt(TargetAttemptPermitProvider.Permit permit) {
        if (!(preparationState instanceof PreparationState.Ready<P> ready)) {
            permit.close();
            impossible(
                "target attempt start",
                new IllegalStateException(
                    "preparation is " + preparationState.getClass().getSimpleName()
                )
            );
            return;
        }
        var transformationStatus = ready.preparation().transformationStatus();
        if (!(transformationStatus.isCompleted() || transformationStatus.isError())) {
            permit.close();
            impossible(
                "target attempt start",
                new IllegalStateException("filtered request received a target-attempt permit")
            );
            return;
        }
        var attemptNumber = nextAttemptNumber++;
        var registration = operations.register(
            partitionGenerationId(),
            connectionProcessingId(),
            requestId(),
            OperationType.TARGET_ATTEMPT,
            nominalTargetTime,
            WaitReason.WAITING_FOR_TARGET
        );
        targetServerState = new TargetServerState.StartingAttempt<>(
            attemptNumber,
            permit,
            registration
        );
        var currentTargetAttemptContext = replayContext.createTargetRequestContext();
        targetAttemptContext = currentTargetAttemptContext;
        final TargetChannelPort.Attempt<R> attempt;
        try {
            attempt = Objects.requireNonNull(
                targetChannel.startAttempt(
                    new TargetChannelPort.AttemptInput<>(
                        attemptNumber,
                        ready.preparation().value(),
                        currentTargetAttemptContext,
                        new TargetChannelPort.WriteMilestoneListener() {
                            @Override
                            public void firstTargetWriteSubmitted(int reportedAttempt) {
                                observeFirstWrite(reportedAttempt);
                            }

                            @Override
                            public void finalTargetWriteSubmitted(int reportedAttempt) {
                                observeFinalWrite(reportedAttempt);
                            }
                        }
                    )
                ),
                "target channel returned no attempt"
            );
        } catch (Throwable failure) {
            closeTargetAttemptContext();
            permit.close();
            impossible("target attempt submission", failure);
            return;
        }
        targetServerState = new TargetServerState.AttemptInProgress<>(
            attemptNumber,
            permit,
            attempt,
            registration
        );
        final CompletionStage<TargetAttemptOutcome<R>> outcome;
        try {
            outcome = Objects.requireNonNull(
                attempt.outcome(),
                "target attempt returned no outcome stage"
            );
        } catch (Throwable failure) {
            closeTargetAttemptContext();
            permit.close();
            impossible("target attempt outcome registration", failure);
            return;
        }
        outcome.whenComplete((result, failure) ->
            postRequired(
                "target attempt outcome",
                () -> applyTargetAttemptOutcome(
                    attemptNumber,
                    registration,
                    result,
                    unwrap(failure)
                ),
                rejection -> {
                    currentTargetAttemptContext.close();
                    permit.close();
                }
            )
        );
    }

    private void observeFirstWrite(int attemptNumber) {
        postRequired(
            "first target write",
            () -> {
                validateAttemptMilestone(attemptNumber, "first target write");
                if (firstWriteAttempt == 0) {
                    firstWriteAttempt = attemptNumber;
                    var registration = operations.register(
                        partitionGenerationId(),
                        connectionProcessingId(),
                        requestId(),
                        OperationType.TARGET_WRITE_MILESTONE,
                        nominalTargetTime,
                        WaitReason.WAITING_FOR_RECEIVER
                    );
                    deliverRequired(
                        registration,
                        "first target write",
                        () -> callbacks.firstTargetWriteSubmitted(requestId()),
                        () -> {}
                    );
                } else if (firstWriteAttempt == attemptNumber) {
                    impossible(
                        "first target write",
                        new IllegalStateException(
                            "first target write was submitted more than once"
                        )
                    );
                }
            },
            ignored -> {}
        );
    }

    private void observeFinalWrite(int attemptNumber) {
        postRequired(
            "final target write",
            () -> {
                validateAttemptMilestone(attemptNumber, "final target write");
                if (firstWriteAttempt == 0) {
                    impossible(
                        "final target write",
                        new IllegalStateException(
                            "final target write arrived before first target write"
                        )
                    );
                    return;
                }
                if (finalWriteAttempt == 0) {
                    finalWriteAttempt = attemptNumber;
                    var registration = operations.register(
                        partitionGenerationId(),
                        connectionProcessingId(),
                        requestId(),
                        OperationType.TARGET_WRITE_MILESTONE,
                        nominalTargetTime,
                        WaitReason.WAITING_FOR_RECEIVER
                    );
                    deliverRequired(
                        registration,
                        "final target write",
                        () -> callbacks.finalTargetWriteSubmitted(requestId()),
                        () -> {}
                    );
                } else if (finalWriteAttempt == attemptNumber) {
                    impossible(
                        "final target write",
                        new IllegalStateException(
                            "final target write was submitted more than once"
                        )
                    );
                }
            },
            ignored -> {}
        );
    }

    private void validateAttemptMilestone(int attemptNumber, String operation) {
        var activeAttempt = switch (targetServerState) {
            case TargetServerState.StartingAttempt<R> starting -> starting.attemptNumber();
            case TargetServerState.AttemptInProgress<R> active -> active.attemptNumber();
            case TargetServerState.AbortingAttempt<R> aborting -> aborting.attemptNumber();
            default -> -1;
        };
        if (activeAttempt != attemptNumber) {
            impossible(
                operation,
                new IllegalStateException(
                    "write milestone for attempt "
                        + attemptNumber
                        + " while active attempt is "
                        + activeAttempt
                )
            );
        }
    }

    private void applyTargetAttemptOutcome(
        int attemptNumber,
        OutstandingOperationRegistry.Registration registration,
        TargetAttemptOutcome<R> outcome,
        Throwable failure
    ) {
        requireOwnerThread();
        if (targetServerState instanceof TargetServerState.AbortingAttempt<R> aborting
            && aborting.attemptNumber() == attemptNumber) {
            return;
        }
        if (!(targetServerState instanceof TargetServerState.AttemptInProgress<R> active)
            || active.attemptNumber() != attemptNumber
            || active.registration() != registration) {
            if (targetServerState instanceof TargetServerState.Cancelled<R>) {
                return;
            }
            impossible(
                "target attempt outcome",
                new IllegalStateException("target attempt outcome has no active attempt")
            );
            return;
        }
        if (failure != null) {
            closeTargetAttemptContext();
            active.permit().close();
            impossible("target attempt exceptional completion", failure);
            return;
        }
        if (outcome == null) {
            closeTargetAttemptContext();
            active.permit().close();
            impossible(
                "target attempt completion",
                new NullPointerException("target attempt completed without an outcome")
            );
            return;
        }
        if (outcome instanceof TargetAttemptOutcome.TargetResponseObtained<R>
            && finalWriteAttempt == 0) {
            closeTargetAttemptContext();
            active.permit().close();
            impossible(
                "target response before final write",
                new IllegalStateException(
                    "target response arrived before FinalTargetWriteSubmitted"
                )
            );
            return;
        }
        attemptHistory.add(outcome);
        closeTargetAttemptContext();
        active.permit().close();
        operations.complete(registration);
        switch (outcome) {
            case TargetAttemptOutcome.NoTargetResponseObtained<R> ignored ->
                scheduleRetry();
            case TargetAttemptOutcome.TargetResponseObtained<R> obtained ->
                evaluateTargetResponse(obtained);
        }
    }

    private void evaluateTargetResponse(
        TargetAttemptOutcome.TargetResponseObtained<R> response
    ) {
        final boolean needsSource;
        try {
            needsSource = retryPolicy.requiresSourceResponse(response.response());
        } catch (Throwable failure) {
            impossible("retry source-response requirement", failure);
            return;
        }
        if (needsSource && retrySourceState instanceof RetrySourceState.Unresolved<F>) {
            var registration = operations.register(
                partitionGenerationId(),
                connectionProcessingId(),
                requestId(),
                OperationType.RETRY_SOURCE_RESPONSE_WAIT,
                nominalTargetTime,
                WaitReason.WAITING_FOR_RETRY_SOURCE_RESPONSE
            );
            targetServerState = new TargetServerState.WaitingForRetrySourceResponse<>(
                response,
                registration
            );
            return;
        }
        applyRetryDecision(response);
    }

    private void applyRetryDecision(
        TargetAttemptOutcome.TargetResponseObtained<R> response
    ) {
        var source = switch (retrySourceState) {
            case RetrySourceState.Complete<F> complete ->
                new CompleteSourceResponseForRetry<>(complete.response());
            case RetrySourceState.Unavailable<F> ignored ->
                new SourceResponseUnavailableForRetry<F>();
            case RetrySourceState.Unresolved<F> ignored ->
                new SourceResponseUnavailableForRetry<F>();
        };
        final RetryDecision decision;
        try {
            decision = Objects.requireNonNull(
                retryPolicy.decide(response.response(), source),
                "retry policy returned no decision"
            );
        } catch (Throwable failure) {
            impossible("retry decision", failure);
            return;
        }
        switch (decision) {
            case RetryDecision.RetryRequired ignored -> scheduleRetry();
            case RetryDecision.TargetServerAttemptsFinished ignored -> {
                targetServerState = new TargetServerState.Finished<>(response);
                emitConnectionTurnFinished(
                    ConnectionTurnCompletion.TARGET_WORK_FINISHED
                );
                tryStartTuple();
            }
        }
    }

    private void scheduleRetry() {
        if (cancellationState instanceof CancellationState.Forced forced) {
            targetServerState = new TargetServerState.Cancelled<>(forced.cause());
            tryEmitCleanup();
            return;
        }
        final Duration delay;
        try {
            delay = Objects.requireNonNull(
                retryPolicy.retryDelay(attemptHistory.size()),
                "retry policy returned no delay"
            );
            if (delay.isNegative()) {
                throw new IllegalArgumentException("retry delay must not be negative");
            }
        } catch (Throwable failure) {
            impossible("retry delay", failure);
            return;
        }
        var registration = operations.register(
            partitionGenerationId(),
            connectionProcessingId(),
            requestId(),
            OperationType.RETRY_TIMER,
            nominalTargetTime,
            WaitReason.WAITING_FOR_RETRY_TIME
        );
        try {
            retryTimerContext = replayContext.createScheduledContext(
                clock.instant().plus(delay)
            );
            var timer = eventLoop.schedule(
                () -> retryTimerFired(registration),
                delay.toNanos(),
                TimeUnit.NANOSECONDS
            );
            targetServerState = new TargetServerState.WaitingForRetryTime<>(
                timer,
                registration
            );
        } catch (Throwable failure) {
            closeRetryTimerContext();
            impossible("retry timer submission", failure);
        }
    }

    private void retryTimerFired(OutstandingOperationRegistry.Registration registration) {
        requireOwnerThread();
        if (!(targetServerState instanceof TargetServerState.WaitingForRetryTime<R> waiting)
            || waiting.registration() != registration) {
            impossible(
                "retry timer",
                new IllegalStateException("retry timer fired from a non-waiting state")
            );
            return;
        }
        closeRetryTimerContext();
        if (cancellationState instanceof CancellationState.Forced forced) {
            targetServerState = new TargetServerState.Cancelled<>(forced.cause());
            operations.complete(registration);
            tryEmitCleanup();
            return;
        }
        targetServerState = new TargetServerState.WaitingForPermit<>();
        operations.complete(registration);
        retryPermitRegistration = operations.register(
            partitionGenerationId(),
            connectionProcessingId(),
            requestId(),
            OperationType.PERMIT_ACQUISITION,
            nominalTargetTime,
            WaitReason.WAITING_FOR_PERMIT
        );
        final CompletionStage<Void> delivery;
        try {
            delivery = Objects.requireNonNull(
                callbacks.requestAttemptPermit(requestId()),
                "connection owner returned no retry-permit delivery"
            );
        } catch (Throwable failure) {
            impossible("retry permit request", failure);
            return;
        }
        delivery.whenComplete((ignored, failure) ->
            postRequired(
                "retry permit delivery",
                () -> {
                    if (failure != null) {
                        impossible("retry permit delivery", unwrap(failure));
                        return;
                    }
                    if (retryPermitRegistration != null) {
                        operations.complete(retryPermitRegistration);
                        retryPermitRegistration = null;
                    }
                },
                ignoredFailure -> {}
            )
        );
    }

    private void sourceResponseChanged() {
        if (targetServerState
            instanceof TargetServerState.WaitingForRetrySourceResponse<R> waiting) {
            operations.complete(waiting.registration());
            applyRetryDecision(waiting.response());
        }
        if (finalSourceWaitRegistration != null
            && !(finalSourceState instanceof FinalSourceState.Unresolved<F>)) {
            operations.complete(finalSourceWaitRegistration);
            finalSourceWaitRegistration = null;
        }
        tryStartTuple();
    }

    private void emitConnectionTurnFinished(ConnectionTurnCompletion completion) {
        if (turnMilestone != MilestoneState.NOT_SUBMITTED) {
            impossible(
                "connection-turn completion",
                new IllegalStateException("connection-turn completion was emitted twice")
            );
            return;
        }
        turnMilestone = MilestoneState.SUBMITTED;
        var registration = operations.register(
            partitionGenerationId(),
            connectionProcessingId(),
            requestId(),
            OperationType.REQUIRED_DELIVERY,
            nominalTargetTime,
            WaitReason.WAITING_FOR_RECEIVER
        );
        deliverRequired(
            registration,
            "connection-turn completion",
            () -> callbacks.connectionTurnFinished(requestId(), completion),
            () -> {
                turnMilestone = MilestoneState.ACCEPTED;
                tryEmitProcessingFinished();
                tryEmitCleanup();
            }
        );
    }

    private void tryStartTuple() {
        if (!(tupleState instanceof TupleState.NotReady)
            || !(preparationState instanceof PreparationState.Ready<P> ready)) {
            return;
        }
        final TargetAttemptOutcome.TargetResponseObtained<R> terminalTargetResponse;
        switch (targetServerState) {
            case TargetServerState.Finished<R> finished ->
                terminalTargetResponse = finished.response();
            case TargetServerState.Filtered<R> ignored ->
                terminalTargetResponse = null;
            default -> {
                return;
            }
        }
        final FinalSourceResponse<F> finalResponse;
        switch (finalSourceState) {
            case FinalSourceState.Unresolved<F> ignored -> {
                if (finalSourceWaitRegistration == null) {
                    finalSourceWaitRegistration = operations.register(
                        partitionGenerationId(),
                        connectionProcessingId(),
                        requestId(),
                        OperationType.FINAL_SOURCE_RESPONSE_WAIT,
                        nominalTargetTime,
                        WaitReason.WAITING_FOR_FINAL_SOURCE_RESPONSE
                    );
                }
                return;
            }
            case FinalSourceState.Complete<F> complete ->
                finalResponse = new CompleteFinalSourceResponse<>(
                    complete.response(),
                    complete.keptAlive()
                );
            case FinalSourceState.Incomplete<F> incomplete ->
                finalResponse = new IncompleteFinalSourceResponse<>(incomplete.reason());
        }
        final T tuple;
        var currentTupleContext = replayContext.createTupleContext();
        tupleContext = currentTupleContext;
        try {
            tuple = Objects.requireNonNull(
                tupleFactory.create(
                    currentTupleContext,
                    new RequestResult<>(
                        requestId(),
                        sourceRequest,
                        ready.preparation().value(),
                        ready.preparation().transformationStatus(),
                        attemptHistory,
                        terminalTargetResponse,
                        finalResponse
                    )
                ),
                "tuple factory returned no tuple"
            );
        } catch (Throwable failure) {
            closeTupleContext();
            impossible("tuple construction", failure);
            return;
        }
        var registration = operations.register(
            partitionGenerationId(),
            connectionProcessingId(),
            requestId(),
            OperationType.TUPLE_DURABILITY,
            nominalTargetTime,
            WaitReason.WAITING_FOR_TUPLE_DURABILITY
        );
        final TupleWriter.LogicalWrite write;
        try {
            write = Objects.requireNonNull(
                tupleWriter.write(
                    new TupleWriter.WriteTuple<>(tupleContext, tuple)
                ),
                "tuple writer returned no logical write"
            );
        } catch (Throwable failure) {
            closeTupleContext();
            impossible("logical tuple write", failure);
            return;
        }
        tupleState = new TupleState.Writing(write, registration);
        final CompletionStage<TupleWriteResult> completion;
        try {
            completion = Objects.requireNonNull(
                write.completion(),
                "tuple writer returned no completion stage"
            );
        } catch (Throwable failure) {
            closeTupleContext();
            impossible("logical tuple write completion", failure);
            return;
        }
        completion.whenComplete((result, failure) ->
            postRequired(
                "tuple write result",
                () -> applyTupleResult(registration, result, unwrap(failure)),
                ignored -> currentTupleContext.close()
            )
        );
    }

    private void applyTupleResult(
        OutstandingOperationRegistry.Registration registration,
        TupleWriteResult result,
        Throwable failure
    ) {
        requireOwnerThread();
        if (!(tupleState instanceof TupleState.Writing writing)
            || writing.registration() != registration) {
            impossible(
                "tuple write completion",
                new IllegalStateException("tuple completion arrived before tuple writing")
            );
            return;
        }
        if (failure != null) {
            closeTupleContext();
            impossible("tuple write exceptional completion", failure);
            return;
        }
        if (result == null) {
            closeTupleContext();
            impossible(
                "tuple write completion",
                new NullPointerException("tuple writer completed without a typed result")
            );
            return;
        }
        switch (result) {
            case TupleWriter.TupleDurable ignored -> {
                closeTupleContext();
                tupleState = new TupleState.Durable();
                releaseRequestResources();
                operations.complete(registration);
                tryEmitProcessingFinished();
                tryEmitCleanup();
            }
            case TupleWriteCancelled cancelled -> {
                closeTupleContext();
                tupleState = new TupleState.Cancelled(cancelled.cause());
                if (!(cancellationState instanceof CancellationState.Forced)) {
                    impossible("unexpected tuple cancellation", cancelled.cause());
                    return;
                }
                releaseRequestResources();
                operations.complete(registration);
                tryEmitCleanup();
            }
        }
    }

    private void tryEmitProcessingFinished() {
        if (!(tupleState instanceof TupleState.Durable)
            || turnMilestone != MilestoneState.ACCEPTED
            || processingMilestone != MilestoneState.NOT_SUBMITTED
            || cancellationState instanceof CancellationState.Forced) {
            return;
        }
        processingMilestone = MilestoneState.SUBMITTED;
        var registration = operations.register(
            partitionGenerationId(),
            connectionProcessingId(),
            requestId(),
            OperationType.REQUIRED_DELIVERY,
            nominalTargetTime,
            WaitReason.WAITING_FOR_RECEIVER
        );
        deliverRequired(
            registration,
            "request-processing completion",
            () -> callbacks.requestProcessingFinished(requestId()),
            () -> {
                processingMilestone = MilestoneState.ACCEPTED;
                cancelGracefulDeadline();
                closeReplayContext();
            }
        );
    }

    private void cancelPreparation(CancellationException cause) {
        switch (preparationState) {
            case PreparationState.Admitted<P> ignored ->
                preparationState = new PreparationState.Cancelled<>(cause);
            case PreparationState.Preparing<P> preparing -> {
                try {
                    preparing.operation().cancel(cause);
                } catch (Throwable failure) {
                    impossible("request preparation cancellation", failure);
                }
            }
            case PreparationState.Ready<P> ignored -> {}
            case PreparationState.Cancelled<P> ignored -> {}
        }
    }

    private void cancelTargetWork(CancellationException cause) {
        switch (targetServerState) {
            case TargetServerState.NotStarted<R> ignored ->
                targetServerState = new TargetServerState.Cancelled<>(cause);
            case TargetServerState.WaitingForPermit<R> ignored -> {
                callbacks.cancelAttemptPermit(requestId(), cause);
                targetServerState = new TargetServerState.Cancelled<>(cause);
                if (retryPermitRegistration != null) {
                    operations.complete(retryPermitRegistration);
                    retryPermitRegistration = null;
                }
                emitConnectionTurnFinished(
                    ConnectionTurnCompletion.CANCELLATION_ENDED_STARTED_TURN
                );
            }
            case TargetServerState.StartingAttempt<R> starting -> {
                closeTargetAttemptContext();
                starting.permit().close();
                operations.complete(starting.registration());
                targetServerState = new TargetServerState.Cancelled<>(cause);
                emitConnectionTurnFinished(
                    ConnectionTurnCompletion.CANCELLATION_ENDED_STARTED_TURN
                );
            }
            case TargetServerState.AttemptInProgress<R> active ->
                abortAttempt(active, cause);
            case TargetServerState.AbortingAttempt<R> ignored -> {}
            case TargetServerState.WaitingForRetrySourceResponse<R> waiting -> {
                operations.complete(waiting.registration());
                targetServerState = new TargetServerState.Cancelled<>(cause);
                emitConnectionTurnFinished(
                    ConnectionTurnCompletion.CANCELLATION_ENDED_STARTED_TURN
                );
            }
            case TargetServerState.WaitingForRetryTime<R> waiting -> {
                waiting.timer().cancel(false);
                closeRetryTimerContext();
                operations.complete(waiting.registration());
                targetServerState = new TargetServerState.Cancelled<>(cause);
                emitConnectionTurnFinished(
                    ConnectionTurnCompletion.CANCELLATION_ENDED_STARTED_TURN
                );
            }
            case TargetServerState.Finished<R> ignored -> {}
            case TargetServerState.Filtered<R> ignored -> {}
            case TargetServerState.Cancelled<R> ignored -> {}
        }
    }

    private void abortAttempt(
        TargetServerState.AttemptInProgress<R> active,
        CancellationException cause
    ) {
        var abortRegistration = operations.register(
            partitionGenerationId(),
            connectionProcessingId(),
            requestId(),
            OperationType.CANCELLATION_CLEANUP,
            nominalTargetTime,
            WaitReason.WAITING_FOR_CHANNEL_TEARDOWN
        );
        targetServerState = new TargetServerState.AbortingAttempt<>(
            active.attemptNumber(),
            active.permit(),
            active.attempt(),
            active.registration(),
            abortRegistration
        );
        final CompletionStage<Void> abort;
        try {
            abort = Objects.requireNonNull(
                active.attempt().abort(cause),
                "target attempt abort returned no completion stage"
            );
        } catch (Throwable failure) {
            closeTargetAttemptContext();
            active.permit().close();
            impossible("target attempt abort", failure);
            return;
        }
        var currentTargetAttemptContext = targetAttemptContext;
        abort.whenComplete((ignored, failure) ->
            postRequired(
                "target attempt abort completion",
                () -> finishAbortedAttempt(active.attemptNumber(), cause, unwrap(failure)),
                rejection -> {
                    if (currentTargetAttemptContext != null) {
                        currentTargetAttemptContext.close();
                    }
                    active.permit().close();
                }
            )
        );
    }

    private void finishAbortedAttempt(
        int attemptNumber,
        CancellationException cause,
        Throwable failure
    ) {
        if (!(targetServerState instanceof TargetServerState.AbortingAttempt<R> aborting)
            || aborting.attemptNumber() != attemptNumber) {
            impossible(
                "target attempt abort completion",
                new IllegalStateException("abort completion has no active aborted attempt")
            );
            return;
        }
        if (failure != null) {
            closeTargetAttemptContext();
            aborting.permit().close();
            impossible("target channel teardown", failure);
            return;
        }
        closeTargetAttemptContext();
        aborting.permit().close();
        operations.complete(aborting.attemptRegistration());
        operations.complete(aborting.abortRegistration());
        targetServerState = new TargetServerState.Cancelled<>(cause);
        emitConnectionTurnFinished(
            ConnectionTurnCompletion.CANCELLATION_ENDED_STARTED_TURN
        );
        tryEmitCleanup();
    }

    private void cancelTuple(CancellationException cause) {
        switch (tupleState) {
            case TupleState.NotReady ignored ->
                tupleState = new TupleState.Cancelled(cause);
            case TupleState.Writing writing -> writing.write().cancel(cause);
            case TupleState.Durable ignored -> {}
            case TupleState.Cancelled ignored -> {}
        }
    }

    private void cancelFinalSourceWait() {
        if (finalSourceWaitRegistration != null) {
            operations.complete(finalSourceWaitRegistration);
            finalSourceWaitRegistration = null;
        }
    }

    private void closeTargetAttemptContext() {
        if (targetAttemptContext != null) {
            targetAttemptContext.close();
            targetAttemptContext = null;
        }
    }

    private void closeTupleContext() {
        if (tupleContext != null) {
            tupleContext.close();
            tupleContext = null;
        }
    }

    private void closeRetryTimerContext() {
        if (retryTimerContext != null) {
            retryTimerContext.close();
            retryTimerContext = null;
        }
    }

    private void closeReplayContext() {
        if (!replayContextClosed) {
            replayContextClosed = true;
            replayContext.close();
        }
    }

    private void tryEmitCleanup() {
        if (!(cancellationState instanceof CancellationState.Forced forced)
            || cleanupMilestone != MilestoneState.NOT_SUBMITTED
            || preparationState instanceof PreparationState.Preparing<P>
            || targetServerState instanceof TargetServerState.StartingAttempt<R>
            || targetServerState instanceof TargetServerState.AttemptInProgress<R>
            || targetServerState instanceof TargetServerState.AbortingAttempt<R>
            || targetServerState instanceof TargetServerState.WaitingForPermit<R>
            || targetServerState instanceof TargetServerState.WaitingForRetrySourceResponse<R>
            || targetServerState instanceof TargetServerState.WaitingForRetryTime<R>
            || tupleState instanceof TupleState.Writing
            || turnMilestone == MilestoneState.SUBMITTED) {
            return;
        }
        releaseRequestResources();
        cleanupMilestone = MilestoneState.SUBMITTED;
        var registration = operations.register(
            partitionGenerationId(),
            connectionProcessingId(),
            requestId(),
            OperationType.REQUIRED_DELIVERY,
            nominalTargetTime,
            WaitReason.WAITING_FOR_RECEIVER
        );
        deliverRequired(
            registration,
            "request cleanup",
            () -> callbacks.requestCleanupFinished(requestId(), forced.cause()),
            () -> {
                cleanupMilestone = MilestoneState.ACCEPTED;
                closeReplayContext();
            }
        );
    }

    private void scheduleGracefulDeadline(
        CancellationDeadline deadline,
        CancellationException cause
    ) {
        cancelGracefulDeadline();
        try {
            gracefulDeadlineTimer = eventLoop.schedule(
                () -> forceCancel(cause),
                deadline.remainingNanos(nanoTime.getAsLong()),
                TimeUnit.NANOSECONDS
            );
        } catch (Throwable failure) {
            impossible("graceful cancellation deadline", failure);
        }
    }

    private void cancelGracefulDeadline() {
        if (gracefulDeadlineTimer != null) {
            gracefulDeadlineTimer.cancel(false);
            gracefulDeadlineTimer = null;
        }
    }

    private void deliverRequired(
        OutstandingOperationRegistry.Registration registration,
        String operation,
        RequiredSubmission submission,
        Runnable afterAcceptance
    ) {
        final CompletionStage<Void> acceptance;
        try {
            acceptance = Objects.requireNonNull(
                submission.submit(),
                operation + " returned no receiver completion"
            );
        } catch (Throwable failure) {
            impossible(operation + " submission", failure);
            return;
        }
        acceptance.whenComplete((ignored, failure) ->
            postRequired(
                operation + " receiver completion",
                () -> {
                    if (failure != null) {
                        impossible(operation + " receiver completion", unwrap(failure));
                        return;
                    }
                    afterAcceptance.run();
                    operations.complete(registration);
                },
                ignoredFailure -> {}
            )
        );
    }

    private void releaseLatePreparation(RequestPreparationResult<P> result) {
        if (result instanceof RequestPreparationReady<P> ready && ready.value() != null) {
            try {
                resourceReleaser.releasePreparedRequest(ready.value());
            } catch (Throwable failure) {
                reportFatal("late prepared-request release", failure);
            }
        }
    }

    private void closePreparedOnly() {
        if (preparationState instanceof PreparationState.Ready<P> ready
            && ready.preparation().value() != null) {
            try {
                resourceReleaser.releasePreparedRequest(ready.preparation().value());
            } catch (Throwable failure) {
                reportFatal("prepared-request release", failure);
            }
        }
    }

    private void releaseRequestResources() {
        if (resourcesReleased) {
            return;
        }
        resourcesReleased = true;
        try {
            resourceReleaser.releaseSourceRequest(sourceRequest);
            if (preparationState instanceof PreparationState.Ready<P> ready
                && ready.preparation().value() != null) {
                resourceReleaser.releasePreparedRequest(ready.preparation().value());
            }
            for (var outcome : attemptHistory) {
                if (outcome
                    instanceof TargetAttemptOutcome.TargetResponseObtained<R> obtained) {
                    resourceReleaser.releaseTargetResponse(obtained.response());
                }
            }
            if (finalSourceState instanceof FinalSourceState.Complete<F> complete) {
                resourceReleaser.releaseSourceResponse(complete.response());
            }
        } catch (Throwable failure) {
            reportFatal("request resource release", failure);
        }
    }

    private void postRequired(
        String operation,
        Runnable transition,
        Consumer<Throwable> rejectionCleanup
    ) {
        if (eventLoop.inEventLoop()) {
            runTransition(operation, transition);
            return;
        }
        try {
            eventLoop.execute(() -> runTransition(operation, transition));
        } catch (Throwable failure) {
            try {
                rejectionCleanup.accept(failure);
            } catch (Throwable cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
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

    private void requireOwnerThread() {
        if (!eventLoop.inEventLoop()) {
            throw new IllegalStateException(
                "request owner for " + requestId() + " accessed outside its event loop"
            );
        }
    }

    private void impossible(String operation, Throwable cause) {
        reportFatal("impossible transition during " + operation, cause);
    }

    private void reportFatal(String operation, Throwable cause) {
        fatalHandler.onFatal(new Error(
            "Request-owner failure for " + requestId() + ": " + operation,
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

    @FunctionalInterface
    private interface RequiredSubmission {
        CompletionStage<Void> submit();
    }
}
