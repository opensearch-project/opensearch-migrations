package org.opensearch.migrations.replay.lifecycle;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

import org.opensearch.migrations.replay.lifecycle.ReplayIdentity.ConnectionSessionKey;
import org.opensearch.migrations.replay.lifecycle.ReplayIdentity.PartitionGenerationId;
import org.opensearch.migrations.replay.lifecycle.ReplayIdentity.ReplayRequestId;
import org.opensearch.migrations.replay.lifecycle.ReplayOutcomes.PreparationOutcome;
import org.opensearch.migrations.replay.lifecycle.ReplayOutcomes.ProcessingCancellationResult;
import org.opensearch.migrations.replay.lifecycle.ReplayOutcomes.SessionOutcome;
import org.opensearch.migrations.replay.lifecycle.ReplayOutcomes.SessionOutcome.AbortReason;

import lombok.NonNull;

public final class TargetConnectionOwner<P extends TargetConnectionOwner.PreparedRequest, R> {
    public sealed interface RequestAdmissionResult permits
        RequestAdmissionAccepted,
        RequestAdmissionRejected {}

    public record RequestAdmissionAccepted() implements RequestAdmissionResult {}

    public record RequestAdmissionRejected(
        @NonNull CancellationException cause
    ) implements RequestAdmissionResult {}

    public record RequestAdmission<R>(
        @NonNull CompletionStage<RequestAdmissionResult> admissionResult,
        @NonNull CompletionStage<RequestTurnResult<R>> turnCompletion
    ) {}

    public record CloseAdmission(
        @NonNull CompletionStage<Void> admissionAccepted,
        @NonNull CompletionStage<SessionOutcome> closeCompletion
    ) {}

    @FunctionalInterface
    public interface FatalHandler {
        void onFatal(Error failure);
    }

    public interface PreparedRequest extends AutoCloseable {
        void connectionTurnFinished() throws Exception;
    }

    public enum HeadWaitReason {
        SCHEDULED_START("scheduled_start"),
        PREPARATION("preparation"),
        ACTIVE_EXCHANGE("active_exchange"),
        ORDERED_CLOSE("ordered_close");

        private final String metricLabel;

        HeadWaitReason(String metricLabel) {
            this.metricLabel = metricLabel;
        }

        public String metricLabel() {
            return metricLabel;
        }
    }

    public enum AbortChild {
        TARGET_EXCHANGE("target_exchange");

        private final String metricLabel;

        AbortChild(String metricLabel) {
            this.metricLabel = metricLabel;
        }

        public String metricLabel() {
            return metricLabel;
        }
    }

    public interface Metrics {
        Metrics NOOP = new Metrics() {
            @Override
            public void queuedCommandsChanged(int delta) {
                // Metrics are optional for non-production actor instances.
            }

            @Override
            public void headWaitChanged(HeadWaitReason reason, int delta) {
                // Metrics are optional for non-production actor instances.
            }

            @Override
            public void activeDuration(Duration duration) {
                // Metrics are optional for non-production actor instances.
            }

            @Override
            public void abortDuration(Duration duration) {
                // Metrics are optional for non-production actor instances.
            }

            @Override
            public void pendingAbortChildChanged(AbortChild child, int delta) {
                // Metrics are optional for non-production actor instances.
            }

        };

        void queuedCommandsChanged(int delta);

        void headWaitChanged(HeadWaitReason reason, int delta);

        void activeDuration(Duration duration);

        void abortDuration(Duration duration);

        void pendingAbortChildChanged(AbortChild child, int delta);

    }

    public interface TargetExchange<P, R> {
        CompletionStage<RequestTurnResult<R>> execute(
            ReplayRequestId requestId,
            P preparedRequest
        );

        CompletionStage<Void> close();

        CompletionStage<Void> abort(CancellationException cause);
    }

    public sealed interface RequestTurnResult<T>
        permits RequestTurnResult.Completed,
            RequestTurnResult.PreparationFiltered,
            RequestTurnResult.Cancelled {

        record Completed<T>(@NonNull T value) implements RequestTurnResult<T> {}

        record PreparationFiltered<T>(@NonNull String reason) implements RequestTurnResult<T> {}

        record Cancelled<T>(@NonNull CancellationException cause) implements RequestTurnResult<T> {}
    }

    public interface RequestPreparation<P> {
        CompletionStage<PreparationOutcome<P>> completion();

        default void admitted() {}

        default void begin() {}

        CompletionStage<Void> cancel(CancellationException cause);
    }

    public interface RequestLifecycleSink {
        CompletionStage<Void> connectionRequestFinished(
            PartitionGenerationId partitionGenerationId,
            ReplayRequestId requestId
        );

        CompletionStage<Void> requestProcessingFinished(
            PartitionGenerationId partitionGenerationId,
            ReplayRequestId requestId
        );
    }

    public sealed interface RequestProcessingOutcome permits
        RequestProcessingOutcome.TupleDurable,
        RequestProcessingOutcome.RequestCleanupFinished {

        record TupleDurable() implements RequestProcessingOutcome {}

        record RequestCleanupFinished(
            @NonNull CancellationException cause
        ) implements RequestProcessingOutcome {}
    }

    public static final class RequestProcessingRegistration {
        @FunctionalInterface
        public interface ProcessingCanceller {
            CompletionStage<ProcessingCancellationResult> apply(
                CancellationException cause
            );
        }

        private final CompletionStage<RequestProcessingOutcome> completion;
        private final ProcessingCanceller cancel;
        private final CompletableFuture<Void> lifecycleHandled;
        private final AtomicBoolean admissionRejectionCleanupStarted = new AtomicBoolean();
        private final CompletableFuture<Void> admissionRejectionCleanup = new CompletableFuture<>();

        public RequestProcessingRegistration(
            @NonNull CompletionStage<RequestProcessingOutcome> completion,
            @NonNull ProcessingCanceller cancel
        ) {
            this(completion, cancel, new CompletableFuture<>());
        }

        public RequestProcessingRegistration(
            @NonNull CompletionStage<RequestProcessingOutcome> completion,
            @NonNull ProcessingCanceller cancel,
            @NonNull CompletableFuture<Void> lifecycleHandled
        ) {
            this.completion = completion;
            this.cancel = cancel;
            this.lifecycleHandled = lifecycleHandled;
        }

        public static RequestProcessingRegistration withTypedCancellation(
            @NonNull CompletionStage<RequestProcessingOutcome> completion,
            @NonNull ProcessingCanceller cancel
        ) {
            return new RequestProcessingRegistration(
                completion,
                cancel
            );
        }

        public CompletionStage<RequestProcessingOutcome> completion() {
            return completion;
        }

        public ProcessingCanceller cancel() {
            return cancel;
        }

        public CompletionStage<Void> lifecycleHandled() {
            return lifecycleHandled.minimalCompletionStage();
        }

        public CompletionStage<Void> rejectAdmission(CancellationException cause) {
            if (!admissionRejectionCleanupStarted.compareAndSet(false, true)) {
                return admissionRejectionCleanup.minimalCompletionStage();
            }
            final CompletionStage<ProcessingCancellationResult> cancellation;
            try {
                cancellation = java.util.Objects.requireNonNull(
                    cancel.apply(cause),
                    "rejected request processing cancellation returned no acknowledgement"
                );
            } catch (Throwable failure) {
                failAdmissionRejectionCleanup(failure);
                return admissionRejectionCleanup.minimalCompletionStage();
            }
            cancellation.thenCombine(completion, (decision, outcome) -> {
                if (!(decision instanceof ProcessingCancellationResult.CancellationWon)) {
                    throw new CompletionException(new IllegalStateException(
                        "request processing completed before rejected admission cancellation"
                    ));
                }
                if (!(outcome instanceof RequestProcessingOutcome.RequestCleanupFinished)) {
                    throw new CompletionException(new IllegalStateException(
                        "rejected request processing did not report typed cancellation"
                    ));
                }
                return null;
            }).whenComplete((ignored, failure) -> {
                if (failure == null) {
                    completeLifecycleHandling();
                    admissionRejectionCleanup.complete(null);
                } else {
                    failAdmissionRejectionCleanup(unwrap(failure));
                }
            });
            return admissionRejectionCleanup.minimalCompletionStage();
        }

        private void failAdmissionRejectionCleanup(Throwable failure) {
            failLifecycleHandling(failure);
            admissionRejectionCleanup.completeExceptionally(failure);
        }

        private void completeLifecycleHandling() {
            lifecycleHandled.complete(null);
        }

        private void failLifecycleHandling(Throwable failure) {
            lifecycleHandled.completeExceptionally(failure);
        }
    }

    private enum State {
        OPEN,
        ACTIVE,
        ABORTING,
        TERMINATED
    }

    private sealed interface Command<P, R> permits RequestCommand, CloseCommand {
        PartitionGenerationId partitionGenerationId();

        Long capturedOrdinal();

        Instant admissionStart();

        Instant scheduledStart();

        boolean due();

        void markDue();
    }

    private static final class RequestCommand<P extends PreparedRequest, R> implements Command<P, R> {
        private final RequestReplayOwner<P, R> owner;
        private final RequestProcessingRegistration processingRegistration;
        private final Long capturedOrdinal;
        private final Instant admissionStart;
        private final Instant scheduledStart;
        private final CompletionGate<RequestAdmissionResult> admissionResult =
            new CompletionGate<>();
        private final AtomicBoolean emergencyCleanupStarted = new AtomicBoolean();
        private final AtomicBoolean emergencyPreparedReleaseStarted = new AtomicBoolean();
        private boolean due;

        private RequestCommand(
            PartitionGenerationId partitionGenerationId,
            ReplayRequestId requestId,
            Long capturedOrdinal,
            Instant admissionStart,
            Instant scheduledStart,
            RequestPreparation<P> preparationController,
            RequestProcessingRegistration processingRegistration,
            java.util.function.BooleanSupplier inOwnerThread
        ) {
            this.owner = new RequestReplayOwner<>(
                partitionGenerationId,
                requestId,
                preparationController,
                inOwnerThread
            );
            this.processingRegistration = java.util.Objects.requireNonNull(processingRegistration);
            this.capturedOrdinal = capturedOrdinal;
            this.admissionStart = admissionStart;
            this.scheduledStart = scheduledStart;
        }

        @Override
        public PartitionGenerationId partitionGenerationId() {
            return owner.partitionGenerationId;
        }

        @Override
        public Long capturedOrdinal() {
            return capturedOrdinal;
        }

        @Override
        public Instant admissionStart() {
            return admissionStart;
        }

        @Override
        public Instant scheduledStart() {
            return scheduledStart;
        }

        @Override
        public boolean due() {
            return due;
        }

        @Override
        public void markDue() {
            due = true;
        }
    }

    private static final class CloseCommand<P, R> implements Command<P, R> {
        private final PartitionGenerationId partitionGenerationId;
        private final Long capturedOrdinal;
        private final Instant scheduledStart;
        private final CompletionGate<Void> admissionAccepted = new CompletionGate<>();
        private final CompletionGate<SessionOutcome> completion = new CompletionGate<>();
        private boolean due;

        private CloseCommand(
            PartitionGenerationId partitionGenerationId,
            Long capturedOrdinal,
            Instant scheduledStart
        ) {
            this.partitionGenerationId = java.util.Objects.requireNonNull(partitionGenerationId);
            this.capturedOrdinal = capturedOrdinal;
            this.scheduledStart = scheduledStart;
        }

        @Override
        public PartitionGenerationId partitionGenerationId() {
            return partitionGenerationId;
        }

        @Override
        public Long capturedOrdinal() {
            return capturedOrdinal;
        }

        @Override
        public Instant admissionStart() {
            return scheduledStart;
        }

        @Override
        public Instant scheduledStart() {
            return scheduledStart;
        }

        @Override
        public boolean due() {
            return due;
        }

        @Override
        public void markDue() {
            due = true;
        }
    }

    private final ConnectionSessionKey sessionKey;
    private final ActorMailbox mailbox;
    private final OwnerThreadGuard ownerThreadGuard;
    private final TargetExchange<P, R> targetExchange;
    private final Metrics metrics;
    private final LongSupplier nanoTime;
    private final FatalHandler fatalHandler;
    private final RequestLifecycleSink lifecycleSink;
    private final PartitionGenerationId partitionGenerationId;
    private final Deque<Command<P, R>> admissionCommands = new ArrayDeque<>();
    private final Deque<Command<P, R>> executionCommands = new ArrayDeque<>();
    private final Set<Command<P, R>> obligations = new LinkedHashSet<>();
    private final Map<ReplayRequestId, RequestReplayOwner<P, R>> requestOwners = new LinkedHashMap<>();
    private final Set<RequestReplayOwner<P, R>> tupleDurablePendingConnectionAcceptance =
        new LinkedHashSet<>();
    private final Map<RequestReplayOwner<P, R>, PendingProcessingCompletion>
        processingCompletionsPendingCancellationDecision = new LinkedHashMap<>();
    private final CompletionGate<SessionOutcome> termination = new CompletionGate<>();
    private ActorMailbox.ScheduledTask admissionTimer;
    private ActorMailbox.ScheduledTask executionTimer;
    private RequestCommand<P, R> activeRequest;
    private HeadWaitReason headWaitReason;
    private long activeStartedNanos;
    private long abortStartedNanos;
    private boolean orderedCloseActive;
    private boolean sourceCloseAdmitted;
    private boolean targetAbortPending;
    private Throwable abortCleanupFailure;
    private CancellationException abortCause;
    private SessionOutcome pendingTerminationOutcome;
    private boolean processFatalTransition;
    private Long lastAdmittedCapturedOrdinal;

    private record PendingProcessingCompletion(
        RequestProcessingOutcome outcome,
        Throwable failure
    ) {}
    private State state = State.OPEN;

    public TargetConnectionOwner(
        @NonNull ConnectionSessionKey sessionKey,
        @NonNull PartitionGenerationId partitionGenerationId,
        @NonNull ActorMailbox mailbox,
        @NonNull TargetExchange<P, R> targetExchange,
        @NonNull Metrics metrics,
        @NonNull FatalHandler fatalHandler,
        @NonNull RequestLifecycleSink lifecycleSink
    ) {
        this(
            sessionKey,
            partitionGenerationId,
            mailbox,
            targetExchange,
            metrics,
            System::nanoTime,
            fatalHandler,
            lifecycleSink
        );
    }

    TargetConnectionOwner(
        @NonNull ConnectionSessionKey sessionKey,
        @NonNull PartitionGenerationId partitionGenerationId,
        @NonNull ActorMailbox mailbox,
        @NonNull TargetExchange<P, R> targetExchange,
        @NonNull Metrics metrics,
        @NonNull LongSupplier nanoTime,
        @NonNull FatalHandler fatalHandler,
        @NonNull RequestLifecycleSink lifecycleSink
    ) {
        this.sessionKey = sessionKey;
        this.partitionGenerationId = partitionGenerationId;
        this.mailbox = mailbox;
        this.ownerThreadGuard = new OwnerThreadGuard(
            "connection actor for " + sessionKey,
            mailbox::inMailbox
        );
        this.targetExchange = targetExchange;
        this.metrics = metrics;
        this.nanoTime = nanoTime;
        this.fatalHandler = fatalHandler;
        this.lifecycleSink = lifecycleSink;
    }

    private void post(String operation, Runnable command) {
        post(operation, command, ignored -> {});
    }

    private void post(
        String operation,
        Runnable command,
        Consumer<Throwable> transitionFailureHandler
    ) {
        try {
            mailbox.execute(() ->
                runMailboxTransition(operation, command, transitionFailureHandler)
            );
        } catch (RejectedExecutionException e) {
            reportRejectedSubmission(operation, e);
            transitionFailureHandler.accept(e);
        }
    }

    private void applyNowOrPost(String operation, Runnable command) {
        if (mailbox.inMailbox()) {
            runMailboxTransition(operation, command);
        } else {
            post(operation, command);
        }
    }

    private void reportRejectedSubmission(String operation, RejectedExecutionException cause) {
        if (mailbox.inMailbox()) {
            if (processFatalTransition) {
                return;
            }
            processFatalTransition = true;
        }
        fatalHandler.onFatal(new Error(
            "Required connection-actor submission was rejected during "
                + operation
                + " for "
                + sessionKey,
            cause
        ));
    }

    private void reportImpossibleTransition(String operation, Throwable cause) {
        processFatalTransition = true;
        fatalHandler.onFatal(new Error(
            "Impossible connection-owner transition during "
                + operation
                + " for "
                + sessionKey,
            cause
        ));
    }

    private void runMailboxTransition(String operation, Runnable command) {
        runMailboxTransition(operation, command, ignored -> {});
    }

    private void runMailboxTransition(
        String operation,
        Runnable command,
        Consumer<Throwable> transitionFailureHandler
    ) {
        if (processFatalTransition) {
            transitionFailureHandler.accept(new IllegalStateException(
                "connection owner is no longer accepting transitions for " + sessionKey
            ));
            return;
        }
        try {
            ownerThreadGuard.requireOwnerThread();
            command.run();
        } catch (Error failure) {
            transitionFailureHandler.accept(failure);
            processFatalTransition = true;
            fatalHandler.onFatal(failure);
        } catch (Throwable failure) {
            transitionFailureHandler.accept(failure);
            reportImpossibleTransition(operation, failure);
        }
    }

    public RequestAdmission<R> admitRequestWithAcceptance(
        @NonNull PartitionGenerationId partitionGenerationId,
        @NonNull ReplayRequestId requestId,
        long capturedOrdinal,
        @NonNull Instant preparationStart,
        @NonNull Instant scheduledStart,
        @NonNull RequestPreparation<P> preparation,
        @NonNull RequestProcessingRegistration processingRegistration
    ) {
        if (!requestId.session().equals(sessionKey)) {
            throw new IllegalArgumentException("request belongs to a different session");
        }
        if (capturedOrdinal < 0) {
            throw new IllegalArgumentException("capturedOrdinal must not be negative");
        }
        var command = new RequestCommand<P, R>(
            partitionGenerationId,
            requestId,
            capturedOrdinal,
            preparationStart,
            scheduledStart,
            preparation,
            processingRegistration,
            mailbox::inMailbox
        );
        post(
            "request admission " + requestId,
            () -> admit(command),
            failure -> handleRequestAdmissionTransitionFailure(command, failure)
        );
        return new RequestAdmission<>(
            command.admissionResult.stage(),
            command.owner.completion.stage()
        );
    }

    private CancellationException cancellationForRejectedAdmission(
        String reason,
        RequestCommand<P, R> command,
        Throwable failure
    ) {
        var cancellation = new CancellationException(
            reason + ": " + command.owner.requestId
        );
        if (failure != null) {
            cancellation.initCause(failure);
        }
        return cancellation;
    }

    private void rejectUnadmittedRequest(
        RequestCommand<P, R> command,
        CancellationException cause
    ) {
        if (mailbox.inMailbox()) {
            if (obligations.contains(command)) {
                untrackObligation(command);
            }
            requestOwners.remove(command.owner.requestId, command.owner);
        }
        command.admissionResult.complete(new RequestAdmissionRejected(cause));
        command.owner.completion.complete(new RequestTurnResult.Cancelled<>(cause));
    }

    private void handleRequestAdmissionTransitionFailure(
        RequestCommand<P, R> command,
        Throwable failure
    ) {
        if (command.admissionResult.isDone()) {
            command.owner.completion.completeExceptionally(failure);
            command.processingRegistration.failLifecycleHandling(failure);
            return;
        }
        rejectUnadmittedRequest(
            command,
            cancellationForRejectedAdmission(
                "request admission could not reach its connection owner",
                command,
                failure
            )
        );
    }

    @SuppressWarnings("java:S1181") // Emergency cleanup runs only when the owner mailbox is unavailable.
    private void failRequestSubmission(
        RequestCommand<P, R> command,
        Throwable failure,
        PreparationOutcome<P> settledPreparation
    ) {
        Throwable cleanupFailure = failure;
        if (settledPreparation instanceof PreparationOutcome.Prepared<P> prepared
            && command.emergencyPreparedReleaseStarted.compareAndSet(false, true)) {
            try {
                prepared.value().close();
            } catch (Throwable releaseFailure) {
                cleanupFailure = combineFailures(cleanupFailure, releaseFailure);
            }
        }
        if (command.emergencyCleanupStarted.compareAndSet(false, true)) {
            var cancellation = new CancellationException(
                "request submission failed because its connection owner is unavailable: "
                    + command.owner.requestId
            );
            cancellation.initCause(failure);
            try {
                var acknowledgement = java.util.Objects.requireNonNull(
                    command.owner.preparationController.cancel(cancellation),
                    "rejected request preparation cancellation returned no acknowledgement"
                );
                acknowledgement.whenComplete((ignored, acknowledgementFailure) -> {
                    if (acknowledgementFailure != null) {
                        fatalHandler.onFatal(new Error(
                            "Rejected request preparation cleanup failed for "
                                + command.owner.requestId,
                            unwrap(acknowledgementFailure)
                        ));
                    }
                });
            } catch (Throwable cancellationFailure) {
                cleanupFailure = combineFailures(cleanupFailure, cancellationFailure);
            }
            try {
                var acknowledgement = java.util.Objects.requireNonNull(
                    command.processingRegistration.cancel().apply(cancellation),
                    "rejected request processing cancellation returned no acknowledgement"
                );
                acknowledgement.whenComplete((ignored, acknowledgementFailure) -> {
                    if (acknowledgementFailure != null) {
                        fatalHandler.onFatal(new Error(
                            "Rejected request cleanup acknowledgement failed for "
                                + command.owner.requestId,
                            unwrap(acknowledgementFailure)
                        ));
                    }
                });
            } catch (Throwable cancellationFailure) {
                cleanupFailure = combineFailures(cleanupFailure, cancellationFailure);
            }
        }
        command.owner.completion.completeExceptionally(cleanupFailure);
        command.processingRegistration.failLifecycleHandling(cleanupFailure);
    }

    public CompletionStage<SessionOutcome> admitClose(
        @NonNull PartitionGenerationId partitionGenerationId,
        @NonNull Instant scheduledStart
    ) {
        return admitCloseWithAcceptance(
            partitionGenerationId,
            null,
            scheduledStart
        ).closeCompletion();
    }

    public CloseAdmission admitCloseWithAcceptance(
        @NonNull PartitionGenerationId partitionGenerationId,
        @NonNull Instant scheduledStart
    ) {
        return admitCloseWithAcceptance(partitionGenerationId, null, scheduledStart);
    }

    public CloseAdmission admitCloseWithAcceptance(
        @NonNull PartitionGenerationId partitionGenerationId,
        long capturedOrdinal,
        @NonNull Instant scheduledStart
    ) {
        if (capturedOrdinal < 0) {
            throw new IllegalArgumentException("capturedOrdinal must not be negative");
        }
        return admitCloseWithAcceptance(
            partitionGenerationId,
            Long.valueOf(capturedOrdinal),
            scheduledStart
        );
    }

    private CloseAdmission admitCloseWithAcceptance(
        PartitionGenerationId partitionGenerationId,
        Long capturedOrdinal,
        Instant scheduledStart
    ) {
        var command = new CloseCommand<P, R>(
            partitionGenerationId,
            capturedOrdinal,
            scheduledStart
        );
        post(
            "ordered close admission",
            () -> admit(command),
            failure -> failCloseAdmission(command, failure)
        );
        return new CloseAdmission(
            command.admissionAccepted.stage(),
            command.completion.stage()
        );
    }

    private void failCloseAdmission(CloseCommand<P, R> command, Throwable failure) {
        command.admissionAccepted.completeExceptionally(failure);
        command.completion.complete(new SessionOutcome.Failed(failure));
    }

    private void installProcessingRegistration(
        RequestReplayOwner<P, R> request,
        RequestProcessingRegistration registration
    ) {
        assertInMailbox();
        request.registerProcessing(registration);
        registration.completion().whenComplete((outcome, failure) ->
            stageRequestProcessingCompletion(request, outcome, failure)
        );
        if (state == State.ABORTING || state == State.TERMINATED) {
            cancelRequestProcessing(
                request,
                new CancellationException("connection is terminating: " + sessionKey)
            );
        }
    }

    public void firstTargetWriteSubmitted(@NonNull ReplayRequestId requestId) {
        if (!requestId.session().equals(sessionKey)) {
            throw new IllegalArgumentException("request belongs to a different session");
        }
        applyNowOrPost("first target write " + requestId, () -> {
            var request = requestOwners.get(requestId);
            if (request == null) {
                throw new IllegalStateException(
                    "first target write arrived for an unknown request: " + requestId
                );
            }
            if (activeRequest == null || activeRequest.owner != request) {
                throw new IllegalStateException(
                    "first target write arrived while the request did not own the connection turn: "
                        + requestId
                );
            }
            request.markFirstTargetWriteSubmitted();
        });
    }

    public CompletionStage<SessionOutcome> abort(
        @NonNull AbortReason reason,
        @NonNull CancellationException cause
    ) {
        post("connection abort " + reason, () -> beginAbort(reason, cause));
        return termination.stage();
    }

    public CompletionStage<SessionOutcome> termination() {
        return termination.stage();
    }

    private void trackObligation(Command<P, R> command) {
        assertInMailbox();
        if (!obligations.add(command)) {
            throw new IllegalStateException("command obligation was already tracked");
        }
        metrics.queuedCommandsChanged(1);
    }

    private void admit(Command<P, R> command) {
        assertInMailbox();
        trackObligation(command);
        if (!validatePartitionGeneration(command)) {
            return;
        }
        if (state == State.ABORTING || state == State.TERMINATED || sourceCloseAdmitted) {
            rejectLateCommand(command);
            return;
        }
        validateCapturedOrdinal(command);
        if (command instanceof RequestCommand<P, R> request
            && requestOwners.putIfAbsent(request.owner.requestId, request.owner) != null) {
            throw new IllegalStateException("request was admitted twice: " + request.owner.requestId);
        }
        if (command instanceof CloseCommand<P, R>) {
            sourceCloseAdmitted = true;
        } else if (command instanceof RequestCommand<P, R> request) {
            installProcessingRegistration(request.owner, request.processingRegistration);
            request.owner.preparationController.admitted();
            request.owner.preparationController.completion().whenComplete((outcome, failure) ->
                stagePreparation(request, outcome, failure)
            );
        }
        admissionCommands.addLast(command);
        if (command instanceof CloseCommand<P, R> close) {
            close.admissionAccepted.complete(null);
        } else if (command instanceof RequestCommand<P, R> request) {
            request.admissionResult.complete(new RequestAdmissionAccepted());
        }
        if (admissionCommands.peekFirst() == command) {
            startAdmissionHead();
        }
    }

    private void validateCapturedOrdinal(Command<P, R> command) {
        var suppliedOrdinal = command.capturedOrdinal();
        var capturedOrdinal = suppliedOrdinal == null
            ? lastAdmittedCapturedOrdinal == null ? 0L : lastAdmittedCapturedOrdinal + 1
            : suppliedOrdinal;
        if (lastAdmittedCapturedOrdinal != null
            && capturedOrdinal <= lastAdmittedCapturedOrdinal) {
            throw new IllegalStateException(
                "captured ordinal "
                    + capturedOrdinal
                    + " did not follow "
                    + lastAdmittedCapturedOrdinal
                    + " for "
                    + sessionKey
            );
        }
        lastAdmittedCapturedOrdinal = capturedOrdinal;
    }

    private boolean validatePartitionGeneration(Command<P, R> command) {
        if (partitionGenerationId.equals(command.partitionGenerationId())) {
            return true;
        }
        var failure = new IllegalStateException(
            "connection owner "
                + sessionKey
                + " is bound to "
                + partitionGenerationId
                + " but received "
                + command.partitionGenerationId()
        );
        if (command instanceof RequestCommand<P, R> request) {
            rejectUnadmittedRequest(
                request,
                cancellationForRejectedAdmission(
                    "request rejected because it belongs to another partition generation",
                    request,
                    failure
                )
            );
        } else if (command instanceof CloseCommand<P, R> close) {
            close.admissionAccepted.completeExceptionally(failure);
            close.completion.complete(new SessionOutcome.Failed(failure));
            untrackObligation(command);
            processFatalTransition = true;
            reportImpossibleTransition(
                "partition-generation admission " + command,
                failure
            );
        }
        return false;
    }

    private void rejectLateCommand(Command<P, R> command) {
        var cause = new CancellationException("session is no longer accepting work: " + sessionKey);
        if (command instanceof RequestCommand<P, R> request) {
            rejectUnadmittedRequest(request, cause);
        } else if (command instanceof CloseCommand<P, R> close) {
            close.admissionAccepted.completeExceptionally(cause);
            close.completion.complete(new SessionOutcome.Aborted(AbortReason.SESSION_TERMINATED, cause));
            untrackObligation(command);
        }
    }

    private void stagePreparation(
        RequestCommand<P, R> command,
        PreparationOutcome<P> outcome,
        Throwable failure
    ) {
        var normalized = failure == null
            ? outcome
            : new PreparationOutcome.Failed<P>(unwrap(failure));
        if (normalized == null) {
            normalized = new PreparationOutcome.Failed<>(
                new NullPointerException("preparation completed without an outcome")
            );
        }
        var settledPreparation = normalized;
        post(
            "request preparation completion " + command.owner.requestId,
            () -> onPreparationSettled(command, settledPreparation),
            deliveryFailure -> failRequestSubmission(
                command,
                deliveryFailure,
                settledPreparation
            )
        );
    }

    private void onPreparationSettled(
        RequestCommand<P, R> command,
        PreparationOutcome<P> preparation
    ) {
        assertInMailbox();
        if (command.owner.connectionTurnSettled()
            || state == State.TERMINATED
            || state == State.ABORTING) {
            releaseLatePreparation(preparation);
            return;
        }
        command.owner.recordPreparation(preparation);
        if (executionCommands.peekFirst() == command) {
            tryRunExecutionHead();
        }
    }

    private void startAdmissionHead() {
        assertInMailbox();
        cancelAdmissionTimer();
        if (processFatalTransition) {
            setHeadWaitReason(null);
            return;
        }
        var head = admissionCommands.peekFirst();
        if (head == null) {
            return;
        }
        var delay = Duration.between(mailbox.now(), head.admissionStart());
        if (delay.isNegative() || delay.isZero()) {
            promoteAdmissionHead(head);
        } else {
            if (executionCommands.isEmpty() && activeRequest == null && !orderedCloseActive) {
                setHeadWaitReason(HeadWaitReason.SCHEDULED_START);
            }
            try {
                admissionTimer = mailbox.schedule(
                    () -> runMailboxTransition("scheduled preparation start", () -> {
                        assertInMailbox();
                        if (admissionCommands.peekFirst() == head) {
                            admissionTimer = null;
                            promoteAdmissionHead(head);
                        }
                    }),
                    delay
                );
            } catch (RejectedExecutionException e) {
                reportRejectedSubmission("scheduled preparation start", e);
            }
        }
    }

    private void promoteAdmissionHead(Command<P, R> head) {
        assertInMailbox();
        if (admissionCommands.removeFirst() != head) {
            throw new IllegalStateException("admission queue head changed during promotion");
        }
        executionCommands.addLast(head);
        if (head instanceof RequestCommand<P, R> request) {
            request.owner.beginPreparation();
        }
        startAdmissionHead();
        if (executionCommands.peekFirst() == head) {
            startExecutionHead();
        }
    }

    private void startExecutionHead() {
        assertInMailbox();
        cancelExecutionTimer();
        if (processFatalTransition) {
            setHeadWaitReason(null);
            return;
        }
        var head = executionCommands.peekFirst();
        if (head == null) {
            setHeadWaitReason(null);
            return;
        }
        var delay = Duration.between(mailbox.now(), head.scheduledStart());
        if (delay.isNegative() || delay.isZero()) {
            head.markDue();
            tryRunExecutionHead();
        } else {
            setHeadWaitReason(HeadWaitReason.SCHEDULED_START);
            try {
                executionTimer = mailbox.schedule(
                    () -> runMailboxTransition("scheduled execution start", () -> {
                        assertInMailbox();
                        if (executionCommands.peekFirst() == head) {
                            head.markDue();
                            executionTimer = null;
                            tryRunExecutionHead();
                        }
                    }),
                    delay
                );
            } catch (RejectedExecutionException e) {
                reportRejectedSubmission("scheduled execution start", e);
            }
        }
    }

    private void tryRunExecutionHead() {
        assertInMailbox();
        if (processFatalTransition || state == State.ABORTING || state == State.TERMINATED) {
            setHeadWaitReason(null);
            return;
        }
        if (activeRequest != null) {
            setHeadWaitReason(HeadWaitReason.ACTIVE_EXCHANGE);
            return;
        }
        if (orderedCloseActive) {
            setHeadWaitReason(HeadWaitReason.ORDERED_CLOSE);
            return;
        }
        var head = executionCommands.peekFirst();
        if (head == null) {
            setHeadWaitReason(null);
            return;
        }
        if (!head.due()) {
            setHeadWaitReason(HeadWaitReason.SCHEDULED_START);
            return;
        }
        if (head instanceof RequestCommand<P, R> request) {
            if (request.owner.preparationOutcome() == null) {
                setHeadWaitReason(HeadWaitReason.PREPARATION);
                return;
            }
            setHeadWaitReason(null);
            handlePreparedRequest(request);
        } else if (head instanceof CloseCommand<P, R> close) {
            setHeadWaitReason(null);
            runOrderedClose(close);
        }
    }

    private void handlePreparedRequest(RequestCommand<P, R> request) {
        request.owner.preparationOutcome().visit(new PreparationOutcome.Visitor<>() {
            @Override
            public Void onPrepared(PreparationOutcome.Prepared<P> outcome) {
                runTargetExchange(request, outcome.value());
                return null;
            }

            @Override
            public Void onFiltered(PreparationOutcome.Filtered<P> outcome) {
                request.owner.markTurnActive();
                settleRequest(request, new RequestTurnResult.PreparationFiltered<>(outcome.reason()));
                return null;
            }

            @Override
            public Void onFailed(PreparationOutcome.Failed<P> outcome) {
                request.owner.completion.completeExceptionally(outcome.cause());
                reportImpossibleTransition(
                    "request preparation " + request.owner.requestId,
                    outcome.cause()
                );
                return null;
            }

            @Override
            public Void onCancelled(PreparationOutcome.Cancelled<P> outcome) {
                failRequestWithoutNormalMilestone(
                    request,
                    "unexpected preparation cancellation " + request.owner.requestId,
                    outcome.cause()
                );
                return null;
            }
        });
    }

    private void runTargetExchange(RequestCommand<P, R> request, P preparedRequest) {
        request.owner.markTurnActive();
        state = State.ACTIVE;
        activeRequest = request;
        activeStartedNanos = nanoTime.getAsLong();
        setHeadWaitReason(HeadWaitReason.ACTIVE_EXCHANGE);
        CompletionStage<RequestTurnResult<R>> exchange;
        try {
            exchange = java.util.Objects.requireNonNull(
                targetExchange.execute(request.owner.requestId, preparedRequest),
                "target exchange returned no completion stage"
            );
        } catch (Throwable t) {
            failRequestWithoutNormalMilestone(
                request,
                "target exchange startup " + request.owner.requestId,
                unwrap(t)
            );
            return;
        }
        exchange.whenComplete((outcome, failure) ->
            post("target exchange completion " + request.owner.requestId, () -> {
                if (state == State.ABORTING || state == State.TERMINATED) {
                    return;
                }
                if (request.owner.connectionTurnSettled()) {
                    releasePreparedAfterSettledTurn(request);
                    return;
                }
                if (failure != null) {
                    var cause = unwrap(failure);
                    failRequestWithoutNormalMilestone(
                        request,
                        "target exchange completion " + request.owner.requestId,
                        cause
                    );
                    return;
                }
                if (outcome == null) {
                    failRequestWithoutNormalMilestone(
                        request,
                        "target exchange completion " + request.owner.requestId,
                        new NullPointerException("target exchange completed without an outcome")
                    );
                    return;
                }
                if (outcome instanceof RequestTurnResult.Cancelled<R> cancelled) {
                    failRequestWithoutNormalMilestone(
                        request,
                        "unexpected target cancellation " + request.owner.requestId,
                        cancelled.cause()
                    );
                } else {
                    releaseConnectionTurnResourcesAndSettle(request, outcome);
                }
            })
        );
    }

    private void releaseConnectionTurnResourcesAndSettle(
        RequestCommand<P, R> request,
        RequestTurnResult<R> outcome
    ) {
        try {
            request.owner.releaseConnectionTurnResources();
        } catch (Throwable t) {
            failRequestWithoutNormalMilestone(
                request,
                "connection-turn resource release " + request.owner.requestId,
                t
            );
            return;
        }
        settleRequest(request, outcome);
    }

    private void failRequestWithoutNormalMilestone(
        RequestCommand<P, R> request,
        String operation,
        Throwable cause
    ) {
        var failure = combineFailures(cause, releasePreparedFailure(request));
        request.owner.completion.completeExceptionally(failure);
        reportImpossibleTransition(operation, failure);
    }

    private void settleRequest(RequestCommand<P, R> request, RequestTurnResult<R> outcome) {
        assertInMailbox();
        if (request.owner.connectionTurnSettled()) {
            reportImpossibleTransition(
                "duplicate connection-turn completion " + request.owner.requestId,
                new IllegalStateException("request connection turn was already settled")
            );
            return;
        }
        request.owner.markConnectionTurnFinished();
        if (activeRequest == request) {
            recordActiveDuration();
        }
        activeRequest = null;
        if (executionCommands.peekFirst() != request) {
            throw new IllegalStateException("settled request was not the actor head");
        }
        executionCommands.removeFirst();
        untrackObligation(request);
        if (state == State.ACTIVE) {
            state = State.OPEN;
        }
        final CompletionStage<Void> acceptance;
        try {
            acceptance = java.util.Objects.requireNonNull(
                lifecycleSink.connectionRequestFinished(
                    request.owner.partitionGenerationId,
                    request.owner.requestId
                ),
                "request lifecycle sink returned no connection-turn acceptance"
            );
        } catch (Throwable t) {
            request.owner.processingRegistration().failLifecycleHandling(t);
            reportImpossibleTransition(
                "connection-turn completion " + request.owner.requestId,
                t
            );
            startExecutionHead();
            return;
        }
        startExecutionHead();
        acceptance.whenComplete((ignored, failure) ->
            applyNowOrPost("connection-turn milestone acceptance " + request.owner.requestId, () -> {
                if (failure != null) {
                    request.owner.processingRegistration().failLifecycleHandling(unwrap(failure));
                    reportImpossibleTransition(
                        "connection-turn milestone acceptance " + request.owner.requestId,
                        unwrap(failure)
                    );
                    return;
                }
                request.owner.markConnectionTurnAccepted();
                request.owner.completion.complete(outcome);
                if (tupleDurablePendingConnectionAcceptance.remove(request.owner)) {
                    request.owner.recordProcessingCompletion(
                        new RequestProcessingOutcome.TupleDurable()
                    );
                }
                if (request.owner.processingCompletionReceived()) {
                    applyRequestProcessingCompletion(request.owner);
                }
            })
        );
    }

    private void runOrderedClose(CloseCommand<P, R> close) {
        assertInMailbox();
        orderedCloseActive = true;
        setHeadWaitReason(HeadWaitReason.ORDERED_CLOSE);
        CompletionStage<Void> closeStage;
        try {
            closeStage = java.util.Objects.requireNonNull(
                targetExchange.close(),
                "target close returned no completion stage"
            );
        } catch (Throwable t) {
            closeStage = CompletableFuture.failedFuture(t);
        }
        closeStage.whenComplete((ignored, failure) ->
            post("ordered close completion", () -> {
                if (state == State.ABORTING || state == State.TERMINATED) {
                    return;
                }
                orderedCloseActive = false;
                var closeFailure = failure == null ? null : unwrap(failure);
                var outcome = closeFailure == null
                    ? new SessionOutcome.Closed()
                    : new SessionOutcome.Failed(closeFailure);
                close.completion.complete(outcome);
                executionCommands.removeFirst();
                untrackObligation(close);
                pendingTerminationOutcome = outcome;
                if (closeFailure != null) {
                    processFatalTransition = true;
                    reportImpossibleTransition("ordered close completion", closeFailure);
                    return;
                }
                tryFinishTermination();
            })
        );
    }

    private void beginAbort(AbortReason reason, CancellationException cause) {
        assertInMailbox();
        if (state == State.TERMINATED || state == State.ABORTING) {
            return;
        }
        state = State.ABORTING;
        abortCause = cause;
        abortStartedNanos = nanoTime.getAsLong();
        setHeadWaitReason(null);
        cancelAdmissionTimer();
        cancelExecutionTimer();
        Throwable queuedCleanupFailure = null;
        for (var command : new ArrayList<>(obligations)) {
            if (command instanceof RequestCommand<P, R> request && request != activeRequest) {
                queuedCleanupFailure = combineFailures(
                    queuedCleanupFailure,
                    cancelQueuedRequest(request, cause)
                );
                cancelRequestProcessing(request.owner, cause);
            } else if (command instanceof CloseCommand<P, R> close) {
                close.completion.complete(new SessionOutcome.Aborted(reason, cause));
            }
        }
        for (var request : new ArrayList<>(requestOwners.values())) {
            var stillOwnsConnectionWork = obligations.stream().anyMatch(command ->
                command instanceof RequestCommand<P, R> requestCommand
                    && requestCommand.owner == request
            );
            if (!stillOwnsConnectionWork) {
                cancelRequestProcessing(request, cause);
            }
        }
        abortCleanupFailure = combineFailures(abortCleanupFailure, queuedCleanupFailure);

        CompletionStage<Void> abortStage;
        targetAbortPending = true;
        metrics.pendingAbortChildChanged(AbortChild.TARGET_EXCHANGE, 1);
        try {
            abortStage = java.util.Objects.requireNonNull(
                targetExchange.abort(cause),
                "target abort returned no completion stage"
            );
        } catch (Throwable t) {
            abortStage = CompletableFuture.failedFuture(t);
        }
        abortStage.whenComplete((ignored, failure) ->
            post("target abort completion", () -> {
                if (targetAbortPending) {
                    targetAbortPending = false;
                    metrics.pendingAbortChildChanged(AbortChild.TARGET_EXCHANGE, -1);
                }
                orderedCloseActive = false;
                var terminationFailure = failure == null ? null : unwrap(failure);
                if (activeRequest != null) {
                    recordActiveDuration();
                }
                for (var command : new ArrayList<>(obligations)) {
                    if (command instanceof RequestCommand<P, R> request) {
                        request.owner.cancelConnectionTurn(cause);
                        terminationFailure = combineFailures(
                            terminationFailure,
                            cancelPreparationFailure(request)
                        );
                        terminationFailure = combineFailures(
                            terminationFailure,
                            releasePreparedFailure(request)
                        );
                        request.owner.completion.complete(new RequestTurnResult.Cancelled<>(cause));
                        cancelRequestProcessing(request.owner, cause);
                    } else if (command instanceof CloseCommand<P, R> close) {
                        close.completion.complete(new SessionOutcome.Aborted(reason, cause));
                    }
                    untrackObligation(command);
                }
                activeRequest = null;
                terminationFailure = combineFailures(
                    terminationFailure,
                    abortCleanupFailure
                );
                abortCleanupFailure = null;
                admissionCommands.clear();
                executionCommands.clear();
                metrics.abortDuration(elapsedSince(abortStartedNanos));
                pendingTerminationOutcome =
                    terminationFailure == null
                        ? new SessionOutcome.Aborted(reason, cause)
                        : new SessionOutcome.Failed(terminationFailure);
                tryFinishTermination();
            })
        );
    }

    private void stageRequestProcessingCompletion(
        RequestReplayOwner<P, R> request,
        RequestProcessingOutcome outcome,
        Throwable failure
    ) {
        var settledOutcome = outcome;
        var settledFailure = failure == null ? null : unwrap(failure);
        applyNowOrPost(
            "request-processing completion " + request.requestId,
            () -> {
                if (requestOwners.get(request.requestId) != request) {
                    return;
                }
                if (state == State.ABORTING && !request.processingCancellationRequested()) {
                    cancelRequestProcessing(
                        request,
                        java.util.Objects.requireNonNull(
                            abortCause,
                            "aborting connection had no cancellation cause"
                        )
                    );
                }
                if (request.processingCancellationRequested()
                    && request.processingCancellationResult() == null) {
                    var previous = processingCompletionsPendingCancellationDecision.putIfAbsent(
                        request,
                        new PendingProcessingCompletion(settledOutcome, settledFailure)
                    );
                    if (previous != null) {
                        reportImpossibleTransition(
                            "duplicate request-processing completion " + request.requestId,
                            new IllegalStateException(
                                "request-processing completion arrived twice while cancellation "
                                    + "ownership was pending"
                            )
                        );
                    }
                    return;
                }
                applyRequestProcessingCompletionValue(request, settledOutcome, settledFailure);
            }
        );
    }

    private void applyRequestProcessingCompletionValue(
        RequestReplayOwner<P, R> request,
        RequestProcessingOutcome settledOutcome,
        Throwable settledFailure
    ) {
        assertInMailbox();
        var cancellationResult = request.processingCancellationResult();
        var cancellationWon =
            cancellationResult instanceof ProcessingCancellationResult.CancellationWon;
        var processingCompletionWon =
            cancellationResult instanceof ProcessingCancellationResult.ProcessingCompletionWon;
        if (request.processingCompletionReceived()) {
            reportImpossibleTransition(
                "duplicate request-processing completion " + request.requestId,
                new IllegalStateException("request-processing completion arrived twice")
            );
            return;
        }
        if (tupleDurablePendingConnectionAcceptance.contains(request)) {
            reportImpossibleTransition(
                "duplicate request-processing completion " + request.requestId,
                new IllegalStateException(
                    "request-processing completion arrived while tuple durability "
                        + "was pending connection-turn acceptance"
                )
            );
            return;
        }
        if (settledFailure != null) {
            request.markProcessingFailed(settledFailure);
            request.processingRegistration().failLifecycleHandling(settledFailure);
            pendingTerminationOutcome = new SessionOutcome.Failed(settledFailure);
            reportImpossibleTransition(
                "request-processing failure " + request.requestId,
                settledFailure
            );
            return;
        }
        if (settledOutcome == null) {
            var missingOutcome = new NullPointerException(
                "request processing completed without an outcome"
            );
            request.markProcessingFailed(missingOutcome);
            request.processingRegistration().failLifecycleHandling(missingOutcome);
            pendingTerminationOutcome = new SessionOutcome.Failed(missingOutcome);
            reportImpossibleTransition(
                "request-processing completion " + request.requestId,
                missingOutcome
            );
            return;
        }
        if (settledOutcome instanceof RequestProcessingOutcome.RequestCleanupFinished cleanup
            && !cancellationWon) {
            request.markProcessingFailed(cleanup.cause());
            request.processingRegistration().failLifecycleHandling(cleanup.cause());
            pendingTerminationOutcome = new SessionOutcome.Failed(cleanup.cause());
            reportImpossibleTransition(
                processingCompletionWon
                    ? "request processing reported cancellation after processing won "
                        + request.requestId
                    : "unexpected request-processing cancellation " + request.requestId,
                cleanup.cause()
            );
            return;
        }
        if (!(settledOutcome instanceof RequestProcessingOutcome.RequestCleanupFinished)
            && cancellationWon) {
            var contradictoryCompletion = new IllegalStateException(
                "request processing completed with "
                    + settledOutcome.getClass().getSimpleName()
                    + " after cancellation won for "
                    + request.requestId
            );
            request.markProcessingFailed(contradictoryCompletion);
            request.processingRegistration().failLifecycleHandling(
                contradictoryCompletion
            );
            pendingTerminationOutcome = new SessionOutcome.Failed(
                contradictoryCompletion
            );
            reportImpossibleTransition(
                "request-processing completion after cancellation "
                    + request.requestId,
                contradictoryCompletion
            );
            return;
        }
        if (settledOutcome instanceof RequestProcessingOutcome.TupleDurable
            && request.connectionTurnSettled()
            && !request.connectionTurnFinished()) {
            tupleDurablePendingConnectionAcceptance.add(request);
            return;
        }
        request.recordProcessingCompletion(settledOutcome);
        applyRequestProcessingCompletion(request);
    }

    private void applyRequestProcessingCompletion(RequestReplayOwner<P, R> request) {
        assertInMailbox();
        if (requestOwners.get(request.requestId) != request || request.processingMilestoneSubmitted()) {
            return;
        }
        var outcome = request.processingOutcome();
        if (outcome == null) {
            return;
        }
        switch (outcome) {
            case RequestProcessingOutcome.RequestCleanupFinished ignored -> {
                finishProcessingWithoutNormalMilestone(request, "cancelled");
                return;
            }
            case RequestProcessingOutcome.TupleDurable ignored -> {
                // Continue below after the connection-turn milestone is accepted.
            }
        }
        if (!request.connectionTurnFinished()) {
            if (request.connectionTurnSettled()) {
                return;
            }
            var orderingFailure = new IllegalStateException(
                "normal request processing completed before ConnectionTurnFinished"
            );
            request.markProcessingFailed(orderingFailure);
            request.processingRegistration().failLifecycleHandling(orderingFailure);
            reportImpossibleTransition(
                "request-processing completion before connection turn " + request.requestId,
                orderingFailure
            );
            return;
        }

        try {
            request.releasePrepared();
        } catch (Throwable releaseFailure) {
            request.markProcessingFailed(releaseFailure);
            request.processingRegistration().failLifecycleHandling(releaseFailure);
            pendingTerminationOutcome = new SessionOutcome.Failed(releaseFailure);
            reportImpossibleTransition(
                "final prepared-request release " + request.requestId,
                releaseFailure
            );
            return;
        }
        request.markProcessingMilestoneSubmitted();
        final CompletionStage<Void> acceptance;
        try {
            acceptance = java.util.Objects.requireNonNull(
                lifecycleSink.requestProcessingFinished(
                    request.partitionGenerationId,
                    request.requestId
                ),
                "request lifecycle sink returned no processing acceptance"
            );
        } catch (Throwable t) {
            request.processingRegistration().failLifecycleHandling(t);
            reportImpossibleTransition(
                "request-processing milestone submission " + request.requestId,
                t
            );
            return;
        }
        acceptance.whenComplete((ignored, failure) ->
            applyNowOrPost("request-processing milestone acceptance " + request.requestId, () -> {
                if (failure != null) {
                    request.processingRegistration().failLifecycleHandling(unwrap(failure));
                    reportImpossibleTransition(
                        "request-processing milestone acceptance " + request.requestId,
                        unwrap(failure)
                    );
                    return;
                }
                request.finishProcessing();
                request.processingRegistration().completeLifecycleHandling();
                removeRequestOwner(request);
            })
        );
    }

    private void finishProcessingWithoutNormalMilestone(
        RequestReplayOwner<P, R> request,
        String outcomeDescription
    ) {
        if (!request.connectionTurnCleanupComplete()
            || !request.preparationCancellationAcknowledgementSatisfied()
            || !request.processingCancellationAcknowledgementSatisfied()) {
            return;
        }
        try {
            request.releasePrepared();
        } catch (Throwable releaseFailure) {
            request.markProcessingFailed(releaseFailure);
            request.processingRegistration().failLifecycleHandling(releaseFailure);
            pendingTerminationOutcome = new SessionOutcome.Failed(releaseFailure);
            reportImpossibleTransition(
                outcomeDescription + " prepared-request release " + request.requestId,
                releaseFailure
            );
            return;
        }
        request.finishProcessing();
        request.processingRegistration().completeLifecycleHandling();
        removeRequestOwner(request);
    }

    private void cancelRequestProcessing(
        RequestReplayOwner<P, R> request,
        CancellationException cause
    ) {
        if (request.processingMilestoneSubmitted()) {
            applyRequestProcessingCompletion(request);
            return;
        }
        if (tupleDurablePendingConnectionAcceptance.contains(request)) {
            applyRequestProcessingCompletion(request);
            return;
        }
        if (request.processingCompletionReceived()) {
            applyRequestProcessingCompletion(request);
            return;
        }
        if (request.processingCancellationRequested()) {
            return;
        }
        var registration = request.processingRegistration();
        request.cancelProcessing(cause);
        if (registration == null) {
            removeRequestOwner(request);
            return;
        }
        request.beginProcessingCancellationAcknowledgement();
        try {
            observeProcessingCancellationAcceptance(
                request,
                java.util.Objects.requireNonNull(
                    registration.cancel().apply(cause),
                    "request processing cancellation returned no acknowledgement"
                )
            );
        } catch (Throwable t) {
            request.failProcessingCancellationAcknowledgement();
            registration.failLifecycleHandling(t);
            recordAbortCleanupFailure(t);
            reportImpossibleTransition(
                "request-processing cancellation " + request.requestId,
                t
            );
        }
    }

    private void observeProcessingCancellationAcceptance(
        RequestReplayOwner<P, R> request,
        CompletionStage<ProcessingCancellationResult> acknowledgement
    ) {
        acknowledgement.whenComplete((result, failure) -> {
            applyNowOrPost("request-processing cancellation acceptance " + request.requestId, () -> {
                if (requestOwners.get(request.requestId) != request) {
                    throw new IllegalStateException(
                        "request-processing cancellation acceptance arrived for an unowned request: "
                            + request.requestId
                    );
                }
                if (failure == null) {
                    if (result == null) {
                        throw new IllegalStateException(
                            "request-processing cancellation completed without a result for "
                                + request.requestId
                        );
                    }
                    request.acceptProcessingCancellationAcknowledgement(result);
                    var pendingCompletion =
                        processingCompletionsPendingCancellationDecision.remove(request);
                    if (pendingCompletion != null) {
                        applyRequestProcessingCompletionValue(
                            request,
                            pendingCompletion.outcome(),
                            pendingCompletion.failure()
                        );
                    } else if (request.processingCompletionReceived()) {
                        applyRequestProcessingCompletion(request);
                    }
                    return;
                }
                var cause = unwrap(failure);
                request.failProcessingCancellationAcknowledgement();
                request.processingRegistration().failLifecycleHandling(cause);
                recordAbortCleanupFailure(cause);
                reportImpossibleTransition(
                    "request-processing cancellation acceptance " + request.requestId,
                    cause
                );
            });
        });
    }

    private void recordAbortCleanupFailure(Throwable failure) {
        if (pendingTerminationOutcome == null) {
            abortCleanupFailure = combineFailures(abortCleanupFailure, failure);
            return;
        }
        if (pendingTerminationOutcome instanceof SessionOutcome.Failed failed) {
            pendingTerminationOutcome = new SessionOutcome.Failed(
                combineFailures(failed.cause(), failure)
            );
        } else {
            pendingTerminationOutcome = new SessionOutcome.Failed(failure);
        }
    }

    private void removeRequestOwner(RequestReplayOwner<P, R> request) {
        assertInMailbox();
        tupleDurablePendingConnectionAcceptance.remove(request);
        processingCompletionsPendingCancellationDecision.remove(request);
        if (requestOwners.remove(request.requestId, request)) {
            tryFinishTermination();
        }
    }

    private void tryFinishTermination() {
        assertInMailbox();
        if (processFatalTransition
            || pendingTerminationOutcome == null
            || !requestOwners.isEmpty()
            || !admissionCommands.isEmpty()
            || !executionCommands.isEmpty()
            || activeRequest != null
            || orderedCloseActive
            || targetAbortPending) {
            return;
        }
        finishTermination(pendingTerminationOutcome);
    }

    private Throwable cancelQueuedRequest(
        RequestCommand<P, R> request,
        CancellationException cause
    ) {
        request.owner.cancelConnectionTurn(cause);
        var cleanupFailure = cancelPreparationFailure(request);
        cleanupFailure = combineFailures(cleanupFailure, releasePreparedFailure(request));
        request.owner.completion.complete(new RequestTurnResult.Cancelled<>(cause));
        return cleanupFailure;
    }

    private Throwable cancelPreparationFailure(RequestCommand<P, R> request) {
        if (!request.owner.beginPreparationCancellation()) {
            return null;
        }
        try {
            observePreparationCancellationAcceptance(
                request.owner,
                java.util.Objects.requireNonNull(
                    request.owner.preparationController.cancel(
                        new CancellationException(
                            "request preparation cancelled for " + request.owner.requestId
                        )
                    ),
                    "request preparation cancellation returned no acknowledgement"
                )
            );
            return null;
        } catch (Throwable t) {
            request.owner.failPreparationCancellationAcknowledgement(t);
            return t;
        }
    }

    private void observePreparationCancellationAcceptance(
        RequestReplayOwner<P, R> request,
        CompletionStage<Void> acknowledgement
    ) {
        acknowledgement.whenComplete((ignored, failure) ->
            applyNowOrPost("preparation cancellation acceptance " + request.requestId, () -> {
                if (failure != null) {
                    var cause = unwrap(failure);
                    request.failPreparationCancellationAcknowledgement(cause);
                    if (request.processingRegistration() != null) {
                        request.processingRegistration().failLifecycleHandling(cause);
                    }
                    recordAbortCleanupFailure(cause);
                    reportImpossibleTransition(
                        "preparation cancellation acceptance " + request.requestId,
                        cause
                    );
                    return;
                }
                request.acceptPreparationCancellationAcknowledgement();
                if (requestOwners.get(request.requestId) == request
                    && request.processingCompletionReceived()) {
                    applyRequestProcessingCompletion(request);
                }
            })
        );
    }

    private void untrackObligation(Command<P, R> command) {
        if (obligations.remove(command)) {
            metrics.queuedCommandsChanged(-1);
        }
    }

    private void finishTermination(SessionOutcome outcome) {
        assertInMailbox();
        state = State.TERMINATED;
        setHeadWaitReason(null);
        cancelAdmissionTimer();
        cancelExecutionTimer();
        termination.complete(outcome);
    }

    private void releasePrepared(RequestCommand<P, R> request) throws Exception {
        request.owner.releasePrepared();
    }

    private void releasePreparedAfterSettledTurn(RequestCommand<P, R> request) {
        var failure = releasePreparedFailure(request);
        if (failure != null) {
            reportImpossibleTransition(
                "settled target-exchange prepared-request release " + request.owner.requestId,
                failure
            );
        }
    }

    private Throwable releasePreparedFailure(RequestCommand<P, R> request) {
        try {
            releasePrepared(request);
            return null;
        } catch (Throwable t) {
            return t;
        }
    }

    private void releaseLatePreparation(PreparationOutcome<P> preparation) {
        if (preparation instanceof PreparationOutcome.Prepared<P> prepared) {
            try {
                prepared.value().close();
            } catch (Throwable failure) {
                reportImpossibleTransition("late prepared-request release", failure);
            }
        }
    }

    private static Throwable combineFailures(Throwable first, Throwable additional) {
        if (first == null) {
            return additional;
        }
        if (additional != null && additional != first) {
            first.addSuppressed(additional);
        }
        return first;
    }

    private void cancelAdmissionTimer() {
        if (admissionTimer != null) {
            var timer = admissionTimer;
            admissionTimer = null;
            timer.cancel();
        }
    }

    private void cancelExecutionTimer() {
        if (executionTimer != null) {
            var timer = executionTimer;
            executionTimer = null;
            timer.cancel();
        }
    }

    private void setHeadWaitReason(HeadWaitReason reason) {
        if (headWaitReason == reason) {
            return;
        }
        if (headWaitReason != null) {
            metrics.headWaitChanged(headWaitReason, -1);
        }
        headWaitReason = reason;
        if (reason != null) {
            metrics.headWaitChanged(reason, 1);
        }
    }

    private void recordActiveDuration() {
        metrics.activeDuration(elapsedSince(activeStartedNanos));
    }

    private Duration elapsedSince(long startNanos) {
        return Duration.ofNanos(Math.max(0, nanoTime.getAsLong() - startNanos));
    }

    private void assertInMailbox() {
        ownerThreadGuard.requireOwnerThread();
    }

    private static Throwable unwrap(Throwable throwable) {
        var current = throwable;
        while ((current instanceof java.util.concurrent.CompletionException
            || current instanceof java.util.concurrent.ExecutionException)
            && current.getCause() != null)
        {
            current = current.getCause();
        }
        return current;
    }
}
