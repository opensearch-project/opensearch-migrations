package org.opensearch.migrations.replay.lifecycle;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicInteger;

import org.opensearch.migrations.replay.lifecycle.ReplayIdentity.ConnectionSessionKey;
import org.opensearch.migrations.replay.lifecycle.ReplayIdentity.ReplayRequestId;
import org.opensearch.migrations.replay.lifecycle.ReplayIdentity.SourceConnectionKey;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class ReplayTransactionRegistryTest {
    @Test
    void rememberedRunwayLossReachesLateTransactionsExactlyOnce() {
        var mailbox = new DeterministicMailbox();
        var registry = new ReplayTransactionRegistry(session(), mailbox);
        registry.observeRunwayLost(ReplayTransaction.RunwayLossReason.SOURCE_REASSIGNMENT);
        registry.observeRunwayLost(ReplayTransaction.RunwayLossReason.SHUTDOWN);
        mailbox.runUntilIdle();

        var runwayLosses = new AtomicInteger();
        var transaction = new ReplayTransaction<String>(
            request(0),
            mailbox,
            (id, source, target) -> CompletableFuture.completedFuture(
                new ReplayOutcomes.EvidenceOutcome.Durable("unused")
            ),
            new ReplayDispositionPolicy(),
            new RecordDispositionLedger(Runnable::run),
            java.util.List.of(),
            java.util.List.of(),
            new ReplayTransaction.Metrics() {
                @Override
                public void phaseChanged(ReplayTransaction.Phase phase, int delta) {}

                @Override
                public void runwayStateChanged(ReplayTransaction.RunwayState state, int delta) {}

                @Override
                public void runwayLost(ReplayTransaction.RunwayLossReason reason) {
                    Assertions.assertTrue(mailbox.inMailbox());
                    Assertions.assertEquals(
                        ReplayTransaction.RunwayLossReason.SOURCE_REASSIGNMENT,
                        reason
                    );
                    runwayLosses.incrementAndGet();
                }

                @Override
                public void terminalOutcome(ReplayTransaction.TerminalOutcome outcome) {}

                @Override
                public void disposition(RecordDisposition disposition) {}
            }
        );

        registry.register(request(0), transaction);
        mailbox.runUntilIdle();

        Assertions.assertEquals(1, runwayLosses.get());
    }

    @Test
    void terminationWaitsForEveryRegisteredTransactionAndRejectsLateRegistration() {
        var mailbox = new DeterministicMailbox();
        var registry = new ReplayTransactionRegistry(session(), mailbox);
        var first = new CompletableFuture<Void>();
        var second = new CompletableFuture<Void>();
        registry.register(request(0), first);
        registry.register(request(1), second);
        mailbox.runUntilIdle();

        var termination = registry.beginTermination().toCompletableFuture();
        termination.cancel(false);
        mailbox.runUntilIdle();
        Assertions.assertFalse(registry.beginTermination().toCompletableFuture().isDone());
        Assertions.assertEquals(
            2,
            unresolved(registry, mailbox).size()
        );

        first.complete(null);
        mailbox.runUntilIdle();
        Assertions.assertFalse(registry.beginTermination().toCompletableFuture().isDone());

        second.complete(null);
        mailbox.runUntilIdle();
        Assertions.assertDoesNotThrow(() -> registry.beginTermination().toCompletableFuture().join());
        Assertions.assertTrue(unresolved(registry, mailbox).isEmpty());

        var late = registry.register(request(2), CompletableFuture.completedFuture(null));
        mailbox.runUntilIdle();
        Assertions.assertThrows(CompletionException.class, () -> late.toCompletableFuture().join());
    }

    @Test
    void lateTypedRegistrationReleasesItsOwnedRecordAndResource() {
        var mailbox = new DeterministicMailbox();
        var ledger = new RecordDispositionLedger(Runnable::run);
        var registry = new ReplayTransactionRegistry(session(), mailbox);
        registry.beginTermination();
        mailbox.runUntilIdle();
        var resource = new CloseWatchingResource();
        var record = new RetentionWatchingHandle(
            new ReplayIdentity.KafkaRecordId("topic", 0, 26, 1)
        );
        var transaction = transaction(mailbox, ledger, resource, record);
        mailbox.runUntilIdle();

        var registration = registry.register(request(1), transaction).toCompletableFuture();
        mailbox.runUntilIdle();

        Assertions.assertTrue(registration.isCompletedExceptionally());
        Assertions.assertTrue(
            transaction.completion().toCompletableFuture().isCompletedExceptionally(),
            "a late typed registration must not retain untracked transaction ownership"
        );
        Assertions.assertEquals(1, resource.closes.get());
        Assertions.assertEquals(1, record.releasesWithoutCommit.get());
        Assertions.assertEquals(0, record.commits.get());
    }

    @Test
    void duplicateTypedRegistrationReleasesTheRejectedTransaction() {
        var mailbox = new DeterministicMailbox();
        var ledger = new RecordDispositionLedger(Runnable::run);
        var registry = new ReplayTransactionRegistry(session(), mailbox);
        var existing = new CompletableFuture<Void>();
        registry.register(request(1), existing);
        mailbox.runUntilIdle();
        var resource = new CloseWatchingResource();
        var record = new RetentionWatchingHandle(
            new ReplayIdentity.KafkaRecordId("topic", 0, 27, 1)
        );
        var transaction = transaction(mailbox, ledger, resource, record);
        mailbox.runUntilIdle();

        var registration = registry.register(request(1), transaction).toCompletableFuture();
        mailbox.runUntilIdle();

        Assertions.assertTrue(registration.isCompletedExceptionally());
        Assertions.assertTrue(
            transaction.completion().toCompletableFuture().isCompletedExceptionally(),
            "a duplicate typed registration must release the rejected transaction"
        );
        Assertions.assertEquals(1, resource.closes.get());
        Assertions.assertEquals(1, record.releasesWithoutCommit.get());
        Assertions.assertEquals(0, record.commits.get());
        existing.complete(null);
        mailbox.runUntilIdle();
    }

    @Test
    void duplicateRegistrationOfTheSameTransactionDoesNotTerminateTheOwnedInstance() {
        var mailbox = new DeterministicMailbox();
        var registry = new ReplayTransactionRegistry(session(), mailbox);
        var transaction = new ReplayTransaction<String>(
            request(1),
            mailbox,
            (id, source, target) -> CompletableFuture.completedFuture(
                new ReplayOutcomes.EvidenceOutcome.Durable("unused")
            ),
            new ReplayDispositionPolicy(),
            new RecordDispositionLedger(Runnable::run),
            java.util.List.of(),
            java.util.List.of(),
            ReplayTransaction.Metrics.NOOP
        );
        mailbox.runUntilIdle();
        registry.register(request(1), transaction);
        mailbox.runUntilIdle();

        var duplicate = registry.register(request(1), transaction).toCompletableFuture();
        mailbox.runUntilIdle();

        Assertions.assertTrue(duplicate.isCompletedExceptionally());
        Assertions.assertFalse(
            transaction.completion().toCompletableFuture().isDone(),
            "rejecting a duplicate handle must not terminate the transaction already owned by the registry"
        );

        transaction.settleSource(new ReplayOutcomes.SourceOutcome.Interrupted("shutdown"));
        transaction.settleTarget(
            new ReplayOutcomes.TargetOutcome.Cancelled<>(new CancellationException("shutdown"))
        );
        mailbox.runUntilIdle();
        Assertions.assertDoesNotThrow(() -> transaction.completion().toCompletableFuture().join());
    }

    @Test
    void transactionFailurePropagatesOnlyAfterTheRegistryDrains() {
        var mailbox = new DeterministicMailbox();
        var registry = new ReplayTransactionRegistry(session(), mailbox);
        var failed = new CompletableFuture<Void>();
        var stillRunning = new CompletableFuture<Void>();
        registry.register(request(0), failed);
        registry.register(request(1), stillRunning);
        mailbox.runUntilIdle();
        var termination = registry.beginTermination();
        mailbox.runUntilIdle();

        failed.completeExceptionally(new IllegalStateException("disposition failed"));
        mailbox.runUntilIdle();
        Assertions.assertFalse(termination.toCompletableFuture().isDone());

        stillRunning.complete(null);
        mailbox.runUntilIdle();
        var error = Assertions.assertThrows(
            CompletionException.class,
            () -> termination.toCompletableFuture().join()
        );
        Assertions.assertEquals("disposition failed", error.getCause().getMessage());
    }

    /**
     * The hang this guards against: a transaction settles only once both of its sides have reported, so
     * an aborted session leaves the ones it was running unsettleable.  Waiting for them would keep the
     * connection actor, and with it the whole replay shutdown, alive forever.
     */
    @Test
    void cancellingOutstandingTransactionsLetsAnAbortedSessionTerminate() {
        var mailbox = new DeterministicMailbox();
        var ledger = new RecordDispositionLedger(Runnable::run);
        var registry = new ReplayTransactionRegistry(session(), mailbox);
        var stranded = new ReplayTransaction<String>(
            request(1),
            mailbox,
            (id, source, target) -> CompletableFuture.completedFuture(
                new ReplayOutcomes.EvidenceOutcome.Durable("unused")
            ),
            new ReplayDispositionPolicy(),
            ledger,
            java.util.List.of(),
            java.util.List.of(),
            ReplayTransaction.Metrics.NOOP
        );
        var strandedHandle = new RetentionWatchingHandle(
            new ReplayIdentity.KafkaRecordId("topic", 0, 6, 1)
        );
        ledger.register(strandedHandle, stranded.ledgerOwner()).toCompletableFuture().join();
        registry.register(request(1), stranded);
        // Only the source side reports, so the transaction owns the record; termination is what makes
        // the target side unreachable.
        stranded.settleSource(
            new ReplayOutcomes.SourceOutcome.Complete(),
            java.util.List.of(strandedHandle.id())
        );
        mailbox.runUntilIdle();
        Assertions.assertFalse(stranded.completion().toCompletableFuture().isDone());

        // Termination alone waits, because a session that closed cleanly still has intake behind it.
        var patientTermination = registry.beginTermination().toCompletableFuture();
        mailbox.runUntilIdle();
        Assertions.assertFalse(patientTermination.isDone());

        registry.cancelOutstanding(new java.util.concurrent.CancellationException("replay is shutting down"));
        var termination = registry.beginTermination().toCompletableFuture();
        mailbox.runUntilIdle();

        Assertions.assertTrue(termination.isDone(), "termination must not wait for an unreachable side");
        Assertions.assertDoesNotThrow(termination::join, "a cancelled transaction is not a session failure");
        var completionError = Assertions.assertThrows(
            CompletionException.class,
            () -> stranded.completion().toCompletableFuture().join()
        );
        Assertions.assertInstanceOf(java.util.concurrent.CancellationException.class, completionError.getCause());
        Assertions.assertEquals(0, strandedHandle.commits.get(), "a cancelled transaction cannot commit");
        Assertions.assertEquals(1, strandedHandle.contextCloses.get());
        Assertions.assertTrue(unresolved(registry, mailbox).isEmpty());
    }

    @Test
    void rejectedRegistrationCancelsTheTrackedTransactionAndRetainsItsRecord() {
        var mailbox = new DeterministicMailbox();
        var ledger = new RecordDispositionLedger(Runnable::run);
        var registry = new ReplayTransactionRegistry(session(), mailbox);
        var resource = new CloseWatchingResource();
        var record = new RetentionWatchingHandle(
            new ReplayIdentity.KafkaRecordId("topic", 0, 22, 1)
        );
        var transaction = transaction(mailbox, ledger, resource, record);
        mailbox.runUntilIdle();
        mailbox.rejectNewTasks();

        var registration = registry.register(request(1), transaction).toCompletableFuture();

        Assertions.assertTrue(registration.isCompletedExceptionally());
        Assertions.assertTrue(
            transaction.completion().toCompletableFuture().isCompletedExceptionally(),
            "a rejected registration must not leave the transaction's completion gate unresolved"
        );
        Assertions.assertDoesNotThrow(
            () -> registry.terminateAfterMailboxLoss(new CancellationException("mailbox stopped"))
                .toCompletableFuture()
                .join()
        );
        Assertions.assertEquals(1, resource.closes.get());
        Assertions.assertEquals(1, record.releasesWithoutCommit.get());
        Assertions.assertEquals(0, record.commits.get());
    }

    @Test
    void droppedRegistrationIsSweptAndItsAcknowledgementSettles() {
        var mailbox = new DeterministicMailbox();
        var ledger = new RecordDispositionLedger(Runnable::run);
        var registry = new ReplayTransactionRegistry(session(), mailbox);
        var resource = new CloseWatchingResource();
        var record = new RetentionWatchingHandle(
            new ReplayIdentity.KafkaRecordId("topic", 0, 23, 1)
        );
        var transaction = transaction(mailbox, ledger, resource, record);
        mailbox.runUntilIdle();

        var registration = registry.register(request(1), transaction).toCompletableFuture();
        mailbox.dropQueuedTasks();
        registry.terminateAfterMailboxLoss(new CancellationException("mailbox stopped"))
            .toCompletableFuture()
            .join();

        Assertions.assertTrue(registration.isCompletedExceptionally());
        Assertions.assertTrue(transaction.completion().toCompletableFuture().isCompletedExceptionally());
        Assertions.assertEquals(1, resource.closes.get());
        Assertions.assertEquals(1, record.releasesWithoutCommit.get());
        Assertions.assertEquals(0, record.commits.get());
    }

    @Test
    void droppedTransactionCompletionCallbackStillDrainsEmergencyTermination() {
        var mailbox = new DeterministicMailbox();
        var registry = new ReplayTransactionRegistry(session(), mailbox);
        var transactionCompletion = new CompletableFuture<Void>();
        registry.register(request(0), transactionCompletion);
        mailbox.runUntilIdle();

        transactionCompletion.complete(null);
        Assertions.assertEquals(1, mailbox.queuedTaskCount());
        mailbox.dropQueuedTasks();

        Assertions.assertDoesNotThrow(
            () -> registry.terminateAfterMailboxLoss(new CancellationException("mailbox stopped"))
                .toCompletableFuture()
                .join()
        );
    }

    @Test
    void rejectedTransactionCompletionCallbackTriggersEmergencyDrain() {
        var mailbox = new DeterministicMailbox();
        var registry = new ReplayTransactionRegistry(session(), mailbox);
        var transactionCompletion = new CompletableFuture<Void>();
        registry.register(request(0), transactionCompletion);
        mailbox.runUntilIdle();
        mailbox.rejectNewTasks();

        transactionCompletion.complete(null);

        Assertions.assertDoesNotThrow(
            () -> registry.terminateAfterMailboxLoss(new CancellationException("mailbox stopped"))
                .toCompletableFuture()
                .join()
        );
    }

    @Test
    void droppedCancellationAndTerminationCommandsAreCompletedByEmergencyTermination() {
        var mailbox = new DeterministicMailbox();
        var ledger = new RecordDispositionLedger(Runnable::run);
        var registry = new ReplayTransactionRegistry(session(), mailbox);
        var resource = new CloseWatchingResource();
        var record = new RetentionWatchingHandle(
            new ReplayIdentity.KafkaRecordId("topic", 0, 24, 1)
        );
        var transaction = transaction(mailbox, ledger, resource, record);
        mailbox.runUntilIdle();
        registry.register(request(1), transaction);
        mailbox.runUntilIdle();

        var cancellation = registry.cancelOutstanding(
            new CancellationException("session stopping")
        ).toCompletableFuture();
        var termination = registry.beginTermination().toCompletableFuture();
        mailbox.dropQueuedTasks();

        registry.terminateAfterMailboxLoss(new CancellationException("mailbox stopped"))
            .toCompletableFuture()
            .join();

        Assertions.assertTrue(cancellation.isDone());
        Assertions.assertTrue(termination.isDone());
        Assertions.assertDoesNotThrow(termination::join);
        Assertions.assertTrue(transaction.completion().toCompletableFuture().isCompletedExceptionally());
        Assertions.assertEquals(1, resource.closes.get());
        Assertions.assertEquals(1, record.releasesWithoutCommit.get());
    }

    @Test
    void rejectedCancellationCommandTriggersEmergencyTermination() {
        var mailbox = new DeterministicMailbox();
        var ledger = new RecordDispositionLedger(Runnable::run);
        var registry = new ReplayTransactionRegistry(session(), mailbox);
        var resource = new CloseWatchingResource();
        var record = new RetentionWatchingHandle(
            new ReplayIdentity.KafkaRecordId("topic", 0, 25, 1)
        );
        var transaction = transaction(mailbox, ledger, resource, record);
        mailbox.runUntilIdle();
        registry.register(request(1), transaction);
        mailbox.runUntilIdle();
        mailbox.rejectNewTasks();

        var cancellation = registry.cancelOutstanding(
            new CancellationException("session stopping")
        ).toCompletableFuture();

        Assertions.assertTrue(cancellation.isDone());
        Assertions.assertDoesNotThrow(
            () -> registry.terminateAfterMailboxLoss(new CancellationException("mailbox stopped"))
                .toCompletableFuture()
                .join()
        );
        Assertions.assertTrue(transaction.completion().toCompletableFuture().isCompletedExceptionally());
        Assertions.assertEquals(1, resource.closes.get());
        Assertions.assertEquals(1, record.releasesWithoutCommit.get());
    }

    private static ReplayTransaction<String> transaction(
        ActorMailbox mailbox,
        RecordDispositionLedger ledger,
        AutoCloseable resource,
        RetentionWatchingHandle record
    ) {
        var transaction = new ReplayTransaction<String>(
            request(1),
            mailbox,
            (id, source, target) -> CompletableFuture.completedFuture(
                new ReplayOutcomes.EvidenceOutcome.Durable("unused")
            ),
            new ReplayDispositionPolicy(),
            ledger,
            java.util.List.of(record.id()),
            java.util.List.of(resource),
            ReplayTransaction.Metrics.NOOP
        );
        ledger.register(record, transaction.ledgerOwner()).toCompletableFuture().join();
        return transaction;
    }

    private static final class CloseWatchingResource implements AutoCloseable {
        private final AtomicInteger closes = new AtomicInteger();

        @Override
        public void close() {
            closes.incrementAndGet();
        }
    }

    private static final class RetentionWatchingHandle implements RecordDispositionLedger.RecordHandle {
        private final ReplayIdentity.KafkaRecordId id;
        private final AtomicInteger commits = new AtomicInteger();
        private final AtomicInteger contextCloses = new AtomicInteger();
        private final AtomicInteger releasesWithoutCommit = new AtomicInteger();

        private RetentionWatchingHandle(ReplayIdentity.KafkaRecordId id) {
            this.id = id;
        }

        @Override
        public ReplayIdentity.KafkaRecordId id() {
            return id;
        }

        @Override
        public ReplayIdentity.SourcePartitionKey sourcePartition() {
            return new ReplayIdentity.SourcePartitionKey(id.topic(), id.partition(), id.sourceGeneration());
        }

        @Override
        public void closeContext() {
            contextCloses.incrementAndGet();
        }

        @Override
        public void releaseWithoutCommit() {
            releasesWithoutCommit.incrementAndGet();
        }

        @Override
        public CompletableFuture<Void> commit() {
            commits.incrementAndGet();
            return CompletableFuture.completedFuture(null);
        }
    }

    private static ConnectionSessionKey session() {
        return new ConnectionSessionKey(new SourceConnectionKey("node", "connection"), 3, 7);
    }

    private static ReplayRequestId request(int index) {
        return new ReplayRequestId(session(), index);
    }

    private static Map<ReplayRequestId, String> unresolved(
        ReplayTransactionRegistry registry,
        DeterministicMailbox mailbox
    ) {
        var snapshot = registry.unresolvedTransactions().toCompletableFuture();
        mailbox.runUntilIdle();
        return snapshot.join();
    }

    private static final class DeterministicMailbox implements ActorMailbox {
        private final Queue<Runnable> commands = new ArrayDeque<>();
        private boolean running;
        private boolean rejectNewTasks;

        @Override
        public void execute(Runnable command) {
            if (rejectNewTasks) {
                throw new java.util.concurrent.RejectedExecutionException("mailbox rejected task");
            }
            commands.add(command);
        }

        @Override
        public boolean inMailbox() {
            return running;
        }

        @Override
        public Instant now() {
            return Instant.EPOCH;
        }

        @Override
        public ScheduledTask schedule(Runnable command, Duration delay) {
            throw new UnsupportedOperationException();
        }

        private void runUntilIdle() {
            while (!commands.isEmpty()) {
                running = true;
                try {
                    commands.remove().run();
                } finally {
                    running = false;
                }
            }
        }

        private void rejectNewTasks() {
            rejectNewTasks = true;
        }

        private void dropQueuedTasks() {
            commands.clear();
        }

        private int queuedTaskCount() {
            return commands.size();
        }
    }
}
