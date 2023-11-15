package org.opensearch.migrations.replay;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
import lombok.extern.slf4j.Slf4j;
import org.junit.Assume;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.opensearch.migrations.replay.datatypes.ITrafficStreamKey;
import org.opensearch.migrations.replay.datatypes.PojoTrafficStreamWithKey;
import org.opensearch.migrations.replay.traffic.source.ISimpleTrafficCaptureSource;
import org.opensearch.migrations.replay.traffic.source.ITrafficStreamWithKey;
import org.opensearch.migrations.testutils.SimpleNettyHttpServer;
import org.opensearch.migrations.testutils.WrapWithNettyLeakDetection;
import org.opensearch.migrations.trafficcapture.protos.CloseObservation;
import org.opensearch.migrations.trafficcapture.protos.TrafficObservation;
import org.opensearch.migrations.trafficcapture.protos.TrafficStream;
import org.opensearch.migrations.trafficcapture.protos.TrafficStreamUtils;

import java.io.EOFException;
import java.time.Duration;
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

    private static class IndexWatchingReceiverFactory implements Supplier<Consumer<SourceTargetCaptureTuple>> {
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
                httpServer.localhostEndpoint(), new IndexWatchingReceiverFactory(), trafficSourceSupplier);
        Assertions.assertEquals(1, trafficSourceSupplier.nextReadCursor.get());
        log.error("done");
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
                httpServer.localhostEndpoint(), new IndexWatchingReceiverFactory(), trafficSourceSupplier);
        Assertions.assertEquals(trafficSourceSupplier.streams.size(), trafficSourceSupplier.nextReadCursor.get());
        log.error("done");
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
        List<TrafficStream> streams;
        AtomicInteger nextReadCursor = new AtomicInteger();

        public ArrayCursorTrafficSourceFactory(List<TrafficStream> streams) {
            this.streams = streams;
        }

        public ISimpleTrafficCaptureSource get() {
            var rval = new ArrayCursorTrafficCaptureSource(this);
            log.error("trafficSource="+rval+" readCursor="+rval.readCursor.get()+" nextReadCursor="+ nextReadCursor.get());
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
            if (arrayCursorTrafficSourceFactory.streams.size() <= idx) {
                return CompletableFuture.failedFuture(new EOFException());
            }
            var stream = arrayCursorTrafficSourceFactory.streams.get(idx);
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
