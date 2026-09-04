package org.opensearch.migrations.replay.lifecycle;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Queue;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import org.opensearch.migrations.replay.lifecycle.ReplayIdentity.ConnectionSessionKey;
import org.opensearch.migrations.replay.lifecycle.ReplayIdentity.KafkaRecordId;
import org.opensearch.migrations.replay.lifecycle.ReplayIdentity.ReplayRequestId;
import org.opensearch.migrations.replay.lifecycle.ReplayIdentity.SourceConnectionKey;
import org.opensearch.migrations.replay.lifecycle.ReplayOutcomes.EvidenceOutcome;
import org.opensearch.migrations.replay.lifecycle.ReplayOutcomes.SourceOutcome;
import org.opensearch.migrations.replay.lifecycle.ReplayOutcomes.TargetOutcome;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class ReplayTransactionTest {
    @Test
    void successfulTransactionWaitsForEvidenceAndCommitBeforeCompleting() {
        var mailbox = new QueuedMailbox();
        var ledger = new RecordDispositionLedger(Runnable::run);
        var record = new TestRecordHandle(record(1));
        var evidence = new CompletableFuture<EvidenceOutcome>();
        var resource = new TestResource();
        register(ledger, record, request().toString());
        var transaction = transaction(mailbox, ledger, evidence, record.id(), resource);

        transaction.settleSource(new SourceOutcome.Complete());
        transaction.settleTarget(new TargetOutcome.Succeeded<>("response"));
        mailbox.runUntilIdle();
        Assertions.assertFalse(transaction.completion().toCompletableFuture().isDone());
        Assertions.assertEquals(0, record.commits.get());

        evidence.complete(new EvidenceOutcome.Durable("receipt"));
        mailbox.runUntilIdle();

        var outcome = transaction.completion().toCompletableFuture().join();
        Assertions.assertInstanceOf(RecordDisposition.Commit.class, outcome.disposition());
        Assertions.assertEquals(1, record.contextCloses.get());
        Assertions.assertEquals(1, record.commits.get());
        Assertions.assertEquals(1, resource.closes);
    }

    @Test
    void completionWaitsForKafkaCommitAcknowledgement() {
        var mailbox = new QueuedMailbox();
        var ledger = new RecordDispositionLedger(Runnable::run);
        var record = new TestRecordHandle(record(5));
        record.commitCompletion = new CompletableFuture<>();
        register(ledger, record, request().toString());
        var transaction = transaction(
            mailbox,
            ledger,
            CompletableFuture.completedFuture(new EvidenceOutcome.Durable("receipt")),
            record.id(),
            new TestResource()
        );

        transaction.settleSource(new SourceOutcome.Complete());
        transaction.settleTarget(new TargetOutcome.Succeeded<>("response"));
        mailbox.runUntilIdle();
        Assertions.assertFalse(transaction.completion().toCompletableFuture().isDone());

        record.commitCompletion.complete(null);
        mailbox.runUntilIdle();
        Assertions.assertInstanceOf(
            RecordDisposition.Commit.class,
            transaction.completion().toCompletableFuture().join().disposition()
        );
    }

    @Test
    void cancellationRetainsWithoutWritingEvidenceAndReleasesResources() {
        var mailbox = new QueuedMailbox();
        var ledger = new RecordDispositionLedger(Runnable::run);
        var record = new TestRecordHandle(record(2));
        var evidenceCalls = new AtomicInteger();
        var resource = new TestResource();
        register(ledger, record, request().toString());
        var transaction = new ReplayTransaction<String>(
            request(),
            mailbox,
            (id, source, target) -> {
                evidenceCalls.incrementAndGet();
                return CompletableFuture.completedFuture(new EvidenceOutcome.Durable("unexpected"));
            },
            new ReplayDispositionPolicy(),
            ledger,
            java.util.List.of(record.id()),
            java.util.List.of(resource)
        );

        transaction.settleSource(new SourceOutcome.Interrupted("rebalance"));
        transaction.settleTarget(new TargetOutcome.Cancelled<>(new CancellationException("rebalance")));
        mailbox.runUntilIdle();

        var outcome = transaction.completion().toCompletableFuture().join();
        Assertions.assertInstanceOf(RecordDisposition.Retain.class, outcome.disposition());
        Assertions.assertEquals(0, evidenceCalls.get());
        Assertions.assertEquals(0, record.commits.get());
        Assertions.assertEquals(1, record.contextCloses.get());
        Assertions.assertEquals(1, resource.closes);
    }

    @Test
    void duplicateOutcomeIsRejectedAndCallerCannotCancelCompletion() {
        var mailbox = new QueuedMailbox();
        var ledger = new RecordDispositionLedger(Runnable::run);
        var record = new TestRecordHandle(record(3));
        register(ledger, record, request().toString());
        var transaction = transaction(
            mailbox,
            ledger,
            CompletableFuture.completedFuture(new EvidenceOutcome.Durable("receipt")),
            record.id(),
            new TestResource()
        );
        var first = transaction.settleSource(new SourceOutcome.Complete()).toCompletableFuture();
        var duplicate = transaction.settleSource(new SourceOutcome.CapturedClose()).toCompletableFuture();
        transaction.completion().toCompletableFuture().cancel(false);
        mailbox.runUntilIdle();

        first.join();
        Assertions.assertTrue(duplicate.isCompletedExceptionally());
        Assertions.assertFalse(transaction.completion().toCompletableFuture().isDone());
    }

    @Test
    void evidenceFailureRetainsAndHalts() {
        var mailbox = new QueuedMailbox();
        var ledger = new RecordDispositionLedger(Runnable::run);
        var record = new TestRecordHandle(record(4));
        register(ledger, record, request().toString());
        var transaction = transaction(
            mailbox,
            ledger,
            CompletableFuture.completedFuture(
                new EvidenceOutcome.Failed(new IllegalStateException("sink failed"))
            ),
            record.id(),
            new TestResource()
        );

        transaction.settleSource(new SourceOutcome.Complete());
        transaction.settleTarget(new TargetOutcome.Succeeded<>("response"));
        mailbox.runUntilIdle();

        var outcome = transaction.completion().toCompletableFuture().join();
        Assertions.assertInstanceOf(RecordDisposition.Retain.class, outcome.disposition());
        Assertions.assertTrue(outcome.haltReplay());
        Assertions.assertEquals(0, record.commits.get());
    }

    private static ReplayTransaction<String> transaction(
        ActorMailbox mailbox,
        RecordDispositionLedger ledger,
        CompletableFuture<EvidenceOutcome> evidence,
        KafkaRecordId recordId,
        AutoCloseable resource
    ) {
        return new ReplayTransaction<>(
            request(),
            mailbox,
            (id, source, target) -> evidence,
            new ReplayDispositionPolicy(),
            ledger,
            java.util.List.of(recordId),
            java.util.List.of(resource)
        );
    }

    private static void register(
        RecordDispositionLedger ledger,
        TestRecordHandle handle,
        String owner
    ) {
        ledger.register(handle, owner).toCompletableFuture().join();
    }

    private static ReplayRequestId request() {
        return new ReplayRequestId(
            new ConnectionSessionKey(new SourceConnectionKey("node", "connection"), 0, 1),
            0
        );
    }

    private static KafkaRecordId record(long offset) {
        return new KafkaRecordId("topic", 0, offset, 1);
    }

    private static final class TestResource implements AutoCloseable {
        private int closes;

        @Override
        public void close() {
            closes++;
        }
    }

    private static final class TestRecordHandle implements RecordDispositionLedger.RecordHandle {
        private final KafkaRecordId id;
        private final AtomicInteger contextCloses = new AtomicInteger();
        private final AtomicInteger commits = new AtomicInteger();
        private CompletableFuture<Void> commitCompletion = CompletableFuture.completedFuture(null);

        private TestRecordHandle(KafkaRecordId id) {
            this.id = id;
        }

        @Override
        public KafkaRecordId id() {
            return id;
        }

        @Override
        public void closeContext() {
            contextCloses.incrementAndGet();
        }

        @Override
        public CompletableFuture<Void> commit() {
            commits.incrementAndGet();
            return commitCompletion;
        }
    }

    private static final class QueuedMailbox implements ActorMailbox {
        private final Queue<Runnable> tasks = new ArrayDeque<>();
        private boolean running;

        @Override
        public void execute(Runnable command) {
            tasks.add(command);
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

        void runUntilIdle() {
            while (!tasks.isEmpty()) {
                running = true;
                try {
                    tasks.remove().run();
                } finally {
                    running = false;
                }
            }
        }
    }
}
