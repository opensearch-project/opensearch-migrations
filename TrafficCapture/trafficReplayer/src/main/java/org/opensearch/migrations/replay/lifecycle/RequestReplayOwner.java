package org.opensearch.migrations.replay.lifecycle;

import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

import org.opensearch.migrations.replay.lifecycle.ReplayIdentity.PartitionGenerationId;
import org.opensearch.migrations.replay.lifecycle.ReplayIdentity.ReplayRequestId;
import org.opensearch.migrations.replay.lifecycle.ReplayOutcomes.PreparationOutcome;
import org.opensearch.migrations.replay.lifecycle.ReplayOutcomes.ProcessingCancellationResult;

import lombok.NonNull;

/**
 * Event-loop-confined state for one admitted replay request.
 *
 * <p>The owning {@link TargetConnectionOwner} invokes every transition on their shared Netty
 * event loop.  The connection owner retains this object in its registry after the target turn
 * finishes and removes it only after replay intake accepts normal processing completion or after
 * cancellation cleanup.</p>
 */
public final class RequestReplayOwner<P extends TargetConnectionOwner.PreparedRequest, R> {
    sealed interface PreparationState<P>
        permits PreparationState.Admitted,
            PreparationState.Preparing,
            PreparationState.Ready,
            PreparationState.Cancelled {

        record Admitted<P>() implements PreparationState<P> {}

        record Preparing<P>() implements PreparationState<P> {}

        record Ready<P>(@NonNull PreparationOutcome<P> outcome) implements PreparationState<P> {}

        record Cancelled<P>(@NonNull CancellationException cause) implements PreparationState<P> {}
    }

    enum ConnectionTurnState {
        QUEUED,
        ACTIVE,
        FINISHED_PENDING_INTAKE,
        FINISHED,
        CANCELLED
    }

    enum FirstTargetWriteState {
        NOT_SUBMITTED,
        SUBMITTED
    }

    enum ProcessingCancellationAcknowledgementState {
        NOT_REQUESTED,
        PENDING,
        ACCEPTED,
        FAILED
    }

    enum PreparationCancellationAcknowledgementState {
        NOT_REQUESTED,
        PENDING,
        ACCEPTED,
        FAILED
    }

    sealed interface ProcessingState
        permits ProcessingState.Unregistered,
            ProcessingState.Registered,
            ProcessingState.CancellationRequested,
            ProcessingState.CompletionReceived,
            ProcessingState.MilestoneSubmitted,
            ProcessingState.Finished,
            ProcessingState.Failed,
            ProcessingState.Cancelled {

        record Unregistered() implements ProcessingState {}

        record Registered(
            @NonNull TargetConnectionOwner.RequestProcessingRegistration registration
        ) implements ProcessingState {}

        record CancellationRequested(
            @NonNull TargetConnectionOwner.RequestProcessingRegistration registration,
            @NonNull CancellationException cause
        ) implements ProcessingState {}

        record CompletionReceived(
            @NonNull TargetConnectionOwner.RequestProcessingOutcome outcome
        ) implements ProcessingState {}

        record MilestoneSubmitted() implements ProcessingState {}

        record Finished() implements ProcessingState {}

        record Failed(@NonNull Throwable cause) implements ProcessingState {}

        record Cancelled(@NonNull CancellationException cause) implements ProcessingState {}
    }

    final PartitionGenerationId partitionGenerationId;
    final ReplayRequestId requestId;
    final TargetConnectionOwner.RequestPreparation<P> preparationController;
    final CompletionGate<TargetConnectionOwner.RequestTurnResult<R>> completion = new CompletionGate<>();

    private PreparationState<P> preparationState = new PreparationState.Admitted<>();
    private final BooleanSupplier inOwnerThread;
    private ConnectionTurnState connectionTurnState = ConnectionTurnState.QUEUED;
    private ProcessingState processingState = new ProcessingState.Unregistered();
    private TargetConnectionOwner.RequestProcessingRegistration processingRegistration;
    private boolean connectionTurnResourcesReleased;
    private boolean preparedReleased;
    private PreparationCancellationAcknowledgementState preparationCancellationAcknowledgementState =
        PreparationCancellationAcknowledgementState.NOT_REQUESTED;
    private final java.util.concurrent.CompletableFuture<Void> preparationCancellationAcknowledgement =
        new java.util.concurrent.CompletableFuture<>();
    private FirstTargetWriteState firstTargetWriteState = FirstTargetWriteState.NOT_SUBMITTED;
    private ProcessingCancellationAcknowledgementState processingCancellationAcknowledgementState =
        ProcessingCancellationAcknowledgementState.NOT_REQUESTED;
    private ProcessingCancellationResult processingCancellationResult;

    RequestReplayOwner(
        @NonNull PartitionGenerationId partitionGenerationId,
        @NonNull ReplayRequestId requestId,
        @NonNull TargetConnectionOwner.RequestPreparation<P> preparationController,
        @NonNull BooleanSupplier inOwnerThread
    ) {
        this.partitionGenerationId = partitionGenerationId;
        this.requestId = requestId;
        this.preparationController = preparationController;
        this.inOwnerThread = inOwnerThread;
    }

    void beginPreparation() {
        requireOwnerThread();
        if (preparationState instanceof PreparationState.Admitted<P>) {
            preparationState = new PreparationState.Preparing<>();
        } else if (!(preparationState instanceof PreparationState.Ready<P>)) {
            throw new IllegalStateException(
                "cannot begin request preparation from " + preparationState.getClass().getSimpleName()
            );
        }
        preparationController.begin();
    }

    void recordPreparation(@NonNull PreparationOutcome<P> outcome) {
        requireOwnerThread();
        if (!(preparationState instanceof PreparationState.Admitted<P>)
            && !(preparationState instanceof PreparationState.Preparing<P>)) {
            throw new IllegalStateException(
                "request preparation completed from " + preparationState.getClass().getSimpleName()
            );
        }
        preparationState = new PreparationState.Ready<>(outcome);
    }

    PreparationOutcome<P> preparationOutcome() {
        requireOwnerThread();
        return preparationState instanceof PreparationState.Ready<P> ready
            ? ready.outcome()
            : null;
    }

    void markTurnActive() {
        requireOwnerThread();
        if (connectionTurnState != ConnectionTurnState.QUEUED) {
            throw new IllegalStateException(
                "cannot begin target turn from " + connectionTurnState
            );
        }
        connectionTurnState = ConnectionTurnState.ACTIVE;
    }

    void markConnectionTurnFinished() {
        requireOwnerThread();
        if (connectionTurnState != ConnectionTurnState.ACTIVE) {
            throw new IllegalStateException(
                "cannot finish connection turn from " + connectionTurnState
            );
        }
        connectionTurnState = ConnectionTurnState.FINISHED_PENDING_INTAKE;
    }

    void markConnectionTurnAccepted() {
        requireOwnerThread();
        if (connectionTurnState != ConnectionTurnState.FINISHED_PENDING_INTAKE) {
            throw new IllegalStateException(
                "cannot accept connection-turn completion from " + connectionTurnState
            );
        }
        connectionTurnState = ConnectionTurnState.FINISHED;
    }

    boolean connectionTurnFinished() {
        requireOwnerThread();
        return connectionTurnState == ConnectionTurnState.FINISHED;
    }

    boolean connectionTurnSettled() {
        requireOwnerThread();
        return connectionTurnState == ConnectionTurnState.FINISHED_PENDING_INTAKE
            || connectionTurnState == ConnectionTurnState.FINISHED
            || connectionTurnState == ConnectionTurnState.CANCELLED;
    }

    boolean connectionTurnCleanupComplete() {
        requireOwnerThread();
        return connectionTurnState == ConnectionTurnState.FINISHED
            || connectionTurnState == ConnectionTurnState.CANCELLED;
    }

    void cancelConnectionTurn(@NonNull CancellationException cause) {
        requireOwnerThread();
        if (!connectionTurnSettled()) {
            connectionTurnState = ConnectionTurnState.CANCELLED;
        }
        if (preparationState instanceof PreparationState.Admitted<P>
            || preparationState instanceof PreparationState.Preparing<P>) {
            preparationState = new PreparationState.Cancelled<>(cause);
        }
    }

    boolean beginPreparationCancellation() {
        requireOwnerThread();
        if (preparationCancellationAcknowledgementState
            != PreparationCancellationAcknowledgementState.NOT_REQUESTED) {
            return false;
        }
        preparationCancellationAcknowledgementState =
            PreparationCancellationAcknowledgementState.PENDING;
        return true;
    }

    void acceptPreparationCancellationAcknowledgement() {
        requireOwnerThread();
        if (preparationCancellationAcknowledgementState
            != PreparationCancellationAcknowledgementState.PENDING) {
            throw new IllegalStateException(
                "cannot accept preparation cancellation acknowledgement from "
                    + preparationCancellationAcknowledgementState
            );
        }
        preparationCancellationAcknowledgementState =
            PreparationCancellationAcknowledgementState.ACCEPTED;
        preparationCancellationAcknowledgement.complete(null);
    }

    void failPreparationCancellationAcknowledgement(@NonNull Throwable failure) {
        requireOwnerThread();
        if (preparationCancellationAcknowledgementState
            == PreparationCancellationAcknowledgementState.PENDING) {
            preparationCancellationAcknowledgementState =
                PreparationCancellationAcknowledgementState.FAILED;
            preparationCancellationAcknowledgement.completeExceptionally(failure);
        }
    }

    boolean preparationCancellationAcknowledgementSatisfied() {
        requireOwnerThread();
        return preparationCancellationAcknowledgementState
            == PreparationCancellationAcknowledgementState.NOT_REQUESTED
            || preparationCancellationAcknowledgementState
                == PreparationCancellationAcknowledgementState.ACCEPTED;
    }

    java.util.concurrent.CompletionStage<Void> preparationCancellationAcknowledgement() {
        return preparationCancellationAcknowledgement.minimalCompletionStage();
    }

    void registerProcessing(
        @NonNull TargetConnectionOwner.RequestProcessingRegistration registration
    ) {
        requireOwnerThread();
        if (!(processingState instanceof ProcessingState.Unregistered)) {
            throw new IllegalStateException(
                "request processing registered from " + processingState.getClass().getSimpleName()
            );
        }
        this.processingRegistration = registration;
        processingState = new ProcessingState.Registered(registration);
    }

    TargetConnectionOwner.RequestProcessingRegistration processingRegistration() {
        requireOwnerThread();
        return processingRegistration;
    }

    void recordProcessingCompletion(
        @NonNull TargetConnectionOwner.RequestProcessingOutcome outcome
    ) {
        requireOwnerThread();
        var accepted = switch (processingState) {
            case ProcessingState.Registered ignored ->
                !(outcome instanceof
                    TargetConnectionOwner.RequestProcessingOutcome.RequestCleanupFinished);
            case ProcessingState.CancellationRequested ignored ->
                processingCancellationResult
                    instanceof ProcessingCancellationResult.CancellationWon
                        ? outcome instanceof
                            TargetConnectionOwner.RequestProcessingOutcome.RequestCleanupFinished
                        : processingCancellationResult
                            instanceof ProcessingCancellationResult.ProcessingCompletionWon
                                && !(outcome
                                    instanceof TargetConnectionOwner.RequestProcessingOutcome
                                        .RequestCleanupFinished);
            default -> false;
        };
        if (!accepted) {
            throw new IllegalStateException(
                "request-processing "
                    + outcome.getClass().getSimpleName()
                    + " completion arrived from "
                    + processingState.getClass().getSimpleName()
            );
        }
        processingState = new ProcessingState.CompletionReceived(outcome);
    }

    boolean processingCompletionReceived() {
        requireOwnerThread();
        return processingState instanceof ProcessingState.CompletionReceived;
    }

    boolean processingCancellationRequested() {
        requireOwnerThread();
        return processingState instanceof ProcessingState.CancellationRequested
            || processingState instanceof ProcessingState.Cancelled;
    }

    CancellationException processingCancellationCause() {
        requireOwnerThread();
        if (processingState instanceof ProcessingState.CancellationRequested requested) {
            return requested.cause();
        }
        if (processingState instanceof ProcessingState.Cancelled cancelled) {
            return cancelled.cause();
        }
        return null;
    }

    void beginProcessingCancellationAcknowledgement() {
        requireOwnerThread();
        if (processingCancellationAcknowledgementState
            != ProcessingCancellationAcknowledgementState.NOT_REQUESTED) {
            throw new IllegalStateException(
                "cannot begin request-processing cancellation acknowledgement from "
                    + processingCancellationAcknowledgementState
            );
        }
        processingCancellationAcknowledgementState =
            ProcessingCancellationAcknowledgementState.PENDING;
    }

    void acceptProcessingCancellationAcknowledgement(
        @NonNull ProcessingCancellationResult result
    ) {
        requireOwnerThread();
        if (processingCancellationAcknowledgementState
            != ProcessingCancellationAcknowledgementState.PENDING) {
            throw new IllegalStateException(
                "cannot accept request-processing cancellation acknowledgement from "
                    + processingCancellationAcknowledgementState
            );
        }
        processingCancellationAcknowledgementState =
            ProcessingCancellationAcknowledgementState.ACCEPTED;
        processingCancellationResult = result;
    }

    void failProcessingCancellationAcknowledgement() {
        requireOwnerThread();
        if (processingCancellationAcknowledgementState
            == ProcessingCancellationAcknowledgementState.PENDING) {
            processingCancellationAcknowledgementState =
                ProcessingCancellationAcknowledgementState.FAILED;
        }
    }

    boolean processingCancellationAcknowledgementSatisfied() {
        requireOwnerThread();
        return processingCancellationAcknowledgementState
            == ProcessingCancellationAcknowledgementState.NOT_REQUESTED
            || processingCancellationAcknowledgementState
                == ProcessingCancellationAcknowledgementState.ACCEPTED;
    }

    ProcessingCancellationResult processingCancellationResult() {
        requireOwnerThread();
        return processingCancellationResult;
    }

    TargetConnectionOwner.RequestProcessingOutcome processingOutcome() {
        requireOwnerThread();
        return processingState instanceof ProcessingState.CompletionReceived received
            ? received.outcome()
            : null;
    }

    boolean processingMilestoneSubmitted() {
        requireOwnerThread();
        return processingState instanceof ProcessingState.MilestoneSubmitted
            || processingState instanceof ProcessingState.Finished;
    }

    void markProcessingMilestoneSubmitted() {
        requireOwnerThread();
        if (!(processingState instanceof ProcessingState.CompletionReceived received)
            || !(received.outcome() instanceof TargetConnectionOwner.RequestProcessingOutcome.TupleDurable)
            || !connectionTurnFinished()) {
            throw new IllegalStateException(
                "cannot submit RequestProcessingFinished from "
                    + processingState.getClass().getSimpleName()
                    + " with turn "
                    + connectionTurnState
            );
        }
        processingState = new ProcessingState.MilestoneSubmitted();
    }

    void markProcessingFailed(@NonNull Throwable cause) {
        requireOwnerThread();
        processingState = new ProcessingState.Failed(cause);
    }

    void finishProcessing() {
        requireOwnerThread();
        var allowed = switch (processingState) {
            case ProcessingState.MilestoneSubmitted ignored -> connectionTurnCleanupComplete();
            case ProcessingState.CompletionReceived received -> switch (received.outcome()) {
                case TargetConnectionOwner.RequestProcessingOutcome.RequestCleanupFinished ignored ->
                    cancellationCleanupComplete();
                case TargetConnectionOwner.RequestProcessingOutcome.TupleDurable ignored -> false;
            };
            case ProcessingState.Cancelled ignored -> cancellationCleanupComplete();
            default -> false;
        };
        if (!allowed) {
            throw new IllegalStateException(
                "cannot finish request processing from "
                    + processingState.getClass().getSimpleName()
                    + " with turn "
                    + connectionTurnState
                    + ", preparation cancellation "
                    + preparationCancellationAcknowledgementState
                    + ", and processing cancellation "
                    + processingCancellationAcknowledgementState
            );
        }
        processingState = new ProcessingState.Finished();
    }

    void cancelProcessing(@NonNull CancellationException cause) {
        requireOwnerThread();
        if (processingState instanceof ProcessingState.Unregistered) {
            processingState = new ProcessingState.Cancelled(cause);
            return;
        }
        if (processingState instanceof ProcessingState.Registered registered) {
            processingState = new ProcessingState.CancellationRequested(
                registered.registration(),
                cause
            );
            return;
        }
        if (processingState instanceof ProcessingState.CancellationRequested
            || processingState instanceof ProcessingState.Cancelled
            || processingState instanceof ProcessingState.CompletionReceived
            || processingState instanceof ProcessingState.MilestoneSubmitted
            || processingState instanceof ProcessingState.Finished) {
            return;
        }
        throw new IllegalStateException(
            "cannot cancel request processing from " + processingState.getClass().getSimpleName()
        );
    }

    boolean firstTargetWriteSubmitted() {
        requireOwnerThread();
        return firstTargetWriteState == FirstTargetWriteState.SUBMITTED;
    }

    void markFirstTargetWriteSubmitted() {
        requireOwnerThread();
        if (firstTargetWriteState == FirstTargetWriteState.SUBMITTED) {
            throw new IllegalStateException("first target write was submitted more than once");
        }
        firstTargetWriteState = FirstTargetWriteState.SUBMITTED;
    }

    void releaseConnectionTurnResources() throws Exception {
        requireOwnerThread();
        if (!connectionTurnResourcesReleased
            && preparationOutcome() instanceof PreparationOutcome.Prepared<P> prepared) {
            connectionTurnResourcesReleased = true;
            prepared.value().connectionTurnFinished();
        }
    }

    void releasePrepared() throws Exception {
        requireOwnerThread();
        if (!preparedReleased && preparationOutcome() instanceof PreparationOutcome.Prepared<P> prepared) {
            preparedReleased = true;
            Throwable failure = null;
            try {
                releaseConnectionTurnResources();
            } catch (Throwable t) {
                failure = t;
            }
            try {
                prepared.value().close();
            } catch (Throwable t) {
                failure = combineReleaseFailures(failure, t);
            }
            rethrowReleaseFailure(failure);
        }
    }

    private void requireOwnerThread() {
        if (!inOwnerThread.getAsBoolean()) {
            throw new IllegalStateException(
                "request replay owner for " + requestId + " accessed from non-owner thread "
                    + Thread.currentThread().getName()
            );
        }
    }

    private boolean cancellationCleanupComplete() {
        return connectionTurnCleanupComplete()
            && preparationCancellationAcknowledgementSatisfied()
            && processingCancellationAcknowledgementSatisfied();
    }

    private static Throwable combineReleaseFailures(Throwable first, Throwable additional) {
        if (first == null) {
            return additional;
        }
        if (additional == first) {
            return first;
        }
        if (additional instanceof Error && !(first instanceof Error)) {
            additional.addSuppressed(first);
            return additional;
        }
        first.addSuppressed(additional);
        return first;
    }

    private static void rethrowReleaseFailure(Throwable failure) throws Exception {
        if (failure instanceof Error error) {
            throw error;
        }
        if (failure instanceof Exception exception) {
            throw exception;
        }
        if (failure != null) {
            throw new RuntimeException(failure);
        }
    }
}
