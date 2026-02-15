package org.opensearch.migrations.replay.e2etests;

import javax.net.ssl.SSLException;

import java.io.EOFException;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import org.opensearch.migrations.replay.CapturedTrafficToHttpTransactionAccumulator;
import org.opensearch.migrations.replay.ReplayEngine;
import org.opensearch.migrations.replay.SourceTargetCaptureTuple;
import org.opensearch.migrations.replay.TestHttpServerContext;
import org.opensearch.migrations.replay.TimeShifter;
import org.opensearch.migrations.replay.TrafficReplayerTopLevel;
import org.opensearch.migrations.replay.datatypes.ITrafficStreamKey;
import org.opensearch.migrations.replay.datatypes.PojoTrafficStreamAndKey;
import org.opensearch.migrations.replay.datatypes.PojoTrafficStreamKeyAndContext;
import org.opensearch.migrations.replay.tracing.IRootReplayerContext;
import org.opensearch.migrations.replay.tracing.ITrafficSourceContexts;
import org.opensearch.migrations.replay.traffic.generator.ExhaustiveTrafficStreamGenerator;
import org.opensearch.migrations.replay.traffic.source.ArrayCursorTrafficSourceContext;
import org.opensearch.migrations.replay.traffic.source.ISimpleTrafficCaptureSource;
import org.opensearch.migrations.replay.traffic.source.ITrafficStreamWithKey;
import org.opensearch.migrations.replay.traffic.source.TrafficStreamCursorKey;
import org.opensearch.migrations.replay.traffic.source.TrafficStreamLimiter;
import org.opensearch.migrations.replay.util.OrderedWorkerTracker;
import org.opensearch.migrations.testutils.SimpleNettyHttpServer;
import org.opensearch.migrations.testutils.WrapWithNettyLeakDetection;
import org.opensearch.migrations.tracing.InstrumentationTest;
import org.opensearch.migrations.tracing.TestContext;
import org.opensearch.migrations.trafficcapture.protos.CloseObservation;
import org.opensearch.migrations.trafficcapture.protos.EndOfSegmentsIndication;
import org.opensearch.migrations.trafficcapture.protos.EndOfMessageIndication;
import org.opensearch.migrations.trafficcapture.protos.ReadObservation;
import org.opensearch.migrations.trafficcapture.protos.TrafficObservation;
import org.opensearch.migrations.trafficcapture.protos.TrafficStream;
import org.opensearch.migrations.trafficcapture.protos.TrafficStreamUtils;
import org.opensearch.migrations.trafficcapture.protos.WriteObservation;
import org.opensearch.migrations.transform.IAuthTransformerFactory;
import org.opensearch.migrations.transform.IJsonTransformer;
import org.opensearch.migrations.transform.StaticAuthTransformerFactory;
import org.opensearch.migrations.transform.TransformationLoader;

import com.google.protobuf.ByteString;
import com.google.protobuf.Timestamp;
import lombok.Lombok;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

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

    protected static class TrafficReplayerWithWaitOnClose extends TrafficReplayerTopLevel {

        private final Duration maxWaitTime;

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
                new TrafficStreamLimiter(maxConcurrentOutstandingRequests),
                new OrderedWorkerTracker<>()
            );
            this.maxWaitTime = maxWaitTime;
        }

        @Override
        @SneakyThrows
        protected void wrapUpWorkAndEmitSummary(
            ReplayEngine replayEngine,
            CapturedTrafficToHttpTransactionAccumulator accumulator
        ) {
            var startTime = System.nanoTime();
            for (Duration waitTime = Duration.ofMillis(10); replayEngine.isWorkOutstanding(); waitTime = waitTime
                .multipliedBy(2)) {
                var totalDurationSpent = Duration.ofNanos(System.nanoTime() - startTime);
                if (maxWaitTime.minus(totalDurationSpent).isNegative()) {
                    throw new TimeoutException(
                        "Spent too long "
                            + totalDurationSpent
                            + " waiting for the ReplayEngine ("
                            + replayEngine
                            + ") to complete its outstanding work."
                    );
                }
                Thread.sleep(waitTime.toMillis());
            }
            super.wrapUpWorkAndEmitSummary(replayEngine, accumulator);
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
            var trafficSourceSupplier = new ArrayCursorTrafficSourceContext(List.of(trafficStreamWithJustClose));
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
            var trafficSourceSupplier = new ArrayCursorTrafficSourceContext(List.of(trafficStreamWithJustClose));

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
                    public CompletableFuture<List<ITrafficStreamWithKey>> readNextTrafficStreamChunk(
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
                    public CommitResult commitTrafficStream(ITrafficStreamKey trafficStreamKey) throws IOException {
                        return null;
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
                        new ArrayCursorTrafficSourceContext(List.of())
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
            var trafficSourceSupplier = new ArrayCursorTrafficSourceContext(trafficStreams);
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

    @Test
    @ResourceLock("TrafficReplayerRunner")
    @Tag("longTest")
    public void testReusedConnectionAndSessionFailsWithStaleSessionState() {
        var random = new Random(1);
        try (
            var httpServer = SimpleNettyHttpServer.makeServer(
                false,
                Duration.ofMillis(5),
                response -> TestHttpServerContext.makeResponse(random, response)
            )
        ) {
            var firstRequest = "GET /first HTTP/1.1\r\nConnection: Keep-Alive\r\nHost: localhost\r\n\r\n";
            var secondRequest = "GET /second HTTP/1.1\r\nConnection: Keep-Alive\r\nHost: localhost\r\n\r\n";
            var firstStream = makeSingleRequestStream(TEST_NODE_ID, TEST_CONNECTION_ID, firstRequest);
            var secondStream = makeSingleRequestStream(TEST_NODE_ID, TEST_CONNECTION_ID, secondRequest);
            var trafficSourceSupplier = new ArrayCursorTrafficSourceContext(List.of(firstStream, secondStream));

            var thrown = Assertions.assertThrows(Throwable.class, () ->
                TrafficReplayerRunner.runReplayer(
                    2,
                    httpServer.localhostEndpoint(),
                    () -> t -> { },
                    () -> TestContext.noOtelTracking(),
                    trafficSourceSupplier,
                    new TimeShifter(10 * 1000)
                ));

            var rootCause = getDeepestCause(thrown);
            Assertions.assertInstanceOf(IllegalStateException.class, rootCause);
            Assertions.assertTrue(rootCause.getMessage().contains("dependencyDiagnosticFutureRef was already set"));
        } catch (Throwable t) {
            throw Lombok.sneakyThrow(t);
        }
    }

    @Test
    @ResourceLock("TrafficReplayerRunner")
    @Tag("longTest")
    public void testReadSegmentRequestContainsUnexpectedEmptyPacket() throws Throwable {
        var random = new Random(1);
        try (
            var httpServer = SimpleNettyHttpServer.makeServer(
                false,
                Duration.ofMillis(5),
                response -> TestHttpServerContext.makeResponse(random, response)
            )
        ) {
            var now = Instant.now();
            var fixedTimestamp = Timestamp.newBuilder()
                .setSeconds(now.getEpochSecond())
                .setNanos(now.getNano())
                .build();
            var part1 = "GET /segmented HTTP/1.1\r\n";
            var part2 = "Connection: Keep-Alive\r\nHost: localhost\r\n\r\n";
            var stream = TrafficStream.newBuilder()
                .setNodeId(TEST_NODE_ID)
                .setConnectionId(TEST_CONNECTION_ID)
                .setPriorRequestsReceived(0)
                .addSubStream(
                    TrafficObservation.newBuilder()
                        .setTs(fixedTimestamp)
                        .setReadSegment(
                            org.opensearch.migrations.trafficcapture.protos.ReadSegmentObservation.newBuilder()
                                .setData(ByteString.copyFrom(part1.getBytes(StandardCharsets.UTF_8)))
                                .build()
                        )
                        .build()
                )
                .addSubStream(
                    TrafficObservation.newBuilder()
                        .setTs(fixedTimestamp)
                        .setReadSegment(
                            org.opensearch.migrations.trafficcapture.protos.ReadSegmentObservation.newBuilder()
                                .setData(ByteString.copyFrom(part2.getBytes(StandardCharsets.UTF_8)))
                                .build()
                        )
                        .build()
                )
                .addSubStream(
                    TrafficObservation.newBuilder()
                        .setTs(fixedTimestamp)
                        .setSegmentEnd(EndOfSegmentsIndication.getDefaultInstance())
                        .build()
                )
                .addSubStream(
                    TrafficObservation.newBuilder()
                        .setTs(fixedTimestamp)
                        .setEndOfMessageIndicator(
                            EndOfMessageIndication.newBuilder()
                                .setFirstLineByteLength(24)
                                .setHeadersByteLength(58)
                                .build()
                        )
                        .build()
                )
                .addSubStream(
                    TrafficObservation.newBuilder()
                        .setTs(fixedTimestamp)
                        .setWrite(
                            WriteObservation.newBuilder()
                                .setData(ByteString.copyFrom("HTTP/1.1 OK 200\r\n".getBytes(StandardCharsets.UTF_8)))
                                .build()
                        )
                        .build()
                )
                .addSubStream(
                    TrafficObservation.newBuilder()
                        .setTs(fixedTimestamp)
                        .setClose(CloseObservation.getDefaultInstance())
                        .build()
                )
                .build();

            var trafficSourceSupplier = new ArrayCursorTrafficSourceContext(List.of(stream));
            var requestPacketSizes = new AtomicInteger(-1);

            TrafficReplayerRunner.runReplayer(
                1,
                httpServer.localhostEndpoint(),
                () -> tuple -> requestPacketSizes.set(tuple.sourcePair.getRequestData().packetBytes.size()),
                () -> TestContext.noOtelTracking(),
                trafficSourceSupplier,
                new TimeShifter(10 * 1000)
            );

            Assertions.assertEquals(3, requestPacketSizes.get());
        }
    }

    private static TrafficStream makeSingleRequestStream(String nodeId, String connectionId, String rawRequest) {
        var now = Instant.now();
        var fixedTimestamp = Timestamp.newBuilder()
            .setSeconds(now.getEpochSecond())
            .setNanos(now.getNano())
            .build();
        return TrafficStream.newBuilder()
            .setNodeId(nodeId)
            .setConnectionId(connectionId)
            .setPriorRequestsReceived(0)
            .addSubStream(
                TrafficObservation.newBuilder()
                    .setTs(fixedTimestamp)
                    .setRead(
                        ReadObservation.newBuilder()
                            .setData(ByteString.copyFrom(rawRequest.getBytes(StandardCharsets.UTF_8)))
                            .build()
                    )
                    .build()
            )
            .addSubStream(
                TrafficObservation.newBuilder()
                    .setTs(fixedTimestamp)
                    .setEndOfMessageIndicator(
                        EndOfMessageIndication.newBuilder()
                            .setFirstLineByteLength(18)
                            .setHeadersByteLength(58)
                            .build()
                    )
                    .build()
            )
            .addSubStream(
                TrafficObservation.newBuilder()
                    .setTs(fixedTimestamp)
                    .setWrite(
                        WriteObservation.newBuilder()
                            .setData(ByteString.copyFrom("HTTP/1.1 OK 200\r\n".getBytes(StandardCharsets.UTF_8)))
                            .build()
                    )
                    .build()
            )
            .addSubStream(
                TrafficObservation.newBuilder()
                    .setTs(fixedTimestamp)
                    .setClose(CloseObservation.getDefaultInstance())
                    .build()
            )
            .build();
    }

    private static Throwable getDeepestCause(Throwable throwable) {
        var current = throwable;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current;
    }
}
