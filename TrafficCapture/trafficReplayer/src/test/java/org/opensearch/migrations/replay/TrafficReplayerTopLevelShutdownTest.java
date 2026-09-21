package org.opensearch.migrations.replay;

import java.lang.reflect.Field;
import java.net.URI;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.opensearch.migrations.replay.lifecycle.RecordDisposition;
import org.opensearch.migrations.replay.lifecycle.RecordDispositionLedger;
import org.opensearch.migrations.replay.lifecycle.ReplayIdentity.KafkaRecordId;
import org.opensearch.migrations.replay.lifecycle.ReplayIdentity.SourcePartitionKey;
import org.opensearch.migrations.replay.lifecycle.ReplayIntakeMailbox;
import org.opensearch.migrations.replay.tracing.IRootReplayerContext;
import org.opensearch.migrations.transform.IAuthTransformerFactory;
import org.opensearch.migrations.transform.IJsonTransformer;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.slf4j.event.Level;

class TrafficReplayerTopLevelShutdownTest {

    /** Proves rebuild plan S2: runtime shutdown hooks signal shutdown without joining owner work. */
    @Test
    void runtimeShutdownHookSignalsWithoutWaitingForOwnedShutdown() {
        var replayer = Mockito.mock(TrafficReplayerTopLevel.class);
        var shutdown = new CompletableFuture<Void>();
        Mockito.when(replayer.shutdown(null)).thenReturn(shutdown);

        TrafficReplayer.awaitReplayerShutdown(replayer);

        Mockito.verify(replayer).shutdown(null);
        Assertions.assertFalse(shutdown.isDone());
    }

    /** Proves processing architecture §10.3: fatal shutdown never waits on a failed owner. */
    @Test
    void fatalShutdownSkipsActorAndNettyCleanup() throws Exception {
        var connectionPool = Mockito.mock(ClientConnectionPool.class);
        var replayEngine = Mockito.mock(ReplayEngine.class);
        var replayer = new TrafficReplayerTopLevel(
            Mockito.mock(IRootReplayerContext.class),
            URI.create("http://localhost:9200"),
            Mockito.mock(IAuthTransformerFactory.class),
            () -> Mockito.mock(IJsonTransformer.class),
            connectionPool,
            1,
            Mockito.mock(TrafficReplayerTopLevel.IStreamableWorkTracker.class)
        );
        currentReplayEngine(replayer).set(replayEngine);
        var fatalError = new Error("owner failed");

        var shutdown = replayer.shutdown(fatalError);

        Assertions.assertTrue(shutdown.isCompletedExceptionally());
        Mockito.verifyNoInteractions(replayEngine);
        Mockito.verifyNoInteractions(connectionPool);
    }

    /** Proves rebuild plan S2's single named bound for normal remaining-work waiting. */
    @Test
    void normalWrapUpWaitsOnlyOnce() throws Exception {
        var replayer = new TimeoutRecordingReplayer();

        replayer.wrapUpWorkAndEmitSummary(
            Mockito.mock(ReplayEngine.class),
            Mockito.mock(CapturedTrafficToHttpTransactionAccumulator.class)
        );

        Assertions.assertEquals(1, replayer.waitCalls);
        Assertions.assertEquals(Duration.ofMinutes(2), replayer.lastWait);
    }

    @Test
    void shutdownCrossesTheIntakeFenceAndSettlesActorsBeforeStoppingNetty() throws Exception {
        var connectionPool = Mockito.mock(ClientConnectionPool.class);
        var actorShutdown = new CompletableFuture<Void>();
        var nettyShutdown = new CompletableFuture<Void>();
        Mockito.when(connectionPool.shutdownNow()).thenReturn(nettyShutdown);
        var replayEngine = Mockito.mock(ReplayEngine.class);
        Mockito.when(replayEngine.shutdownConnections(Mockito.any())).thenReturn(actorShutdown);
        var replayer = new TrafficReplayerTopLevel(
            Mockito.mock(IRootReplayerContext.class),
            URI.create("http://localhost:9200"),
            Mockito.mock(IAuthTransformerFactory.class),
            () -> Mockito.mock(IJsonTransformer.class),
            connectionPool,
            1,
            Mockito.mock(TrafficReplayerTopLevel.IStreamableWorkTracker.class)
        );
        var intakeMailbox = new ReplayIntakeMailbox();
        replayer.intakeMailboxRef.set(intakeMailbox);
        currentReplayEngine(replayer).set(replayEngine);

        var shutdownInvocation = CompletableFuture.supplyAsync(() -> replayer.shutdown(null));
        var shutdown = shutdownInvocation.get(5, TimeUnit.SECONDS);

        Mockito.verifyNoInteractions(replayEngine);
        Mockito.verify(connectionPool, Mockito.never()).shutdownNow();

        intakeMailbox.runUntilIdle();
        Mockito.verify(replayEngine).shutdownConnections(Mockito.any());
        Mockito.verify(connectionPool, Mockito.never()).shutdownNow();
        Assertions.assertFalse(shutdown.isDone());

        actorShutdown.complete(null);
        Mockito.verify(connectionPool).shutdownNow();
        Assertions.assertFalse(shutdown.isDone());

        nettyShutdown.complete(null);
        shutdown.get(5, TimeUnit.SECONDS);
    }

    @Test
    void shutdownWaitsForCommitInvocationBeforeStoppingNetty() throws Exception {
        var connectionPool = Mockito.mock(ClientConnectionPool.class);
        var actorShutdown = new CompletableFuture<Void>();
        var nettyShutdown = new CompletableFuture<Void>();
        Mockito.when(connectionPool.shutdownNow()).thenReturn(nettyShutdown);
        var replayEngine = Mockito.mock(ReplayEngine.class);
        Mockito.when(replayEngine.shutdownConnections(Mockito.any())).thenReturn(actorShutdown);
        var replayer = new TrafficReplayerTopLevel(
            Mockito.mock(IRootReplayerContext.class),
            URI.create("http://localhost:9200"),
            Mockito.mock(IAuthTransformerFactory.class),
            () -> Mockito.mock(IJsonTransformer.class),
            connectionPool,
            1,
            Mockito.mock(TrafficReplayerTopLevel.IStreamableWorkTracker.class)
        );
        var intakeMailbox = new ReplayIntakeMailbox();
        var dispositionLedger = new RecordDispositionLedger(intakeMailbox);
        var commitAcknowledgement = new CompletableFuture<Void>();
        var recordHandle = new TestRecordHandle(commitAcknowledgement);
        replayer.intakeMailboxRef.set(intakeMailbox);
        replayer.dispositionLedgerRef.set(dispositionLedger);
        currentReplayEngine(replayer).set(replayEngine);
        var registration = dispositionLedger.register(recordHandle, "transaction");
        intakeMailbox.runUntilIdle();
        registration.toCompletableFuture().join();

        var shutdown = CompletableFuture.supplyAsync(() -> replayer.shutdown(null))
            .get(5, TimeUnit.SECONDS);
        intakeMailbox.runUntilIdle();
        Mockito.verify(replayEngine).shutdownConnections(Mockito.any());

        actorShutdown.complete(null);
        Mockito.verify(connectionPool, Mockito.never()).shutdownNow();
        var disposition = dispositionLedger.dispose(
            recordHandle.id(),
            "transaction",
            new RecordDisposition.Commit("replay-succeeded")
        );
        intakeMailbox.runUntilIdle();
        disposition.toCompletableFuture().join();
        Mockito.verify(connectionPool).shutdownNow();
        Assertions.assertFalse(
            commitAcknowledgement.isDone(),
            "Kafka's later commit result must not hold replay resources"
        );
        Assertions.assertFalse(shutdown.isDone());

        nettyShutdown.complete(null);
        shutdown.get(5, TimeUnit.SECONDS);
    }

    @Test
    void shutdownStartsDirectlyAfterTheIntakeOwnerRelinquishesTheMailbox() throws Exception {
        var connectionPool = Mockito.mock(ClientConnectionPool.class);
        var actorShutdown = new CompletableFuture<Void>();
        var nettyShutdown = new CompletableFuture<Void>();
        Mockito.when(connectionPool.shutdownNow()).thenReturn(nettyShutdown);
        var replayEngine = Mockito.mock(ReplayEngine.class);
        Mockito.when(replayEngine.shutdownConnections(Mockito.any())).thenReturn(actorShutdown);
        var replayer = new TrafficReplayerTopLevel(
            Mockito.mock(IRootReplayerContext.class),
            URI.create("http://localhost:9200"),
            Mockito.mock(IAuthTransformerFactory.class),
            () -> Mockito.mock(IJsonTransformer.class),
            connectionPool,
            1,
            Mockito.mock(TrafficReplayerTopLevel.IStreamableWorkTracker.class)
        );
        var intakeMailbox = new ReplayIntakeMailbox();
        var dispositionLedger = new RecordDispositionLedger(intakeMailbox);
        replayer.intakeMailboxRef.set(intakeMailbox);
        replayer.dispositionLedgerRef.set(dispositionLedger);
        currentReplayEngine(replayer).set(replayEngine);
        replayer.finishIntakeLifecycle(intakeMailbox);

        var lateRegistration = dispositionLedger.register(
            new TestRecordHandle(CompletableFuture.completedFuture(null)),
            "late-transaction"
        );
        Assertions.assertThrows(
            java.util.concurrent.CompletionException.class,
            () -> lateRegistration.toCompletableFuture().join()
        );
        var shutdown = CompletableFuture.supplyAsync(() -> replayer.shutdown(null))
            .get(5, TimeUnit.SECONDS);

        Mockito.verify(replayEngine).shutdownConnections(Mockito.any());
        Mockito.verify(connectionPool, Mockito.never()).shutdownNow();
        actorShutdown.complete(null);
        Mockito.verify(connectionPool).shutdownNow();
        nettyShutdown.complete(null);
        shutdown.get(5, TimeUnit.SECONDS);
    }

    @SuppressWarnings("unchecked")
    private static AtomicReference<ReplayEngine> currentReplayEngine(
        TrafficReplayerTopLevel replayer
    ) throws ReflectiveOperationException {
        Field field = TrafficReplayerTopLevel.class.getDeclaredField("currentReplayEngine");
        field.setAccessible(true);
        return (AtomicReference<ReplayEngine>) field.get(replayer);
    }

    private static final class TimeoutRecordingReplayer extends TrafficReplayerTopLevel {
        private int waitCalls;
        private Duration lastWait;

        private TimeoutRecordingReplayer() {
            super(
                Mockito.mock(IRootReplayerContext.class),
                URI.create("http://localhost:9200"),
                Mockito.mock(IAuthTransformerFactory.class),
                () -> Mockito.mock(IJsonTransformer.class),
                Mockito.mock(ClientConnectionPool.class),
                1,
                Mockito.mock(TrafficReplayerTopLevel.IStreamableWorkTracker.class)
            );
        }

        @Override
        protected void waitForRemainingWork(Level logLevel, Duration timeout)
            throws java.util.concurrent.TimeoutException {
            waitCalls++;
            lastWait = timeout;
            throw new java.util.concurrent.TimeoutException("test timeout");
        }
    }

    private static final class TestRecordHandle implements RecordDispositionLedger.RecordHandle {
        private final KafkaRecordId id = new KafkaRecordId("topic", 0, 1, 1);
        private final CompletableFuture<Void> commitAcknowledgement;

        private TestRecordHandle(CompletableFuture<Void> commitAcknowledgement) {
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
        public void closeContext() {}

        @Override
        public void releaseWithoutCommit() {}

        @Override
        public CompletableFuture<Void> commit() {
            return commitAcknowledgement;
        }
    }
}
