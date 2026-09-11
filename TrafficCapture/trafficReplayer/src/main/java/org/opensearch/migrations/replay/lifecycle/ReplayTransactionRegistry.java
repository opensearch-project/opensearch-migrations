package org.opensearch.migrations.replay.lifecycle;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
        private Throwable completionFailure;
        private boolean completionReady;

        private Entry(ReplayTransaction<?> transaction) {
            this.transaction = transaction;
        }
    }

    private static final class PendingRegistration {
        private final ReplayRequestId requestId;
        private final CompletionStage<?> transactionCompletion;
        private final ReplayTransaction<?> transaction;
        private final CompletableFuture<Void> acknowledgement = new CompletableFuture<>();

        private PendingRegistration(
            ReplayRequestId requestId,
            CompletionStage<?> transactionCompletion,
            ReplayTransaction<?> transaction
        ) {
            this.requestId = requestId;
            this.transactionCompletion = transactionCompletion;
            this.transaction = transaction;
        }
    }

    private static final class RegistryCommand {
        private final Runnable transition;
        private final CompletableFuture<Void> acknowledgement;
        private final boolean completeAfterTransition;

        private RegistryCommand(
            Runnable transition,
            CompletableFuture<Void> acknowledgement,
            boolean completeAfterTransition
        ) {
            this.transition = transition;
            this.acknowledgement = acknowledgement;
            this.completeAfterTransition = completeAfterTransition;
        }
    }

    private final Object stateLock = new Object();
    private final ConnectionSessionKey sessionKey;
    private final ActorMailbox mailbox;
    private final OwnerThreadGuard ownerThreadGuard;
    private final Map<ReplayRequestId, Entry> active = new LinkedHashMap<>();
    private final Set<PendingRegistration> pendingRegistrations =
        Collections.newSetFromMap(new IdentityHashMap<>());
    private final Set<RegistryCommand> pendingCommands =
        Collections.newSetFromMap(new IdentityHashMap<>());
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
        this.ownerThreadGuard = new OwnerThreadGuard(
            "replay transaction registry for " + sessionKey,
            mailbox::inMailbox
        );
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
        var pending = new PendingRegistration(requestId, transactionCompletion, transaction);
        synchronized (stateLock) {
            pendingRegistrations.add(pending);
        }
        try {
            mailbox.execute(() -> runRegistration(pending));
        } catch (RuntimeException | Error failure) {
            pending.acknowledgement.completeExceptionally(failure);
        }
        return pending.acknowledgement.minimalCompletionStage();
    }

    private void runRegistration(PendingRegistration pending) {
        synchronized (stateLock) {
            if (!pendingRegistrations.remove(pending)) {
                return;
            }
            assertInMailbox();
            try {
                if (terminating) {
                    var failure = new IllegalStateException(
                        "session is already terminating: " + sessionKey
                    );
                    pending.acknowledgement.completeExceptionally(failure);
                    failRejectedTransaction(pending.transaction, failure);
                    return;
                }
                var existing = active.get(pending.requestId);
                if (existing != null) {
                    var failure = new IllegalStateException(
                        "transaction is already registered: " + pending.requestId
                    );
                    pending.acknowledgement.completeExceptionally(failure);
                    if (pending.transaction != existing.transaction) {
                        failRejectedTransaction(pending.transaction, failure);
                    }
                    return;
                }
                var entry = new Entry(pending.transaction);
                active.put(pending.requestId, entry);
                pending.transactionCompletion.whenComplete((ignored, failure) ->
                    stageTransactionCompletion(pending.requestId, entry, failure)
                );
                if (pending.transaction != null && runwayLossReason != null) {
                    pending.transaction.observeRunwayLost(runwayLossReason);
                }
                if (cancellationCause != null) {
                    cancel(pending.requestId, pending.transaction);
                }
                pending.acknowledgement.complete(null);
            } catch (RuntimeException | Error failure) {
                pending.acknowledgement.completeExceptionally(failure);
                throw failure;
            }
        }
    }

    private void stageTransactionCompletion(
        ReplayRequestId requestId,
        Entry entry,
        Throwable failure
    ) {
        synchronized (stateLock) {
            if (active.get(requestId) != entry) {
                return;
            }
            entry.completionFailure = failure == null ? null : unwrap(failure);
            entry.completionReady = true;
        }
        mailbox.execute(() -> settleFromMailbox(requestId, entry));
    }

    private void settleFromMailbox(ReplayRequestId requestId, Entry entry) {
        synchronized (stateLock) {
            if (active.get(requestId) != entry || !entry.completionReady) {
                return;
            }
            assertInMailbox();
            settleLocked(requestId, entry.completionFailure);
        }
    }

    public CompletionStage<Void> observeRunwayLost(
        @NonNull ReplayTransaction.RunwayLossReason reason
    ) {
        var acknowledgement = new CompletableFuture<Void>();
        enqueueCommand(new RegistryCommand(
            () -> {
                if (runwayLossReason != null) {
                    acknowledgement.complete(null);
                    return;
                }
                runwayLossReason = reason;
                var acknowledgements = active.values()
                    .stream()
                    .filter(entry -> entry.transaction != null)
                    .map(entry -> entry.transaction.observeRunwayLost(reason).toCompletableFuture())
                    .toArray(CompletableFuture[]::new);
                CompletableFuture.allOf(acknowledgements)
                    .whenComplete((ignored, failure) -> {
                        if (failure == null) {
                            acknowledgement.complete(null);
                        } else {
                            acknowledgement.completeExceptionally(unwrap(failure));
                        }
                    });
            },
            acknowledgement,
            false
        ));
        return acknowledgement.minimalCompletionStage();
    }

    public CompletionStage<Void> beginTermination() {
        enqueueCommand(new RegistryCommand(
            () -> {
                terminating = true;
                log.atDebug()
                    .setMessage("Beginning transaction-registry termination for {}; active={}")
                    .addArgument(sessionKey)
                    .addArgument(active::size)
                    .log();
                tryCompleteTerminationLocked();
            },
            null,
            true
        ));
        return termination.stage();
    }

    public CompletionStage<Void> cancelOutstanding(@NonNull CancellationException cause) {
        var acknowledgement = new CompletableFuture<Void>();
        enqueueCommand(new RegistryCommand(
            () -> {
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
            },
            acknowledgement,
            true
        ));
        return acknowledgement.minimalCompletionStage();
    }

    public CompletionStage<Map<ReplayRequestId, String>> unresolvedTransactions() {
        synchronized (stateLock) {
            var snapshot = new LinkedHashMap<ReplayRequestId, String>();
            active.forEach((requestId, ignored) ->
                snapshot.put(requestId, "awaiting transaction completion")
            );
            pendingRegistrations.forEach(pending ->
                snapshot.put(pending.requestId, "awaiting registry admission")
            );
            return CompletableFuture.completedFuture(Map.copyOf(snapshot));
        }
    }

    private void enqueueCommand(RegistryCommand command) {
        synchronized (stateLock) {
            pendingCommands.add(command);
        }
        try {
            mailbox.execute(() -> runCommand(command));
        } catch (RuntimeException | Error failure) {
            if (command.acknowledgement != null) {
                command.acknowledgement.completeExceptionally(failure);
            }
        }
    }

    private void runCommand(RegistryCommand command) {
        synchronized (stateLock) {
            if (!pendingCommands.remove(command)) {
                return;
            }
            assertInMailbox();
            try {
                command.transition.run();
                if (command.completeAfterTransition && command.acknowledgement != null) {
                    command.acknowledgement.complete(null);
                }
            } catch (RuntimeException | Error failure) {
                if (command.acknowledgement != null) {
                    command.acknowledgement.completeExceptionally(failure);
                }
                throw failure;
            }
        }
    }

    private void cancel(ReplayRequestId requestId, ReplayTransaction<?> transaction) {
        assertInMailbox();
        if (transaction == null) {
            return;
        }
        transaction.fail(
            new CancellationException(
                SESSION_TERMINATED + requestId + ": " + cancellationCause.getMessage()
            )
        );
    }

    private void settleLocked(ReplayRequestId requestId, Throwable failure) {
        if (active.remove(requestId) == null) {
            return;
        }
        log.atDebug()
            .setMessage("Transaction settled during session lifecycle for {}; failure={}; remaining={}")
            .addArgument(requestId)
            .addArgument(failure)
            .addArgument(active::size)
            .log();
        recordFailureLocked(failure);
        tryCompleteTerminationLocked();
    }

    private void recordFailureLocked(Throwable failure) {
        if (failure != null
            && firstFailure == null
            && !(unwrap(failure) instanceof CancellationException)) {
            firstFailure = unwrap(failure);
        }
    }

    private void tryCompleteTerminationLocked() {
        if (!terminating
            || !active.isEmpty()
            || !pendingRegistrations.isEmpty()
            || termination.isDone()) {
            return;
        }
        if (firstFailure == null) {
            termination.complete(null);
        } else {
            termination.completeExceptionally(firstFailure);
        }
    }

    private void failRejectedTransaction(
        ReplayTransaction<?> transaction,
        Throwable registrationFailure
    ) {
        if (transaction == null) {
            return;
        }
        var cancellation = new CancellationException(
            "transaction registration was rejected: " + registrationFailure.getMessage()
        );
        cancellation.initCause(registrationFailure);
        transaction.fail(cancellation);
    }

    private void assertInMailbox() {
        ownerThreadGuard.requireOwnerThread();
    }

    private static Throwable unwrap(Throwable throwable) {
        var current = throwable;
        while ((current instanceof java.util.concurrent.CompletionException
            || current instanceof java.util.concurrent.ExecutionException)
            && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }
}
