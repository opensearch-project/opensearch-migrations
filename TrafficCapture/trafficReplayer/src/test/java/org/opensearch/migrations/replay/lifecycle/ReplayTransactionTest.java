package org.opensearch.migrations.replay.lifecycle;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import org.opensearch.migrations.replay.lifecycle.ReplayIdentity.ConnectionSessionKey;
import org.opensearch.migrations.replay.lifecycle.ReplayIdentity.KafkaRecordId;
import org.opensearch.migrations.replay.lifecycle.ReplayIdentity.ReplayRequestId;
import org.opensearch.migrations.replay.lifecycle.ReplayIdentity.SourceConnectionKey;
import org.opensearch.migrations.replay.lifecycle.ReplayIdentity.SourcePartitionKey;
import org.opensearch.migrations.replay.lifecycle.ReplayOutcomes.EvidenceOutcome;
import org.opensearch.migrations.replay.lifecycle.ReplayOutcomes.SourceOutcome;
import org.opensearch.migrations.replay.lifecycle.ReplayOutcomes.TargetOutcome;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class ReplayTransactionTest {
    @Test
    void lifecycleMetricsFollowMailboxOwnedPhasesAndRetireAtCompletion() {
        var mailbox = new QueuedMailbox();
        var ledger = new RecordDispositionLedger(Runnable::run);
        var record = new TestRecordHandle(record(9));
        var evidence = new CompletableFuture<EvidenceOutcome>();
        var metrics = new RecordingMetrics(mailbox);
        register(ledger, record, request().toString());
        var transaction = new ReplayTransaction<String>(
            request(),
            mailbox,
            (id, source, target) -> evidence,
            new ReplayDispositionPolicy(),
            ledger,
            List.of(record.id()),
            List.of(),
            metrics
        );

        mailbox.runUntilIdle();
        Assertions.assertEquals(1, metrics.phaseCount(ReplayTransaction.Phase.WAITING_FOR_JOIN));
        Assertions.assertEquals(1, metrics.runwayCount(ReplayTransaction.RunwayState.AVAILABLE));

        transaction.settleSource(new SourceOutcome.Complete());
        transaction.settleTarget(new TargetOutcome.Succeeded<>("response"));
        mailbox.runUntilIdle();
        Assertions.assertEquals(0, metrics.phaseCount(ReplayTransaction.Phase.WAITING_FOR_JOIN));
        Assertions.assertEquals(1, metrics.phaseCount(ReplayTransaction.Phase.WRITING_EVIDENCE));

        evidence.complete(new EvidenceOutcome.Durable("receipt"));
        mailbox.runUntilIdle();

        Assertions.assertTrue(metrics.onlyMailboxCallbacks);
        Assertions.assertEquals(0, metrics.totalActivePhases());
        Assertions.assertEquals(0, metrics.totalRunwayStates());
        Assertions.assertEquals(List.of(ReplayTransaction.TerminalOutcome.COMMITTED), metrics.terminalOutcomes);
        Assertions.assertEquals(1, metrics.dispositions.size());
        Assertions.assertInstanceOf(RecordDisposition.Commit.class, metrics.dispositions.get(0));
    }

    @Test
    void runwayLossIsMonotonicAndRetiredAtFailure() {
        var mailbox = new QueuedMailbox();
        var metrics = new RecordingMetrics(mailbox);
        var transaction = new ReplayTransaction<String>(
            request(),
            mailbox,
            (id, source, target) -> CompletableFuture.completedFuture(
                new EvidenceOutcome.Durable("unused")
            ),
            new ReplayDispositionPolicy(),
            new RecordDispositionLedger(Runnable::run),
            List.of(),
            List.of(),
            metrics
        );
        mailbox.runUntilIdle();

        transaction.observeRunwayLost(ReplayTransaction.RunwayLossReason.SOURCE_REASSIGNMENT);
        transaction.observeRunwayLost(ReplayTransaction.RunwayLossReason.SHUTDOWN);
        mailbox.runUntilIdle();

        Assertions.assertEquals(0, metrics.runwayCount(ReplayTransaction.RunwayState.AVAILABLE));
        Assertions.assertEquals(1, metrics.runwayCount(ReplayTransaction.RunwayState.LOST));
        Assertions.assertEquals(
            List.of(ReplayTransaction.RunwayLossReason.SOURCE_REASSIGNMENT),
            metrics.runwayLosses
        );

        transaction.fail(new IllegalStateException("failed"));
        mailbox.runUntilIdle();

        Assertions.assertTrue(metrics.onlyMailboxCallbacks);
        Assertions.assertEquals(0, metrics.totalActivePhases());
        Assertions.assertEquals(0, metrics.totalRunwayStates());
        Assertions.assertEquals(List.of(ReplayTransaction.TerminalOutcome.FAILED), metrics.terminalOutcomes);
    }

    @Test
    void ownedResourcesSettleOnlyAfterTheWholeTransaction() {
        var mailbox = new QueuedMailbox();
        var ledger = new RecordDispositionLedger(Runnable::run);
        var record = new TestRecordHandle(record(0));
        record.commitCompletion = new CompletableFuture<>();
        var evidence = new CompletableFuture<EvidenceOutcome>();
        var resource = new TestResource();
        register(ledger, record, request().toString());
        var transaction = new ReplayTransaction<String>(
            request(),
            mailbox,
            (id, source, target) -> evidence,
            new ReplayDispositionPolicy(),
            ledger,
            List.of(record.id()),
            List.of(resource)
        );

        transaction.settleTarget(new TargetOutcome.Succeeded<>("response"));
        mailbox.runUntilIdle();
        Assertions.assertEquals(0, resource.closes);

        transaction.settleSource(new SourceOutcome.Complete());
        mailbox.runUntilIdle();
        Assertions.assertEquals(0, resource.closes);

        evidence.complete(new EvidenceOutcome.Durable("receipt"));
        mailbox.runUntilIdle();
        Assertions.assertEquals(0, resource.closes);
        Assertions.assertFalse(transaction.completion().toCompletableFuture().isDone());

        record.commitCompletion.complete(null);
        mailbox.runUntilIdle();

        transaction.completion().toCompletableFuture().join();
        Assertions.assertEquals(1, resource.closes);
    }

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

    @Test
    void classifiedPoisonCommitsOnlyAfterDurableEvidence() {
        var mailbox = new QueuedMailbox();
        var ledger = new RecordDispositionLedger(Runnable::run);
        var record = new TestRecordHandle(record(7));
        var evidence = new CompletableFuture<EvidenceOutcome>();
        register(ledger, record, request().toString());
        var transaction = transaction(mailbox, ledger, evidence, record.id(), new TestResource());

        transaction.settleSource(new SourceOutcome.Complete());
        transaction.settleTarget(new TargetOutcome.ClassifiedSkip<>("response", "allowlisted"));
        mailbox.runUntilIdle();
        Assertions.assertFalse(transaction.completion().toCompletableFuture().isDone());

        evidence.complete(new EvidenceOutcome.Durable("receipt"));
        mailbox.runUntilIdle();

        var outcome = transaction.completion().toCompletableFuture().join();
        Assertions.assertInstanceOf(RecordDisposition.Commit.class, outcome.disposition());
        Assertions.assertEquals("target-classified-skip", outcome.disposition().reasonCode());
        Assertions.assertEquals(1, record.commits.get());
    }

    @Test
    void revokedRunwayRetainsAnOtherwiseSuccessfulTransaction() {
        var mailbox = new QueuedMailbox();
        var ledger = new RecordDispositionLedger(Runnable::run);
        var record = new TestRecordHandle(record(8));
        var evidence = new CompletableFuture<EvidenceOutcome>();
        ledger.onAssigned(List.of(record.sourcePartition()));
        register(ledger, record, request().toString());
        var transaction = transaction(mailbox, ledger, evidence, record.id(), new TestResource());

        transaction.settleSource(new SourceOutcome.Complete());
        transaction.settleTarget(new TargetOutcome.Succeeded<>("response"));
        mailbox.runUntilIdle();

        ledger.onRevoked(List.of(record.sourcePartition()));
        evidence.complete(new EvidenceOutcome.Durable("receipt"));
        mailbox.runUntilIdle();

        var outcome = transaction.completion().toCompletableFuture().join();
        Assertions.assertInstanceOf(RecordDisposition.Retain.class, outcome.disposition());
        Assertions.assertEquals(0, record.commits.get());
        Assertions.assertEquals(1, record.contextCloses.get());
        Assertions.assertFalse(outcome.haltReplay());
    }

    @Test
    void explicitFailureRejectsLateOutcomesAndReleasesResourcesOnce() {
        var mailbox = new QueuedMailbox();
        var ledger = new RecordDispositionLedger(Runnable::run);
        var record = new TestRecordHandle(record(6));
        var resource = new TestResource();
        register(ledger, record, request().toString());
        var transaction = transaction(
            mailbox,
            ledger,
            CompletableFuture.completedFuture(new EvidenceOutcome.Durable("unused")),
            record.id(),
            resource
        );

        var failureAcknowledgement = transaction.fail(new IllegalStateException("actor failed"))
            .toCompletableFuture();
        mailbox.runUntilIdle();
        failureAcknowledgement.join();

        var sourceAcknowledgement = transaction.settleSource(new SourceOutcome.Complete())
            .toCompletableFuture();
        var targetAcknowledgement = transaction.settleTarget(new TargetOutcome.Succeeded<>("late"))
            .toCompletableFuture();
        mailbox.runUntilIdle();

        Assertions.assertTrue(sourceAcknowledgement.isCompletedExceptionally());
        Assertions.assertTrue(targetAcknowledgement.isCompletedExceptionally());
        Assertions.assertTrue(transaction.completion().toCompletableFuture().isCompletedExceptionally());
        Assertions.assertEquals(1, resource.closes);
        Assertions.assertEquals(0, record.contextCloses.get());
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

    private static final class RecordingMetrics implements ReplayTransaction.Metrics {
        private final ActorMailbox mailbox;
        private final EnumMap<ReplayTransaction.Phase, Integer> phases =
            new EnumMap<>(ReplayTransaction.Phase.class);
        private final EnumMap<ReplayTransaction.RunwayState, Integer> runwayStates =
            new EnumMap<>(ReplayTransaction.RunwayState.class);
        private final List<ReplayTransaction.RunwayLossReason> runwayLosses = new ArrayList<>();
        private final List<ReplayTransaction.TerminalOutcome> terminalOutcomes = new ArrayList<>();
        private final List<RecordDisposition> dispositions = new ArrayList<>();
        private boolean onlyMailboxCallbacks = true;

        private RecordingMetrics(ActorMailbox mailbox) {
            this.mailbox = mailbox;
        }

        @Override
        public void phaseChanged(ReplayTransaction.Phase phase, int delta) {
            recordThread();
            phases.merge(phase, delta, Integer::sum);
        }

        @Override
        public void runwayStateChanged(ReplayTransaction.RunwayState state, int delta) {
            recordThread();
            runwayStates.merge(state, delta, Integer::sum);
        }

        @Override
        public void runwayLost(ReplayTransaction.RunwayLossReason reason) {
            recordThread();
            runwayLosses.add(reason);
        }

        @Override
        public void terminalOutcome(ReplayTransaction.TerminalOutcome outcome) {
            recordThread();
            terminalOutcomes.add(outcome);
        }

        @Override
        public void disposition(RecordDisposition disposition) {
            recordThread();
            dispositions.add(disposition);
        }

        private int phaseCount(ReplayTransaction.Phase phase) {
            return phases.getOrDefault(phase, 0);
        }

        private int runwayCount(ReplayTransaction.RunwayState state) {
            return runwayStates.getOrDefault(state, 0);
        }

        private int totalActivePhases() {
            return phases.values().stream().mapToInt(Integer::intValue).sum();
        }

        private int totalRunwayStates() {
            return runwayStates.values().stream().mapToInt(Integer::intValue).sum();
        }

        private void recordThread() {
            onlyMailboxCallbacks &= mailbox.inMailbox();
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
        public SourcePartitionKey sourcePartition() {
            return new SourcePartitionKey(id.topic(), id.partition(), id.sourceGeneration());
        }

        @Override
        public void closeContext() {
            contextCloses.incrementAndGet();
        }

        @Override
        public void releaseWithoutCommit() {
            // Test handles have no source-owned parent context.
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
