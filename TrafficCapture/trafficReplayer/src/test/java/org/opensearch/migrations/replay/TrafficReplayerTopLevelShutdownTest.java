package org.opensearch.migrations.replay;


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
