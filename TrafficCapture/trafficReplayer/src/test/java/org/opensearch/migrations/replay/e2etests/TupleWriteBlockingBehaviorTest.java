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
import java.util.concurrent.atomic.AtomicReference;

import org.opensearch.migrations.replay.RootReplayerConstructorExtensions;
import org.opensearch.migrations.replay.TestHttpServerContext;
import org.opensearch.migrations.replay.TimeShifter;
import org.opensearch.migrations.replay.TrafficReplayer;
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
import org.junit.jupiter.api.Timeout;

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

    static class FailingTupleSink implements TupleSink {
        final CountDownLatch allAccepted;

        FailingTupleSink(int expectedCount) {
            this.allAccepted = new CountDownLatch(expectedCount);
        }

        @Override
        public void accept(Map<String, Object> tupleMap, CompletableFuture<Void> future) {
            allAccepted.countDown();
            future.completeExceptionally(new RuntimeException("tuple write failed"));
        }

        @Override
        public void flush() {}

        @Override
        public void periodicFlush() {}

        @Override
        public void close() {}
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
     * <p>Coordination is signal-driven throughout: the test waits on a
     * {@link CountDownLatch} the sink counts down each time it accepts a tuple, then waits
     * on a {@link CompletableFuture} that completes when the replay thread exits, and
     * polls the source's commit cursor until it advances. The only timeouts are the
     * method-level {@code @Timeout} and the {@link #awaitCursorAdvance} safety net —
     * no tight inner deadline depends on CI scheduling fairness.
     */
    @Test
    @Timeout(value = 2, unit = TimeUnit.MINUTES)
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

                // Run the replayer in a background thread. The future completes when the
                // thread exits — that's the signal we use to assert "replay is done"
                // instead of a Thread.join with a hard-coded deadline.
                var replayDone = new CompletableFuture<Void>();
                var replayThread = new Thread(() -> {
                    try {
                        tr.setupRunAndWaitForReplayToFinish(
                            Duration.ofSeconds(70), Duration.ofSeconds(30),
                            blockingTrafficSource, new TimeShifter(10 * 1000),
                            tupleWriter, Duration.ofSeconds(5));
                        replayDone.complete(null);
                    } catch (Exception e) {
                        log.atError().setCause(e).setMessage("Replay thread exception").log();
                        replayDone.completeExceptionally(e);
                    }
                });
                replayThread.start();

                // Wait for the sink to receive every tuple. Bare await — the method-level
                // @Timeout is the upper bound, so we don't have to pick a number here that
                // somehow has to fit JVM warmup + Netty startup + 3 round-trips.
                latchedSink.allAccepted.await();

                // Invariant under test: while the futures are still held, the commit
                // cursor MUST not have advanced. This is a snapshot of a state the
                // production code is contractually blocked from changing right now —
                // if it has advanced, that IS the bug.
                Assertions.assertEquals(0, sourceContext.nextReadCursor.get(),
                    "Offsets must not be committed while tuple futures are pending");

                // Release the futures and wait — signal-driven — for the commit to
                // catch up. awaitCursorAdvance polls the production-set cursor; no
                // assumption about how fast the commit thread will get scheduled.
                latchedSink.releaseAll();
                awaitCursorAdvance(sourceContext, 1, Duration.ofMinutes(1));

                // Wait for the replay loop to exit cleanly. Surface any exception
                // that occurred inside the thread.
                replayDone.get(1, TimeUnit.MINUTES);
                Assertions.assertFalse(replayThread.isAlive(), "Replay thread should have finished");

                tr.shutdown(null).get();
            }
        }
    }

    /**
     * Verifies that subsequent requests on the same connection are NOT blocked
     * while tuple writes are pending.
     *
     * <p>If tuple writes back-pressured request processing, the latch would only ever
     * count down once (the first request). The fact that it counts all the way to zero
     * — with every future still held by the sink — is the invariant. The bare
     * {@code await()} relies on the method-level {@code @Timeout} as the upper bound
     * rather than picking a number that has to absorb cold-JVM and CI-scheduling jitter.
     */
    @Test
    @Timeout(value = 2, unit = TimeUnit.MINUTES)
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

                var replayDone = new CompletableFuture<Void>();
                var replayThread = new Thread(() -> {
                    try {
                        tr.setupRunAndWaitForReplayToFinish(
                            Duration.ofSeconds(70), Duration.ofSeconds(30),
                            blockingTrafficSource, new TimeShifter(10 * 1000),
                            tupleWriter, Duration.ofSeconds(5));
                        replayDone.complete(null);
                    } catch (Exception e) {
                        log.atError().setCause(e).setMessage("Replay thread exception").log();
                        replayDone.completeExceptionally(e);
                    }
                });
                replayThread.start();

                // The only assertion that matters: every request was accepted by the
                // sink even though no future was completed. Bare await — the method
                // @Timeout is the upper bound, not an arbitrary inner deadline.
                latchedSink.allAccepted.await();
                Assertions.assertEquals(0, latchedSink.allAccepted.getCount(),
                    "All requests should have been accepted by the sink without "
                        + "waiting for future completion");

                // Clean up — release futures, then wait for the replay to finish.
                latchedSink.releaseAll();
                replayDone.get(1, TimeUnit.MINUTES);
                tr.shutdown(null).get();
            }
        }
    }

    /**
     * Polls a signal the production code controls — the commit cursor — until it
     * reaches the expected value. Deadline-bounded so a real hang fails the test
     * rather than tying up the CI runner.
     */
    private static void awaitCursorAdvance(
            ArrayCursorTrafficSourceContext ctx, int target, Duration deadline)
            throws InterruptedException {
        var endNanos = System.nanoTime() + deadline.toNanos();
        while (ctx.nextReadCursor.get() < target) {
            if (System.nanoTime() > endNanos) {
                Assertions.fail("Commit cursor did not advance to " + target
                    + " within " + deadline + " (still at " + ctx.nextReadCursor.get() + ")");
            }
            Thread.sleep(20);
        }
    }

    @Test
    public void tupleWriteFailureStopsReplayWithoutCommittingOffset() throws Throwable {
        var random = new Random(1);
        var failingSink = new FailingTupleSink(NUM_REQUESTS);

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
                 var tupleWriter = new ThreadLocalTupleWriter(i -> failingSink)) {

                var replayFailure = new AtomicReference<Throwable>();
                var replayThread = new Thread(() -> {
                    try {
                        tr.setupRunAndWaitForReplayWithShutdownChecks(
                            Duration.ofSeconds(70), Duration.ofSeconds(30),
                            blockingTrafficSource, new TimeShifter(10 * 1000),
                            tupleWriter, Duration.ofSeconds(5));
                    } catch (Throwable t) {
                        replayFailure.set(t);
                        log.atError().setCause(t).setMessage("Replay thread exception").log();
                    }
                });
                replayThread.start();

                Assertions.assertTrue(
                    failingSink.allAccepted.await(30, TimeUnit.SECONDS),
                    "Timed out waiting for failing sink to accept all tuples"
                );

                replayThread.join(30_000);
                Assertions.assertFalse(replayThread.isAlive(), "Replay thread should have finished");
                Assertions.assertEquals(0, sourceContext.nextReadCursor.get(),
                    "Offsets should not be committed after tuple write failure");
                Assertions.assertInstanceOf(TrafficReplayer.TerminationException.class, replayFailure.get());
                var termination = (TrafficReplayer.TerminationException) replayFailure.get();
                Assertions.assertInstanceOf(Error.class, termination.originalCause);
                Assertions.assertTrue(
                    termination.originalCause.getMessage().contains("Fatal tuple write failure"),
                    "Fatal shutdown should explain that tuple output was not durably written"
                );

                tr.shutdown(null).get();
            }
        }
    }
}
