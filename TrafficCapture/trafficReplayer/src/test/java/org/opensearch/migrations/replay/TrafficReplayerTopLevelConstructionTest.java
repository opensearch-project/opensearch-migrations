/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.migrations.replay;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

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
import org.opensearch.migrations.replay.kafkasource.KafkaSourceOwner;
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
import org.opensearch.migrations.transform.IAuthTransformerFactory;
import org.opensearch.migrations.transform.TransformationLoader;

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
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.sdk.metrics.data.MetricData;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.MockConsumer;
import org.apache.kafka.clients.consumer.OffsetResetStrategy;
import org.apache.kafka.common.KafkaException;
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
    void deployedApplicationFactorySelectsThePreservedMetricAndOwnerChain()
        throws Exception {
        var parameters = new TrafficReplayer.Parameters();
        parameters.kafkaTrafficTopic = TOPIC_PARTITION.topic();
        parameters.numClientThreads = 2;
        parameters.readyRequestsBufferPerThread = 3;
        parameters.maxConcurrentTargetAttempts = 7;
        var consumer = new MockConsumer<String, byte[]>(OffsetResetStrategy.EARLIEST);
        var targetEventLoops = new NioEventLoopGroup(
            parameters.numClientThreads,
            new DefaultThreadFactory("g9-deployed-construction")
        );

        try (var telemetry = new InMemoryInstrumentationBundle(false, true)) {
            var rootContext = new RootReplayerContext(telemetry.openTelemetrySdk);
            var deployed = TrafficReplayer.createDeployedReplayApplication(
                parameters,
                URI.create("http://localhost:9200"),
                new org.opensearch.migrations.replay.intake.PartitionIntakeState.BrokerTimeConfiguration(
                    30_000,
                    1_000,
                    5_000
                ),
                Duration.ZERO,
                rootContext,
                consumer,
                targetEventLoops,
                IAuthTransformerFactory.NullAuthTransformerFactory.instance,
                Optional.empty(),
                Clock.systemUTC(),
                System::nanoTime
            );
            try {
                var replayer = deployed.lifecycle().replayer();
                var configuration = replayer.configuration();

                Assertions.assertEquals(2, replayer.tupleWriterWorkerCount());
                Assertions.assertEquals(2, configuration.targetEventLoops().size());
                Assertions.assertEquals(3, configuration.readyRequestsBufferPerThread());
                Assertions.assertEquals(6, configuration.retryReadyRequestSupplyTarget());
                Assertions.assertEquals(7, configuration.maximumTargetAttempts());
                Assertions.assertInstanceOf(
                    org.opensearch.migrations.replay.http.retries.OpenSearchDefaultRetry.class,
                    configuration.retryPolicy()
                );

                Assertions.assertAll(
                    "the deployed root constructs every producer group for the 33 preserved names",
                    () -> Assertions.assertNotNull(rootContext.kafkaRecordInstruments),
                    () -> Assertions.assertNotNull(rootContext.trafficStreamLifecycleInstruments),
                    () -> Assertions.assertNotNull(rootContext.httpTransactionInstruments),
                    () -> Assertions.assertNotNull(rootContext.transformationInstruments),
                    () -> Assertions.assertNotNull(rootContext.scheduledInstruments),
                    () -> Assertions.assertNotNull(rootContext.targetRequestInstruments),
                    () -> Assertions.assertNotNull(rootContext.channelKeyInstruments),
                    () -> Assertions.assertNotNull(rootContext.socketInstruments),
                    () -> Assertions.assertNotNull(rootContext.tupleHandlingInstruments),
                    () -> Assertions.assertEquals(33, preservedReplayPipelineMetricNames().size())
                );

                deployed.lifecycle().start();
                Assertions.assertEquals(
                    java.util.Set.of(TOPIC_PARTITION.topic()),
                    consumer.subscription()
                );
                Assertions.assertFalse(deployed.supervisor().fatalTerminationStarted());
            } finally {
                try {
                    deployed.lifecycle().closeOrderly();
                } finally {
                    deployed.lifecycle().closeTargetOwnersAfterOrderly();
                }
            }
            Assertions.assertTrue(consumer.closed());
        }
    }

    private static Set<String> preservedReplayPipelineMetricNames() {
        return Set.of(
            IReplayContexts.MetricNames.KAFKA_RECORD_READ,
            IReplayContexts.MetricNames.KAFKA_BYTES_READ,
            IReplayContexts.MetricNames.TRAFFIC_STREAMS_READ,
            IReplayContexts.MetricNames.TRANSFORM_HEADER_PARSE,
            IReplayContexts.MetricNames.TRANSFORM_PAYLOAD_PARSE_REQUIRED,
            IReplayContexts.MetricNames.TRANSFORM_PAYLOAD_PARSE_SUCCESS,
            IReplayContexts.MetricNames.TRANSFORM_JSON_REQUIRED,
            IReplayContexts.MetricNames.TRANSFORM_JSON_SUCCEEDED,
            IReplayContexts.MetricNames.TRANSFORM_TEXT_SUCCEEDED,
            IReplayContexts.MetricNames.TRANSFORM_TEXT_FAILED,
            IReplayContexts.MetricNames.TRANSFORM_PAYLOAD_BINARY,
            IReplayContexts.MetricNames.TRANSFORM_PAYLOAD_BYTES_IN,
            IReplayContexts.MetricNames.TRANSFORM_UNCOMPRESSED_BYTES_IN,
            IReplayContexts.MetricNames.TRANSFORM_UNCOMPRESSED_BYTES_OUT,
            IReplayContexts.MetricNames.TRANSFORM_FINAL_PAYLOAD_BYTES_OUT,
            IReplayContexts.MetricNames.TRANSFORM_SUCCESS,
            IReplayContexts.MetricNames.TRANSFORM_SKIPPED,
            IReplayContexts.MetricNames.TRANSFORM_ERROR,
            IReplayContexts.MetricNames.TRANSFORM_BYTES_IN,
            IReplayContexts.MetricNames.TRANSFORM_BYTES_OUT,
            IReplayContexts.MetricNames.TRANSFORM_CHUNKS_IN,
            IReplayContexts.MetricNames.TRANSFORM_CHUNKS_OUT,
            IReplayContexts.MetricNames.NETTY_SCHEDULE_LAG,
            IReplayContexts.MetricNames.NUM_REQUEST_RETRIES,
            IReplayContexts.MetricNames.SOURCE_TO_TARGET_REQUEST_LAG,
            IReplayContexts.MetricNames.ACTIVE_CHANNELS_YET_TO_BE_FULLY_DISCARDED,
            IReplayContexts.MetricNames.NONRETRYABLE_CONNECTION_FAILURES,
            IReplayContexts.MetricNames.ACTIVE_TARGET_CONNECTIONS,
            IReplayContexts.MetricNames.CONNECTIONS_OPENED,
            IReplayContexts.MetricNames.CONNECTIONS_CLOSED,
            IReplayContexts.MetricNames.BYTES_WRITTEN_TO_TARGET,
            IReplayContexts.MetricNames.BYTES_READ_FROM_TARGET,
            IReplayContexts.MetricNames.TUPLE_COMPARISON
        );
    }

    @Test
    void deployedKafkaSourceFailureReachesTheProcessSupervisor() throws Exception {
        var parameters = new TrafficReplayer.Parameters();
        parameters.kafkaTrafficTopic = TOPIC_PARTITION.topic();
        parameters.numClientThreads = 1;
        var consumer = new MockConsumer<String, byte[]>(OffsetResetStrategy.EARLIEST);
        consumer.setPollException(new KafkaException("injected source-owner failure"));
        var targetEventLoops = new NioEventLoopGroup(
            parameters.numClientThreads,
            new DefaultThreadFactory("g9-source-fatal-routing")
        );
        var exits = new ArrayList<Integer>();
        var watchdogAction = new AtomicReference<Runnable>();

        try (var telemetry = new InMemoryInstrumentationBundle(false, true)) {
            var rootContext = new RootReplayerContext(telemetry.openTelemetrySdk);
            var deployed = TrafficReplayer.createDeployedReplayApplication(
                parameters,
                URI.create("http://localhost:9200"),
                new org.opensearch.migrations.replay.intake.PartitionIntakeState.BrokerTimeConfiguration(
                    30_000,
                    5_000,
                    5_000
                ),
                TrafficReplayer.deployedTupleRetryDelayPolicy(() -> 0.5),
                rootContext,
                consumer,
                targetEventLoops,
                IAuthTransformerFactory.NullAuthTransformerFactory.instance,
                Optional.empty(),
                Clock.systemUTC(),
                System::nanoTime,
                (metrics, inputStopper) -> new ProcessSupervisor(
                    metrics,
                    inputStopper,
                    exits::add,
                    ignored -> Assertions.fail("halt must wait for the watchdog"),
                    (delay, action) -> {
                        Assertions.assertEquals(
                            ProcessSupervisor.EXIT_WATCHDOG_LIMIT,
                            delay
                        );
                        watchdogAction.set(action);
                    },
                    ignored -> Assertions.fail("thread dump must wait for the watchdog"),
                    () -> {},
                    System.err
                )
            );
            try {
                deployed.lifecycle().start();
                deployed.lifecycle().runSourceOnce();

                Assertions.assertEquals(
                    List.of(ProcessSupervisor.UNEXPECTED_OWNER_EXIT_CODE),
                    exits
                );
                var signal = deployed.supervisor().firstFatalSignal().orElseThrow();
                Assertions.assertEquals("Kafka source owner", signal.owner());
                Assertions.assertEquals("run-loop iteration", signal.operation());
                Assertions.assertTrue(
                    signal.failure().getCause().getMessage()
                        .contains("injected source-owner failure")
                );
                Assertions.assertTrue(
                    deployed.lifecycle().replayer().sourceInputs().isClosed()
                );
                Assertions.assertNotNull(watchdogAction.get());
            } finally {
                try {
                    deployed.lifecycle().closeOrderly();
                } finally {
                    deployed.lifecycle().closeTargetOwnersAfterOrderly();
                }
            }
        }
    }

    @Test
    void realOwnerQueuesCarrySourceAdmissionAndCompletionUsingOneOperationRegistry() throws Exception {
        var clock = new FakeClock();
        var eventLoop = new TestEventLoop(clock);
        var consumer = new MockConsumer<String, byte[]>(OffsetResetStrategy.EARLIEST);
        consumer.updateBeginningOffsets(Map.of(TOPIC_PARTITION, 0L));
        var targetAttempts = new AtomicInteger();
        var tupleTransformations = new AtomicInteger();
        var tupleWrites = new AtomicInteger();
        var tupleCompletion = new CompletableFuture<Void>();
        var fatalFailures = new ArrayList<ProcessSupervisor.FatalSignal>();
        var transformerWorkerIndices = new ArrayList<Integer>();
        var sinkWorkerIndices = new ArrayList<Integer>();
        var closedTransformerIndices = new ArrayList<Integer>();
        var closedSinkIndices = new ArrayList<Integer>();

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
                List.of(eventLoop, new TestEventLoop(clock), new TestEventLoop(clock)),
                (loop, task) -> {
                    loop.execute(task);
                    ((TestEventLoop) loop).runUntilIdle();
                },
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
                workerIndex -> {
                    transformerWorkerIndices.add(workerIndex);
                    return new ManagedTupleTransformer<>() {
                    @Override
                    public TupleWriter.TupleTransformation<Map<String, Object>> transform(
                        org.opensearch.migrations.replay.tracing.IReplayContexts.ITupleHandlingContext
                            replayContext,
                        Map<String, Object> tuple
                    ) {
                        Assertions.assertTrue(eventLoop.inEventLoop());
                        tupleTransformations.incrementAndGet();
                        var transformed = new LinkedHashMap<>(tuple);
                        transformed.put("transformed", true);
                        return new TupleWriter.TransformedTuple<>(transformed);
                    }

                    @Override
                    public void close() {
                        closedTransformerIndices.add(workerIndex);
                    }
                    };
                },
                workerIndex -> {
                    sinkWorkerIndices.add(workerIndex);
                    return new ManagedPhysicalTupleSink<>() {
                    @Override
                    public CompletionStage<Void> write(
                        org.opensearch.migrations.replay.tracing.IReplayContexts.ITupleHandlingContext
                            replayContext,
                        Map<String, Object> tuple
                    ) {
                        Assertions.assertTrue(eventLoop.inEventLoop());
                        tupleWrites.incrementAndGet();
                        return tupleCompletion;
                    }

                    @Override
                    public void flush() {}

                    @Override
                    public void close() {
                        closedSinkIndices.add(workerIndex);
                    }
                    };
                },
                ignored -> {},
                Duration.ofMillis(1),
                new org.opensearch.migrations.replay.intake.PartitionIntakeState.BrokerTimeConfiguration(
                    30_000,
                    1_000,
                    5_000
                ),
                4,
                1
            );
            Assertions.assertEquals(
                TrafficReplayerTopLevel.DEFAULT_READY_REQUESTS_BUFFER_PER_THREAD,
                configuration.readyRequestsBufferPerThread()
            );
            Assertions.assertEquals(6, configuration.retryReadyRequestSupplyTarget(), "N = P * T_threads");
            var rootContext = new RootReplayerContext(telemetry.openTelemetrySdk);
            var replayer = new TrafficReplayerTopLevel<>(
                consumer,
                rootContext,
                configuration,
                Duration.ZERO,
                fatalFailures::add
            );
            Assertions.assertEquals(List.of(0, 1, 2), transformerWorkerIndices);
            Assertions.assertEquals(List.of(0, 1, 2), sinkWorkerIndices);
            var cancellationGrace = KafkaSourceOwner.class.getDeclaredField("cancellationGrace");
            cancellationGrace.setAccessible(true);
            Assertions.assertEquals(
                KafkaSourceOwner.DEFAULT_REVOCATION_GRACE,
                cancellationGrace.get(replayer.sourceOwner()),
                "the real default construction path must wire the one-second revocation grace"
            );
            try {
                replayer.startIntake();
                consumer.subscribe(List.of(TOPIC_PARTITION.topic()), replayer.rebalanceListener());
                consumer.schedulePollTask(() -> consumer.rebalance(List.of(TOPIC_PARTITION)));
                replayer.runSourceOnce();

                var generation = replayer.sourceOwner().partitionState(TOPIC_PARTITION)
                    .orElseThrow()
                    .generation();
                intakeFence(replayer);
                replayer.runSourceOnce();

                Assertions.assertEquals(
                    new PartitionBatchRequestId(generation, 1),
                    replayer.sourceOwner().partitionState(TOPIC_PARTITION)
                        .orElseThrow()
                        .outstandingRequest()
                        .orElseThrow(),
                    "assignment's ordinary demand pass must install one explicit request beside bootstrap"
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
                intakeFence(replayer);

                eventLoop.runUntilIdle();

                Assertions.assertEquals(0, targetAttempts.get());
                Assertions.assertEquals(1, tupleTransformations.get());
                Assertions.assertEquals(1, tupleWrites.get());
                Assertions.assertEquals(List.of(0, 1, 2), transformerWorkerIndices);
                Assertions.assertEquals(List.of(0, 1, 2), sinkWorkerIndices);
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
                intakeFence(replayer);
                eventLoop.runUntilIdle();
                intakeFence(replayer);
                eventLoop.runUntilIdle();
                intakeFence(replayer);
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
            Assertions.assertTrue(
                replayer.intakeOwner().termination().toCompletableFuture().isDone(),
                "a fully drained orderly shutdown must stop replay intake before returning"
            );
            Assertions.assertTrue(
                consumer.closed(),
                "a fully drained orderly shutdown must close the Kafka consumer before returning"
            );
            Assertions.assertEquals(List.of(0, 1, 2), closedTransformerIndices);
            Assertions.assertEquals(List.of(0, 1, 2), closedSinkIndices);
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

    @Test
    void deployedRequestPreparerDrivesInheritedTransformationMetrics() {
        var generation = new PartitionGenerationId(TOPIC_PARTITION, 10);
        var connection = new ConnectionProcessingId(
            generation,
            new CapturedConnectionId("writer", "connection"),
            1
        );
        var requestId = new ReplayRequestId(connection, 3);
        var sourceRequest = request(
            "POST /index HTTP/1.1\r\n"
                + "Host: source.example\r\n"
                + "Content-Type: text/plain\r\n"
                + "Content-Length: 15\r\n"
                + "\r\n"
                + "This is a test\r\n"
        );
        var transformerConfig = "[{\"JsonJoltTransformerProvider\":{\"script\":"
            + "{\"operation\":\"modify-overwrite-beta\",\"spec\":{\"payload\":"
            + "{\"inlinedTextBody\":\"ReplacedPlainText\"}}}}}]";
        try (var telemetry = new InMemoryInstrumentationBundle(true, true)) {
            var root = new RootReplayerContext(telemetry.openTelemetrySdk);
            var record = root.createKafkaRecordContext(
                new KafkaRecordId(generation, 2),
                0
            );
            var traffic = record.createTrafficStreamContext(2);
            var requestContext = traffic.createRequestContext(requestId, Instant.EPOCH);
            requestContext.onRequestReconstituted();
            var transformationContext = requestContext.createTransformationContext();
            NettyPacketToHttpConsumer.PreparedRequest prepared = null;
            try {
                var preparer = TrafficReplayerTopLevel.deployedRequestPreparer(
                    () -> new TransformationLoader()
                        .getTransformerFactoryLoader(transformerConfig),
                    null,
                    Duration.ZERO
                );
                var result = preparer.begin(
                    requestId,
                    sourceRequest,
                    transformationContext
                ).completion().toCompletableFuture().join();
                var ready = Assertions.assertInstanceOf(
                    RequestPreparationReady.class,
                    result
                );
                prepared = Assertions.assertInstanceOf(
                    NettyPacketToHttpConsumer.PreparedRequest.class,
                    ready.value()
                );
                Assertions.assertTrue(ready.transformationStatus().isCompleted());
            } finally {
                transformationContext.close();
                if (prepared != null) {
                    prepared.close();
                }
                requestContext.close();
                traffic.close();
                record.complete(IReplayContexts.RecordDisposition.COMMIT_INELIGIBLE);
            }

            var metrics = telemetry.getFinishedMetrics();
            Assertions.assertEquals(
                1,
                InMemoryInstrumentationBundle.getMetricValueOrZero(
                    metrics,
                    IReplayContexts.MetricNames.TRANSFORM_HEADER_PARSE
                )
            );
            Assertions.assertEquals(
                1,
                InMemoryInstrumentationBundle.getMetricValueOrZero(
                    metrics,
                    IReplayContexts.MetricNames.TRANSFORM_TEXT_SUCCEEDED
                )
            );
            Assertions.assertEquals(
                1,
                InMemoryInstrumentationBundle.getMetricValueOrZero(
                    metrics,
                    IReplayContexts.MetricNames.TRANSFORM_SUCCESS
                )
            );
            Assertions.assertTrue(
                InMemoryInstrumentationBundle.getMetricValueOrZero(
                    metrics,
                    IReplayContexts.MetricNames.TRANSFORM_BYTES_IN
                ) > 0
            );
            Assertions.assertTrue(
                InMemoryInstrumentationBundle.getMetricValueOrZero(
                    metrics,
                    IReplayContexts.MetricNames.TRANSFORM_BYTES_OUT
                ) > 0
            );
        }
    }

    @Test
    void deployedRequestPreparerPreservesCapturedPacketPacingAtConfiguredSpeed() {
        var sourceRequest = new HttpMessageAndTimestamp.Request(Instant.EPOCH);
        sourceRequest.setLastPacketTimestamp(Instant.EPOCH.plusSeconds(4));
        var first = Unpooled.wrappedBuffer(new byte[] { 1 });
        var second = Unpooled.wrappedBuffer(new byte[] { 2 });
        var third = Unpooled.wrappedBuffer(new byte[] { 3 });
        var packets = new ByteBufList(first, second, third);
        first.release();
        second.release();
        third.release();
        var prepared = ByteBufListProducer.of(packets);
        try {
            Assertions.assertEquals(
                Duration.ofSeconds(1),
                TrafficReplayerTopLevel.deployedPacketInterval(
                    sourceRequest,
                    prepared,
                    2.0
                ),
                "four captured seconds at 2x over three packets produces two one-second gaps"
            );
        } finally {
            prepared.close();
        }
    }

    @Test
    void deployedRequestPreparerCompletesExceptionallyAndReleasesOutputWhenFinalPreparationFails() {
        var generation = new PartitionGenerationId(TOPIC_PARTITION, 12);
        var requestId = new ReplayRequestId(
            new ConnectionProcessingId(
                generation,
                new CapturedConnectionId("writer", "connection"),
                1
            ),
            5
        );
        var sourceRequest = request(
            "GET /index HTTP/1.1\r\n"
                + "Host: source.example\r\n"
                + "Content-Length: 0\r\n\r\n"
        );
        var transformedOutput = new AtomicReference<
            org.opensearch.migrations.replay.datatypes.OwnedPreparedRequest
        >();
        var expectedFailure = new IllegalStateException("injected packet interval failure");
        var preparer = TrafficReplayerTopLevel.deployedRequestPreparer(
            () -> input -> input,
            null,
            (ignoredSourceRequest, output) -> {
                transformedOutput.set(output);
                throw expectedFailure;
            }
        );
        var root = new RootReplayerContext(OpenTelemetry.noop());
        var record = root.createKafkaRecordContext(new KafkaRecordId(generation, 4), 0);
        var traffic = record.createTrafficStreamContext(4);
        var requestContext = traffic.createRequestContext(requestId, Instant.EPOCH);
        requestContext.onRequestReconstituted();
        var transformationContext = requestContext.createTransformationContext();
        try {
            var failure = Assertions.assertThrows(
                java.util.concurrent.CompletionException.class,
                () -> preparer.begin(
                    requestId,
                    sourceRequest,
                    transformationContext
                ).completion().toCompletableFuture().join()
            );

            Assertions.assertSame(expectedFailure, failure.getCause());
            var output = Assertions.assertInstanceOf(
                ByteBufListProducer.class,
                transformedOutput.get()
            );
            Assertions.assertEquals(0, output.refCnt());
        } finally {
            transformationContext.close();
            requestContext.close();
            traffic.close();
            record.complete(IReplayContexts.RecordDisposition.COMMIT_INELIGIBLE);
        }
    }

    @Test
    void deployedRequestPreparerRepresentsSuccessfulPassThroughAsReady() throws Exception {
        var generation = new PartitionGenerationId(TOPIC_PARTITION, 13);
        var requestId = new ReplayRequestId(
            new ConnectionProcessingId(
                generation,
                new CapturedConnectionId("writer", "connection"),
                1
            ),
            6
        );
        var sourceRequest = request(
            "GET /index HTTP/1.1\r\n"
                + "Host: source.example\r\n"
                + "Content-Length: 0\r\n\r\n"
        );
        var preparer = TrafficReplayerTopLevel.deployedRequestPreparer(
            () -> input -> input,
            null,
            Duration.ZERO
        );
        var root = new RootReplayerContext(OpenTelemetry.noop());
        var record = root.createKafkaRecordContext(new KafkaRecordId(generation, 5), 0);
        var traffic = record.createTrafficStreamContext(5);
        var requestContext = traffic.createRequestContext(requestId, Instant.EPOCH);
        requestContext.onRequestReconstituted();
        var transformationContext = requestContext.createTransformationContext();
        NettyPacketToHttpConsumer.PreparedRequest prepared = null;
        try {
            var result = preparer.begin(
                requestId,
                sourceRequest,
                transformationContext
            ).completion().toCompletableFuture().get(5, TimeUnit.SECONDS);
            var ready = Assertions.assertInstanceOf(
                RequestPreparationReady.class,
                result
            );
            prepared = Assertions.assertInstanceOf(
                NettyPacketToHttpConsumer.PreparedRequest.class,
                ready.value()
            );
            Assertions.assertTrue(ready.transformationStatus().isCompleted());
        } finally {
            if (prepared != null) {
                prepared.close();
            }
            transformationContext.close();
            requestContext.close();
            traffic.close();
            record.complete(IReplayContexts.RecordDisposition.COMMIT_INELIGIBLE);
        }
    }

    @Test
    void deployedTupleFactoryPopulatesComparisonAttributesFromActualMessages() {
        var generation = new PartitionGenerationId(TOPIC_PARTITION, 11);
        var connection = new ConnectionProcessingId(
            generation,
            new CapturedConnectionId("writer", "connection"),
            1
        );
        var requestId = new ReplayRequestId(connection, 4);
        var sourceRequest = request(
            "POST /index HTTP/1.1\r\n"
                + "Host: source.example\r\n"
                + "Content-Length: 0\r\n\r\n"
        );
        var sourceResponse = response(
            "HTTP/1.1 200 OK\r\nContent-Length: 0\r\n\r\n"
        );
        var targetRequestBytes = (
            "POST /index HTTP/1.1\r\n"
                + "Host: target.example\r\n"
                + "Content-Length: 0\r\n\r\n"
        ).getBytes(StandardCharsets.UTF_8);
        var targetResponseBytes = (
            "HTTP/1.1 503 Service Unavailable\r\n"
                + "Content-Length: 0\r\n\r\n"
        ).getBytes(StandardCharsets.UTF_8);
        var targetRequestBuffer = Unpooled.wrappedBuffer(targetRequestBytes);
        var targetRequestPackets = new ByteBufList(targetRequestBuffer);
        targetRequestBuffer.release();
        var prepared = new NettyPacketToHttpConsumer.PreparedRequest(
            ByteBufListProducer.of(targetRequestPackets),
            Duration.ZERO
        );
        var targetResponse = new AggregatedRawResponse(
            null,
            targetResponseBytes.length,
            Duration.ofMillis(2),
            List.of(new AbstractMap.SimpleEntry<>(
                Instant.EPOCH.plusMillis(2),
                targetResponseBytes
            )),
            null
        );
        var terminal = new TargetAttemptOutcome.TargetResponseObtained<>(
            targetResponse
        );
        try (var telemetry = new InMemoryInstrumentationBundle(true, true)) {
            var root = new RootReplayerContext(telemetry.openTelemetrySdk);
            var record = root.createKafkaRecordContext(
                new KafkaRecordId(generation, 3),
                0
            );
            var traffic = record.createTrafficStreamContext(3);
            var requestContext = traffic.createRequestContext(requestId, Instant.EPOCH);
            requestContext.onRequestReconstituted();
            var tupleContext = requestContext.createTupleContext();
            try {
                var result = new RequestReplayOwner.RequestResult<>(
                    requestId,
                    sourceRequest,
                    prepared,
                    HttpRequestTransformationStatus.completed(),
                    List.of(terminal),
                    terminal,
                    new RequestReplayOwner.CompleteFinalSourceResponse<>(
                        sourceResponse,
                        true
                    )
                );
                var tuple = TrafficReplayerTopLevel.deployedTupleFactory().create(
                    tupleContext,
                    result
                );
                Assertions.assertEquals(
                    200,
                    ((Map<?, ?>) tuple.get("sourceResponse"))
                        .get(ParsedHttpMessagesAsDicts.STATUS_CODE_KEY)
                );
                var targetResponses = (List<?>) tuple.get("targetResponses");
                Assertions.assertEquals(
                    503,
                    ((Map<?, ?>) targetResponses.getLast())
                        .get(ParsedHttpMessagesAsDicts.STATUS_CODE_KEY)
                );
            } finally {
                tupleContext.close();
                prepared.close();
                requestContext.close();
                traffic.close();
                record.complete(IReplayContexts.RecordDisposition.COMMIT_INELIGIBLE);
            }

            assertLongPoint(
                telemetry.getFinishedMetrics(),
                IReplayContexts.MetricNames.TUPLE_COMPARISON,
                Attributes.builder()
                    .put(
                        IReplayContexts.ITupleHandlingContext.SOURCE_STATUS_CODE_KEY,
                        200L
                    )
                    .put(
                        IReplayContexts.ITupleHandlingContext.TARGET_STATUS_CODE_KEY,
                        500L
                    )
                    .put(IReplayContexts.ITupleHandlingContext.METHOD_KEY, "POST")
                    .put(
                        IReplayContexts.ITupleHandlingContext.STATUS_CODE_MATCH_KEY,
                        false
                    )
                    .build(),
                1
            );
        }
    }

    private static HttpMessageAndTimestamp.Request request(String text) {
        var request = new HttpMessageAndTimestamp.Request(Instant.EPOCH);
        request.add(text.getBytes(StandardCharsets.UTF_8));
        request.setLastPacketTimestamp(Instant.EPOCH);
        return request;
    }

    private static HttpMessageAndTimestamp.Response response(String text) {
        var response = new HttpMessageAndTimestamp.Response(
            Instant.EPOCH.plusMillis(1)
        );
        response.add(text.getBytes(StandardCharsets.UTF_8));
        response.setLastPacketTimestamp(Instant.EPOCH.plusMillis(1));
        return response;
    }

    private static void assertLongPoint(
        Iterable<MetricData> metrics,
        String name,
        Attributes attributes,
        long expectedValue
    ) {
        for (var metric : metrics) {
            if (metric.getName().equals(name)) {
                var point = metric.getLongSumData()
                    .getPoints()
                    .stream()
                    .filter(candidate -> candidate.getAttributes().equals(attributes))
                    .findFirst()
                    .orElseThrow();
                Assertions.assertEquals(expectedValue, point.getValue());
                return;
            }
        }
        throw new AssertionError("Missing metric " + name);
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
        NettyPacketToHttpConsumer.PreparedRequest,
        AggregatedRawResponse,
        HttpMessageAndTimestamp.Response
    >
    terminalRetryPolicy() {
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
        TrafficReplayerTopLevel<?, ?, ?> replayer
    ) {
        replayer.intakeInputs().awaitPriorInputsHandled().toCompletableFuture().join();
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
