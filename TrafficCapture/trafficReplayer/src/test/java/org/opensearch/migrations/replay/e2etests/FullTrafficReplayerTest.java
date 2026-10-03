package org.opensearch.migrations.replay.e2etests;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import org.opensearch.migrations.replay.AggregatedRawResponse;
import org.opensearch.migrations.replay.HttpMessageAndTimestamp;
import org.opensearch.migrations.replay.ProcessSupervisor;
import org.opensearch.migrations.replay.ResultsToLogsConsumer;
import org.opensearch.migrations.replay.TrafficReplayerTopLevel;
import org.opensearch.migrations.replay.datahandlers.NettyPacketToHttpConsumer;
import org.opensearch.migrations.replay.datatypes.HttpRequestTransformationStatus;
import org.opensearch.migrations.replay.http.retries.BulkItemErrorClassifier;
import org.opensearch.migrations.replay.http.retries.OpenSearchDefaultRetry;
import org.opensearch.migrations.replay.intake.PartitionIntakeState;
import org.opensearch.migrations.replay.kafkasource.KafkaSourceOwner;
import org.opensearch.migrations.replay.lifecycle.RequestReplayOwner;
import org.opensearch.migrations.replay.tracing.IReplayContexts;
import org.opensearch.migrations.replay.tracing.RootReplayerContext;
import org.opensearch.migrations.testutils.SimpleHttpResponse;
import org.opensearch.migrations.testutils.SimpleNettyHttpServer;
import org.opensearch.migrations.trafficcapture.protos.CaptureRecord;
import org.opensearch.migrations.trafficcapture.protos.CloseObservation;
import org.opensearch.migrations.trafficcapture.protos.EndOfMessageIndication;
import org.opensearch.migrations.trafficcapture.protos.ReadObservation;
import org.opensearch.migrations.trafficcapture.protos.TrafficObservation;
import org.opensearch.migrations.trafficcapture.protos.TrafficStream;
import org.opensearch.migrations.trafficcapture.protos.WriteObservation;
import org.opensearch.migrations.transform.IJsonTransformer;
import org.opensearch.migrations.transform.TransformationLoader;

import com.google.protobuf.ByteString;
import com.google.protobuf.Timestamp;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.util.concurrent.DefaultThreadFactory;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.MockConsumer;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.consumer.OffsetCommitCallback;
import org.apache.kafka.clients.consumer.OffsetResetStrategy;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.apache.kafka.common.record.TimestampType;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;


/**
 * Shared current-architecture harness for the inherited long-running replay tests.
 *
 * <p>It uses the real G5-G9 owner, queue, target-channel, tuple, and shutdown chain. Kafka's
 * {@link MockConsumer} makes delivery and commit callbacks observable without sleeps; the target is a real
 * Netty server.</p>
 */
@Tag("longTest")
public class FullTrafficReplayerTest {
    protected static final TopicPartition TOPIC_PARTITION =
        new TopicPartition("g9-inherited-replay", 0);

    protected record ReplayResult(
        List<Map<String, Object>> tuples,
        List<HttpRequestTransformationStatus> transformationStatuses,
        int targetChannelsCreated,
        Map<TopicPartition, OffsetAndMetadata> committedOffsets,
        List<ProcessSupervisor.FatalSignal> fatalFailures
    ) {}

    @Test
    void deployedOwnerChainReplaysOneRecordAndClosesOrderly() throws Exception {
        var targetRequests = new AtomicInteger();
        try (var server = SimpleNettyHttpServer.makeServer(
            false,
            request -> {
                targetRequests.incrementAndGet();
                return new SimpleHttpResponse(
                    Map.of(HttpHeaderNames.CONTENT_LENGTH.toString(), "2"),
                    "OK".getBytes(StandardCharsets.UTF_8),
                    "OK",
                    200
                );
            }
        )) {
            var result = replayOne(
                server.localhostEndpoint(),
                requestResponseAndClose("GET / HTTP/1.1\r\nHost: source\r\n\r\n"),
                () -> new TransformationLoader()
                    .getTransformerFactoryLoaderWithNewHostName("localhost"),
                null
            );

            Assertions.assertEquals(1, targetRequests.get());
            Assertions.assertEquals(1, result.targetChannelsCreated());
            Assertions.assertEquals(1, result.tuples().size());
            Assertions.assertEquals(1, result.transformationStatuses().size());
            Assertions.assertTrue(
                result.transformationStatuses().getFirst().isCompleted()
            );
            Assertions.assertEquals(
                1L,
                result.committedOffsets().get(TOPIC_PARTITION).offset()
            );
            Assertions.assertTrue(result.fatalFailures().isEmpty());
        }
    }

    protected static ReplayResult replayOne(
        URI targetUri,
        CaptureRecord record,
        Supplier<IJsonTransformer> requestTransformerSupplier,
        Supplier<IJsonTransformer> responsePostProcessorSupplier
    ) throws Exception {
        return replayOne(
            targetUri,
            record,
            requestTransformerSupplier,
            responsePostProcessorSupplier,
            new RootReplayerContext(io.opentelemetry.api.OpenTelemetry.noop())
        );
    }

    protected static ReplayResult replayOne(
        URI targetUri,
        CaptureRecord record,
        Supplier<IJsonTransformer> requestTransformerSupplier,
        Supplier<IJsonTransformer> responsePostProcessorSupplier,
        RootReplayerContext rootContext
    ) throws Exception {
        return replay(
            targetUri,
            List.of(record),
            requestTransformerSupplier,
            responsePostProcessorSupplier,
            rootContext
        );
    }

    protected static ReplayResult replay(
        URI targetUri,
        List<CaptureRecord> records,
        Supplier<IJsonTransformer> requestTransformerSupplier,
        Supplier<IJsonTransformer> responsePostProcessorSupplier,
        RootReplayerContext rootContext
    ) throws Exception {
        return replay(
            targetUri,
            records,
            requestTransformerSupplier,
            responsePostProcessorSupplier,
            rootContext,
            records.size()
        );
    }

    protected static ReplayResult replay(
        URI targetUri,
        List<CaptureRecord> records,
        Supplier<IJsonTransformer> requestTransformerSupplier,
        Supplier<IJsonTransformer> responsePostProcessorSupplier,
        RootReplayerContext rootContext,
        int expectedTupleCount
    ) throws Exception {
        if (records.isEmpty()) {
            throw new IllegalArgumentException("records must not be empty");
        }
        if (expectedTupleCount < 1) {
            throw new IllegalArgumentException("expected tuple count must be positive");
        }
        var consumer = new RecordingMockConsumer();
        consumer.updateBeginningOffsets(Map.of(TOPIC_PARTITION, 0L));
        var targetChannelsCreated = new AtomicInteger();
        var targetConnectionId =
            new java.util.concurrent.atomic.AtomicReference<
                org.opensearch.migrations.replay.identity.ConnectionProcessingId
            >();
        var tuples = new CopyOnWriteArrayList<Map<String, Object>>();
        var transformationStatuses =
            new CopyOnWriteArrayList<HttpRequestTransformationStatus>();
        var tupleWritten = new CompletableFuture<Void>();
        var protocolViolationExitCodes = new CopyOnWriteArrayList<Integer>();
        var fatalFailures =
            new CopyOnWriteArrayList<ProcessSupervisor.FatalSignal>();
        var fatalObserved = new CompletableFuture<ProcessSupervisor.FatalSignal>();
        var clock = Clock.systemUTC();
        var eventLoopGroup = new NioEventLoopGroup(
            1,
            new DefaultThreadFactory("g9-inherited-full-replay")
        );
        var eventLoop = eventLoopGroup.next();
        try {
            var resultsToLogs = new ResultsToLogsConsumer();
            var configuration = new TrafficReplayerTopLevel.Configuration<
                NettyPacketToHttpConsumer.PreparedRequest,
                AggregatedRawResponse,
                Map<String, Object>
            >(
                clock,
                System::nanoTime,
                ignored -> clock.instant(),
                ignored -> eventLoop,
                List.of(eventLoop),
                TrafficReplayerTopLevel.deployedOwnerTaskRunner(),
                (connectionId, connectionContext) ->
                    TrafficReplayerTopLevel.deployedRequestPreparer(
                        requestTransformerSupplier,
                        null,
                        1.0
                    ),
                new OpenSearchDefaultRetry(new BulkItemErrorClassifier()),
                (connectionId, owner, connectionContext) -> {
                    targetChannelsCreated.incrementAndGet();
                    targetConnectionId.set(connectionId);
                    return NettyPacketToHttpConsumer.create(
                        connectionId,
                        owner,
                        clock,
                        connectionContext,
                        targetUri,
                        null,
                        Duration.ofSeconds(5)
                    );
                },
                (replayContext, result) -> {
                    transformationStatuses.add(result.transformationStatus());
                    return resultsToLogs.createTupleAndReportProgress(
                        replayContext,
                        result
                    );
                },
                resourceReleaser(),
                ignored -> TrafficReplayerTopLevel.deployedTupleTransformer(
                    () -> input -> input,
                    responsePostProcessorSupplier
                ),
                ignored -> new TrafficReplayerTopLevel.ManagedPhysicalTupleSink<>() {
                    @Override
                    public CompletionStage<Void> write(
                        IReplayContexts.ITupleHandlingContext replayContext,
                        Map<String, Object> tuple
                    ) {
                        tuples.add(Map.copyOf(tuple));
                        if (tuples.size() == expectedTupleCount) {
                            tupleWritten.complete(null);
                        }
                        return CompletableFuture.completedFuture(null);
                    }

                    @Override
                    public void flush() {}

                    @Override
                    public void close() {}
                },
                ignored -> {},
                Duration.ZERO,
                new PartitionIntakeState.BrokerTimeConfiguration(
                    30_000,
                    5_000,
                    5_000
                ),
                2,
                4,
                1
            );
            var replayer = new TrafficReplayerTopLevel<>(
                consumer,
                rootContext,
                configuration,
                Duration.ZERO,
                KafkaSourceOwner.DEFAULT_REVOCATION_GRACE,
                protocolViolationExitCodes::add,
                signal -> {
                    fatalFailures.add(signal);
                    fatalObserved.complete(signal);
                }
            );
            var replayCompleted = false;
            try {
                replayer.startIntake();
                consumer.subscribe(
                    List.of(TOPIC_PARTITION.topic()),
                    replayer.rebalanceListener()
                );
                consumer.schedulePollTask(() ->
                    consumer.rebalance(List.of(TOPIC_PARTITION))
                );
                replayer.runSourceOnce();
                intakeFence(replayer);
                replayer.runSourceOnce();

                consumer.schedulePollTask(() -> {
                    for (var offset = 0; offset < records.size(); offset++) {
                        var value = records.get(offset).toByteArray();
                        consumer.addRecord(new ConsumerRecord<>(
                            TOPIC_PARTITION.topic(),
                            TOPIC_PARTITION.partition(),
                            offset,
                            0L,
                            TimestampType.LOG_APPEND_TIME,
                            3,
                            value.length,
                            "key-" + offset,
                            value,
                            new RecordHeaders(),
                            Optional.empty()
                        ));
                    }
                });
                replayer.runSourceOnce();

                try {
                    CompletableFuture.anyOf(tupleWritten, fatalObserved)
                        .get(10, TimeUnit.SECONDS);
                } catch (java.util.concurrent.TimeoutException timeout) {
                    var connectionId = targetConnectionId.get();
                    throw new AssertionError(
                        "replay stalled before tuple durability; connection="
                            + connectionId
                            + " activity="
                            + (connectionId == null
                                ? List.of()
                                : activitySnapshot(replayer, connectionId))
                            + " source="
                            + replayer.sourceOwner().partitionState(TOPIC_PARTITION)
                            + " protocolViolationExitCodes="
                            + protocolViolationExitCodes
                            + " fatal="
                            + fatalFailures,
                        timeout
                    );
                }
                Assertions.assertTrue(
                    tupleWritten.isDone(),
                    () -> "the deployed tuple sink did not observe the replayed request; fatal="
                        + fatalFailures.stream()
                            .map(signal -> signal.failure().getCause())
                            .toList()
                );
                eventLoop.submit(() -> {}).sync();
                intakeFence(replayer);
                replayer.runSourceOnce();
                Assertions.assertTrue(
                    consumer.commitObserved.await(10, TimeUnit.SECONDS),
                    "the durable tuple did not advance the Kafka commit"
                );
                eventLoop.submit(() -> {}).sync();
                intakeFence(replayer);
                replayCompleted = true;
            } finally {
                if (replayCompleted) {
                    replayer.close();
                } else {
                    replayer.stopNewInputForFatal(new ProcessSupervisor.FatalSignal(
                        ProcessSupervisor.Reason.UNEXPECTED_FATAL_ERROR,
                        "test diagnostic",
                        "avoid entering orderly close with an unfinished connection",
                        new Error("unfinished connection")
                    ));
                }
                replayer.allowTargetEventLoopTermination();
            }
        } finally {
            eventLoopGroup.shutdownGracefully(0, 0, TimeUnit.MILLISECONDS)
                .syncUninterruptibly();
        }
        return new ReplayResult(
            List.copyOf(tuples),
            List.copyOf(transformationStatuses),
            targetChannelsCreated.get(),
            consumer.lastCommit,
            List.copyOf(fatalFailures)
        );
    }

    protected static CaptureRecord requestResponseAndClose(String request) {
        return requestResponseAndClose("writer", "connection", request);
    }

    protected static CaptureRecord requestResponseAndClose(
        String writerNodeId,
        String connectionId,
        String request
    ) {
        return requestsAndClose(
            writerNodeId,
            connectionId,
            List.of(request)
        );
    }

    protected static CaptureRecord requestsAndClose(
        String writerNodeId,
        String connectionId,
        List<String> requests
    ) {
        var stream = TrafficStream.newBuilder()
            .setNodeId(writerNodeId)
            .setConnectionId(connectionId)
            .setNumber(0);
        long sequence = 0;
        for (var request : requests) {
            stream.addSubStream(observation(sequence++).setRead(
                    ReadObservation.newBuilder().setData(
                        ByteString.copyFromUtf8(request)
                    )
                ))
                .addSubStream(observation(sequence++).setEndOfMessageIndicator(
                    EndOfMessageIndication.getDefaultInstance()
                ))
                .addSubStream(observation(sequence++).setWrite(
                WriteObservation.newBuilder().setData(
                    ByteString.copyFromUtf8(
                        "HTTP/1.1 200 OK\r\nContent-Length: 0\r\n\r\n"
                    )
                )
            ));
        }
        stream.addSubStream(observation(sequence).setClose(
            CloseObservation.getDefaultInstance()
        ));
        return CaptureRecord.newBuilder().setTrafficStream(stream.build()).build();
    }

    private static TrafficObservation.Builder observation(long sequence) {
        return TrafficObservation.newBuilder()
            .setTs(Timestamp.newBuilder().setSeconds(sequence))
            .setConnectionObservationSequence(sequence);
    }

    private static RequestReplayOwner.ResourceReleaser<
        HttpMessageAndTimestamp.Request,
        NettyPacketToHttpConsumer.PreparedRequest,
        AggregatedRawResponse,
        HttpMessageAndTimestamp.Response
    > resourceReleaser() {
        return new RequestReplayOwner.ResourceReleaser<>() {
            @Override
            public void releaseSourceRequest(
                HttpMessageAndTimestamp.Request sourceRequest
            ) {}

            @Override
            public void releasePreparedRequest(
                NettyPacketToHttpConsumer.PreparedRequest preparedRequest
            ) {
                preparedRequest.close();
            }

            @Override
            public void releaseTargetResponse(
                AggregatedRawResponse targetResponse
            ) {}

            @Override
            public void releaseSourceResponse(
                HttpMessageAndTimestamp.Response sourceResponse
            ) {}
        };
    }

    private static void intakeFence(TrafficReplayerTopLevel<?, ?, ?> replayer) {
        replayer.intakeInputs()
            .awaitPriorInputsHandled()
            .toCompletableFuture()
            .join();
    }

    @SuppressWarnings("unchecked")
    private static List<
        org.opensearch.migrations.replay.lifecycle.OutstandingOperationRegistry.Snapshot
    > activitySnapshot(
        TrafficReplayerTopLevel<?, ?, ?> replayer,
        org.opensearch.migrations.replay.identity.ConnectionProcessingId connectionId
    ) throws ReflectiveOperationException {
        var method = TrafficReplayerTopLevel.class.getDeclaredMethod(
            "activitySnapshot",
            org.opensearch.migrations.replay.identity.ConnectionProcessingId.class
        );
        method.setAccessible(true);
        return (List<
            org.opensearch.migrations.replay.lifecycle.OutstandingOperationRegistry.Snapshot
        >) method.invoke(replayer, connectionId);
    }

    private static final class RecordingMockConsumer
        extends MockConsumer<String, byte[]> {
        private final CountDownLatch commitObserved = new CountDownLatch(1);
        private Map<TopicPartition, OffsetAndMetadata> lastCommit = Map.of();

        private RecordingMockConsumer() {
            super(OffsetResetStrategy.EARLIEST);
        }

        @Override
        public synchronized void commitAsync(
            Map<TopicPartition, OffsetAndMetadata> offsets,
            OffsetCommitCallback callback
        ) {
            lastCommit = Map.copyOf(offsets);
            callback.onComplete(offsets, null);
            commitObserved.countDown();
        }
    }
}
