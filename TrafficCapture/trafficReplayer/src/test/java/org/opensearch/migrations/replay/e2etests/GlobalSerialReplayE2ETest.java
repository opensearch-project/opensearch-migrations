package org.opensearch.migrations.replay.e2etests;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import org.opensearch.migrations.replay.tracing.RootReplayerContext;
import org.opensearch.migrations.testutils.SimpleHttpResponse;
import org.opensearch.migrations.testutils.SimpleNettyHttpServer;
import org.opensearch.migrations.transform.TransformationLoader;

import io.netty.handler.codec.http.HttpHeaderNames;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

// REBUILD-LIMBO(G10) -- inherited bodies remain marked and recoverable; the live G9
// replacement follows the marked regions. Javadoc stays outside the regions.
// Resolve each region to dead, keep, or refactor deliberately. If a member is deleted, delete its
// javadoc with it. See AGENTS.md section 8a.
// Test carried byte-identical. Unresolved: TestContext . Per AGENTS.md section 4 an inherited test may stay broken while the architectures are partly connected; this one is restored by the milestone that rebuilds its subject, keeping its assertions conceptually stable while changing the mechanics.
// Un-mark a member by deleting the delimiter lines around it and splitting this region; the
// code between them is verbatim, so blame survives. Read this before writing anything new

// REBUILD-LIMBO-START(G10)
/*

import javax.net.ssl.SSLException;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.opensearch.migrations.replay.TestHttpServerContext;
import org.opensearch.migrations.replay.TimeShifter;
import org.opensearch.migrations.replay.traffic.source.ArrayCursorTrafficSourceContext;
import org.opensearch.migrations.testutils.SimpleNettyHttpServer;
import org.opensearch.migrations.testutils.WrapWithNettyLeakDetection;
import org.opensearch.migrations.tracing.TestContext;
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
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.parallel.ResourceLock;

@WrapWithNettyLeakDetection(disableLeakChecks = true)
@Tag("longTest")
class GlobalSerialReplayE2ETest {

    private static final String NODE_ID = "global-order-test-node";
    private static final int REQUEST_COUNT = 4_096;
    private static final int CONNECTION_COUNT = 32;
    private static final long CONNECTION_RANDOM_SEED = 0x5EEDL;
    private static final int SENDING_THREADS = 4;
    private static final int MAX_CONCURRENT_REQUESTS = 1;
    private static final Timestamp OBSERVATION_TIME = Timestamp.newBuilder().setSeconds(1).build();

    @Test
    @Timeout(value = 5, unit = TimeUnit.MINUTES)
    @ResourceLock("TrafficReplayerRunner")
    void oneOutstandingRequestReplaysRandomlyInterleavedSameTimestampTrafficInSourceOrder() throws Throwable {
        var targetOrder = Collections.synchronizedList(new ArrayList<String>());
        var activeTargetRequests = new AtomicInteger();
        var maxActiveTargetRequests = new AtomicInteger();

        try (
            var httpServer = SimpleNettyHttpServer.makeServer(false, Duration.ofSeconds(5), request -> {
                targetOrder.add(request.getPath().getPath());
                var active = activeTargetRequests.incrementAndGet();
                maxActiveTargetRequests.accumulateAndGet(active, Integer::max);
                try {
                    return TestHttpServerContext.makeResponse(request, Duration.ZERO);
                } finally {
                    activeTargetRequests.decrementAndGet();
                }
            })
        ) {
            var sourceOrder = new ArrayList<String>(REQUEST_COUNT);
            var trafficStreams = new ArrayList<TrafficStream>(REQUEST_COUNT);
            var connectionSequence = new Random(CONNECTION_RANDOM_SEED)
                .ints(REQUEST_COUNT, 0, CONNECTION_COUNT)
                .toArray();
            var requestsPerConnection = new int[CONNECTION_COUNT];
            for (var connectionIndex : connectionSequence) {
                ++requestsPerConnection[connectionIndex];
            }
            var nextRequestIndex = new int[CONNECTION_COUNT];
            for (int globalRequestIndex = 0; globalRequestIndex < REQUEST_COUNT; ++globalRequestIndex) {
                var connectionIndex = connectionSequence[globalRequestIndex];
                var requestIndex = nextRequestIndex[connectionIndex]++;
                var connectionId = "C" + connectionIndex;
                var path = "/requests/" + globalRequestIndex + "/" + connectionId + "/" + requestIndex;
                sourceOrder.add(path);
                trafficStreams.add(
                    makeTrafficStream(
                        connectionId,
                        requestIndex,
                        requestIndex == requestsPerConnection[connectionIndex] - 1,
                        path
                    )
                );
            }
            var trafficSource = new ArrayCursorTrafficSourceContext(trafficStreams, 0);

            TrafficReplayerRunner.runReplayer(
                REQUEST_COUNT,
                (rootContext, targetConnectionPoolName) -> {
                    try {
                        return new FullTrafficReplayerTest.TrafficReplayerWithWaitOnClose(
                            Duration.ofMinutes(3),
                            rootContext,
                            httpServer.localhostEndpoint(),
                            new StaticAuthTransformerFactory("TEST"),
                            true,
                            SENDING_THREADS,
                            MAX_CONCURRENT_REQUESTS,
                            new TransformationLoader().getTransformerFactoryLoaderWithNewHostName("localhost"),
                            targetConnectionPoolName
                        );
                    } catch (SSLException e) {
                        throw new RuntimeException(e);
                    }
                },
                () -> tuple -> {},
                TestContext::noOtelTracking,
                trafficSource,
                new TimeShifter(10_000, Duration.ofMillis(100))
            );

            assertOrderMatches(sourceOrder, List.copyOf(targetOrder));
            Assertions.assertEquals(1, maxActiveTargetRequests.get(), "target request concurrency");
            Assertions.assertEquals(trafficStreams.size(), trafficSource.nextReadCursor.get(), "committed records");
        }
    }

    private static TrafficStream makeTrafficStream(
        String connectionId,
        int requestIndex,
        boolean isLastChunk,
        String path
    ) {
        var request = "GET "
            + path
            + " HTTP/1.1\r\n"
            + "Host: localhost\r\n"
            + "Connection: Keep-Alive\r\n"
            + "\r\n";
        var requestBytes = request.getBytes(StandardCharsets.UTF_8);
        var responseBytes = "HTTP/1.1 200 OK\r\nContent-Length: 0\r\n\r\n".getBytes(StandardCharsets.UTF_8);

        var stream = TrafficStream.newBuilder()
            .setNodeId(NODE_ID)
            .setConnectionId(connectionId)
            .setPriorRequestsReceived(requestIndex)
            .addSubStream(
                TrafficObservation.newBuilder()
                    .setTs(OBSERVATION_TIME)
                    .setRead(ReadObservation.newBuilder().setData(ByteString.copyFrom(requestBytes)))
            )
            .addSubStream(
                TrafficObservation.newBuilder()
                    .setTs(OBSERVATION_TIME)
                    .setEndOfMessageIndicator(
                        EndOfMessageIndication.newBuilder()
                            .setFirstLineByteLength(request.indexOf("\r\n"))
                            .setHeadersByteLength(requestBytes.length - 4)
                    )
            )
            .addSubStream(
                TrafficObservation.newBuilder()
                    .setTs(OBSERVATION_TIME)
                    .setWrite(WriteObservation.newBuilder().setData(ByteString.copyFrom(responseBytes)))
            );

        if (isLastChunk) {
            stream.setNumberOfThisLastChunk(requestIndex)
                .addSubStream(
                    TrafficObservation.newBuilder()
                        .setTs(OBSERVATION_TIME)
                        .setClose(CloseObservation.getDefaultInstance())
                );
        } else {
            stream.setNumber(requestIndex);
        }
        return stream.build();
    }

    private static void assertOrderMatches(List<String> sourceOrder, List<String> targetOrder) {
        Assertions.assertEquals(sourceOrder.size(), targetOrder.size(), "target request count");
        for (int i = 0; i < sourceOrder.size(); ++i) {
            if (!sourceOrder.get(i).equals(targetOrder.get(i))) {
                var windowStart = Math.max(0, i - 3);
                var windowEnd = Math.min(sourceOrder.size(), i + 4);
                Assertions.fail(
                    "First replay ordering mismatch at index "
                        + i
                        + "; source="
                        + sourceOrder.subList(windowStart, windowEnd)
                        + "; target="
                        + targetOrder.subList(windowStart, windowEnd)
                );
            }
        }
    }
}

*/
// REBUILD-LIMBO-END(G10)

@Tag("longTest")
class GlobalSerialReplayE2ETest extends FullTrafficReplayerTest {
    private static final int REQUEST_COUNT = 16;

    @Test
    void oneTargetPermitSerializesAttemptsWithoutInventingCrossConnectionOrder()
        throws Exception {
        var targetOrder = Collections.synchronizedList(new ArrayList<String>());
        var activeRequests = new AtomicInteger();
        var maximumActiveRequests = new AtomicInteger();
        var firstRequestObserved = new CompletableFuture<Void>();
        var releaseFirstRequest = new CompletableFuture<Void>();
        var concurrentRequestObserved = new CompletableFuture<Void>();
        try (var server = SimpleNettyHttpServer.makeServer(false, request -> {
            targetOrder.add(request.getPath().getPath());
            var active = activeRequests.incrementAndGet();
            maximumActiveRequests.accumulateAndGet(active, Math::max);
            try {
                if (firstRequestObserved.complete(null)) {
                    releaseFirstRequest.join();
                } else {
                    concurrentRequestObserved.complete(null);
                }
                return new SimpleHttpResponse(
                    Map.of(HttpHeaderNames.CONTENT_LENGTH.toString(), "2"),
                    "OK".getBytes(StandardCharsets.UTF_8),
                    "OK",
                    200
                );
            } finally {
                activeRequests.decrementAndGet();
            }
        })) {
            var sourceOrder = new ArrayList<String>();
            var records = new ArrayList<
                org.opensearch.migrations.trafficcapture.protos.CaptureRecord
            >();
            for (var index = 0; index < REQUEST_COUNT; index++) {
                var path = "/ordered/" + index;
                sourceOrder.add(path);
                records.add(requestResponseAndClose(
                    "writer",
                    "connection-" + index,
                    "GET " + path + " HTTP/1.1\r\nHost: source\r\n\r\n"
                ));
            }

            var replayFinished = CompletableFuture.supplyAsync(() -> {
                try {
                    return replay(
                        server.localhostEndpoint(),
                        records,
                        () -> new TransformationLoader()
                            .getTransformerFactoryLoaderWithNewHostName("localhost"),
                        null,
                        new RootReplayerContext(
                            io.opentelemetry.api.OpenTelemetry.noop()
                        )
                    );
                } catch (Exception failure) {
                    throw new CompletionException(failure);
                }
            });
            try {
                firstRequestObserved.get(5, TimeUnit.SECONDS);
                Assertions.assertThrows(
                    TimeoutException.class,
                    () -> concurrentRequestObserved.get(1, TimeUnit.SECONDS),
                    "a second target request must not start while the only permit is held"
                );
            } finally {
                releaseFirstRequest.complete(null);
            }
            var result = replayFinished.get(20, TimeUnit.SECONDS);

            Assertions.assertEquals(
                new java.util.HashSet<>(sourceOrder),
                new java.util.HashSet<>(targetOrder)
            );
            Assertions.assertEquals(REQUEST_COUNT, targetOrder.size());
            Assertions.assertEquals(1, maximumActiveRequests.get());
            Assertions.assertEquals(REQUEST_COUNT, result.tuples().size());
            Assertions.assertEquals(
                REQUEST_COUNT,
                result.committedOffsets().get(TOPIC_PARTITION).offset()
            );
            Assertions.assertTrue(result.fatalFailures().isEmpty());
        }
    }

    @Test
    void requestsOnOneConnectionRetainCapturedOrder() throws Exception {
        var targetOrder = Collections.synchronizedList(new ArrayList<String>());
        try (var server = SimpleNettyHttpServer.makeServer(false, request -> {
            targetOrder.add(request.getPath().getPath());
            return new SimpleHttpResponse(
                Map.of(HttpHeaderNames.CONTENT_LENGTH.toString(), "2"),
                "OK".getBytes(StandardCharsets.UTF_8),
                "OK",
                200
            );
        })) {
            var sourceOrder = new ArrayList<String>();
            var requests = new ArrayList<String>();
            for (var index = 0; index < REQUEST_COUNT; index++) {
                var path = "/same-connection/" + index;
                sourceOrder.add(path);
                requests.add("GET " + path + " HTTP/1.1\r\nHost: source\r\n\r\n");
            }

            var result = replay(
                server.localhostEndpoint(),
                List.of(requestsAndClose("writer", "connection", requests)),
                () -> new TransformationLoader()
                    .getTransformerFactoryLoaderWithNewHostName("localhost"),
                null,
                new RootReplayerContext(io.opentelemetry.api.OpenTelemetry.noop()),
                REQUEST_COUNT
            );

            Assertions.assertEquals(sourceOrder, targetOrder);
            Assertions.assertEquals(1, result.targetChannelsCreated());
            Assertions.assertEquals(REQUEST_COUNT, result.tuples().size());
            Assertions.assertEquals(
                1,
                result.committedOffsets().get(TOPIC_PARTITION).offset()
            );
            Assertions.assertTrue(result.fatalFailures().isEmpty());
        }
    }
}
