/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.migrations.replay;

// REBUILD-LIMBO(G10) -- the inherited subprocess and legacy-owner evidence remains recoverable
// through the final completeness sweep. The live G9 replacement follows these marked regions.
// REBUILD-LIMBO-START(G10)
/*

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.opensearch.migrations.replay.lifecycle.ReplayIdentity;
import org.opensearch.migrations.replay.lifecycle.ResourceOwnership;
import org.opensearch.migrations.replay.lifecycle.TargetConnectionOwner;
import org.opensearch.migrations.replay.lifecycle.TargetExchangeState;
import org.opensearch.migrations.tracing.TestContext;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class ReplayProcessFatalHandlerTest {
    private static final int PROCESS_TIMEOUT_SECONDS = 15;

    @Test
    void emitsMetricFlushesDiagnosticsAndTerminatesExactlyOnce() {
        var events = new ArrayList<String>();
        var stderrBytes = new RecordingOutputStream(() -> {
            events.add("stderr-flush");
        });
        var errorStream = new PrintStream(stderrBytes, false, StandardCharsets.UTF_8);
        var handler = new ReplayProcessFatalHandler(
            ReplayProcessFatalHandler.Reason.EVENT_LOOP_TERMINATED,
            reason -> {
                Assertions.assertEquals(ReplayProcessFatalHandler.Reason.EVENT_LOOP_TERMINATED, reason);
                events.add("metric");
            },
            exitCode -> {
                Assertions.assertEquals(
                    ReplayProcessFatalHandler.Reason.EVENT_LOOP_TERMINATED.exitCode(),
                    exitCode
                );
                events.add("terminate");
            },
            () -> events.add("log4j-flush"),
            errorStream,
            ignored -> events.add("fatal-shutdown")
        );
        var failure = new Error(
            "event-loop owner died",
            new IllegalStateException("root event-loop failure")
        );

        handler.onFatal(failure);
        handler.onFatal(new Error("duplicate fatal signal"));

        Assertions.assertEquals(
            List.of(
                "metric",
                "log4j-flush",
                "stderr-flush",
                "fatal-shutdown",
                "terminate"
            ),
            events
        );
        var stderr = stderrBytes.toString(StandardCharsets.UTF_8);
        Assertions.assertTrue(
            stderr.contains(ReplayProcessFatalHandler.Reason.EVENT_LOOP_TERMINATED.message())
        );
        Assertions.assertTrue(stderr.contains("java.lang.Error: event-loop owner died"));
        Assertions.assertTrue(stderr.contains("Caused by: java.lang.IllegalStateException: root event-loop failure"));
    }

    @Test
    void subprocessExitsWithReasonSpecificExitCodeAfterWritingFatalDiagnostics() throws Exception {
        var process = launchChild(FatalChild.class);
        var exited = process.waitFor(PROCESS_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        if (!exited) {
            process.destroyForcibly();
            Assertions.fail("fatal-handler subprocess did not terminate promptly");
        }
        var output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);

        Assertions.assertEquals(
            ReplayProcessFatalHandler.Reason.EVENT_LOOP_TERMINATED.exitCode(),
            process.exitValue()
        );
        Assertions.assertTrue(
            output.contains(ReplayProcessFatalHandler.Reason.EVENT_LOOP_TERMINATED.message()),
            output
        );
        Assertions.assertTrue(output.contains("java.lang.Error: subprocess event-loop failure"), output);
        Assertions.assertTrue(
            output.contains("Caused by: java.lang.IllegalStateException: subprocess root cause"),
            output
        );
    }

*/
// REBUILD-LIMBO-END(G10)
    /** Proves R19's event-loop process boundary with a live connection owner. */
// REBUILD-LIMBO-START(G10)
/*
    @Test
    void liveEventLoopDeathExitsProcessWithCode80() throws Exception {
        var process = launchChild(EventLoopFatalChild.class);
        var exited = process.waitFor(PROCESS_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        if (!exited) {
            process.destroyForcibly();
            Assertions.fail("event-loop fatal subprocess did not terminate promptly");
        }
        var output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);

        Assertions.assertEquals(
            ReplayProcessFatalHandler.Reason.EVENT_LOOP_TERMINATED.exitCode(),
            process.exitValue()
        );
        Assertions.assertTrue(output.contains("live-event-loop-session"), output);
        Assertions.assertTrue(
            output.contains(ReplayProcessFatalHandler.Reason.EVENT_LOOP_TERMINATED.message()),
            output
        );
    }

    @Test
    void fatalHaltCodesAreDistinctFromNormalReplayerExitCodes() {
        Assertions.assertEquals(80, ReplayProcessFatalHandler.Reason.EVENT_LOOP_TERMINATED.exitCode());
        Assertions.assertEquals(89, ReplayProcessFatalHandler.Reason.UNEXPECTED_FATAL_ERROR.exitCode());
        for (var reason : ReplayProcessFatalHandler.Reason.values()) {
            Assertions.assertFalse(
                List.of(1, 2, 3, 4, 5).contains(reason.exitCode()),
                () -> reason + " must not reuse a normal System.exit code"
            );
        }
    }

    private static Process launchChild(Class<?> childClass) throws IOException {
        var javaExecutable = System.getProperty("java.home")
            + File.separator
            + "bin"
            + File.separator
            + "java";
        return new ProcessBuilder(
            javaExecutable,
            "-cp",
            System.getProperty("java.class.path"),
            childClass.getName()
        )
            .redirectErrorStream(true)
            .start();
    }

    public static final class FatalChild {
        private FatalChild() {}

        public static void main(String[] args) {
            var handler = new ReplayProcessFatalHandler(
                ReplayProcessFatalHandler.Reason.EVENT_LOOP_TERMINATED,
                ignored -> {},
                new ProcessSupervisor()
            );
            handler.onFatal(
                new Error(
                    "subprocess event-loop failure",
                    new IllegalStateException("subprocess root cause")
                )
            );
            throw new AssertionError("System.exit returned");
        }
    }

    public static final class EventLoopFatalChild {
        private EventLoopFatalChild() {}

        public static void main(String[] args) throws Exception {
            try (var context = TestContext.noOtelTracking()) {
                var connectionPool = new ClientConnectionPool(
                    (eventLoop, channelContext) -> {
                        throw new AssertionError("fatal child must not open a target channel");
                    },
                    "event-loop-fatal-child",
                    1
                );
                var fatalHandler = new ReplayProcessFatalHandler(
                    ReplayProcessFatalHandler.Reason.EVENT_LOOP_TERMINATED,
                    ignored -> {},
                    new ProcessSupervisor()
                );
                var orchestrator = new RequestSenderOrchestrator(
                    connectionPool,
                    (session, requestContext, firstTargetWriteSubmitted) -> {
                        throw new AssertionError("fatal child must not start target work");
                    },
                    RequestSenderOrchestrator.noSourceTerminationObligations(),
                    TargetConnectionOwner.Metrics.NOOP,
                    TargetExchangeState.Metrics.NOOP,
                    ResourceOwnership.Metrics.NOOP,
                    new TargetConnectionOwner.RequestLifecycleSink() {
                        @Override
                        public java.util.concurrent.CompletionStage<Void> connectionRequestFinished(
                            ReplayIdentity.PartitionGenerationId partitionGenerationId,
                            ReplayIdentity.ReplayRequestId requestId
                        ) {
                            throw new AssertionError("fatal child must not finish a request turn");
                        }

                        @Override
                        public java.util.concurrent.CompletionStage<Void> requestProcessingFinished(
                            ReplayIdentity.PartitionGenerationId partitionGenerationId,
                            ReplayIdentity.ReplayRequestId requestId
                        ) {
                            throw new AssertionError("fatal child must not finish request processing");
                        }
                    },
                    fatalHandler
                );
                var requestContext = context.getTestConnectionRequestContext("live-event-loop-session", 0);
                orchestrator.transactionRuntime(
                    requestContext.getReplayerRequestKey(),
                    new ReplayIdentity.PartitionGenerationId(
                        new org.apache.kafka.common.TopicPartition("traffic", 0),
                        1
                    ),
                    requestContext.getChannelKeyContext()
                );

                connectionPool.shutdownNow().get(30, TimeUnit.SECONDS);
                new CountDownLatch(1).await(10, TimeUnit.SECONDS);
                throw new AssertionError("live event-loop termination did not exit the process");
            }
        }
    }

    private static final class RecordingOutputStream extends OutputStream {
        private final ByteArrayOutputStream delegate = new ByteArrayOutputStream();
        private final Runnable flushObserver;

        private RecordingOutputStream(Runnable flushObserver) {
            this.flushObserver = flushObserver;
        }

        @Override
        public void write(int value) {
            delegate.write(value);
        }

        @Override
        public void write(byte[] bytes, int offset, int length) {
            delegate.write(bytes, offset, length);
        }

        @Override
        public void flush() {
            flushObserver.run();
        }

        private String toString(java.nio.charset.Charset charset) {
            return delegate.toString(charset);
        }
    }
}

*/
// REBUILD-LIMBO-END(G10)

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.opensearch.migrations.replay.TrafficReplayerTopLevel.ManagedPhysicalTupleSink;
import org.opensearch.migrations.replay.TrafficReplayerTopLevel.ManagedTupleTransformer;
import org.opensearch.migrations.replay.datahandlers.NettyPacketToHttpConsumer;
import org.opensearch.migrations.replay.datatypes.HttpRequestTransformationStatus;
import org.opensearch.migrations.replay.identity.ConnectionProcessingId;
import org.opensearch.migrations.replay.intake.PartitionIntakeState;
import org.opensearch.migrations.replay.lifecycle.ReplayOutcomes.RequestPreparationReady;
import org.opensearch.migrations.replay.lifecycle.ReplayOutcomes.RequestPreparationResult;
import org.opensearch.migrations.replay.lifecycle.ReplayOutcomes.RetryDecision;
import org.opensearch.migrations.replay.lifecycle.RequestReplayOwner;
import org.opensearch.migrations.replay.lifecycle.TargetChannelPort;
import org.opensearch.migrations.replay.sink.TupleWriter;
import org.opensearch.migrations.replay.tracing.IReplayContexts;
import org.opensearch.migrations.replay.tracing.RootReplayerContext;
import org.opensearch.migrations.tracing.InMemoryInstrumentationBundle;

import io.netty.channel.EventLoop;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.util.concurrent.DefaultThreadFactory;
import org.apache.kafka.clients.consumer.MockConsumer;
import org.apache.kafka.clients.consumer.OffsetResetStrategy;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class ReplayProcessFatalHandlerTest {

    /**
     * Proves R19 at the process boundary without terminating the test JVM: a required target owner is
     * deliberately stopped while it still has queued work, and the deployed failure chain reaches exit 80.
     */
    @Test
    void liveTargetEventLoopDeathUnderLoadStopsInputAndStartsExit80() throws Exception {
        var eventLoopGroup = new NioEventLoopGroup(
            1,
            new DefaultThreadFactory("g9-event-loop-fatal-test")
        );
        var eventLoop = eventLoopGroup.next();
        var blockerStarted = new CountDownLatch(1);
        var releaseBlocker = new CountDownLatch(1);
        var queuedWorkRan = new AtomicInteger();
        var exitCode = new CompletableFuture<Integer>();
        var watchdogAction = new AtomicReference<Runnable>();
        var replayerReference = new AtomicReference<TrafficReplayerTopLevel<
            NettyPacketToHttpConsumer.PreparedRequest,
            AggregatedRawResponse,
            Map<String, Object>
        >>();
        var stderr = new ByteArrayOutputStream();

        eventLoop.execute(() -> {
            blockerStarted.countDown();
            await(releaseBlocker);
        });
        for (var i = 0; i < 100; i++) {
            eventLoop.execute(queuedWorkRan::incrementAndGet);
        }
        Assertions.assertTrue(blockerStarted.await(10, TimeUnit.SECONDS));

        try (var telemetry = new InMemoryInstrumentationBundle(false, true)) {
            var rootContext = new RootReplayerContext(telemetry.openTelemetrySdk);
            var supervisor = new ProcessSupervisor(
                rootContext.getReplayProcessFatalMetrics(),
                signal -> replayerReference.get().stopNewInputForFatal(signal),
                exitCode::complete,
                ignored -> Assertions.fail("watchdog halt must not run before its bound"),
                (delay, action) -> {
                    Assertions.assertEquals(ProcessSupervisor.EXIT_WATCHDOG_LIMIT, delay);
                    watchdogAction.set(action);
                },
                ignored -> Assertions.fail("thread dump must wait for the watchdog bound"),
                () -> {},
                new PrintStream(stderr, false, StandardCharsets.UTF_8)
            );
            var consumer = new MockConsumer<String, byte[]>(OffsetResetStrategy.EARLIEST);
            var replayer = new TrafficReplayerTopLevel<>(
                consumer,
                rootContext,
                configuration(eventLoop),
                Duration.ZERO,
                supervisor.failureSink()
            );
            replayerReference.set(replayer);

            eventLoopGroup.shutdownGracefully(0, 0, TimeUnit.MILLISECONDS);
            releaseBlocker.countDown();

            Assertions.assertEquals(
                ProcessSupervisor.EVENT_LOOP_EXIT_CODE,
                exitCode.get(10, TimeUnit.SECONDS)
            );
            Assertions.assertTrue(eventLoopGroup.terminationFuture().await(10, TimeUnit.SECONDS));
            Assertions.assertEquals(100, queuedWorkRan.get());
            var signal = supervisor.firstFatalSignal().orElseThrow();
            Assertions.assertEquals(ProcessSupervisor.Reason.EVENT_LOOP_TERMINATED, signal.reason());
            Assertions.assertEquals("target event loop worker 0", signal.owner());
            Assertions.assertEquals("termination future", signal.operation());
            Assertions.assertTrue(replayer.sourceInputs().isClosed());
            Assertions.assertFalse(
                replayer.intakeInputs().submit(
                    new org.opensearch.migrations.replay.intake.ReplayIntakeInput.FinalizedArchivePartitionEnd(
                        new org.opensearch.migrations.replay.identity.PartitionGenerationId(
                            new org.apache.kafka.common.TopicPartition("traffic", 0),
                            1
                        )
                    )
                )
            );
            Assertions.assertNotNull(watchdogAction.get());
            Assertions.assertTrue(stderr.toString(StandardCharsets.UTF_8).contains(
                "target event loop worker 0"
            ));
        } finally {
            releaseBlocker.countDown();
            eventLoopGroup.shutdownGracefully(0, 0, TimeUnit.MILLISECONDS)
                .awaitUninterruptibly(10, TimeUnit.SECONDS);
        }
    }

    @Test
    void fatalExitCodesRetainTheDesignedClassification() {
        Assertions.assertEquals(
            80,
            ProcessSupervisor.Reason.EVENT_LOOP_TERMINATED.exitCode()
        );
        Assertions.assertEquals(
            89,
            ProcessSupervisor.Reason.UNEXPECTED_FATAL_ERROR.exitCode()
        );
    }

    private static TrafficReplayerTopLevel.Configuration<
        NettyPacketToHttpConsumer.PreparedRequest,
        AggregatedRawResponse,
        Map<String, Object>
    > configuration(EventLoop eventLoop) {
        return new TrafficReplayerTopLevel.Configuration<>(
            Clock.systemUTC(),
            System::nanoTime,
            sourceTime -> sourceTime,
            ignored -> eventLoop,
            List.of(eventLoop),
            TrafficReplayerTopLevel.deployedOwnerTaskRunner(),
            (connectionId, context) -> skippedPreparer(),
            terminalRetryPolicy(),
            (connectionId, owner, context) -> unusedTargetChannel(),
            (context, result) -> Map.of(),
            noOpReleaser(),
            ignored -> noOpTransformer(),
            ignored -> noOpSink(),
            ignored -> {},
            Duration.ZERO,
            new PartitionIntakeState.BrokerTimeConfiguration(
                30_000,
                0,
                5_000
            ),
            4,
            1
        );
    }

    private static RequestReplayOwner.RequestPreparer<
        HttpMessageAndTimestamp.Request,
        NettyPacketToHttpConsumer.PreparedRequest
    > skippedPreparer() {
        return (requestId, sourceRequest, context) ->
            new RequestReplayOwner.PreparationOperation<>() {
                private final CompletionStage<RequestPreparationResult<
                    NettyPacketToHttpConsumer.PreparedRequest
                >> completion = CompletableFuture.completedFuture(
                    new RequestPreparationReady<>(
                        null,
                        HttpRequestTransformationStatus.skipped()
                    )
                );

                @Override
                public CompletionStage<RequestPreparationResult<
                    NettyPacketToHttpConsumer.PreparedRequest
                >> completion() {
                    return completion;
                }

                @Override
                public void cancel(CancellationException cause) {}
            };
    }

    private static RequestReplayOwner.RetryPolicy<
        NettyPacketToHttpConsumer.PreparedRequest,
        AggregatedRawResponse,
        HttpMessageAndTimestamp.Response
    > terminalRetryPolicy() {
        return new RequestReplayOwner.RetryPolicy<>() {
            @Override
            public boolean requiresSourceResponse(
                NettyPacketToHttpConsumer.PreparedRequest preparedRequest,
                AggregatedRawResponse targetResponse
            ) {
                return false;
            }

            @Override
            public RetryDecision decide(
                NettyPacketToHttpConsumer.PreparedRequest preparedRequest,
                AggregatedRawResponse targetResponse,
                RequestReplayOwner.RetrySourceResponse<HttpMessageAndTimestamp.Response> sourceResponse
            ) {
                return new RetryDecision.TargetServerAttemptsFinished();
            }

            @Override
            public Duration retryDelay(int completedAttemptCount) {
                return Duration.ZERO;
            }
        };
    }

    private static TargetChannelPort<
        NettyPacketToHttpConsumer.PreparedRequest,
        AggregatedRawResponse
    > unusedTargetChannel() {
        return new TargetChannelPort<>() {
            @Override
            public Attempt<AggregatedRawResponse> startAttempt(
                AttemptInput<NettyPacketToHttpConsumer.PreparedRequest> input
            ) {
                throw new AssertionError("fatal owner test must not start a target attempt");
            }

            @Override
            public CompletionStage<Void> close(ConnectionProcessingId connectionProcessingId) {
                return CompletableFuture.completedFuture(null);
            }
        };
    }

    private static RequestReplayOwner.ResourceReleaser<
        HttpMessageAndTimestamp.Request,
        NettyPacketToHttpConsumer.PreparedRequest,
        AggregatedRawResponse,
        HttpMessageAndTimestamp.Response
    > noOpReleaser() {
        return new RequestReplayOwner.ResourceReleaser<>() {
            @Override
            public void releaseSourceRequest(HttpMessageAndTimestamp.Request sourceRequest) {}

            @Override
            public void releasePreparedRequest(
                NettyPacketToHttpConsumer.PreparedRequest preparedRequest
            ) {
                preparedRequest.close();
            }

            @Override
            public void releaseTargetResponse(AggregatedRawResponse targetResponse) {}

            @Override
            public void releaseSourceResponse(HttpMessageAndTimestamp.Response sourceResponse) {}
        };
    }

    private static ManagedTupleTransformer<Map<String, Object>> noOpTransformer() {
        return new ManagedTupleTransformer<>() {
            @Override
            public TupleWriter.TupleTransformation<Map<String, Object>> transform(
                IReplayContexts.ITupleHandlingContext replayContext,
                Map<String, Object> tuple
            ) {
                return new TupleWriter.TransformedTuple<>(tuple);
            }

            @Override
            public void close() {}
        };
    }

    private static ManagedPhysicalTupleSink<Map<String, Object>> noOpSink() {
        return new ManagedPhysicalTupleSink<>() {
            @Override
            public CompletionStage<Void> write(
                IReplayContexts.ITupleHandlingContext replayContext,
                Map<String, Object> tuple
            ) {
                return CompletableFuture.completedFuture(null);
            }

            @Override
            public void flush() {}

            @Override
            public void close() {}
        };
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError("test event-loop blocker was interrupted", interrupted);
        }
    }
}
