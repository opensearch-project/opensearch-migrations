package org.opensearch.migrations.replay.lifecycle;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Map;
import java.util.Queue;
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

    private static final class RetentionWatchingHandle implements RecordDispositionLedger.RecordHandle {
        private final ReplayIdentity.KafkaRecordId id;
        private final AtomicInteger commits = new AtomicInteger();
        private final AtomicInteger contextCloses = new AtomicInteger();

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
            // Retention is asserted through the absence of a commit.
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

        @Override
        public void execute(Runnable command) {
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
    }
}
