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
