package org.opensearch.migrations.replay;

// REBUILD-LIMBO(G10) -- inherited test bodies remain marked and recoverable; the live replacement
// follows the marked regions. Javadoc stays outside the regions and keeps its blame.
// Resolve each region to dead, keep, or refactor deliberately. If a member is deleted, delete its
// javadoc with it. See AGENTS.md section 8a.
// Test carried byte-identical. Unresolved: AsyncPermitPool IRootReplayerContext ReplayEngine ReplayIntakeOwner ReplayProgressController . Per AGENTS.md section 4 an inherited test may stay broken while the architectures are partly connected; this one is restored by the milestone that rebuilds its subject, keeping its assertions conceptually stable while changing the mechanics.
// Un-mark a member by deleting the delimiter lines around it and splitting this region; the
// code between them is verbatim, so blame survives. Read this before writing anything new

// REBUILD-LIMBO-START(G10)
/*

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

*/
// REBUILD-LIMBO-END(G10)

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.opensearch.migrations.replay.testing.TestEventLoop;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class TrafficReplayerTopLevelShutdownTest {

    @Test
    void normalShutdownUsesTheOrderlyDrainBeforeClosingTargetOwners() throws Exception {
        var events = new CopyOnWriteArrayList<String>();
        var sourcePollEntered = new CountDownLatch(1);
        var sourceWakeup = new CountDownLatch(1);
        var lifecycle = new TrafficReplayer.ReplayLifecycle() {
            @Override
            public void start() {
                events.add("start");
            }

            @Override
            public void runSourceOnce() {
                events.add("source-poll");
                sourcePollEntered.countDown();
                await(sourceWakeup);
            }

            @Override
            public void wakeSourceOwner() {
                events.add("source-wakeup");
                sourceWakeup.countDown();
            }

            @Override
            public void closeOrderly() {
                events.add("g8-orderly-drain");
            }

            @Override
            public void closeTargetOwnersAfterOrderly() {
                events.add("close-target-owners");
            }
        };
        var supervisor = supervisor(events);
        var application = new TrafficReplayer.SupervisedReplayApplication(
            lifecycle,
            supervisor
        );
        var running = CompletableFuture.runAsync(application::run);
        Assertions.assertTrue(sourcePollEntered.await(10, TimeUnit.SECONDS));

        application.requestAndAwaitOrderlyShutdown();
        running.get(10, TimeUnit.SECONDS);

        Assertions.assertEquals(
            List.of(
                "start",
                "source-poll",
                "source-wakeup",
                "g8-orderly-drain",
                "close-target-owners"
            ),
            events
        );
        Assertions.assertTrue(application.orderlyShutdownRequested());
        Assertions.assertTrue(application.orderlyShutdownFinished().isDone());
    }

    @Test
    void fatalSourceOwnerFailureSkipsTheOrderlyDrainAndTargetJoin() {
        var events = new CopyOnWriteArrayList<String>();
        var lifecycle = new TrafficReplayer.ReplayLifecycle() {
            @Override
            public void start() {
                events.add("start");
            }

            @Override
            public void runSourceOnce() {
                events.add("source-poll");
                throw new IllegalStateException("Kafka owner escaped");
            }

            @Override
            public void wakeSourceOwner() {
                events.add("source-wakeup");
            }

            @Override
            public void closeOrderly() {
                events.add("unexpected-orderly-drain");
            }

            @Override
            public void closeTargetOwnersAfterOrderly() {
                events.add("unexpected-target-join");
            }
        };
        var supervisor = supervisor(events);
        var application = new TrafficReplayer.SupervisedReplayApplication(
            lifecycle,
            supervisor
        );

        application.run();
        application.requestAndAwaitOrderlyShutdown();

        Assertions.assertTrue(supervisor.fatalTerminationStarted());
        var signal = supervisor.firstFatalSignal().orElseThrow();
        Assertions.assertEquals(ProcessSupervisor.Reason.UNEXPECTED_FATAL_ERROR, signal.reason());
        Assertions.assertEquals("replay application", signal.owner());
        Assertions.assertEquals("startup or source loop", signal.operation());
        Assertions.assertEquals(
            List.of(
                "start",
                "source-poll",
                "watchdog",
                "stop-input",
                "diagnostic-flush",
                "exit:89"
            ),
            events
        );
        Assertions.assertTrue(application.orderlyShutdownFinished().isCompletedExceptionally());
    }

    @Test
    void fatalSignalWinsOverAnUnfinishedOrderlyOwnerWait() {
        var ownerTermination = new CompletableFuture<Void>();
        var fatalSignal = new CompletableFuture<ProcessSupervisor.FatalSignal>();
        fatalSignal.complete(new ProcessSupervisor.FatalSignal(
            ProcessSupervisor.Reason.UNEXPECTED_FATAL_ERROR,
            "replay intake owner",
            "owner loop",
            new Error("injected intake failure during orderly shutdown")
        ));

        Assertions.assertFalse(
            TrafficReplayerTopLevel.awaitOwnerTerminationUnlessFatal(
                ownerTermination,
                fatalSignal,
                () -> true
            )
        );
        Assertions.assertFalse(
            ownerTermination.isDone(),
            "fatal close must not wait for or manufacture failed-owner termination"
        );
    }

    @Test
    void shuttingDownEventLoopDoesNotReclassifyAnInvariantFailureAsOwnerLoss() {
        var eventLoop = new TestEventLoop();
        eventLoop.shutdown();

        Assertions.assertEquals(
            ProcessSupervisor.Reason.UNEXPECTED_FATAL_ERROR,
            TrafficReplayerTopLevel.classifyEventLoopOwnedFailure(
                eventLoop,
                new Error("in-loop invariant failure")
            )
        );
        Assertions.assertEquals(
            ProcessSupervisor.Reason.EVENT_LOOP_TERMINATED,
            TrafficReplayerTopLevel.classifyEventLoopOwnedFailure(
                eventLoop,
                new Error(
                    "required owner submission failed",
                    new RejectedExecutionException("event loop rejected task")
                )
            )
        );
    }

    private static ProcessSupervisor supervisor(List<String> events) {
        var watchdog = new AtomicReference<Runnable>();
        return new ProcessSupervisor(
            ignored -> {},
            ignored -> events.add("stop-input"),
            code -> events.add("exit:" + code),
            code -> events.add("halt:" + code),
            (delay, action) -> {
                Assertions.assertEquals(ProcessSupervisor.EXIT_WATCHDOG_LIMIT, delay);
                watchdog.set(action);
                events.add("watchdog");
            },
            ignored -> events.add("thread-dump"),
            () -> events.add("diagnostic-flush"),
            new PrintStream(
                new ByteArrayOutputStream(),
                false,
                StandardCharsets.UTF_8
            )
        );
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError("test source owner was interrupted", interrupted);
        }
    }
}
    /** Proves rebuild plan S2: runtime shutdown hooks signal shutdown without joining owner work. */
// REBUILD-LIMBO-START(G10)
/*
    @Test
    void runtimeShutdownHookSignalsWithoutWaitingForOwnedShutdown() {
        var replayer = Mockito.mock(TrafficReplayerTopLevel.class);
        var shutdown = new CompletableFuture<Void>();
        Mockito.when(replayer.shutdown(null)).thenReturn(shutdown);

        TrafficReplayer.awaitReplayerShutdown(replayer);

        Mockito.verify(replayer).shutdown(null);
        Assertions.assertFalse(shutdown.isDone());
    }

*/
// REBUILD-LIMBO-END(G10)
    /** Proves processing architecture §10.3: fatal shutdown never waits on a failed owner. */
// REBUILD-LIMBO-START(G10)
/*
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

*/
// REBUILD-LIMBO-END(G10)
    /** Proves rebuild plan S2's single named bound for normal remaining-work waiting. */
// REBUILD-LIMBO-START(G10)
/*
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

*/
// REBUILD-LIMBO-END(G10)
