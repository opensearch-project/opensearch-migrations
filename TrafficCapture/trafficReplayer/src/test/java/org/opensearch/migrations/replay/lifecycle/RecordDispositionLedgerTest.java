package org.opensearch.migrations.replay.lifecycle;

import java.util.concurrent.CompletableFuture;
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

    private static KafkaRecordId record(long offset) {
        return new KafkaRecordId("topic", 0, offset, 1);
    }

    private static class TestRecordHandle implements RecordDispositionLedger.RecordHandle {
        private final KafkaRecordId id;
        private final AtomicInteger contextCloses = new AtomicInteger();
        private final AtomicInteger commits = new AtomicInteger();

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
            return CompletableFuture.completedFuture(null);
        }
    }
}
