/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.migrations.replay;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.opensearch.migrations.replay.TrafficReplayerTopLevel.ManagedPhysicalTupleSink;
import org.opensearch.migrations.replay.TrafficReplayerTopLevel.ManagedTupleTransformer;
import org.opensearch.migrations.replay.datahandlers.NettyPacketToHttpConsumer;
import org.opensearch.migrations.replay.datatypes.ByteBufList;
import org.opensearch.migrations.replay.datatypes.ByteBufListProducer;
import org.opensearch.migrations.replay.datatypes.HttpRequestTransformationStatus;
import org.opensearch.migrations.replay.identity.CapturedConnectionId;
import org.opensearch.migrations.replay.identity.ConnectionProcessingId;
import org.opensearch.migrations.replay.identity.KafkaRecordId;
import org.opensearch.migrations.replay.identity.PartitionBatchRequestId;
import org.opensearch.migrations.replay.identity.PartitionGenerationId;
import org.opensearch.migrations.replay.identity.ReplayRequestId;
import org.opensearch.migrations.replay.intake.ReplayIntakeInput;
import org.opensearch.migrations.replay.kafkasource.KafkaSourceInput;
import org.opensearch.migrations.replay.lifecycle.OutstandingOperationRegistry.OperationType;
import org.opensearch.migrations.replay.lifecycle.ReplayOutcomes.RequestPreparationReady;
import org.opensearch.migrations.replay.lifecycle.ReplayOutcomes.RequestPreparationResult;
import org.opensearch.migrations.replay.lifecycle.ReplayOutcomes.RetryDecision;
import org.opensearch.migrations.replay.lifecycle.ReplayOutcomes.TargetAttemptOutcome;
import org.opensearch.migrations.replay.lifecycle.RequestReplayOwner;
import org.opensearch.migrations.replay.lifecycle.TargetChannelPort;
import org.opensearch.migrations.replay.sink.TupleWriter;
import org.opensearch.migrations.replay.testing.FakeClock;
import org.opensearch.migrations.replay.testing.TestEventLoop;
import org.opensearch.migrations.replay.tracing.ChannelContextManager;
import org.opensearch.migrations.replay.tracing.IReplayContexts;
import org.opensearch.migrations.replay.tracing.RootReplayerContext;
import org.opensearch.migrations.trafficcapture.protos.CaptureRecord;
import org.opensearch.migrations.trafficcapture.protos.CloseObservation;
import org.opensearch.migrations.trafficcapture.protos.EndOfMessageIndication;
import org.opensearch.migrations.trafficcapture.protos.ReadObservation;
import org.opensearch.migrations.trafficcapture.protos.TrafficObservation;
import org.opensearch.migrations.trafficcapture.protos.TrafficStream;
import org.opensearch.migrations.trafficcapture.protos.WriteObservation;
import org.opensearch.migrations.tracing.InMemoryInstrumentationBundle;
import org.opensearch.migrations.testutils.SimpleHttpResponse;
import org.opensearch.migrations.testutils.SimpleNettyHttpServer;

import com.google.protobuf.ByteString;
import com.google.protobuf.Timestamp;
import io.netty.buffer.Unpooled;
import io.netty.channel.EventLoop;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import io.netty.util.concurrent.DefaultThreadFactory;
import io.opentelemetry.api.OpenTelemetry;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.MockConsumer;
import org.apache.kafka.clients.consumer.OffsetResetStrategy;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.apache.kafka.common.record.TimestampType;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class TrafficReplayerTopLevelConstructionTest {
    private static final TopicPartition TOPIC_PARTITION = new TopicPartition("traffic", 0);

    @Test
    void realOwnerQueuesCarrySourceAdmissionAndCompletionUsingOneOperationRegistry() {
        var clock = new FakeClock();
        var eventLoop = new TestEventLoop(clock);
        var consumer = new MockConsumer<String, byte[]>(OffsetResetStrategy.EARLIEST);
        consumer.updateBeginningOffsets(Map.of(TOPIC_PARTITION, 0L));
        var targetAttempts = new AtomicInteger();
        var tupleTransformations = new AtomicInteger();
        var tupleWrites = new AtomicInteger();
        var tupleCompletion = new CompletableFuture<Void>();
        var fatalFailures = new ArrayList<Error>();

        try (var telemetry = new InMemoryInstrumentationBundle(false, true)) {
            var configuration = new TrafficReplayerTopLevel.Configuration<
                NettyPacketToHttpConsumer.PreparedRequest,
                AggregatedRawResponse,
                Map<String, Object>
            >(
                clock,
                () -> Duration.between(Instant.EPOCH, clock.instant()).toNanos(),
                sourceTime -> sourceTime,
                ignored -> eventLoop,
                (connectionId, context) -> filteredPreparer(),
                terminalRetryPolicy(),
                (connectionId, loop, context) -> new NettyPacketToHttpConsumer(
                    connectionId,
                    loop,
                    clock,
                    new NettyPacketToHttpConsumer.TargetPacketConsumerFactory() {
                        @Override
                        public org.opensearch.migrations.replay.datahandlers.TargetPacketConsumer
                        create(
                            org.opensearch.migrations.replay.lifecycle.TargetChannelPort.AttemptInput<
                                NettyPacketToHttpConsumer.PreparedRequest
                            > input,
                            java.util.function.IntConsumer targetWriteAccepted
                        ) {
                            targetAttempts.incrementAndGet();
                            throw new AssertionError(
                                "filtered request opened a target exchange"
                            );
                        }

                        @Override
                        public CompletionStage<Void> close(ConnectionProcessingId ignored) {
                            return CompletableFuture.completedFuture(null);
                        }
                    }
                ),
                (replayContext, result) -> {
                    var tuple = new LinkedHashMap<String, Object>();
                    tuple.put("requestId", result.requestId().toString());
                    tuple.put("status", result.transformationStatus().isSkipped() ? "skipped" : "completed");
                    tuple.put("sourceRequest", result.sourceRequest().toString());
                    return tuple;
                },
                noOpReleaser(),
                ignored -> new ManagedTupleTransformer<>() {
                    @Override
                    public TupleWriter.TupleTransformation<Map<String, Object>> transform(
                        org.opensearch.migrations.replay.tracing.IReplayContexts.ITupleHandlingContext
                            replayContext,
                        Map<String, Object> tuple
                    ) {
                        tupleTransformations.incrementAndGet();
                        var transformed = new LinkedHashMap<>(tuple);
                        transformed.put("transformed", true);
                        return new TupleWriter.TransformedTuple<>(transformed);
                    }

                    @Override
                    public void close() {}
                },
                ignored -> new ManagedPhysicalTupleSink<>() {
                    @Override
                    public CompletionStage<Void> write(
                        org.opensearch.migrations.replay.tracing.IReplayContexts.ITupleHandlingContext
                            replayContext,
                        Map<String, Object> tuple
                    ) {
                        tupleWrites.incrementAndGet();
                        return tupleCompletion;
                    }

                    @Override
                    public void close() {}
                },
                ignored -> {},
                Duration.ofMillis(1),
                1
            );
            var rootContext = new RootReplayerContext(telemetry.openTelemetrySdk);
            var replayer = new TrafficReplayerTopLevel<>(
                consumer,
                rootContext,
                configuration,
                Duration.ZERO,
                Duration.ofSeconds(1),
                fatalFailures::add
            );
            try {
                replayer.startIntake();
                consumer.subscribe(List.of(TOPIC_PARTITION.topic()), replayer.rebalanceListener());
                consumer.schedulePollTask(() -> consumer.rebalance(List.of(TOPIC_PARTITION)));
                replayer.runSourceOnce();

                var generation = replayer.sourceOwner().partitionState(TOPIC_PARTITION)
                    .orElseThrow()
                    .generation();
                intakeFence(replayer, generation);

                replayer.sourceInputs().submit(
                    new KafkaSourceInput.RequestNextPartitionBatch(
                        new PartitionBatchRequestId(generation, 1)
                    )
                );
                consumer.schedulePollTask(() -> {
                    var value = requestResponseAndClose().toByteArray();
                    consumer.addRecord(new ConsumerRecord<>(
                        TOPIC_PARTITION.topic(),
                        TOPIC_PARTITION.partition(),
                        0,
                        0L,
                        TimestampType.CREATE_TIME,
                        3,
                        value.length,
                        "key",
                        value,
                        new RecordHeaders(),
                        Optional.empty()
                    ));
                });
                replayer.runSourceOnce();
                intakeFence(replayer, generation);

                eventLoop.runUntilIdle();

                Assertions.assertEquals(0, targetAttempts.get());
                Assertions.assertEquals(1, tupleTransformations.get());
                Assertions.assertEquals(1, tupleWrites.get());
                var connectionId = new ConnectionProcessingId(
                    generation,
                    new CapturedConnectionId("writer", "connection"),
                    0
                );
                var activity = replayer.activitySnapshot(connectionId);
                Assertions.assertTrue(
                    activity.stream().anyMatch(snapshot ->
                        snapshot.operationType() == OperationType.PHYSICAL_TUPLE_WRITE
                    ),
                    "activity must project the tuple writer's live physical operation"
                );
                Assertions.assertTrue(fatalFailures.isEmpty());

                tupleCompletion.complete(null);
                eventLoop.advance(Duration.ofSeconds(3));
                eventLoop.runUntilIdle();
                intakeFence(replayer, generation);
                eventLoop.runUntilIdle();
                intakeFence(replayer, generation);
                eventLoop.runUntilIdle();
                intakeFence(replayer, generation);
                eventLoop.runUntilIdle();

                Assertions.assertEquals(
                    0,
                    replayer.activeConnectionCount(),
                    () -> "remaining activity=" + replayer.activitySnapshot(connectionId)
                        + " fatal=" + fatalFailures
                );
                Assertions.assertTrue(fatalFailures.isEmpty());
            } finally {
                replayer.close();
            }
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = { false, true })
    void deployedTargetChannelCreatesAndReusesOneRealNettyConnection(boolean useTls)
        throws Exception {
        var requests = new AtomicInteger();
        try (
            var telemetry = new InMemoryInstrumentationBundle(false, true);
            var server = SimpleNettyHttpServer.makeServer(useTls, request -> {
                requests.incrementAndGet();
                if ("HEAD".equals(request.getVerb())) {
                    return new SimpleHttpResponse(
                        Map.of(HttpHeaderNames.CONTENT_LENGTH.toString(), "377"),
                        new byte[0],
                        "Not Found",
                        404
                    );
                }
                return new SimpleHttpResponse(
                    Map.of(HttpHeaderNames.CONTENT_LENGTH.toString(), "2"),
                    "OK".getBytes(StandardCharsets.UTF_8),
                    "OK",
                    200
                );
            })
        ) {
            var eventLoops = new NioEventLoopGroup(
                1,
                new DefaultThreadFactory("g5-real-target-channel")
            );
            try {
                var generation = new PartitionGenerationId(TOPIC_PARTITION, 11);
                var connectionId = new ConnectionProcessingId(
                    generation,
                    new CapturedConnectionId("writer", "real-target"),
                    3
                );
                var rootContext = new RootReplayerContext(telemetry.openTelemetrySdk);
                var contextManager = new ChannelContextManager(rootContext);
                var connectionContext = contextManager.apply(connectionId);
                var eventLoop = eventLoops.next();
                var targetChannel = NettyPacketToHttpConsumer.create(
                    connectionId,
                    eventLoop,
                    Clock.systemUTC(),
                    connectionContext,
                    server.localhostEndpoint(),
                    useTls
                        ? SslContextBuilder.forClient()
                            .trustManager(InsecureTrustManagerFactory.INSTANCE)
                            .build()
                        : null,
                    Duration.ofSeconds(5)
                );

                var first = runRealTargetAttempt(
                    targetChannel,
                    rootContext,
                    generation,
                    connectionId,
                    eventLoop,
                    1
                );
                var second = runRealTargetAttempt(
                    targetChannel,
                    rootContext,
                    generation,
                    connectionId,
                    eventLoop,
                    2
                );
                var head = runRealTargetAttempt(
                    targetChannel,
                    rootContext,
                    generation,
                    connectionId,
                    eventLoop,
                    3,
                    "HE",
                    "AD /geonames HTTP/1.1\r\n"
                        + "Host: localhost\r\n"
                        + "Connection: Keep-Alive\r\n"
                        + "\r\n"
                );

                Assertions.assertInstanceOf(
                    TargetAttemptOutcome.TargetResponseObtained.class,
                    first
                );
                Assertions.assertInstanceOf(
                    TargetAttemptOutcome.TargetResponseObtained.class,
                    second
                );
                Assertions.assertInstanceOf(
                    TargetAttemptOutcome.TargetResponseObtained.class,
                    head
                );
                var headResponse = (
                    (TargetAttemptOutcome.TargetResponseObtained<AggregatedRawResponse>) head
                ).response();
                Assertions.assertNull(headResponse.getError());
                Assertions.assertEquals(404, headResponse.getRawResponse().status().code());
                Assertions.assertEquals(
                    "377",
                    headResponse.getRawResponse().headers().get(HttpHeaderNames.CONTENT_LENGTH)
                );
                Assertions.assertEquals(3, requests.get());

                var close = eventLoop.submit(() -> targetChannel.close(connectionId))
                    .sync()
                    .getNow();
                close.toCompletableFuture().get(10, TimeUnit.SECONDS);
                contextManager.releaseContextFor(connectionContext);

                Assertions.assertEquals(
                    1,
                    InMemoryInstrumentationBundle.getMetricValueOrZero(
                        telemetry.getFinishedMetrics(),
                        IReplayContexts.MetricNames.CONNECTIONS_OPENED
                    ),
                    "both requests must reuse one target socket"
                );
            } finally {
                eventLoops.shutdownGracefully().sync();
            }
        }
    }

    private static TargetAttemptOutcome<AggregatedRawResponse> runRealTargetAttempt(
        NettyPacketToHttpConsumer targetChannel,
        RootReplayerContext rootContext,
        PartitionGenerationId generation,
        ConnectionProcessingId connectionId,
        EventLoop eventLoop,
        long ordinal,
        String... requestFragments
    ) throws Exception {
        var recordContext = rootContext.createKafkaRecordContext(
            new KafkaRecordId(generation, ordinal),
            0
        );
        var trafficContext = recordContext.createTrafficStreamContext(ordinal);
        var requestContext = trafficContext.createRequestContext(
            new ReplayRequestId(connectionId, ordinal),
            Instant.now()
        );
        requestContext.onRequestReconstituted();
        var targetContext = requestContext.createTargetRequestContext();
        if (requestFragments.length == 0) {
            requestFragments = new String[] {
                "GET / HTTP/1.1\r\n"
                    + "Host: localhost\r\n"
                    + "Content-Length: 0\r\n"
                    + "\r\n"
            };
        }
        var rawPackets = new io.netty.buffer.ByteBuf[requestFragments.length];
        for (var i = 0; i < requestFragments.length; ++i) {
            rawPackets[i] = Unpooled.copiedBuffer(
                requestFragments[i],
                StandardCharsets.UTF_8
            );
        }
        var packets = new ByteBufList(rawPackets);
        for (var rawPacket : rawPackets) {
            rawPacket.release();
        }
        var prepared = ByteBufListProducer.of(packets);
        var firstWrites = new AtomicInteger();
        var finalWrites = new AtomicInteger();
        try {
            var attempt = eventLoop.submit(() ->
                targetChannel.startAttempt(
                    new TargetChannelPort.AttemptInput<>(
                        Math.toIntExact(ordinal),
                        new NettyPacketToHttpConsumer.PreparedRequest(
                            prepared,
                            Duration.ZERO
                        ),
                        targetContext,
                        new TargetChannelPort.WriteMilestoneListener() {
                            @Override
                            public void firstTargetWriteSubmitted(int attemptNumber) {
                                firstWrites.incrementAndGet();
                            }

                            @Override
                            public void finalTargetWriteSubmitted(int attemptNumber) {
                                finalWrites.incrementAndGet();
                            }
                        }
                    )
                )
            ).sync().getNow();
            var outcome = attempt.outcome()
                .toCompletableFuture()
                .get(10, TimeUnit.SECONDS);
            Assertions.assertEquals(1, firstWrites.get());
            Assertions.assertEquals(1, finalWrites.get());
            return outcome;
        } finally {
            prepared.close();
            targetContext.close();
            requestContext.close();
            trafficContext.close();
            recordContext.complete(IReplayContexts.RecordDisposition.COMMIT_INELIGIBLE);
        }
    }

    @Test
    void deployedTransformerDropSignalStaysOutsideTheCoreWriter() throws Exception {
        var transformations = new AtomicInteger();
        var contexts = new TupleContextFixture();
        var transformer = TrafficReplayerTopLevel.deployedTupleTransformer(() -> tuple -> {
            transformations.incrementAndGet();
            throw new RequestFilteredException("drop tuple");
        });
        try {
            Assertions.assertInstanceOf(
                TupleWriter.TupleDropped.class,
                transformer.transform(contexts.tuple, new HashMap<>())
            );
            Assertions.assertEquals(1, transformations.get());
        } finally {
            transformer.close();
            contexts.close();
        }
    }

    private static RequestReplayOwner.RequestPreparer<
        HttpMessageAndTimestamp.Request,
        NettyPacketToHttpConsumer.PreparedRequest
    >
    filteredPreparer() {
        return (requestId, sourceRequest, replayContext) ->
            new RequestReplayOwner.PreparationOperation<>() {
                private final CompletableFuture<RequestPreparationResult<
                    NettyPacketToHttpConsumer.PreparedRequest
                >> completion =
                    CompletableFuture.completedFuture(new RequestPreparationReady<>(
                        null,
                        HttpRequestTransformationStatus.skipped()
                    ));

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
        AggregatedRawResponse,
        HttpMessageAndTimestamp.Response
    >
    terminalRetryPolicy() {
        return new RequestReplayOwner.RetryPolicy<>() {
            @Override
            public boolean requiresSourceResponse(AggregatedRawResponse targetResponse) {
                return false;
            }

            @Override
            public RetryDecision decide(
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

    private static void intakeFence(
        TrafficReplayerTopLevel<?, ?, ?> replayer,
        org.opensearch.migrations.replay.identity.PartitionGenerationId generation
    ) {
        var fenceConnection = new ConnectionProcessingId(
            generation,
            new CapturedConnectionId("fence", "fence"),
            0
        );
        replayer.intakeInputs().submitAndAwaitHandling(
            new ReplayIntakeInput.ConnectionRequestFinished(
                generation,
                new ReplayRequestId(fenceConnection, 0)
            )
        ).toCompletableFuture().join();
    }

    private static CaptureRecord requestResponseAndClose() {
        var stream = TrafficStream.newBuilder()
            .setNodeId("writer")
            .setConnectionId("connection")
            .setNumber(0)
            .addSubStream(observation(0).setRead(
                ReadObservation.newBuilder().setData(
                    ByteString.copyFromUtf8("GET / HTTP/1.1\r\nHost: source\r\n\r\n")
                )
            ))
            .addSubStream(observation(1).setEndOfMessageIndicator(
                EndOfMessageIndication.getDefaultInstance()
            ))
            .addSubStream(observation(2).setWrite(
                WriteObservation.newBuilder().setData(
                    ByteString.copyFromUtf8("HTTP/1.1 200 OK\r\nContent-Length: 0\r\n\r\n")
                )
            ))
            .addSubStream(observation(3).setClose(CloseObservation.getDefaultInstance()))
            .build();
        return CaptureRecord.newBuilder().setTrafficStream(stream).build();
    }

    private static TrafficObservation.Builder observation(long sequence) {
        return TrafficObservation.newBuilder()
            .setTs(Timestamp.newBuilder().setSeconds(sequence))
            .setConnectionObservationSequence(sequence);
    }

    private static final class TupleContextFixture implements AutoCloseable {
        private final PartitionGenerationId generation =
            new PartitionGenerationId(TOPIC_PARTITION, 1);
        private final ConnectionProcessingId connection = new ConnectionProcessingId(
            generation,
            new CapturedConnectionId("writer", "connection"),
            1
        );
        private final ReplayRequestId requestId = new ReplayRequestId(connection, 1);
        private final RootReplayerContext root =
            new RootReplayerContext(OpenTelemetry.noop());
        private final IReplayContexts.IKafkaRecordContext record =
            root.createKafkaRecordContext(new KafkaRecordId(generation, 1), 0);
        private final IReplayContexts.ITrafficStreamsLifecycleContext traffic =
            record.createTrafficStreamContext(1);
        private final IReplayContexts.IRequestContext request =
            traffic.createRequestContext(requestId, Instant.EPOCH);
        private final IReplayContexts.ITupleHandlingContext tuple;

        private TupleContextFixture() {
            request.onRequestReconstituted();
            tuple = request.createTupleContext();
        }

        @Override
        public void close() {
            tuple.close();
            request.close();
            traffic.close();
            record.complete(IReplayContexts.RecordDisposition.COMMIT_INELIGIBLE);
        }
    }
}
