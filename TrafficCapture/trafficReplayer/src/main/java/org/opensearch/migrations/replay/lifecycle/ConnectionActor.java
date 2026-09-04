package org.opensearch.migrations.replay.lifecycle;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import org.opensearch.migrations.replay.lifecycle.ReplayIdentity.ConnectionSessionKey;
import org.opensearch.migrations.replay.lifecycle.ReplayIdentity.ReplayRequestId;
import org.opensearch.migrations.replay.lifecycle.ReplayOutcomes.PreparationOutcome;
import org.opensearch.migrations.replay.lifecycle.ReplayOutcomes.SessionOutcome;
import org.opensearch.migrations.replay.lifecycle.ReplayOutcomes.TargetOutcome;

import lombok.NonNull;

public final class ConnectionActor<P extends AutoCloseable, R> {
    public interface TargetExchange<P, R> {
        CompletionStage<TargetOutcome<R>> execute(P preparedRequest);

        CompletionStage<Void> close();

        CompletionStage<Void> abort(CancellationException cause);
    }

    private enum State {
        OPEN,
        ACTIVE,
        ORDERED_CLOSING,
        ABORTING,
        TERMINATED
    }

    private sealed interface Command<P, R> permits RequestCommand, CloseCommand {
        Instant scheduledStart();

        boolean due();

        void markDue();
    }

    private static final class RequestCommand<P, R> implements Command<P, R> {
        private final ReplayRequestId requestId;
        private final Instant scheduledStart;
        private final CompletionGate<TargetOutcome<R>> completion = new CompletionGate<>();
        private CompletableFuture<PreparationOutcome<P>> preparationCompletion;
        private PreparationOutcome<P> preparation;
        private boolean due;
        private boolean settled;
        private boolean preparedReleased;

        private RequestCommand(ReplayRequestId requestId, Instant scheduledStart) {
            this.requestId = requestId;
            this.scheduledStart = scheduledStart;
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
        private final Instant scheduledStart;
        private final CompletionGate<SessionOutcome> completion = new CompletionGate<>();
        private boolean due;

        private CloseCommand(Instant scheduledStart) {
            this.scheduledStart = scheduledStart;
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
    private final TargetExchange<P, R> targetExchange;
    private final Deque<Command<P, R>> commands = new ArrayDeque<>();
    private final CompletionGate<SessionOutcome> termination = new CompletionGate<>();
    private ActorMailbox.ScheduledTask headTimer;
    private RequestCommand<P, R> activeRequest;
    private State state = State.OPEN;

    public ConnectionActor(
        @NonNull ConnectionSessionKey sessionKey,
        @NonNull ActorMailbox mailbox,
        @NonNull TargetExchange<P, R> targetExchange
    ) {
        this.sessionKey = sessionKey;
        this.mailbox = mailbox;
        this.targetExchange = targetExchange;
    }

    public ConnectionSessionKey sessionKey() {
        return sessionKey;
    }

    public CompletionStage<TargetOutcome<R>> admitRequest(
        @NonNull ReplayRequestId requestId,
        @NonNull Instant scheduledStart,
        @NonNull CompletionStage<PreparationOutcome<P>> preparation
    ) {
        if (!requestId.session().equals(sessionKey)) {
            throw new IllegalArgumentException("request belongs to a different session");
        }
        var command = new RequestCommand<P, R>(requestId, scheduledStart);
        command.preparationCompletion = preparation.toCompletableFuture();
        mailbox.execute(() -> admit(command));
        preparation.whenComplete((outcome, failure) ->
            mailbox.execute(() -> onPreparationSettled(command, outcome, failure))
        );
        return command.completion.stage();
    }

    public CompletionStage<SessionOutcome> admitClose(@NonNull Instant scheduledStart) {
        var command = new CloseCommand<P, R>(scheduledStart);
        mailbox.execute(() -> admit(command));
        return command.completion.stage();
    }

    public CompletionStage<SessionOutcome> abort(@NonNull CancellationException cause) {
        mailbox.execute(() -> beginAbort(cause));
        return termination.stage();
    }

    public CompletionStage<SessionOutcome> termination() {
        return termination.stage();
    }

    private void admit(Command<P, R> command) {
        assertInMailbox();
        if (state != State.OPEN && state != State.ACTIVE) {
            rejectLateCommand(command);
            return;
        }
        if (command instanceof CloseCommand<P, R>) {
            state = State.ORDERED_CLOSING;
        }
        commands.addLast(command);
        if (commands.peekFirst() == command) {
            startHead();
        }
    }

    private void rejectLateCommand(Command<P, R> command) {
        var cause = new CancellationException("session is no longer accepting work: " + sessionKey);
        if (command instanceof RequestCommand<P, R> request) {
            request.settled = true;
            request.preparationCompletion.cancel(false);
            request.completion.complete(new TargetOutcome.Cancelled<>(cause));
        } else if (command instanceof CloseCommand<P, R> close) {
            close.completion.complete(new SessionOutcome.Aborted(cause));
        }
    }

    private void onPreparationSettled(
        RequestCommand<P, R> command,
        PreparationOutcome<P> outcome,
        Throwable failure
    ) {
        assertInMailbox();
        var normalized = failure == null
            ? outcome
            : new PreparationOutcome.Failed<P>(unwrap(failure));
        if (normalized == null) {
            normalized = new PreparationOutcome.Failed<>(
                new NullPointerException("preparation completed without an outcome")
            );
        }
        if (command.settled || state == State.TERMINATED || state == State.ABORTING) {
            command.preparation = normalized;
            releasePreparedQuietly(command);
            return;
        }
        command.preparation = normalized;
        if (commands.peekFirst() == command) {
            tryRunHead();
        }
    }

    private void startHead() {
        assertInMailbox();
        cancelHeadTimer();
        var head = commands.peekFirst();
        if (head == null) {
            return;
        }
        var delay = Duration.between(mailbox.now(), head.scheduledStart());
        if (delay.isNegative() || delay.isZero()) {
            head.markDue();
            tryRunHead();
        } else {
            headTimer = mailbox.schedule(() -> {
                assertInMailbox();
                if (commands.peekFirst() == head) {
                    head.markDue();
                    headTimer = null;
                    tryRunHead();
                }
            }, delay);
        }
    }

    private void tryRunHead() {
        assertInMailbox();
        if (activeRequest != null || state == State.ABORTING || state == State.TERMINATED) {
            return;
        }
        var head = commands.peekFirst();
        if (head == null || !head.due()) {
            return;
        }
        if (head instanceof RequestCommand<P, R> request) {
            if (request.preparation == null) {
                return;
            }
            handlePreparedRequest(request);
        } else if (head instanceof CloseCommand<P, R> close) {
            runOrderedClose(close);
        }
    }

    private void handlePreparedRequest(RequestCommand<P, R> request) {
        request.preparation.visit(new PreparationOutcome.Visitor<>() {
            @Override
            public Void onPrepared(PreparationOutcome.Prepared<P> outcome) {
                runTargetExchange(request, outcome.value());
                return null;
            }

            @Override
            public Void onFiltered(PreparationOutcome.Filtered<P> outcome) {
                settleRequest(request, new TargetOutcome.Filtered<>(outcome.reason()));
                return null;
            }

            @Override
            public Void onFailed(PreparationOutcome.Failed<P> outcome) {
                settleRequest(request, new TargetOutcome.Failed<>(outcome.cause()));
                return null;
            }

            @Override
            public Void onCancelled(PreparationOutcome.Cancelled<P> outcome) {
                beginAbort(outcome.cause());
                return null;
            }
        });
    }

    private void runTargetExchange(RequestCommand<P, R> request, P preparedRequest) {
        state = State.ACTIVE;
        activeRequest = request;
        CompletionStage<TargetOutcome<R>> exchange;
        try {
            exchange = targetExchange.execute(preparedRequest);
        } catch (Throwable t) {
            closePreparedAndSettle(request, new TargetOutcome.Failed<>(t));
            return;
        }
        exchange.whenComplete((outcome, failure) ->
            mailbox.execute(() -> {
                if (request.settled) {
                    releasePreparedQuietly(request);
                    return;
                }
                var normalized = failure == null
                    ? outcome
                    : new TargetOutcome.Failed<R>(unwrap(failure));
                if (normalized == null) {
                    normalized = new TargetOutcome.Failed<>(
                        new NullPointerException("target exchange completed without an outcome")
                    );
                }
                if (normalized instanceof TargetOutcome.Cancelled<R> cancelled) {
                    releasePreparedQuietly(request);
                    beginAbort(cancelled.cause());
                } else {
                    closePreparedAndSettle(request, normalized);
                }
            })
        );
    }

    private void closePreparedAndSettle(
        RequestCommand<P, R> request,
        TargetOutcome<R> outcome
    ) {
        try {
            releasePrepared(request);
            settleRequest(request, outcome);
        } catch (Exception e) {
            settleRequest(request, new TargetOutcome.Failed<>(e));
        }
    }

    private void settleRequest(RequestCommand<P, R> request, TargetOutcome<R> outcome) {
        assertInMailbox();
        if (request.settled) {
            return;
        }
        request.settled = true;
        activeRequest = null;
        if (commands.peekFirst() != request) {
            throw new IllegalStateException("settled request was not the actor head");
        }
        commands.removeFirst();
        if (state == State.ACTIVE) {
            state = State.OPEN;
        }
        startHead();
        mailbox.execute(() -> request.completion.complete(outcome));
    }

    private void runOrderedClose(CloseCommand<P, R> close) {
        assertInMailbox();
        CompletionStage<Void> closeStage;
        try {
            closeStage = targetExchange.close();
        } catch (Throwable t) {
            closeStage = CompletableFuture.failedFuture(t);
        }
        closeStage.whenComplete((ignored, failure) ->
            mailbox.execute(() -> {
                var outcome = failure == null
                    ? new SessionOutcome.Closed()
                    : new SessionOutcome.Failed(unwrap(failure));
                close.completion.complete(outcome);
                commands.removeFirst();
                finishTermination(outcome);
            })
        );
    }

    private void beginAbort(CancellationException cause) {
        assertInMailbox();
        if (state == State.TERMINATED || state == State.ABORTING) {
            return;
        }
        state = State.ABORTING;
        cancelHeadTimer();
        for (var command : commands) {
            if (command instanceof RequestCommand<P, R> request && request != activeRequest) {
                cancelQueuedRequest(request, cause);
            } else if (command instanceof CloseCommand<P, R> close) {
                close.completion.complete(new SessionOutcome.Aborted(cause));
            }
        }

        CompletionStage<Void> abortStage;
        try {
            abortStage = targetExchange.abort(cause);
        } catch (Throwable t) {
            abortStage = CompletableFuture.failedFuture(t);
        }
        abortStage.whenComplete((ignored, failure) ->
            mailbox.execute(() -> {
                if (activeRequest != null) {
                    activeRequest.settled = true;
                    activeRequest.completion.complete(new TargetOutcome.Cancelled<>(cause));
                    releasePreparedQuietly(activeRequest);
                    activeRequest = null;
                }
                commands.clear();
                finishTermination(
                    failure == null
                        ? new SessionOutcome.Aborted(cause)
                        : new SessionOutcome.Failed(unwrap(failure))
                );
            })
        );
    }

    private void cancelQueuedRequest(RequestCommand<P, R> request, CancellationException cause) {
        request.settled = true;
        request.preparationCompletion.cancel(false);
        request.completion.complete(new TargetOutcome.Cancelled<>(cause));
        releasePreparedQuietly(request);
    }

    private void finishTermination(SessionOutcome outcome) {
        assertInMailbox();
        state = State.TERMINATED;
        cancelHeadTimer();
        termination.complete(outcome);
    }

    private void releasePrepared(RequestCommand<P, R> request) throws Exception {
        if (!request.preparedReleased
            && request.preparation instanceof PreparationOutcome.Prepared<P> prepared)
        {
            request.preparedReleased = true;
            prepared.value().close();
        }
    }

    private void releasePreparedQuietly(RequestCommand<P, R> request) {
        try {
            releasePrepared(request);
        } catch (Exception ignored) {
            // The owning operation has already reached a stronger terminal outcome.
        }
    }

    private void cancelHeadTimer() {
        if (headTimer != null) {
            headTimer.cancel();
            headTimer = null;
        }
    }

    private void assertInMailbox() {
        if (!mailbox.inMailbox()) {
            throw new IllegalStateException("connection actor transition ran outside its mailbox");
        }
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
