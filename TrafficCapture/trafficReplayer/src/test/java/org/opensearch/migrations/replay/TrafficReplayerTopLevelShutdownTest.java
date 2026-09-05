package org.opensearch.migrations.replay;

import java.lang.reflect.Field;
import java.net.URI;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.opensearch.migrations.replay.lifecycle.ReplayIntakeMailbox;
import org.opensearch.migrations.replay.tracing.IRootReplayerContext;
import org.opensearch.migrations.transform.IAuthTransformerFactory;
import org.opensearch.migrations.transform.IJsonTransformer;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class TrafficReplayerTopLevelShutdownTest {

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
        replayer.intakeMailboxRef.set(intakeMailbox);
        currentReplayEngine(replayer).set(replayEngine);
        replayer.finishIntakeLifecycle(intakeMailbox);

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
}
