package org.opensearch.migrations.replay.lifecycle;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import org.opensearch.migrations.replay.lifecycle.ReplayIdentity.ConnectionSessionKey;
import org.opensearch.migrations.replay.lifecycle.ReplayIdentity.ReplayRequestId;

import lombok.NonNull;

public final class ReplayTransactionRegistry {
    private final ConnectionSessionKey sessionKey;
    private final ActorMailbox mailbox;
    private final Map<ReplayRequestId, CompletionStage<?>> active = new LinkedHashMap<>();
    private final CompletionGate<Void> termination = new CompletionGate<>();
    private boolean terminating;
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
            if (active.putIfAbsent(requestId, transactionCompletion) != null) {
                acknowledgement.completeExceptionally(
                    new IllegalStateException("transaction is already registered: " + requestId)
                );
                return;
            }
            transactionCompletion.whenComplete((ignored, failure) ->
                mailbox.execute(() -> settle(requestId, failure))
            );
            acknowledgement.complete(null);
        });
        return acknowledgement.minimalCompletionStage();
    }

    public CompletionStage<Void> beginTermination() {
        mailbox.execute(() -> {
            terminating = true;
            tryCompleteTermination();
        });
        return termination.stage();
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
        if (failure != null && firstFailure == null) {
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
