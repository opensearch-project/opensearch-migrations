package org.opensearch.migrations.replay.e2etests;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.opensearch.migrations.replay.RootReplayerConstructorExtensions;
import org.opensearch.migrations.replay.TestHttpServerContext;
import org.opensearch.migrations.replay.TimeShifter;
import org.opensearch.migrations.replay.sink.ThreadLocalTupleWriter;
import org.opensearch.migrations.replay.sink.TupleSink;
import org.opensearch.migrations.replay.traffic.source.ArrayCursorTrafficCaptureSource;
import org.opensearch.migrations.replay.traffic.source.ArrayCursorTrafficSourceContext;
import org.opensearch.migrations.replay.traffic.source.BlockingTrafficSource;
import org.opensearch.migrations.testutils.SimpleNettyHttpServer;
import org.opensearch.migrations.testutils.WrapWithNettyLeakDetection;
import org.opensearch.migrations.tracing.InstrumentationTest;
import org.opensearch.migrations.trafficcapture.protos.CloseObservation;
import org.opensearch.migrations.trafficcapture.protos.EndOfMessageIndication;
import org.opensearch.migrations.trafficcapture.protos.ReadObservation;
import org.opensearch.migrations.trafficcapture.protos.TrafficObservation;
import org.opensearch.migrations.trafficcapture.protos.TrafficStream;
import org.opensearch.migrations.trafficcapture.protos.WriteObservation;
import org.opensearch.migrations.transform.StaticAuthTransformerFactory;
import org.opensearch.migrations.transform.TransformationLoader;

import com.google.protobuf.ByteString;
import com.google.protobuf.Timestamp;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Verifies that the ThreadLocalTupleWriter architecture correctly:
 * <ol>
 *   <li>Blocks offset commits until tuple futures are completed (durability confirmed)</li>
 *   <li>Does NOT block subsequent requests while tuple writes are pending</li>
 * </ol>
 */
@Slf4j
@WrapWithNettyLeakDetection(disableLeakChecks = true)
public class TupleWriteBlockingBehaviorTest extends InstrumentationTest {

    private static final int NUM_REQUESTS = 3;

    /**
     * A TupleSink that holds futures without completing them until explicitly released.
     * This lets us observe whether offset commits are gated on future completion.
     */
    static class LatchedTupleSink implements TupleSink {
        final List<CompletableFuture<Void>> heldFutures = Collections.synchronizedList(new ArrayList<>());
        final CountDownLatch allAccepted;

        LatchedTupleSink(int expectedCount) {
            this.allAccepted = new CountDownLatch(expectedCount);
        }

        @Override
        public void accept(Map<String, Object> tupleMap, CompletableFuture<Void> future) {
            heldFutures.add(future);
            allAccepted.countDown();
        }

        @Override
        public void flush() {
            // Intentionally do NOT complete futures here — we control completion externally
        }

        @Override
        public void periodicFlush() {}

        @Override
        public void close() {
            releaseAll();
        }

        void releaseAll() {
            synchronized (heldFutures) {
                heldFutures.forEach(f -> f.complete(null));
                heldFutures.clear();
            }
        }
    }

    private TrafficStream buildTrafficStreamWithRequests(int numRequests) {
        var baseTime = Instant.now();
        var ts = Timestamp.newBuilder()
            .setSeconds(baseTime.getEpochSecond())
            .setNanos(baseTime.getNano())
            .build();
        var tsb = TrafficStream.newBuilder().setConnectionId("C").setNodeId("N");
        for (int i = 0; i < numRequests; i++) {
            tsb.addSubStream(TrafficObservation.newBuilder().setTs(ts)
                .setRead(ReadObservation.newBuilder()
                    .setData(ByteString.copyFrom(
                        ("GET /" + i + " HTTP/1.1\r\n"
                            + "Connection: Keep-Alive\r\n"
                            + "Host: localhost\r\n\r\n").getBytes(StandardCharsets.UTF_8))))
                .build());
            tsb.addSubStream(TrafficObservation.newBuilder().setTs(ts)
                .setEndOfMessageIndicator(EndOfMessageIndication.newBuilder()
                    .setFirstLineByteLength(14)
                    .setHeadersByteLength(58))
                .build());
            tsb.addSubStream(TrafficObservation.newBuilder().setTs(ts)
                .setWrite(WriteObservation.newBuilder()
                    .setData(ByteString.copyFrom(
                        "HTTP/1.1 200 OK\r\n\r\n".getBytes(StandardCharsets.UTF_8))))
                .build());
        }
        tsb.addSubStream(TrafficObservation.newBuilder().setTs(ts)
            .setClose(CloseObservation.getDefaultInstance()));
        return tsb.build();
    }

    /**
     * Verifies that offset commits are blocked until tuple futures complete.
     *
     * Strategy: use a LatchedTupleSink that holds futures indefinitely. After all tuples
     * are accepted (proving requests flowed through), check that offsets have NOT been committed.
     * Then release the futures and verify commits happen.
     */
    @Test
    public void tupleWriteBlocksOffsetCommit() throws Throwable {
        var random = new Random(1);
        var latchedSink = new LatchedTupleSink(NUM_REQUESTS);

        try (var httpServer = SimpleNettyHttpServer.makeServer(
                false, Duration.ofMinutes(10),
                response -> TestHttpServerContext.makeResponse(random, response))) {

            var trafficStream = buildTrafficStreamWithRequests(NUM_REQUESTS);
            var sourceContext = new ArrayCursorTrafficSourceContext(List.of(trafficStream));
            var trafficSource = new ArrayCursorTrafficCaptureSource(rootContext, sourceContext);

            var serverUri = httpServer.localhostEndpoint();
            try (var tr = new RootReplayerConstructorExtensions(
                    rootContext, serverUri,
                    new StaticAuthTransformerFactory("TEST"),
                    new TransformationLoader().getTransformerFactoryLoaderWithNewHostName(serverUri.getHost()),
                    RootReplayerConstructorExtensions.makeNettyPacketConsumerConnectionPool(serverUri, 10),
                    10 * 1024);
                 var blockingTrafficSource = new BlockingTrafficSource(trafficSource, Duration.ofMinutes(2));
                 var tupleWriter = new ThreadLocalTupleWriter(i -> latchedSink)) {

                // Run the replayer in a background thread since it blocks
                var replayThread = new Thread(() -> {
                    try {
                        tr.setupRunAndWaitForReplayToFinish(
                            Duration.ofSeconds(70), Duration.ofSeconds(30),
                            blockingTrafficSource, new TimeShifter(10 * 1000),
                            tupleWriter, Duration.ofSeconds(5));
                    } catch (Exception e) {
                        log.atError().setCause(e).setMessage("Replay thread exception").log();
                    }
                });
                replayThread.start();

                // Wait for all tuples to be accepted by the sink (requests have been processed)
                Assertions.assertTrue(
                    latchedSink.allAccepted.await(30, TimeUnit.SECONDS),
                    "Timed out waiting for all tuples to be accepted"
                );
                Assertions.assertEquals(0, latchedSink.allAccepted.getCount());

                // Offsets should NOT have been committed yet — futures are still held
                Assertions.assertEquals(0, sourceContext.nextReadCursor.get(),
                    "Offsets should not have been committed while tuple futures are pending");

                // Now release all futures
                latchedSink.releaseAll();

                // Wait for the replayer to finish
                replayThread.join(30_000);
                Assertions.assertFalse(replayThread.isAlive(), "Replay thread should have finished");

                // Offsets should now be committed
                Assertions.assertEquals(1, sourceContext.nextReadCursor.get(),
                    "Offsets should have been committed after tuple futures completed");

                tr.shutdown(null).get();
            }
        }
    }

    /**
     * Verifies that subsequent requests on the same connection are NOT blocked
     * while tuple writes are pending.
     *
     * Strategy: use a LatchedTupleSink. If request processing were blocked on tuple
     * completion, we'd never see all NUM_REQUESTS accepted. The fact that all tuples
     * arrive (while none are completed) proves requests flow independently.
     */
    @Test
    public void tupleWriteDoesNotBlockNextRequest() throws Throwable {
        var random = new Random(1);
        var latchedSink = new LatchedTupleSink(NUM_REQUESTS);

        try (var httpServer = SimpleNettyHttpServer.makeServer(
                false, Duration.ofMinutes(10),
                response -> TestHttpServerContext.makeResponse(random, response))) {

            var trafficStream = buildTrafficStreamWithRequests(NUM_REQUESTS);
            var sourceContext = new ArrayCursorTrafficSourceContext(List.of(trafficStream));
            var trafficSource = new ArrayCursorTrafficCaptureSource(rootContext, sourceContext);

            var serverUri = httpServer.localhostEndpoint();
            try (var tr = new RootReplayerConstructorExtensions(
                    rootContext, serverUri,
                    new StaticAuthTransformerFactory("TEST"),
                    new TransformationLoader().getTransformerFactoryLoaderWithNewHostName(serverUri.getHost()),
                    RootReplayerConstructorExtensions.makeNettyPacketConsumerConnectionPool(serverUri, 10),
                    10 * 1024);
                 var blockingTrafficSource = new BlockingTrafficSource(trafficSource, Duration.ofMinutes(2));
                 var tupleWriter = new ThreadLocalTupleWriter(i -> latchedSink)) {

                var replayThread = new Thread(() -> {
                    try {
                        tr.setupRunAndWaitForReplayToFinish(
                            Duration.ofSeconds(70), Duration.ofSeconds(30),
                            blockingTrafficSource, new TimeShifter(10 * 1000),
                            tupleWriter, Duration.ofSeconds(5));
                    } catch (Exception e) {
                        log.atError().setCause(e).setMessage("Replay thread exception").log();
                    }
                });
                replayThread.start();

                // If tuple writes blocked subsequent requests, this latch would never
                // count down to zero — only the first request would be accepted.
                // The fact that ALL requests arrive proves they are not blocked.
                Assertions.assertTrue(
                    latchedSink.allAccepted.await(30, TimeUnit.SECONDS),
                    "All " + NUM_REQUESTS + " requests should be processed even though "
                        + "no tuple futures have been completed — tuple writes must not block "
                        + "subsequent request processing"
                );
                Assertions.assertEquals(0, latchedSink.allAccepted.getCount(),
                    "All requests should have been accepted by the sink without waiting for future completion");

                // Clean up
                latchedSink.releaseAll();
                replayThread.join(30_000);
                tr.shutdown(null).get();
            }
        }
    }
}
