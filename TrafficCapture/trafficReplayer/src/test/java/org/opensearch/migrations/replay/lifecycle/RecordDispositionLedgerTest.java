package org.opensearch.migrations.replay.lifecycle;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicInteger;

import org.opensearch.migrations.replay.lifecycle.ReplayIdentity.KafkaRecordId;
import org.opensearch.migrations.replay.lifecycle.ReplayIdentity.SourceConnectionKey;
import org.opensearch.migrations.replay.lifecycle.ReplayIdentity.SourceControlRecordId;
import org.opensearch.migrations.replay.lifecycle.ReplayIdentity.SourcePartitionKey;
import org.opensearch.migrations.replay.lifecycle.ReplayIdentity.TrafficStreamRecordId;

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
        Assertions.assertEquals(1, handle.releasesWithoutCommit.get());
    }

    @Test
    void sealingRejectsNewRecordsButAllowsAcceptedRecordsToSettle() {
        var ledger = new RecordDispositionLedger(Runnable::run);
        var accepted = new TestRecordHandle(record(21));
        var rejected = new TestRecordHandle(record(22));
        ledger.register(accepted, "transaction").toCompletableFuture().join();

        ledger.sealRegistrations().toCompletableFuture().join();

        var registrationFailure = Assertions.assertThrows(
            CompletionException.class,
            () -> ledger.register(rejected, "transaction").toCompletableFuture().join()
        );
        Assertions.assertInstanceOf(IllegalStateException.class, registrationFailure.getCause());
        ledger.dispose(
            accepted.id(),
            "transaction",
            new RecordDisposition.Retain("shutdown")
        ).toCompletableFuture().join();
        ledger.whenQuiescent().toCompletableFuture().join();
    }

    @Test
    void commitDispositionCompletesWhenTheCommitOperationIsFired() {
        var ledger = new RecordDispositionLedger(Runnable::run);
        var commitAcknowledgement = new CompletableFuture<Void>();
        var handle = new TestRecordHandle(record(12), commitAcknowledgement);
        ledger.register(handle, "transaction").toCompletableFuture().join();

        var disposition = ledger.dispose(
            handle.id(),
            "transaction",
            new RecordDisposition.Commit("replay-succeeded")
        );

        Assertions.assertInstanceOf(
            RecordDisposition.Commit.class,
            disposition.toCompletableFuture().join().disposition()
        );
        Assertions.assertFalse(
            ledger.unresolvedObligations().toCompletableFuture().join().containsKey(handle.id())
        );
        Assertions.assertThrows(
            CompletionException.class,
            () -> ledger.dispose(
                handle.id(),
                "transaction",
                new RecordDisposition.Commit("duplicate")
            ).toCompletableFuture().join()
        );

        Assertions.assertEquals(1, handle.contextCloses.get());
        Assertions.assertEquals(1, handle.commits.get());
        Assertions.assertFalse(commitAcknowledgement.isDone());
        Assertions.assertThrows(
            CompletionException.class,
            () -> ledger.register(
                new TestRecordHandle(handle.id()),
                "replacement"
            ).toCompletableFuture().join(),
            "a terminally failed identity must not be accepted as new work"
        );
    }

    @Test
    void quiescenceCompletesWhenTheCommitOperationIsFired() {
        var ledger = new RecordDispositionLedger(Runnable::run);
        var initiallyQuiescent = ledger.whenQuiescent().toCompletableFuture();
        var commitAcknowledgement = new CompletableFuture<Void>();
        var handle = new TestRecordHandle(record(17), commitAcknowledgement);

        ledger.register(handle, "source-only").toCompletableFuture().join();
        var activeInterval = ledger.whenQuiescent().toCompletableFuture();
        var disposition = ledger.dispose(
            handle.id(),
            "source-only",
            new RecordDisposition.Commit("source-record-ignored")
        );

        Assertions.assertTrue(initiallyQuiescent.isDone());
        disposition.toCompletableFuture().join();
        activeInterval.join();
        Assertions.assertFalse(commitAcknowledgement.isDone());
    }

    @Test
    void retainedRecordCompletesTheActiveQuiescenceInterval() {
        var ledger = new RecordDispositionLedger(Runnable::run);
        var handle = new TestRecordHandle(record(18));
        ledger.register(handle, "source-only").toCompletableFuture().join();
        var activeInterval = ledger.whenQuiescent().toCompletableFuture();

        ledger.dispose(
            handle.id(),
            "source-only",
            new RecordDisposition.Retain("source-inconclusive")
        ).toCompletableFuture().join();

        activeInterval.join();
        Assertions.assertEquals(0, handle.commits.get());
        Assertions.assertEquals(1, handle.releasesWithoutCommit.get());
    }

    @Test
    void callerCannotCancelTheAuthoritativeQuiescenceGate() {
        var ledger = new RecordDispositionLedger(Runnable::run);
        var handle = new TestRecordHandle(record(19));
        ledger.register(handle, "source-only").toCompletableFuture().join();
        var callerFuture = ledger.whenQuiescent().toCompletableFuture();

        callerFuture.cancel(false);

        Assertions.assertFalse(ledger.whenQuiescent().toCompletableFuture().isDone());
        ledger.dispose(
            handle.id(),
            "source-only",
            new RecordDisposition.Retain("shutdown")
        ).toCompletableFuture().join();
        ledger.whenQuiescent().toCompletableFuture().join();
    }

    @Test
    void laterCommitFailureDoesNotReopenTheResolvedDisposition() {
        var ledger = new RecordDispositionLedger(Runnable::run);
        var commitAcknowledgement = new CompletableFuture<Void>();
        var handle = new TestRecordHandle(record(13), commitAcknowledgement);
        ledger.register(handle, "transaction").toCompletableFuture().join();

        var disposition = ledger.dispose(
            handle.id(),
            "transaction",
            new RecordDisposition.Commit("replay-succeeded")
        );
        disposition.toCompletableFuture().join();
        commitAcknowledgement.completeExceptionally(new IllegalStateException("broker rejected commit"));

        Assertions.assertFalse(
            ledger.unresolvedObligations().toCompletableFuture().join().containsKey(handle.id())
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

    @Test
    void revokedRunwayDowngradesAnUnacceptedCommitToRetain() {
        var ledger = new RecordDispositionLedger(Runnable::run);
        var handle = new TestRecordHandle(record(14));
        ledger.onAssigned(java.util.List.of(handle.sourcePartition()));
        ledger.register(handle, "transaction").toCompletableFuture().join();

        ledger.onRevoked(java.util.List.of(handle.sourcePartition()));
        var result = ledger.dispose(
            handle.id(),
            "transaction",
            new RecordDisposition.Commit("replay-succeeded")
        ).toCompletableFuture().join();

        Assertions.assertInstanceOf(RecordDisposition.Retain.class, result.disposition());
        Assertions.assertEquals("source-runway-lost-before-replay-succeeded", result.disposition().reasonCode());
        Assertions.assertEquals(1, handle.contextCloses.get());
        Assertions.assertEquals(0, handle.commits.get());
        Assertions.assertEquals(1, handle.releasesWithoutCommit.get());
    }

    @Test
    void revocationDoesNotRewriteACommitThatTheLedgerAlreadyAccepted() {
        var ledger = new RecordDispositionLedger(Runnable::run);
        var commitAcknowledgement = new CompletableFuture<Void>();
        var handle = new TestRecordHandle(record(15), commitAcknowledgement);
        ledger.onAssigned(java.util.List.of(handle.sourcePartition()));
        ledger.register(handle, "transaction").toCompletableFuture().join();

        var disposition = ledger.dispose(
            handle.id(),
            "transaction",
            new RecordDisposition.Commit("replay-succeeded")
        );
        Assertions.assertEquals(1, handle.commits.get());

        ledger.onRevoked(java.util.List.of(handle.sourcePartition()));
        commitAcknowledgement.complete(null);

        Assertions.assertInstanceOf(
            RecordDisposition.Commit.class,
            disposition.toCompletableFuture().join().disposition()
        );
        Assertions.assertEquals(0, handle.releasesWithoutCommit.get());
    }

    @Test
    void laterSourceRejectionDoesNotRewriteAFiredCommit() {
        var ledger = new RecordDispositionLedger(Runnable::run);
        var commitAcknowledgement = new CompletableFuture<Void>();
        var handle = new TestRecordHandle(record(16), commitAcknowledgement);
        ledger.onAssigned(java.util.List.of(handle.sourcePartition()));
        ledger.register(handle, "transaction").toCompletableFuture().join();
        var activeInterval = ledger.whenQuiescent().toCompletableFuture();

        var disposition = ledger.dispose(
            handle.id(),
            "transaction",
            new RecordDisposition.Commit("replay-succeeded")
        );
        var result = disposition.toCompletableFuture().join();
        ledger.onRevoked(java.util.List.of(handle.sourcePartition()));
        commitAcknowledgement.completeExceptionally(
            new SourceCommitNotAcceptedException(handle.sourcePartition())
        );

        Assertions.assertInstanceOf(RecordDisposition.Commit.class, result.disposition());
        Assertions.assertEquals(0, handle.releasesWithoutCommit.get());
        Assertions.assertFalse(
            ledger.unresolvedObligations().toCompletableFuture().join().containsKey(handle.id())
        );
        activeInterval.join();
    }

    @Test
    void laterCommitFailureAfterPartitionRevocationDoesNotAffectLocalBookkeeping() {
        var ledger = new RecordDispositionLedger(Runnable::run);
        var commitAcknowledgement = new CompletableFuture<Void>();
        var handle = new TestRecordHandle(record(17), commitAcknowledgement);
        ledger.onAssigned(java.util.List.of(handle.sourcePartition()));
        ledger.register(handle, "transaction").toCompletableFuture().join();
        var activeInterval = ledger.whenQuiescent().toCompletableFuture();

        var disposition = ledger.dispose(
            handle.id(),
            "transaction",
            new RecordDisposition.Commit("replay-succeeded")
        ).toCompletableFuture();
        var result = disposition.join();
        ledger.onRevoked(java.util.List.of(handle.sourcePartition()));
        commitAcknowledgement.completeExceptionally(
            new IllegalStateException("commit result unavailable after partition revocation")
        );

        Assertions.assertInstanceOf(RecordDisposition.Commit.class, result.disposition());
        activeInterval.join();
        Assertions.assertFalse(
            ledger.unresolvedObligations().toCompletableFuture().join().containsKey(handle.id())
        );
        Assertions.assertEquals(0, handle.releasesWithoutCommit.get());
    }

    @Test
    void laterCommitFailureForAnotherPartitionDoesNotAffectResolvedDisposition() {
        var ledger = new RecordDispositionLedger(Runnable::run);
        var commitAcknowledgement = new CompletableFuture<Void>();
        var handle = new TestRecordHandle(record(20), commitAcknowledgement);
        ledger.register(handle, "transaction").toCompletableFuture().join();

        var disposition = ledger.dispose(
            handle.id(),
            "transaction",
            new RecordDisposition.Commit("replay-succeeded")
        );
        var result = disposition.toCompletableFuture().join();
        commitAcknowledgement.completeExceptionally(
            new SourceRunwayLostException(new SourcePartitionKey("topic", 1, 1))
        );

        Assertions.assertInstanceOf(RecordDisposition.Commit.class, result.disposition());
        Assertions.assertFalse(
            ledger.unresolvedObligations().toCompletableFuture().join().containsKey(handle.id())
        );
    }

    private static KafkaRecordId record(long offset) {
        return record(offset, 1);
    }

    private static KafkaRecordId record(long offset, int generation) {
        return new KafkaRecordId("topic", 0, offset, generation);
    }

    @Test
    void laterCommitFailureDoesNotAffectQuiescence() {
        var ledger = new RecordDispositionLedger(Runnable::run);
        var commitAcknowledgement = new CompletableFuture<Void>();
        var handle = new TestRecordHandle(record(31), commitAcknowledgement);
        ledger.register(handle, "transaction").toCompletableFuture().join();
        var activeInterval = ledger.whenQuiescent().toCompletableFuture();

        var disposition = ledger.dispose(
            handle.id(),
            "transaction",
            new RecordDisposition.Commit("replay-succeeded")
        ).toCompletableFuture();
        disposition.join();
        activeInterval.join();

        var brokerFailure = new java.util.concurrent.CancellationException(
            "Kafka traffic source closed before commit acknowledgement"
        );
        commitAcknowledgement.completeExceptionally(brokerFailure);

        Assertions.assertFalse(
            ledger.unresolvedObligations().toCompletableFuture().join().containsKey(handle.id())
        );
        Assertions.assertThrows(
            CompletionException.class,
            () -> ledger.dispose(
                handle.id(),
                "transaction",
                new RecordDisposition.Retain("second-attempt")
            ).toCompletableFuture().join()
        );
    }

    @Test
    void wrappedLaterCommitRejectionDoesNotRewriteDisposition() {
        var ledger = new RecordDispositionLedger(Runnable::run);
        var commitAcknowledgement = new CompletableFuture<Void>();
        var handle = new TestRecordHandle(record(32), commitAcknowledgement) {
            @Override
            public CompletableFuture<Void> commit() {
                // Derived stages wrap failures in CompletionException; the ledger must unwrap.
                return super.commit().thenApply(v -> v);
            }
        };
        ledger.register(handle, "transaction").toCompletableFuture().join();
        var activeInterval = ledger.whenQuiescent().toCompletableFuture();

        var disposition = ledger.dispose(
            handle.id(),
            "transaction",
            new RecordDisposition.Commit("replay-succeeded")
        ).toCompletableFuture();
        var result = disposition.join();
        commitAcknowledgement.completeExceptionally(
            new SourceCommitNotAcceptedException(handle.sourcePartition())
        );

        Assertions.assertInstanceOf(RecordDisposition.Commit.class, result.disposition());
        activeInterval.join();
    }

    @Test
    void laterCommitFailureDoesNotPoisonTheNextQuiescenceInterval() {
        var ledger = new RecordDispositionLedger(Runnable::run);
        var failingAcknowledgement = new CompletableFuture<Void>();
        var failing = new TestRecordHandle(record(33), failingAcknowledgement);
        ledger.register(failing, "transaction").toCompletableFuture().join();
        var failingDisposition = ledger.dispose(
            failing.id(),
            "transaction",
            new RecordDisposition.Commit("replay-succeeded")
        ).toCompletableFuture();
        failingDisposition.join();
        ledger.whenQuiescent().toCompletableFuture().join();
        failingAcknowledgement.completeExceptionally(new RuntimeException("broker commit failed"));

        var healthy = new TestRecordHandle(record(34));
        ledger.register(healthy, "transaction").toCompletableFuture().join();
        var freshInterval = ledger.whenQuiescent().toCompletableFuture();
        ledger.dispose(
            healthy.id(),
            "transaction",
            new RecordDisposition.Retain("shutdown")
        ).toCompletableFuture().join();
        freshInterval.join();
    }

    @Test
    void retiredGenerationsDiscardSuccessfulHistoryAndRejectLateRegistrations() {
        var ledger = new RecordDispositionLedger(Runnable::run);

        for (int generation = 1; generation <= 50; ++generation) {
            var handle = new TestRecordHandle(record(generation, generation));
            ledger.onAssigned(java.util.List.of(handle.sourcePartition()));
            ledger.register(handle, "transaction").toCompletableFuture().join();
            ledger.dispose(
                handle.id(),
                "transaction",
                new RecordDisposition.Retain("generation-complete")
            ).toCompletableFuture().join();
            ledger.onRevoked(java.util.List.of(handle.sourcePartition()));
            ledger.onRetired(java.util.List.of(handle.sourcePartition()));

            var snapshot = ledger.stateSnapshot().toCompletableFuture().join();
            Assertions.assertEquals(0, snapshot.unresolved());
            Assertions.assertEquals(0, snapshot.pending());
            Assertions.assertEquals(0, snapshot.resolved());
            Assertions.assertEquals(0, snapshot.failed());
            Assertions.assertEquals(0, snapshot.runwayGenerations());
            Assertions.assertEquals(0, snapshot.retiringGenerations());
            Assertions.assertEquals(
                1,
                snapshot.retiredPartitionWatermarks(),
                "retired history must remain bounded by logical source partition"
            );

            var lateRecord = new TestRecordHandle(record(1_000 + generation, generation));
            var failure = Assertions.assertThrows(
                CompletionException.class,
                () -> ledger.register(lateRecord, "late").toCompletableFuture().join()
            );
            Assertions.assertTrue(
                failure.getCause().getMessage().contains("after source generation retirement")
            );
        }
    }

    @Test
    void retirementDoesNotWaitForLaterCommitCompletionBeforePurgingHistory() {
        var ledger = new RecordDispositionLedger(Runnable::run);
        var commitCompletion = new CompletableFuture<Void>();
        var handle = new TestRecordHandle(record(61, 3), commitCompletion);
        ledger.onAssigned(java.util.List.of(handle.sourcePartition()));
        ledger.register(handle, "transaction").toCompletableFuture().join();
        var disposition = ledger.dispose(
            handle.id(),
            "transaction",
            new RecordDisposition.Commit("replay-succeeded")
        ).toCompletableFuture();

        ledger.onRevoked(java.util.List.of(handle.sourcePartition()));
        ledger.onRetired(java.util.List.of(handle.sourcePartition()));

        disposition.join();

        var retired = ledger.stateSnapshot().toCompletableFuture().join();
        Assertions.assertEquals(0, retired.pending());
        Assertions.assertEquals(0, retired.resolved());
        Assertions.assertEquals(0, retired.retiringGenerations());
        Assertions.assertEquals(1, retired.retiredPartitionWatermarks());
        Assertions.assertFalse(commitCompletion.isDone());
    }

    @Test
    void activeGenerationCompactsSuccessfulKafkaHistoryIntoExactOffsetRanges() {
        var ledger = new RecordDispositionLedger(Runnable::run);
        var partition = new SourcePartitionKey("topic", 0, 7);
        ledger.onAssigned(java.util.List.of(partition));

        for (long offset = 0; offset < 10_000; ++offset) {
            var handle = new TestRecordHandle(record(offset, 7));
            ledger.register(handle, "transaction").toCompletableFuture().join();
            ledger.dispose(
                handle.id(),
                "transaction",
                new RecordDisposition.Retain("test")
            ).toCompletableFuture().join();
        }

        var compact = ledger.stateSnapshot().toCompletableFuture().join();
        Assertions.assertEquals(10_000, compact.resolved());
        Assertions.assertEquals(
            1,
            compact.resolvedIndexEntries(),
            "contiguous offsets should occupy one exact range"
        );
        Assertions.assertThrows(
            CompletionException.class,
            () -> ledger.register(
                new TestRecordHandle(record(5_000, 7)),
                "duplicate"
            ).toCompletableFuture().join(),
            "compaction must preserve exact duplicate rejection"
        );
    }

    @Test
    void outOfOrderTerminalOffsetsMergeWhenTheGapSettles() {
        var ledger = new RecordDispositionLedger(Runnable::run);
        ledger.onAssigned(java.util.List.of(new SourcePartitionKey("topic", 0, 8)));

        settleRetained(ledger, record(10, 8));
        settleRetained(ledger, record(12, 8));
        Assertions.assertEquals(
            2,
            ledger.stateSnapshot().toCompletableFuture().join().resolvedIndexEntries()
        );

        settleRetained(ledger, record(11, 8));
        var merged = ledger.stateSnapshot().toCompletableFuture().join();
        Assertions.assertEquals(3, merged.resolved());
        Assertions.assertEquals(1, merged.resolvedIndexEntries());
    }

    @Test
    void activeStreamGenerationCompactsChunkHistoryPerConnection() {
        var ledger = new RecordDispositionLedger(Runnable::run);
        var partition = new SourcePartitionKey("non-kafka-source", 0, 0);
        ledger.onAssigned(java.util.List.of(partition));
        var connection = new SourceConnectionKey("node", "connection");

        for (int index = 0; index < 10_000; ++index) {
            settleRetained(
                ledger,
                new StreamRecordHandle(
                    new TrafficStreamRecordId(connection, index, 0),
                    partition
                )
            );
        }

        var compact = ledger.stateSnapshot().toCompletableFuture().join();
        Assertions.assertEquals(10_000, compact.resolved());
        Assertions.assertEquals(
            1,
            compact.resolvedIndexEntries(),
            "one stream connection should occupy one exact chunk-index range"
        );
        Assertions.assertThrows(
            CompletionException.class,
            () -> ledger.register(
                new StreamRecordHandle(
                    new TrafficStreamRecordId(connection, 5_000, 0),
                    partition
                ),
                "duplicate"
            ).toCompletableFuture().join()
        );
    }

    @Test
    void retirementPurgesExactSourceControlHistory() {
        var ledger = new RecordDispositionLedger(Runnable::run);
        var partition = new SourcePartitionKey("topic", 0, 9);
        var handle = new SourceControlRecordHandle(
            new SourceControlRecordId(
                new SourceConnectionKey("node", "connection"),
                "source-reader-interrupted-close",
                9
            ),
            partition
        );
        ledger.onAssigned(java.util.List.of(partition));
        settleRetained(ledger, handle);

        var active = ledger.stateSnapshot().toCompletableFuture().join();
        Assertions.assertEquals(1, active.resolved());
        Assertions.assertEquals(1, active.resolvedIndexEntries());
        Assertions.assertThrows(
            CompletionException.class,
            () -> ledger.register(handle, "duplicate").toCompletableFuture().join()
        );

        ledger.onRevoked(java.util.List.of(partition));
        ledger.onRetired(java.util.List.of(partition));

        var retired = ledger.stateSnapshot().toCompletableFuture().join();
        Assertions.assertEquals(0, retired.resolved());
        Assertions.assertEquals(0, retired.resolvedIndexEntries());
        Assertions.assertEquals(1, retired.retiredPartitionWatermarks());
    }

    private static void settleRetained(RecordDispositionLedger ledger, KafkaRecordId recordId) {
        settleRetained(ledger, new TestRecordHandle(recordId));
    }

    private static void settleRetained(
        RecordDispositionLedger ledger,
        RecordDispositionLedger.RecordHandle handle
    ) {
        ledger.register(handle, "transaction").toCompletableFuture().join();
        ledger.dispose(
            handle.id(),
            "transaction",
            new RecordDisposition.Retain("test")
        ).toCompletableFuture().join();
    }

    private record StreamRecordHandle(
        TrafficStreamRecordId id,
        SourcePartitionKey sourcePartition
    ) implements RecordDispositionLedger.RecordHandle {
        @Override
        public void closeContext() {}

        @Override
        public void releaseWithoutCommit() {}

        @Override
        public CompletableFuture<Void> commit() {
            return CompletableFuture.completedFuture(null);
        }
    }

    private record SourceControlRecordHandle(
        SourceControlRecordId id,
        SourcePartitionKey sourcePartition
    ) implements RecordDispositionLedger.RecordHandle {
        @Override
        public void closeContext() {}

        @Override
        public void releaseWithoutCommit() {}

        @Override
        public CompletableFuture<Void> commit() {
            return CompletableFuture.completedFuture(null);
        }
    }

    private static class TestRecordHandle implements RecordDispositionLedger.RecordHandle {
        private final KafkaRecordId id;
        private final CompletableFuture<Void> commitAcknowledgement;
        private final AtomicInteger contextCloses = new AtomicInteger();
        private final AtomicInteger commits = new AtomicInteger();
        private final AtomicInteger releasesWithoutCommit = new AtomicInteger();

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
        public SourcePartitionKey sourcePartition() {
            return new SourcePartitionKey(id.topic(), id.partition(), id.sourceGeneration());
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
            return commitAcknowledgement;
        }
    }
}
