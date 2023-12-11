package org.opensearch.migrations.replay;

import com.google.protobuf.ByteString;
import com.google.protobuf.Timestamp;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.opensearch.migrations.replay.datatypes.ITrafficStreamKey;
import org.opensearch.migrations.replay.datatypes.PojoTrafficStreamWithKey;
import org.opensearch.migrations.replay.traffic.source.BlockingTrafficSource;
import org.opensearch.migrations.replay.traffic.source.ISimpleTrafficCaptureSource;
import org.opensearch.migrations.replay.traffic.source.ITrafficStreamWithKey;
import org.opensearch.migrations.testutils.SimpleNettyHttpServer;
import org.opensearch.migrations.testutils.WrapWithNettyLeakDetection;
import org.opensearch.migrations.trafficcapture.protos.CloseObservation;
import org.opensearch.migrations.trafficcapture.protos.EndOfMessageIndication;
import org.opensearch.migrations.trafficcapture.protos.ReadObservation;
import org.opensearch.migrations.trafficcapture.protos.TrafficObservation;
import org.opensearch.migrations.trafficcapture.protos.TrafficStream;
import org.opensearch.migrations.trafficcapture.protos.TrafficStreamUtils;
import org.opensearch.migrations.trafficcapture.protos.WriteObservation;
import org.opensearch.migrations.transform.StaticAuthTransformerFactory;

import java.io.EOFException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.PriorityQueue;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.Collectors;

@Slf4j
// It would be great to test with leak detection here, but right now this test relies upon TrafficReplayer.shutdown()
// to recycle the TrafficReplayers.  Since that shutdown process optimizes for speed of teardown, rather than tidying
// everything up as it closes the door, some leaks may be inevitable.  E.g. when work is outstanding and being sent
// to the test server, a shutdown will stop those work threads without letting them flush through all of their work
// (since that could take a very long time) and some of the work might have been followed by resource releases.
@WrapWithNettyLeakDetection(disableLeakChecks = true)
public class FullTrafficReplayerTest {

    public static final int INITIAL_STOP_REPLAYER_REQUEST_COUNT = 1;
    public static final String TEST_NODE_ID = "TestNodeId";
    public static final String TEST_CONNECTION_ID = "testConnectionId";

    private static class IndexWatchingListenerFactory implements Supplier<Consumer<SourceTargetCaptureTuple>> {
        AtomicInteger nextStopPointRef = new AtomicInteger(INITIAL_STOP_REPLAYER_REQUEST_COUNT);

        @Override
        public Consumer<SourceTargetCaptureTuple> get() {
            log.info("StopAt="+nextStopPointRef.get());
            var stopPoint = nextStopPointRef.get();
            return tuple -> {
                var key = tuple.uniqueRequestKey;
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
    public void testSingleStreamWithCloseIsCommitted() throws Throwable {
        var random = new Random(1);
        var httpServer = SimpleNettyHttpServer.makeServer(false, Duration.ofMillis(2),
                response->TestHttpServerContext.makeResponse(random, response));
        var trafficStreamWithJustClose = TrafficStream.newBuilder()
                .setNodeId(TEST_NODE_ID)
                .setConnectionId(TEST_CONNECTION_ID)
                .addSubStream(TrafficObservation.newBuilder()
                        .setClose(CloseObservation.newBuilder().build()).build())
                .build();
        var trafficSourceSupplier = new ArrayCursorTrafficSourceFactory(List.of(trafficStreamWithJustClose));
        TrafficReplayerRunner.runReplayerUntilSourceWasExhausted(0,
                httpServer.localhostEndpoint(), new IndexWatchingListenerFactory(), trafficSourceSupplier);
        Assertions.assertEquals(1, trafficSourceSupplier.nextReadCursor.get());
        log.info("done");
    }

    @Test
    public void testDoubleRequestWithCloseIsCommittedOnce() throws Throwable {
        var random = new Random(1);
        var httpServer = SimpleNettyHttpServer.makeServer(false, Duration.ofMillis(2),
                response->TestHttpServerContext.makeResponse(random, response));
        var baseTime = Instant.now();
        var fixedTimestamp =
                Timestamp.newBuilder().setSeconds(baseTime.getEpochSecond()).setNanos(baseTime.getNano()).build();
        var tsb = TrafficStream.newBuilder().setConnectionId("C");
        for (int i=0; i<2; ++i) {
            tsb = tsb
                    .addSubStream(TrafficObservation.newBuilder().setTs(fixedTimestamp)
                            .setRead(ReadObservation.newBuilder()
                                    .setData(ByteString.copyFrom(("GET /" +  i + " HTTP/1.0\r\n")
                                            .getBytes(StandardCharsets.UTF_8)))
                                    .build())
                            .build())
                    .addSubStream(TrafficObservation.newBuilder().setTs(fixedTimestamp)
                            .setEndOfMessageIndicator(EndOfMessageIndication.newBuilder()
                                    .setFirstLineByteLength(14)
                                    .setHeadersByteLength(14)
                                    .build())
                            .build())
                    .addSubStream(TrafficObservation.newBuilder().setTs(fixedTimestamp)
                            .setWrite(WriteObservation.newBuilder()
                                    .setData(ByteString.copyFrom("HTTP/1.0 OK 200\r\n".getBytes(StandardCharsets.UTF_8)))
                                    .build())
                            .build());
        }
        var trafficStream = tsb.addSubStream(TrafficObservation.newBuilder().setTs(fixedTimestamp)
                .setClose(CloseObservation.getDefaultInstance()))
                .build();
        var trafficSource =
                new ArrayCursorTrafficCaptureSource(new ArrayCursorTrafficSourceFactory(List.of(trafficStream)));
        var tr = new TrafficReplayer(httpServer.localhostEndpoint(), null,
                new StaticAuthTransformerFactory("TEST"), null,
                true, 10, 10*1024);

        var tuplesReceived = new HashSet<String>();
        try (var blockingTrafficSource = new BlockingTrafficSource(trafficSource, Duration.ofMinutes(2))) {
            tr.setupRunAndWaitForReplayWithShutdownChecks(Duration.ofSeconds(70), blockingTrafficSource,
                    new TimeShifter(10 * 1000), (t) -> {
                        var key = t.uniqueRequestKey;
                        var wasNew = tuplesReceived.add(key.toString());
                        Assertions.assertTrue(wasNew);
                    });
        } finally {
            tr.shutdown(null);
        }

        Assertions.assertEquals(2, tuplesReceived.size());
        log.info("done");
    }

    @ParameterizedTest
    @CsvSource(value = {
            "3,false",
            "-1,false",
            "3,true",
            "-1,true",
    })
    @Tag("longTest")
    public void fullTest(int testSize, boolean randomize) throws Throwable {
        var random = new Random(1);
        var httpServer = SimpleNettyHttpServer.makeServer(false, Duration.ofMillis(2),
                response->TestHttpServerContext.makeResponse(random,response));
        var streamAndConsumer = TrafficStreamGenerator.generateStreamAndSumOfItsTransactions(testSize, randomize);
        var numExpectedRequests = streamAndConsumer.numHttpTransactions;
        var trafficStreams = streamAndConsumer.stream.collect(Collectors.toList());
        log.atInfo().setMessage(()->trafficStreams.stream().map(ts->TrafficStreamUtils.summarizeTrafficStream(ts))
                        .collect(Collectors.joining("\n"))).log();
        var trafficSourceSupplier = new ArrayCursorTrafficSourceFactory(trafficStreams);
        TrafficReplayerRunner.runReplayerUntilSourceWasExhausted(numExpectedRequests,
                httpServer.localhostEndpoint(), new IndexWatchingListenerFactory(), trafficSourceSupplier);
        Assertions.assertEquals(trafficSourceSupplier.trafficStreamsList.size(), trafficSourceSupplier.nextReadCursor.get());
        log.info("done");
    }

    @Getter
    @ToString
    @EqualsAndHashCode
    private static class TrafficStreamCursorKey implements ITrafficStreamKey, Comparable<TrafficStreamCursorKey> {
        public final int arrayIndex;

        public final String connectionId;
        public final String nodeId;
        public final int trafficStreamIndex;


        public TrafficStreamCursorKey(TrafficStream stream, int arrayIndex) {
            connectionId = stream.getConnectionId();
            nodeId = stream.getNodeId();
            trafficStreamIndex = TrafficStreamUtils.getTrafficStreamIndex(stream);
            this.arrayIndex = arrayIndex;
        }

        @Override
        public int compareTo(TrafficStreamCursorKey other) {
            return Integer.compare(arrayIndex, other.arrayIndex);
        }
    }

    private static class ArrayCursorTrafficSourceFactory implements Supplier<ISimpleTrafficCaptureSource> {
        List<TrafficStream> trafficStreamsList;
        AtomicInteger nextReadCursor = new AtomicInteger();

        public ArrayCursorTrafficSourceFactory(List<TrafficStream> trafficStreamsList) {
            this.trafficStreamsList = trafficStreamsList;
        }

        public ISimpleTrafficCaptureSource get() {
            var rval = new ArrayCursorTrafficCaptureSource(this);
            log.info("trafficSource="+rval+" readCursor="+rval.readCursor.get()+" nextReadCursor="+ nextReadCursor.get());
            return rval;
        }
    }

    private static class ArrayCursorTrafficCaptureSource implements ISimpleTrafficCaptureSource {
        final AtomicInteger readCursor;
        final PriorityQueue<TrafficStreamCursorKey> pQueue = new PriorityQueue<>();
        Integer cursorHighWatermark;
        ArrayCursorTrafficSourceFactory arrayCursorTrafficSourceFactory;

        public ArrayCursorTrafficCaptureSource(ArrayCursorTrafficSourceFactory arrayCursorTrafficSourceFactory) {
            var startingCursor = arrayCursorTrafficSourceFactory.nextReadCursor.get();
            log.info("startingCursor = "  + startingCursor);
            this.readCursor = new AtomicInteger(startingCursor);
            this.arrayCursorTrafficSourceFactory = arrayCursorTrafficSourceFactory;
            cursorHighWatermark = startingCursor;
        }

        @Override
        public CompletableFuture<List<ITrafficStreamWithKey>> readNextTrafficStreamChunk() {
            var idx = readCursor.getAndIncrement();
            log.info("reading chunk from index="+idx);
            if (arrayCursorTrafficSourceFactory.trafficStreamsList.size() <= idx) {
                return CompletableFuture.failedFuture(new EOFException());
            }
            var stream = arrayCursorTrafficSourceFactory.trafficStreamsList.get(idx);
            var key = new TrafficStreamCursorKey(stream, idx);
            synchronized (pQueue) {
                pQueue.add(key);
                cursorHighWatermark = idx;
            }
            return CompletableFuture.supplyAsync(()->List.of(new PojoTrafficStreamWithKey(stream, key)));
        }

        @Override
        public void commitTrafficStream(ITrafficStreamKey trafficStreamKey) {
            synchronized (pQueue) { // figure out if I need to do something more efficient later
                log.info("Commit called for "+trafficStreamKey+" with pQueue.size="+pQueue.size());
                var incomingCursor = ((TrafficStreamCursorKey)trafficStreamKey).arrayIndex;
                int topCursor = pQueue.peek().arrayIndex;
                var didRemove = pQueue.remove(trafficStreamKey);
                if (!didRemove) {
                    log.error("no item "+incomingCursor+" to remove from "+pQueue);
                }
                assert didRemove;
                if (topCursor == incomingCursor) {
                    topCursor = Optional.ofNullable(pQueue.peek()).map(k->k.getArrayIndex())
                            .orElse(cursorHighWatermark+1); // most recent cursor was previously popped
                    log.info("Commit called for "+trafficStreamKey+", and new topCursor="+topCursor);
                    arrayCursorTrafficSourceFactory.nextReadCursor.set(topCursor);
                } else {
                    log.info("Commit called for "+trafficStreamKey+", but topCursor="+topCursor);
                }
            }
        }
    }


}
