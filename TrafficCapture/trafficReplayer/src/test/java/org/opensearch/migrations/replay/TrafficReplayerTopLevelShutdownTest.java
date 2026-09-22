package org.opensearch.migrations.replay;

import java.lang.reflect.Field;
import java.net.URI;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.opensearch.migrations.ExceptionTypeAllowlist;
import org.opensearch.migrations.replay.http.retries.BulkItemErrorClassifier;
import org.opensearch.migrations.replay.lifecycle.AsyncPermitPool;
import org.opensearch.migrations.replay.lifecycle.ReplayIntakeOwner;
import org.opensearch.migrations.replay.lifecycle.ReplayProgressController;
import org.opensearch.migrations.replay.lifecycle.ReplayReadGate;
import org.opensearch.migrations.replay.tracing.IRootReplayerContext;
import org.opensearch.migrations.replay.traffic.source.BufferedFlowController;
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
            Mockito.mock(TrafficReplayerTopLevel.IStreamableWorkTracker.class),
            new BulkItemErrorClassifier(),
            ExceptionTypeAllowlist.empty(),
            unexpectedProcessTerminator()
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
            Mockito.mock(TrafficReplayerTopLevel.IStreamableWorkTracker.class),
            new BulkItemErrorClassifier(),
            ExceptionTypeAllowlist.empty(),
            unexpectedProcessTerminator()
        );
        var intake = installIntakeOwner(replayer);
        currentReplayEngine(replayer).set(replayEngine);

        var shutdown = CompletableFuture.supplyAsync(() -> replayer.shutdown(null))
            .get(5, TimeUnit.SECONDS);
        Mockito.verify(replayEngine, Mockito.timeout(5_000)).shutdownConnections(Mockito.any());
        Mockito.verify(connectionPool, Mockito.never()).shutdownNow();
        Assertions.assertFalse(shutdown.isDone());

        actorShutdown.complete(null);
        Mockito.verify(connectionPool).shutdownNow();
        Assertions.assertFalse(shutdown.isDone());

        nettyShutdown.complete(null);
        shutdown.get(5, TimeUnit.SECONDS);
        intake.stop();
    }

    private static IntakeFixture installIntakeOwner(TrafficReplayerTopLevel replayer) {
        var owner = new ReplayIntakeOwner(failure -> {
            throw failure;
        });
        var permitPool = new AsyncPermitPool(1, owner::submitRequired, AsyncPermitPool.Metrics.NOOP);
        var progress = new ReplayProgressController(
            owner::submitRequired,
            new ReplayReadGate(Duration.ZERO, Mockito.mock(BufferedFlowController.class))
        );
        owner.configureOwnedComponents(permitPool, progress);
        owner.start();
        replayer.intakeOwner = owner;
        return new IntakeFixture(owner);
    }

    private record IntakeFixture(ReplayIntakeOwner owner) {
        private void stop() throws Exception {
            owner.stopOwner().toCompletableFuture().get(5, TimeUnit.SECONDS);
            owner.termination().toCompletableFuture().get(5, TimeUnit.SECONDS);
        }
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
                Mockito.mock(TrafficReplayerTopLevel.IStreamableWorkTracker.class),
                new BulkItemErrorClassifier(),
                ExceptionTypeAllowlist.empty(),
                unexpectedProcessTerminator()
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

    private static ReplayProcessFatalHandler.ProcessTerminator unexpectedProcessTerminator() {
        return exitCode -> Assertions.fail("Unexpected process termination with exit code " + exitCode);
    }

}
