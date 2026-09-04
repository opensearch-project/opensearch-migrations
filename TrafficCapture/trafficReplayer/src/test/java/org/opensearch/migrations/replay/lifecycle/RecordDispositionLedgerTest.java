package org.opensearch.migrations.replay.lifecycle;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicInteger;

import org.opensearch.migrations.replay.lifecycle.ReplayIdentity.KafkaRecordId;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class RecordDispositionLedgerTest {
    @Test
    void commitClosesContextAndCommitsExactlyOnce() {
        var ledger = new RecordDispositionLedger(Runnable::run);
        var handle = new TestRecordHandle(record(10));
        ledger.register(handle, "transaction").toCompletableFuture().join();

        var result = ledger.dispose(
            handle.id(),
            "transaction",
            new RecordDisposition.Commit("replay-succeeded")
        ).toCompletableFuture().join();

        Assertions.assertInstanceOf(RecordDisposition.Commit.class, result.disposition());
        Assertions.assertEquals(1, handle.contextCloses.get());
        Assertions.assertEquals(1, handle.commits.get());
        Assertions.assertThrows(
            Exception.class,
            () -> ledger.dispose(
                handle.id(),
                "transaction",
                new RecordDisposition.Commit("duplicate")
            ).toCompletableFuture().get()
        );
    }

    @Test
    void retainClosesContextWithoutCommittingAndOwnershipIsChecked() {
        var ledger = new RecordDispositionLedger(Runnable::run);
        var handle = new TestRecordHandle(record(11));
        ledger.register(handle, "assembler").toCompletableFuture().join();
        ledger.transfer(handle.id(), "assembler", "transaction").toCompletableFuture().join();

        Assertions.assertThrows(
            Exception.class,
            () -> ledger.dispose(
                handle.id(),
                "assembler",
                new RecordDisposition.Retain("shutdown")
            ).toCompletableFuture().get()
        );

        ledger.dispose(
            handle.id(),
            "transaction",
            new RecordDisposition.Retain("shutdown")
        ).toCompletableFuture().join();
        Assertions.assertEquals(1, handle.contextCloses.get());
        Assertions.assertEquals(0, handle.commits.get());
    }

    @Test
    void commitRemainsUnresolvedUntilTheSourceAcknowledgesIt() {
        var ledger = new RecordDispositionLedger(Runnable::run);
        var commitAcknowledgement = new CompletableFuture<Void>();
        var handle = new TestRecordHandle(record(12), commitAcknowledgement);
        ledger.register(handle, "transaction").toCompletableFuture().join();

        var disposition = ledger.dispose(
            handle.id(),
            "transaction",
            new RecordDisposition.Commit("replay-succeeded")
        );

        Assertions.assertFalse(disposition.toCompletableFuture().isDone());
        Assertions.assertEquals(
            "transaction",
            ledger.unresolvedObligations().toCompletableFuture().join().get(handle.id())
        );
        Assertions.assertThrows(
            CompletionException.class,
            () -> ledger.dispose(
                handle.id(),
                "transaction",
                new RecordDisposition.Commit("duplicate")
            ).toCompletableFuture().join()
        );

        commitAcknowledgement.complete(null);

        Assertions.assertInstanceOf(
            RecordDisposition.Commit.class,
            disposition.toCompletableFuture().join().disposition()
        );
        Assertions.assertFalse(
            ledger.unresolvedObligations().toCompletableFuture().join().containsKey(handle.id())
        );
        Assertions.assertEquals(1, handle.contextCloses.get());
        Assertions.assertEquals(1, handle.commits.get());
    }

    @Test
    void failedCommitAcknowledgementRemainsVisibleAndCannotBeRetried() {
        var ledger = new RecordDispositionLedger(Runnable::run);
        var commitAcknowledgement = new CompletableFuture<Void>();
        var handle = new TestRecordHandle(record(13), commitAcknowledgement);
        ledger.register(handle, "transaction").toCompletableFuture().join();

        var disposition = ledger.dispose(
            handle.id(),
            "transaction",
            new RecordDisposition.Commit("replay-succeeded")
        );
        commitAcknowledgement.completeExceptionally(new IllegalStateException("broker rejected commit"));

        Assertions.assertThrows(CompletionException.class, () -> disposition.toCompletableFuture().join());
        Assertions.assertEquals(
            "transaction",
            ledger.unresolvedObligations().toCompletableFuture().join().get(handle.id())
        );
        Assertions.assertThrows(
            CompletionException.class,
            () -> ledger.dispose(
                handle.id(),
                "transaction",
                new RecordDisposition.Retain("cannot revise an attempted commit")
            ).toCompletableFuture().join()
        );
        Assertions.assertEquals(1, handle.contextCloses.get());
        Assertions.assertEquals(1, handle.commits.get());
    }

    private static KafkaRecordId record(long offset) {
        return new KafkaRecordId("topic", 0, offset, 1);
    }

    private static class TestRecordHandle implements RecordDispositionLedger.RecordHandle {
        private final KafkaRecordId id;
        private final CompletableFuture<Void> commitAcknowledgement;
        private final AtomicInteger contextCloses = new AtomicInteger();
        private final AtomicInteger commits = new AtomicInteger();

        private TestRecordHandle(KafkaRecordId id) {
            this(id, CompletableFuture.completedFuture(null));
        }

        private TestRecordHandle(KafkaRecordId id, CompletableFuture<Void> commitAcknowledgement) {
            this.id = id;
            this.commitAcknowledgement = commitAcknowledgement;
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
            return commitAcknowledgement;
        }
    }
}
