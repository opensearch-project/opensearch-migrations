package org.opensearch.migrations.replay.lifecycle;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import org.opensearch.migrations.replay.lifecycle.ReplayIdentity.ConnectionSessionKey;
import org.opensearch.migrations.replay.lifecycle.ReplayIdentity.ReplayRequestId;

import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public final class ReplayTransactionRegistry {
    static final String SESSION_TERMINATED = "session terminated before the transaction settled: ";

    private static final class Entry {
        private final ReplayTransaction<?> transaction;

        private Entry(ReplayTransaction<?> transaction) {
            this.transaction = transaction;
        }
    }

    private final ConnectionSessionKey sessionKey;
    private final ActorMailbox mailbox;
    private final Map<ReplayRequestId, Entry> active = new LinkedHashMap<>();
    private final CompletionGate<Void> termination = new CompletionGate<>();
    private boolean terminating;
    private CancellationException cancellationCause;
    private ReplayTransaction.RunwayLossReason runwayLossReason;
    private Throwable firstFailure;

    public ReplayTransactionRegistry(
        @NonNull ConnectionSessionKey sessionKey,
        @NonNull ActorMailbox mailbox
    ) {
        this.sessionKey = sessionKey;
        this.mailbox = mailbox;
    }

    public CompletionStage<Void> register(
        @NonNull ReplayRequestId requestId,
        @NonNull CompletionStage<?> transactionCompletion
    ) {
        return register(requestId, transactionCompletion, null);
    }

    public CompletionStage<Void> register(
        @NonNull ReplayRequestId requestId,
        @NonNull ReplayTransaction<?> transaction
    ) {
        return register(requestId, transaction.completion(), transaction);
    }

    private CompletionStage<Void> register(
        ReplayRequestId requestId,
        CompletionStage<?> transactionCompletion,
        ReplayTransaction<?> transaction
    ) {
        if (!requestId.session().equals(sessionKey)) {
            throw new IllegalArgumentException("transaction belongs to a different session");
        }
        var acknowledgement = new CompletableFuture<Void>();
        mailbox.execute(() -> {
            if (terminating) {
                acknowledgement.completeExceptionally(
                    new IllegalStateException("session is already terminating: " + sessionKey)
                );
                return;
            }
            if (active.putIfAbsent(requestId, new Entry(transaction)) != null) {
                acknowledgement.completeExceptionally(
                    new IllegalStateException("transaction is already registered: " + requestId)
                );
                return;
            }
            transactionCompletion.whenComplete((ignored, failure) ->
                mailbox.execute(() -> settle(requestId, failure))
            );
            if (transaction != null && runwayLossReason != null) {
                transaction.observeRunwayLost(runwayLossReason);
            }
            if (cancellationCause != null) {
                cancel(requestId, transaction);
            }
            acknowledgement.complete(null);
        });
        return acknowledgement.minimalCompletionStage();
    }

    public CompletionStage<Void> observeRunwayLost(@NonNull ReplayTransaction.RunwayLossReason reason) {
        var completion = new CompletableFuture<Void>();
        mailbox.execute(() -> {
            if (runwayLossReason != null) {
                completion.complete(null);
                return;
            }
            runwayLossReason = reason;
            var acknowledgements = active.values()
                .stream()
                .filter(entry -> entry.transaction != null)
                .map(entry -> entry.transaction.observeRunwayLost(reason).toCompletableFuture())
                .toArray(CompletableFuture[]::new);
            CompletableFuture.allOf(acknowledgements)
                .whenComplete((ignored, failure) ->
                    mailbox.execute(() -> {
                        if (failure == null) {
                            completion.complete(null);
                        } else {
                            completion.completeExceptionally(unwrap(failure));
                        }
                    })
                );
        });
        return completion.minimalCompletionStage();
    }

    public CompletionStage<Void> beginTermination() {
        mailbox.execute(() -> {
            terminating = true;
            log.atDebug()
                .setMessage("Beginning transaction-registry termination for {}; active={}")
                .addArgument(sessionKey)
                .addArgument(active::size)
                .log();
            tryCompleteTermination();
        });
        return termination.stage();
    }

    /**
     * Cancels the transactions still waiting to settle.  A transaction settles only once both its source
     * and target sides have reported, so when the machinery behind one of those sides has stopped -- a
     * shutdown, an abort, a source reassignment -- waiting for it would wait forever.  Cancellation
     * retains the records, which is always the safe outcome, and the reason is remembered so that a
     * transaction registered after this point is cancelled too rather than stranding the session.
     */
    public CompletionStage<Void> cancelOutstanding(@NonNull CancellationException cause) {
        var acknowledgement = new CompletableFuture<Void>();
        mailbox.execute(() -> {
            if (cancellationCause == null) {
                cancellationCause = cause;
            }
            if (!active.isEmpty()) {
                log.atInfo()
                    .setMessage("Cancelling {} unsettled transaction(s) for {}: {}")
                    .addArgument(active::size)
                    .addArgument(sessionKey)
                    .addArgument(cause::getMessage)
                    .log();
            }
            for (var entry : List.copyOf(active.entrySet())) {
                cancel(entry.getKey(), entry.getValue().transaction);
            }
            acknowledgement.complete(null);
        });
        return acknowledgement.minimalCompletionStage();
    }

    private void cancel(ReplayRequestId requestId, ReplayTransaction<?> transaction) {
        assertInMailbox();
        if (transaction == null) {
            return;
        }
        transaction.fail(
            new CancellationException(SESSION_TERMINATED + requestId + ": " + cancellationCause.getMessage())
        );
    }

    public CompletionStage<Map<ReplayRequestId, String>> unresolvedTransactions() {
        var completion = new CompletableFuture<Map<ReplayRequestId, String>>();
        mailbox.execute(() -> {
            var snapshot = new LinkedHashMap<ReplayRequestId, String>();
            active.forEach((requestId, ignored) -> snapshot.put(requestId, "awaiting transaction completion"));
            completion.complete(Map.copyOf(snapshot));
        });
        return completion.minimalCompletionStage();
    }

    private void settle(ReplayRequestId requestId, Throwable failure) {
        assertInMailbox();
        if (active.remove(requestId) == null) {
            return;
        }
        log.atDebug()
            .setMessage("Transaction settled during session lifecycle for {}; failure={}; remaining={}")
            .addArgument(requestId)
            .addArgument(failure)
            .addArgument(active::size)
            .log();
        // A cancelled transaction retained its records, which is the safe outcome; it is the expected
        // way an in-flight transaction ends when its session terminates, so it must not be reported as
        // a session failure.
        if (failure != null && firstFailure == null && !(unwrap(failure) instanceof CancellationException)) {
            firstFailure = unwrap(failure);
        }
        tryCompleteTermination();
    }

    private void tryCompleteTermination() {
        assertInMailbox();
        if (!terminating || !active.isEmpty() || termination.isDone()) {
            return;
        }
        if (firstFailure == null) {
            termination.complete(null);
        } else {
            termination.completeExceptionally(firstFailure);
        }
    }

    private void assertInMailbox() {
        if (!mailbox.inMailbox()) {
            throw new IllegalStateException("transaction registry transition ran outside its mailbox");
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
