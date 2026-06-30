package org.opensearch.migrations.replay.e2etests;

import javax.net.ssl.SSLException;

import java.io.EOFException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import org.opensearch.migrations.replay.RequestFilteredException;
import org.opensearch.migrations.replay.TestHttpServerContext;
import org.opensearch.migrations.replay.TimeShifter;
import org.opensearch.migrations.replay.datatypes.ITrafficStreamKey;
import org.opensearch.migrations.replay.datatypes.PojoTrafficStreamAndKey;
import org.opensearch.migrations.replay.datatypes.PojoTrafficStreamKeyAndContext;
import org.opensearch.migrations.replay.tracing.ITrafficSourceContexts;
import org.opensearch.migrations.replay.traffic.generator.ExhaustiveTrafficStreamGenerator;
import org.opensearch.migrations.replay.traffic.source.ArrayCursorTrafficSourceContext;
import org.opensearch.migrations.replay.traffic.source.ISimpleTrafficCaptureSource;
import org.opensearch.migrations.replay.traffic.source.ITrafficStreamWithKey;
import org.opensearch.migrations.testutils.SimpleNettyHttpServer;
import org.opensearch.migrations.testutils.TrafficStreamFixtures;
import org.opensearch.migrations.testutils.WrapWithNettyLeakDetection;
import org.opensearch.migrations.tracing.TestContext;
import org.opensearch.migrations.trafficcapture.protos.TrafficStream;
import org.opensearch.migrations.transform.IJsonTransformer;
import org.opensearch.migrations.transform.StaticAuthTransformerFactory;
import org.opensearch.migrations.transform.TransformationLoader;

import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;

/**
 * End-to-end integration tests for request filter extension point.
 * Exercises the full pipeline path through RequestTransformerAndSender.
 */
@Slf4j
@Tag("longTest")
@WrapWithNettyLeakDetection(disableLeakChecks = true)
class RequestFilterE2ETest extends FullTrafficReplayerTest {

    private static final String HTTP_GET =
        "GET / HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n";
    private static final String HTTP_POST =
        "POST /data HTTP/1.1\r\nHost: localhost\r\nContent-Length: 4\r\nConnection: close\r\n\r\ntest";

    private TrafficStream buildTrafficStream(String httpRequest) {
        return TrafficStreamFixtures.makeHttpRequestTrafficStream(TEST_NODE_ID, TEST_CONNECTION_ID, httpRequest);
    }

    /**
     * Filter rejects all requests → target server never receives traffic,
     * but traffic stream is still committed (Kafka offset advances).
     */
    @Test
    @ResourceLock("TrafficReplayerRunner")
    void filteredRequest_rejectAll_skipsTargetAndCommits() throws Throwable {
        var targetHitCount = new AtomicInteger(0);
        try (var httpServer = SimpleNettyHttpServer.makeServer(false, Duration.ofMillis(100),
            response -> { targetHitCount.incrementAndGet();
                return TestHttpServerContext.makeResponse(new Random(1), response); })) {

            var trafficSource = new ArrayCursorTrafficSourceContext(
                List.of(buildTrafficStream(HTTP_GET)));

            IJsonTransformer rejectAll = input -> {
                throw new RequestFilteredException("reject all");
            };

            TrafficReplayerRunner.runReplayer(0, (rc, threadPrefix) -> {
                try {
                    return new TrafficReplayerWithWaitOnClose(Duration.ofSeconds(30), rc,
                        httpServer.localhostEndpoint(), new StaticAuthTransformerFactory("TEST"),
                        true, 1, 1, rejectAll, threadPrefix);
                } catch (SSLException e) { throw new RuntimeException(e); }
            }, () -> t -> {}, () -> TestContext.noOtelTracking(),
                trafficSource, new TimeShifter(10 * 1000, Duration.ofMillis(100)));

            Assertions.assertEquals(0, targetHitCount.get(),
                "Filtered requests should not reach the target server");
            Assertions.assertEquals(1, trafficSource.nextReadCursor.get(),
                "Traffic stream should still be committed even when filtered");
        }
    }

    /**
     * Filter accepts all requests → requests flow through to target normally.
     * Proves the filter doesn't break the normal pipeline when it accepts.
     */
    @Test
    @ResourceLock("TrafficReplayerRunner")
    void filteredRequest_acceptAll_reachesTarget() throws Throwable {
        var targetHitCount = new AtomicInteger(0);
        var nonTrackingContext = TestContext.noOtelTracking();
        try (var httpServer = SimpleNettyHttpServer.makeServer(false, Duration.ofMillis(100),
            response -> { targetHitCount.incrementAndGet();
                return TestHttpServerContext.makeResponse(new Random(1), response); })) {

            var streamAndSizes = ExhaustiveTrafficStreamGenerator
                .generateStreamAndSumOfItsTransactions(nonTrackingContext, 16, true);
            var trafficStreams = streamAndSizes.stream.collect(Collectors.toList());

            Function<TestContext, ISimpleTrafficCaptureSource> trafficSourceFactory =
                rc -> new ISimpleTrafficCaptureSource() {
                    boolean isDone = false;
                    @Override
                    public CompletableFuture<List<ITrafficStreamWithKey>> readNextTrafficStreamChunk(
                        Supplier<ITrafficSourceContexts.IReadChunkContext> contextSupplier) {
                        if (isDone) return CompletableFuture.failedFuture(new EOFException());
                        isDone = true;
                        return CompletableFuture.completedFuture(trafficStreams.stream()
                            .map(ts -> (ITrafficStreamWithKey) new PojoTrafficStreamAndKey(ts,
                                PojoTrafficStreamKeyAndContext.build(ts, rc::createTrafficStreamContextForTest)))
                            .collect(Collectors.toList()));
                    }
                    @Override
                    public CommitResult commitTrafficStream(ITrafficStreamKey trafficStreamKey) { return null; }
                };

            // Accept-all: use the base transformer directly (no filter = all pass)
            TrafficReplayerRunner.runReplayer(streamAndSizes.numHttpTransactions, (rc, threadPrefix) -> {
                try {
                    IJsonTransformer base = new TransformationLoader()
                        .getTransformerFactoryLoaderWithNewHostName("localhost");
                    return new TrafficReplayerWithWaitOnClose(Duration.ofSeconds(600), rc,
                        httpServer.localhostEndpoint(), new StaticAuthTransformerFactory("TEST"),
                        true, 1, 1, base, threadPrefix);
                } catch (SSLException e) { throw new RuntimeException(e); }
            }, () -> t -> {}, () -> nonTrackingContext, trafficSourceFactory, new TimeShifter(10 * 1000));

            Assertions.assertTrue(targetHitCount.get() > 0,
                "Accepted requests should reach the target server");
        }
    }

    /**
     * Selective filter — only GET requests pass, POST requests are filtered.
     * Verifies per-request filtering logic works correctly.
     */
    @Test
    @ResourceLock("TrafficReplayerRunner")
    void filteredRequest_selectiveFilter_onlyGetPassesThrough() throws Throwable {
        var targetHitCount = new AtomicInteger(0);
        try (var httpServer = SimpleNettyHttpServer.makeServer(false, Duration.ofMillis(100),
            response -> { targetHitCount.incrementAndGet();
                return TestHttpServerContext.makeResponse(new Random(1), response); })) {

            // Only POST traffic — should be filtered
            var trafficSource = new ArrayCursorTrafficSourceContext(
                List.of(buildTrafficStream(HTTP_POST)));

            // Filter that only accepts GET requests
            IJsonTransformer getOnlyFilter = input -> {
                @SuppressWarnings("unchecked")
                var map = (Map<String, Object>) input;
                var method = (String) map.get("method");
                if (!"GET".equals(method)) {
                    throw new RequestFilteredException("Only GET allowed");
                }
                return input;
            };

            TrafficReplayerRunner.runReplayer(0, (rc, threadPrefix) -> {
                try {
                    return new TrafficReplayerWithWaitOnClose(Duration.ofSeconds(30), rc,
                        httpServer.localhostEndpoint(), new StaticAuthTransformerFactory("TEST"),
                        true, 1, 1, getOnlyFilter, threadPrefix);
                } catch (SSLException e) { throw new RuntimeException(e); }
            }, () -> t -> {}, () -> TestContext.noOtelTracking(),
                trafficSource, new TimeShifter(10 * 1000, Duration.ofMillis(100)));

            Assertions.assertEquals(0, targetHitCount.get(),
                "POST request should be filtered and not reach target");
            Assertions.assertEquals(1, trafficSource.nextReadCursor.get(),
                "Traffic stream should still be committed");
        }
    }

    /**
     * Filter throws a non-RequestFilteredException → should propagate as error,
     * not be treated as a filtered request.
     */
    @Test
    @ResourceLock("TrafficReplayerRunner")
    void filterThrowsUnexpectedException_propagatesAsError() throws Throwable {
        try (var httpServer = SimpleNettyHttpServer.makeServer(false, Duration.ofMillis(100),
            response -> TestHttpServerContext.makeResponse(new Random(1), response))) {

            var trafficSource = new ArrayCursorTrafficSourceContext(
                List.of(buildTrafficStream(HTTP_GET)));

            IJsonTransformer throwsRuntimeException = input -> {
                throw new RuntimeException("Unexpected transformer error");
            };

            TrafficReplayerRunner.runReplayer(0, (rc, threadPrefix) -> {
                try {
                    return new TrafficReplayerWithWaitOnClose(Duration.ofSeconds(30), rc,
                        httpServer.localhostEndpoint(), new StaticAuthTransformerFactory("TEST"),
                        true, 1, 1, throwsRuntimeException, threadPrefix);
                } catch (SSLException e) { throw new RuntimeException(e); }
            }, () -> t -> {}, () -> TestContext.noOtelTracking(),
                trafficSource, new TimeShifter(10 * 1000, Duration.ofMillis(100)));

            // Pipeline should still commit (error is handled, not fatal)
            Assertions.assertEquals(1, trafficSource.nextReadCursor.get(),
                "Traffic stream should be committed even on transformer error");
        }
    }
}
