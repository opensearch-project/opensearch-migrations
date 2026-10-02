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
