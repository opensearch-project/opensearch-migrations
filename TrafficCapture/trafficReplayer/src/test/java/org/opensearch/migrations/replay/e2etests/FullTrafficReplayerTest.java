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
import org.opensearch.migrations.replay.http.retries.BulkItemErrorClassifier;
import org.opensearch.migrations.replay.http.retries.OpenSearchDefaultRetry;
import org.opensearch.migrations.replay.intake.PartitionIntakeState;
import org.opensearch.migrations.replay.lifecycle.RequestReplayOwner;
import org.opensearch.migrations.replay.sink.TupleWriter;
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
import io.netty.channel.EventLoop;
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

// REBUILD-LIMBO(G10) -- inherited test bodies remain marked and recoverable; the live G9
// deployed-chain replacement follows the marked regions. Javadoc stays outside the regions.
// Resolve each region to dead, keep, or refactor deliberately. If a member is deleted, delete its
// javadoc with it. See AGENTS.md section 8a.
// Test carried byte-identical. Unresolved: CapturedTrafficToHttpTransactionAccumulator ExhaustiveTrafficStreamGenerator InstrumentationTest IRootReplayerContext ITrafficSourceContexts . Per AGENTS.md section 4 an inherited test may stay broken while the architectures are partly connected; this one is restored by the milestone that rebuilds its subject, keeping its assertions conceptually stable while changing the mechanics.
// Un-mark a member by deleting the delimiter lines around it and splitting this region; the
// code between them is verbatim, so blame survives. Read this before writing anything new

// REBUILD-LIMBO-START(G10)
/*

import javax.net.ssl.SSLException;

import java.io.EOFException;
import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import org.opensearch.migrations.ExceptionTypeAllowlist;
import org.opensearch.migrations.replay.CapturedTrafficToHttpTransactionAccumulator;
import org.opensearch.migrations.replay.ReplayEngine;
import org.opensearch.migrations.replay.ReplayProcessFatalHandler;
import org.opensearch.migrations.replay.SourceTargetCaptureTuple;
import org.opensearch.migrations.replay.TestHttpServerContext;
import org.opensearch.migrations.replay.TimeShifter;
import org.opensearch.migrations.replay.TrafficReplayerTopLevel;
import org.opensearch.migrations.replay.datatypes.ITrafficStreamKey;
import org.opensearch.migrations.replay.datatypes.PojoTrafficStreamAndKey;
import org.opensearch.migrations.replay.datatypes.PojoTrafficStreamKeyAndContext;
import org.opensearch.migrations.replay.http.retries.BulkItemErrorClassifier;
import org.opensearch.migrations.replay.lifecycle.ReplayIdentity.ConnectionSessionKey;
import org.opensearch.migrations.replay.tracing.IRootReplayerContext;
import org.opensearch.migrations.replay.tracing.ITrafficSourceContexts;
import org.opensearch.migrations.replay.traffic.generator.ExhaustiveTrafficStreamGenerator;
import org.opensearch.migrations.replay.traffic.source.ArrayCursorTrafficSourceContext;
import org.opensearch.migrations.replay.traffic.source.ISimpleTrafficCaptureSource;
import org.opensearch.migrations.replay.traffic.source.ITrafficStreamWithKey;
import org.opensearch.migrations.replay.traffic.source.TrafficStreamCursorKey;
import org.opensearch.migrations.replay.util.OrderedWorkerTracker;
import org.opensearch.migrations.testutils.SimpleNettyHttpServer;
import org.opensearch.migrations.testutils.WrapWithNettyLeakDetection;
import org.opensearch.migrations.tracing.InstrumentationTest;
import org.opensearch.migrations.tracing.TestContext;
import org.opensearch.migrations.trafficcapture.protos.CloseObservation;
import org.opensearch.migrations.trafficcapture.protos.TrafficObservation;
import org.opensearch.migrations.trafficcapture.protos.TrafficStream;
import org.opensearch.migrations.trafficcapture.protos.TrafficStreamUtils;
import org.opensearch.migrations.transform.IAuthTransformerFactory;
import org.opensearch.migrations.transform.IJsonTransformer;
import org.opensearch.migrations.transform.StaticAuthTransformerFactory;
import org.opensearch.migrations.transform.TransformationLoader;

import lombok.Lombok;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.slf4j.event.Level;

@Slf4j
// It would be great to test with leak detection here, but right now this test relies upon TrafficReplayer.shutdown()
// to recycle the TrafficReplayers. Since that shutdown process optimizes for speed of teardown, rather than tidying
// everything up as it closes the door, some leaks may be inevitable. E.g. when work is outstanding and being sent
// to the test server, a shutdown will stop those work threads without letting them flush through all of their work
// (since that could take a very long time) and some of the work might have been followed by resource releases.
@WrapWithNettyLeakDetection(disableLeakChecks = true)
public class FullTrafficReplayerTest extends InstrumentationTest {

    public static final int INITIAL_STOP_REPLAYER_REQUEST_COUNT = 1;
    public static final String TEST_NODE_ID = "TestNodeId";
    public static final String TEST_CONNECTION_ID = "testConnectionId";
    public static final String DUMMY_URL_THAT_WILL_NEVER_BE_CONTACTED = "http://localhost:9999/";

    private static final class RecordingProcessTerminator
        implements ReplayProcessFatalHandler.ProcessTerminator {
        private final List<Integer> exitCodes = new CopyOnWriteArrayList<>();

        @Override
        public void terminate(int exitCode) {
            exitCodes.add(exitCode);
        }

        private void assertNotTerminated() {
            Assertions.assertTrue(
                exitCodes.isEmpty(),
                () -> "Unexpected process termination with exit codes " + exitCodes
            );
        }

        private void attachFailure(Throwable failure) {
            if (!exitCodes.isEmpty()) {
                failure.addSuppressed(new AssertionError(
                    "Unexpected process termination with exit codes " + exitCodes
                ));
            }
        }
    }

    protected static class TrafficReplayerWithWaitOnClose extends TrafficReplayerTopLevel {

        private final Duration maxWaitTime;
        private final RecordingProcessTerminator processTerminator;

        public TrafficReplayerWithWaitOnClose(
            Duration maxWaitTime,
            IRootReplayerContext context,
            URI serverUri,
            IAuthTransformerFactory authTransformerFactory,
            boolean allowInsecureConnections,
            int numSendingThreads,
            int maxConcurrentOutstandingRequests,
            IJsonTransformer jsonTransformer,
            String targetConnectionPoolName
        ) throws SSLException {
            this(
                maxWaitTime,
                context,
                serverUri,
                authTransformerFactory,
                allowInsecureConnections,
                numSendingThreads,
                maxConcurrentOutstandingRequests,
                jsonTransformer,
                targetConnectionPoolName,
                new RecordingProcessTerminator()
            );
        }

        private TrafficReplayerWithWaitOnClose(
            Duration maxWaitTime,
            IRootReplayerContext context,
            URI serverUri,
            IAuthTransformerFactory authTransformerFactory,
            boolean allowInsecureConnections,
            int numSendingThreads,
            int maxConcurrentOutstandingRequests,
            IJsonTransformer jsonTransformer,
            String targetConnectionPoolName,
            RecordingProcessTerminator processTerminator
        ) throws SSLException {
            super(
                context,
                serverUri,
                authTransformerFactory,
                () -> jsonTransformer,
                TrafficReplayerTopLevel.makeNettyPacketConsumerConnectionPool(
                    serverUri,
                    allowInsecureConnections,
                    numSendingThreads,
                    targetConnectionPoolName
                ),
                maxConcurrentOutstandingRequests,
                new OrderedWorkerTracker<>(),
                new BulkItemErrorClassifier(),
                ExceptionTypeAllowlist.empty(),
                processTerminator
            );
            this.maxWaitTime = maxWaitTime;
            this.processTerminator = processTerminator;
        }

        @Override
        @SneakyThrows
        protected void wrapUpWorkAndEmitSummary(
            ReplayEngine replayEngine,
            CapturedTrafficToHttpTransactionAccumulator accumulator
        ) {
            waitForRemainingWork(Level.INFO, maxWaitTime);
            if (replayEngine.isWorkOutstanding()) {
                throw new IllegalStateException("ReplayEngine reported quiescence with work still outstanding");
            }
            super.wrapUpWorkAndEmitSummary(replayEngine, accumulator);
        }

        public void setResponsePostProcessor(IJsonTransformer postProcessor) {
            this.responsePostProcessor = postProcessor;
        }

        @Override
        public void close() throws Exception {
            try {
                super.close();
            } catch (Exception | Error failure) {
                processTerminator.attachFailure(failure);
                throw failure;
            }
            processTerminator.assertNotTerminated();
        }
    }

    protected static class IndexWatchingListenerFactory implements Supplier<Consumer<SourceTargetCaptureTuple>> {
        AtomicInteger nextStopPointRef = new AtomicInteger(INITIAL_STOP_REPLAYER_REQUEST_COUNT);

        @Override
        public Consumer<SourceTargetCaptureTuple> get() {
            log.info("StopAt=" + nextStopPointRef.get());
            var stopPoint = nextStopPointRef.get();
            return tuple -> {
                var key = tuple.getRequestKey();
                if (((TrafficStreamCursorKey) (key.getTrafficStreamKey())).arrayIndex > stopPoint) {
                    log.error("Request received after our ingest threshold. Throwing.  Discarding " + key);
                    var nextStopPoint = stopPoint + new Random(stopPoint).nextInt(stopPoint + 1);
                    nextStopPointRef.compareAndSet(stopPoint, nextStopPoint);
                    throw new TrafficReplayerRunner.FabricatedErrorToKillTheReplayer(false);
                }
            };
        }
    }

    @Test
    @ResourceLock("TrafficReplayerRunner")
    @Tag("longTest")
    public void testLongRequestEndingAfterEOFStillCountsCorrectly() throws Throwable {
        var random = new Random(1);
        try (
            var httpServer = SimpleNettyHttpServer.makeServer(
                false,
                Duration.ofMillis(2),
                response -> TestHttpServerContext.makeResponse(random, response)
            )
        ) {
            var trafficStreamWithJustClose = TrafficStream.newBuilder()
                .setNodeId(TEST_NODE_ID)
                .setConnectionId(TEST_CONNECTION_ID)
                .addSubStream(TrafficObservation.newBuilder().setClose(CloseObservation.newBuilder().build()).build())
                .build();
            var trafficSourceSupplier = new ArrayCursorTrafficSourceContext(
                List.of(trafficStreamWithJustClose),
                0
            );
            TrafficReplayerRunner.runReplayer(0, (rc, threadPrefix) -> {
                try {
                    return new TrafficReplayerWithWaitOnClose(
                        Duration.ofSeconds(600),
                        rc,
                        httpServer.localhostEndpoint(),
                        new StaticAuthTransformerFactory("TEST"),
                        true,
                        1,
                        1,
                        new TransformationLoader().getTransformerFactoryLoaderWithNewHostName("localhost"),
                        threadPrefix
                    );
                } catch (SSLException e) {
                    throw new RuntimeException(e);
                }
            },
                () -> t -> {},
                () -> TestContext.noOtelTracking(),
                trafficSourceSupplier,
                new TimeShifter(10 * 1000, Duration.ofMillis(100))
            );
            Assertions.assertEquals(1, trafficSourceSupplier.nextReadCursor.get());
        }
    }

    @Test
    @ResourceLock("TrafficReplayerRunner")
    @Tag("longTest")
    public void testSingleStreamWithCloseIsCommitted() throws Throwable {
        var random = new Random(1);
        try (
            var httpServer = SimpleNettyHttpServer.makeServer(
                false,
                Duration.ofMillis(2),
                response -> TestHttpServerContext.makeResponse(random, response)
            )
        ) {
            var trafficStreamWithJustClose = TrafficStream.newBuilder()
                .setNodeId(TEST_NODE_ID)
                .setConnectionId(TEST_CONNECTION_ID)
                .addSubStream(TrafficObservation.newBuilder().setClose(CloseObservation.newBuilder().build()).build())
                .build();
            var trafficSourceSupplier = new ArrayCursorTrafficSourceContext(
                List.of(trafficStreamWithJustClose),
                0
            );

            TrafficReplayerRunner.runReplayer(0, (rc, threadPrefix) -> {
                try {
                    return new TrafficReplayerWithWaitOnClose(
                        Duration.ofSeconds(600),
                        rc,
                        httpServer.localhostEndpoint(),
                        new StaticAuthTransformerFactory("TEST"),
                        true,
                        1,
                        1,
                        new TransformationLoader().getTransformerFactoryLoaderWithNewHostName("localhost"),
                        threadPrefix
                    );
                } catch (SSLException e) {
                    throw new RuntimeException(e);
                }
            }, () -> t -> {}, () -> TestContext.noOtelTracking(), trafficSourceSupplier, new TimeShifter(10 * 1000));

            Assertions.assertEquals(1, trafficSourceSupplier.nextReadCursor.get());
        }
    }

    @Test
    @ResourceLock("TrafficReplayerRunner")
    @Tag("longTest")
    public void fullTestWithThrottledStart() throws Throwable {
        var random = new Random(1);
        try (
            var httpServer = SimpleNettyHttpServer.makeServer(
                false,
                Duration.ofMillis(200),
                firstLine -> TestHttpServerContext.makeResponse(random, firstLine)
            )
        ) {
            var nonTrackingContext = TestContext.noOtelTracking();
            var streamAndSizes = ExhaustiveTrafficStreamGenerator.generateStreamAndSumOfItsTransactions(
                nonTrackingContext,
                16,
                true
            );
            var numExpectedRequests = streamAndSizes.numHttpTransactions;
            var trafficStreams = streamAndSizes.stream.collect(Collectors.toList());
            log.atInfo().setMessage("{}")
                .addArgument(() -> trafficStreams.stream()
                        .map(TrafficStreamUtils::summarizeTrafficStream)
                        .collect(Collectors.joining("\n"))
                )
                .log();
            Function<TestContext, ISimpleTrafficCaptureSource> trafficSourceSupplier =
                rc -> new ISimpleTrafficCaptureSource() {
                    boolean isDone = false;

                    @Override
                    public CompletableFuture<List<org.opensearch.migrations.replay.traffic.source.SourceInput>>
                    readNextTrafficStreamChunk(
                        Supplier<ITrafficSourceContexts.IReadChunkContext> contextSupplier
                    ) {
                        if (isDone) {
                            return CompletableFuture.failedFuture(new EOFException());
                        } else {
                            isDone = true;
                            return CompletableFuture.completedFuture(
                                trafficStreams.stream()
                                    .map(
                                        ts -> new PojoTrafficStreamAndKey(
                                            ts,
                                            PojoTrafficStreamKeyAndContext.build(
                                                ts,
                                                rc::createTrafficStreamContextForTest
                                            )
                                        )
                                    )
                                    .map(v -> (ITrafficStreamWithKey) v)
                                    .collect(Collectors.toList())
                            );
                        }
                    }

                    @Override
                    public CompletionStage<Void> acknowledgeSessionTermination(
                        ConnectionSessionKey sessionKey
                    ) {
                        return CompletableFuture.completedFuture(null);
                    }

                    @Override
                    public void onConnectionAccumulationComplete(ITrafficStreamKey trafficStreamKey) {
                        // This fixture has no per-connection source registry.
                    }
                };

            TrafficReplayerRunner.runReplayer(numExpectedRequests, (rc, threadPrefix) -> {
                try {
                    return new TrafficReplayerWithWaitOnClose(
                        Duration.ofSeconds(600),
                        rc,
                        httpServer.localhostEndpoint(),
                        new StaticAuthTransformerFactory("TEST"),
                        true,
                        1,
                        1,
                        new TransformationLoader().getTransformerFactoryLoaderWithNewHostName("localhost"),
                        threadPrefix
                    );
                } catch (SSLException e) {
                    throw new RuntimeException(e);
                }
            }, () -> t -> {}, () -> nonTrackingContext, trafficSourceSupplier, new TimeShifter(10 * 1000));
            log.info("done");
        }
    }

    @Test
    @Tag("longTest")
    public void makeSureThatCollateralDamageDoesntFreezeTests() throws Throwable {
        var imposterThread = new Thread(() -> {
            while (true) {
                try {
                    Thread.sleep(1000 * 1000);
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
            }
        }, TrafficReplayerTopLevel.TARGET_CONNECTION_POOL_NAME + " Just to break a test");
        imposterThread.start();

        try {
            var workThread = new Thread(() -> {
                try {
                    TrafficReplayerRunner.runReplayer(
                        0,
                        new URI(DUMMY_URL_THAT_WILL_NEVER_BE_CONTACTED),
                        new IndexWatchingListenerFactory(),
                        () -> TestContext.noOtelTracking(),
                        new ArrayCursorTrafficSourceContext(List.of(), 0)
                    );
                } catch (Throwable e) {
                    throw Lombok.sneakyThrow(e);
                }
            });
            workThread.start();
            workThread.join(1000 * 60);
            Assertions.assertFalse(
                workThread.isAlive(),
                "Expected the work thread to die and not be confused by the imposter thread"
            );
        } finally {
            imposterThread.interrupt();
            imposterThread.join();
        }
    }

    @ParameterizedTest
    @CsvSource(value = { "3,false", "-1,false", "3,true", "-1,true", })
    @Tag("longTest")
    @ResourceLock("TrafficReplayerRunner")
    public void fullTestWithRestarts(int testSize, boolean randomize) throws Throwable {

        var random = new Random(1);
        try (
            var httpServer = SimpleNettyHttpServer.makeServer(
                false,
                Duration.ofMillis(200),
                response -> TestHttpServerContext.makeResponse(random, response)
            )
        ) {
            var streamAndSizes = ExhaustiveTrafficStreamGenerator.generateStreamAndSumOfItsTransactions(
                TestContext.noOtelTracking(),
                testSize,
                randomize
            );
            var numExpectedRequests = streamAndSizes.numHttpTransactions;
            var trafficStreams = streamAndSizes.stream.collect(Collectors.toList());
            log.atInfo().setMessage("{}")
                .addArgument(() -> trafficStreams.stream()
                        .map(TrafficStreamUtils::summarizeTrafficStream)
                        .collect(Collectors.joining("\n"))
                )
                .log();
            var trafficSourceSupplier = new ArrayCursorTrafficSourceContext(trafficStreams, 0);
            TrafficReplayerRunner.runReplayer(
                numExpectedRequests,
                httpServer.localhostEndpoint(),
                new IndexWatchingListenerFactory(),
                () -> TestContext.noOtelTracking(),
                trafficSourceSupplier
            );
            Assertions.assertEquals(
                trafficSourceSupplier.trafficStreamsList.size(),
                trafficSourceSupplier.nextReadCursor.get()
            );
            log.info("done");
        }
    }
}

*/
// REBUILD-LIMBO-END(G10)

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
        var tupleWritten = new CompletableFuture<Void>();
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
                resultsToLogs::createTupleAndReportProgress,
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
