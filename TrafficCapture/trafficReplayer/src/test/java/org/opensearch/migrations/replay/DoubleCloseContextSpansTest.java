package org.opensearch.migrations.replay;

// REBUILD-LIMBO(G10) -- nothing in this file is live yet. Javadoc is left outside the marked
// regions so it needs no escaping and keeps its blame; it documents code that is not compiled.
// Resolve each region to dead, keep, or refactor deliberately. If a member is deleted, delete its
// javadoc with it. See AGENTS.md section 8a.
// Test carried byte-identical. Unresolved: ConnectionReplaySession InstrumentationTest NettyPacketToHttpConsumer TestContext . Per AGENTS.md section 4 an inherited test may stay broken while the architectures are partly connected; this one is restored by the milestone that rebuilds its subject, keeping its assertions conceptually stable while changing the mechanics.
// Un-mark a member by deleting the delimiter lines around it and splitting this region; the
// code between them is verbatim, so blame survives. Read this before writing anything new

// REBUILD-LIMBO-START(G10)
/*

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.TreeMap;

import org.opensearch.migrations.replay.datahandlers.NettyPacketToHttpConsumer;
import org.opensearch.migrations.replay.datatypes.ConnectionReplaySession;
import org.opensearch.migrations.testutils.SimpleHttpResponse;
import org.opensearch.migrations.testutils.SimpleNettyHttpServer;
import org.opensearch.migrations.tracing.InMemoryInstrumentationBundle;
import org.opensearch.migrations.tracing.InstrumentationTest;
import org.opensearch.migrations.tracing.TestContext;

import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.util.concurrent.DefaultThreadFactory;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

*/
// REBUILD-LIMBO-END(G10)
/**
 * Verifies that NettyPacketToHttpConsumer.finalizeRequest() closes context spans exactly once.
 *
 * deactivateChannel() closes getCurrentRequestSpan() and getParentContext() in its finally block.
 * Then the outer finally block in finalizeRequest() closes them again.
 * This causes sendMeterEventsForEnd() to be called twice, doubling the metric counts.
 *
 * This test verifies that spans are closed exactly once, so metric count = 1 for 1 request.
 */
// REBUILD-LIMBO-START(G10)
/*
@Slf4j
public class DoubleCloseContextSpansTest extends InstrumentationTest {

    private static final String REQUEST = "GET / HTTP/1.1\r\n"
        + "Host: localhost\r\n"
        + "Connection: close\r\n"
        + "\r\n";

    @Override
    protected TestContext makeInstrumentationContext() {
        return TestContext.withTracking(false, true);
    }

    @Test
    void finalizeRequest_closesSpansExactlyOnce() throws Exception {
        try (var testServer = SimpleNettyHttpServer.makeServer(false, r -> {
            var headers = new TreeMap<>(Map.of(
                "Content-Type", "text/plain",
                HttpHeaderNames.TRANSFER_ENCODING.toString(), HttpHeaderValues.CHUNKED.toString()
            ));
            return new SimpleHttpResponse(headers, "OK".getBytes(StandardCharsets.UTF_8), "OK", 200);
        })) {
            var httpContext = rootContext.getTestConnectionRequestContext(0);
            var channelContext = httpContext.getChannelKeyContext();
            var eventLoop = new NioEventLoopGroup(1, new DefaultThreadFactory("test")).next();
            var replaySession = new ConnectionReplaySession(
                eventLoop, channelContext,
                NettyPacketToHttpConsumer.createClientConnectionFactory(null, testServer.localhostEndpoint())
            );
            var nphc = new NettyPacketToHttpConsumer(
                replaySession,
                httpContext,
                Duration.ofSeconds(10),
                () -> {}
            );
            nphc.consumeBytes(REQUEST.getBytes(StandardCharsets.UTF_8));
            var response = nphc.finalizeRequest().get(Duration.ofSeconds(10));
            Assertions.assertNotNull(response, "Should get a response");
            Assertions.assertNull(response.getError(), "Response should not have an error");

            // Check the metric for targetTransaction — it should be 1 for 1 request,
            // but due to double-close it will be 2
            var metrics = rootContext.inMemoryInstrumentationBundle.getFinishedMetrics();
            long targetTxnCount = InMemoryInstrumentationBundle.getMetricValueOrZero(
                metrics, "targetTransactionCount");

            // targetTransaction metric count is 1 — spans closed exactly once
            Assertions.assertEquals(1, targetTxnCount,
                "targetTransactionCount should be 1 for 1 request (no double-close)");

            eventLoop.shutdownGracefully().sync();
        }
    }
}

*/
// REBUILD-LIMBO-END(G10)