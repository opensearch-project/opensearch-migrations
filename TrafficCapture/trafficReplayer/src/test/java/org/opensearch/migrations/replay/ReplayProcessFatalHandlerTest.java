/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.migrations.replay;


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
