package org.opensearch.migrations.replay.e2etests;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import org.opensearch.migrations.replay.tracing.IReplayContexts;
import org.opensearch.migrations.replay.tracing.RootReplayerContext;
import org.opensearch.migrations.testutils.SimpleHttpResponse;
import org.opensearch.migrations.testutils.SimpleNettyHttpServer;
import org.opensearch.migrations.tracing.InMemoryInstrumentationBundle;
import org.opensearch.migrations.transform.TransformationLoader;

import io.netty.handler.codec.http.HttpHeaderNames;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

// REBUILD-LIMBO(G10) -- inherited bodies remain marked and recoverable; the live G9
// replacement follows the marked regions. Javadoc stays outside the regions.
// Resolve each region to dead, keep, or refactor deliberately. If a member is deleted, delete its
// javadoc with it. See AGENTS.md section 8a.
// Test carried byte-identical. Unresolved: FullTrafficReplayerTest RootReplayerConstructorExtensions TestContext . Per AGENTS.md section 4 an inherited test may stay broken while the architectures are partly connected; this one is restored by the milestone that rebuilds its subject, keeping its assertions conceptually stable while changing the mechanics.
// Un-mark a member by deleting the delimiter lines around it and splitting this region; the
// code between them is verbatim, so blame survives. Read this before writing anything new

// REBUILD-LIMBO-START(G10)
/*

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.stream.Collectors;

import org.opensearch.migrations.replay.RootReplayerConstructorExtensions;
import org.opensearch.migrations.replay.TestHttpServerContext;
import org.opensearch.migrations.replay.TimeShifter;
import org.opensearch.migrations.replay.sink.CallbackTupleSink;
import org.opensearch.migrations.replay.sink.ThreadLocalTupleWriter;
import org.opensearch.migrations.replay.traffic.source.ArrayCursorTrafficCaptureSource;
import org.opensearch.migrations.replay.traffic.source.ArrayCursorTrafficSourceContext;
import org.opensearch.migrations.replay.traffic.source.BlockingTrafficSource;
import org.opensearch.migrations.testutils.SimpleNettyHttpServer;
import org.opensearch.migrations.testutils.WrapWithNettyLeakDetection;
import org.opensearch.migrations.tracing.InMemoryInstrumentationBundle;
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
import io.opentelemetry.sdk.trace.data.SpanData;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@Slf4j
@WrapWithNettyLeakDetection(disableLeakChecks = true)
public class FullReplayerWithTracingChecksTest extends FullTrafficReplayerTest {

    @Override
    protected TestContext makeInstrumentationContext() {
        return TestContext.withAllTracking();
    }

    @ParameterizedTest
    @ValueSource(ints = { 1, 2 })
    @ResourceLock("TrafficReplayerRunner")
    // run in isolation to reduce the chance that there's a broken connection, upsetting the tcpConnection count check
    @Tag("isolatedTest")
    public void testStreamWithRequestsWithCloseIsCommittedOnce(int numRequests) throws Throwable {
        var random = new Random(1);
        try (
            var httpServer = SimpleNettyHttpServer.makeServer(
                false,
                Duration.ofMinutes(10),
                response -> TestHttpServerContext.makeResponse(random, response)
            )
        ) {
            var baseTime = Instant.now();
            var fixedTimestamp = Timestamp.newBuilder()
                .setSeconds(baseTime.getEpochSecond())
                .setNanos(baseTime.getNano())
                .build();
            var tsb = TrafficStream.newBuilder().setConnectionId("C");
            for (int i = 0; i < numRequests; ++i) {
                tsb = tsb.addSubStream(
                    TrafficObservation.newBuilder()
                        .setTs(fixedTimestamp)
                        .setRead(
                            ReadObservation.newBuilder()
                                .setData(
                                    ByteString.copyFrom(
                                        ("GET /"
                                            + i
                                            + " HTTP/1.1\r\n"
                                            + "Connection: Keep-Alive\r\n"
                                            + "Host: localhost\r\n\r\n").getBytes(StandardCharsets.UTF_8)
                                    )
                                )
                                .build()
                        )
                        .build()
                )
                    .addSubStream(
                        TrafficObservation.newBuilder()
                            .setTs(fixedTimestamp)
                            .setEndOfMessageIndicator(
                                EndOfMessageIndication.newBuilder()
                                    .setFirstLineByteLength(14)
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
                                    .setData(
                                        ByteString.copyFrom("HTTP/1.1 OK 200\r\n".getBytes(StandardCharsets.UTF_8))
                                    )
                                    .build()
                            )
                            .build()
                    );
            }
            var trafficStream = tsb.addSubStream(
                TrafficObservation.newBuilder().setTs(fixedTimestamp).setClose(CloseObservation.getDefaultInstance())
            ).build();
            var trafficSource = new ArrayCursorTrafficCaptureSource(
                rootContext,
                new ArrayCursorTrafficSourceContext(List.of(trafficStream), 0)
            );

            var tuplesReceived = new HashSet<String>();
            var serverUri = httpServer.localhostEndpoint();
            try (
                var tr = new RootReplayerConstructorExtensions(
                    rootContext,
                    serverUri,
                    new StaticAuthTransformerFactory("TEST"),
                    new TransformationLoader().getTransformerFactoryLoaderWithNewHostName(serverUri.getHost()),
                    RootReplayerConstructorExtensions.makeNettyPacketConsumerConnectionPool(serverUri, 10),
                    10 * 1024,
                    code -> {}
                );
                var blockingTrafficSource = new BlockingTrafficSource(trafficSource, Duration.ofMinutes(2));
                var tupleWriter = new ThreadLocalTupleWriter(i -> new CallbackTupleSink(m -> {}))
            ) {
                tr.setupRunAndWaitForReplayToFinish(
                    Duration.ofSeconds(70),
                    Duration.ofSeconds(30),
                    blockingTrafficSource,
                    new TimeShifter(10 * 1000),
                    tupleWriter,
                    t -> {
                        var wasNew = tuplesReceived.add(t.getRequestKey().toString());
                        Assertions.assertTrue(wasNew);
                    },
                    Duration.ofSeconds(5)
                );
                Assertions.assertEquals(numRequests, tuplesReceived.size());
                tr.shutdown(null).get();
                checkSpansForSimpleReplayedTransactions(rootContext.inMemoryInstrumentationBundle, numRequests);
            }
        }
    }

    private static class TraceProcessor {
        Map<String, List<SpanData>> byName;

        public TraceProcessor(List<SpanData> finishedSpans) {
            byName = finishedSpans.stream().collect(Collectors.groupingBy(SpanData::getName));
        }

        private int getCountAndRemoveSpan(String k) {
            var rval = Optional.ofNullable(byName.get(k)).map(List::size).orElse(-1);
            byName.remove(k);
            return rval;
        }

        public String getRemainingItemsString() {
            return byName.entrySet()
                .stream()
                .map(kvp -> kvp.getKey() + ":" + kvp.getValue())
                .collect(Collectors.joining());
        }
    }

*/
// REBUILD-LIMBO-END(G10)

@Tag("longTest")
public class FullReplayerWithTracingChecksTest extends FullTrafficReplayerTest {

    @Test
    void deployedChainEmitsPreservedSemanticMetricEvents() throws Exception {
        try (
            var telemetry = new InMemoryInstrumentationBundle(true, true);
            var server = SimpleNettyHttpServer.makeServer(false, request ->
                new SimpleHttpResponse(
                    Map.of(HttpHeaderNames.CONTENT_LENGTH.toString(), "2"),
                    "OK".getBytes(StandardCharsets.UTF_8),
                    "OK",
                    200
                )
            )
        ) {
            var root = new RootReplayerContext(telemetry.openTelemetrySdk);
            replayOne(
                server.localhostEndpoint(),
                requestResponseAndClose("GET /metrics HTTP/1.1\r\nHost: source\r\n\r\n"),
                () -> new TransformationLoader()
                    .getTransformerFactoryLoaderWithNewHostName("localhost"),
                null,
                root
            );

            var metrics = telemetry.getFinishedMetrics();
            assertPositive(metrics, IReplayContexts.MetricNames.KAFKA_RECORD_READ);
            assertPositive(metrics, IReplayContexts.MetricNames.TRAFFIC_STREAMS_READ);
            assertPositive(metrics, IReplayContexts.MetricNames.TRANSFORM_SUCCESS);
            assertPositive(metrics, IReplayContexts.MetricNames.TRANSFORM_BYTES_OUT);
            assertPositive(metrics, IReplayContexts.MetricNames.CONNECTIONS_OPENED);
            assertPositive(metrics, IReplayContexts.MetricNames.CONNECTIONS_CLOSED);
            assertPositive(metrics, IReplayContexts.MetricNames.BYTES_WRITTEN_TO_TARGET);
            assertPositive(metrics, IReplayContexts.MetricNames.BYTES_READ_FROM_TARGET);
            assertPositive(metrics, IReplayContexts.MetricNames.TUPLE_COMPARISON);
        }
    }

    private static void assertPositive(
        java.util.Collection<io.opentelemetry.sdk.metrics.data.MetricData> metrics,
        String name
    ) {
        var value = metrics.stream()
            .filter(metric -> metric.getName().startsWith(name))
            .flatMap(metric -> metric.getLongSumData().getPoints().stream())
            .mapToLong(io.opentelemetry.sdk.metrics.data.LongPointData::getValue)
            .sum();
        Assertions.assertTrue(
            value > 0,
            () -> "expected semantic metric event for " + name + "; metrics="
                + metrics.stream().map(metric -> metric.getName()).toList()
        );
    }
}
    /**
     * This function is written like this rather than with a loop so that the backtrace will show WHICH
     * key was corrupted.
     */
// REBUILD-LIMBO-START(G10)
/*
    private void checkSpansForSimpleReplayedTransactions(
        InMemoryInstrumentationBundle inMemoryBundle,
        int numRequests
    ) {
        var traceProcessor = new TraceProcessor(inMemoryBundle.getFinishedSpans());

        Assertions.assertEquals(1, traceProcessor.getCountAndRemoveSpan("channel"));
        Assertions.assertEquals(1, traceProcessor.getCountAndRemoveSpan("trafficStreamLifetime"));
        Assertions.assertEquals(1, traceProcessor.getCountAndRemoveSpan("tcpConnection"));
        Assertions.assertEquals(numRequests, traceProcessor.getCountAndRemoveSpan("httpTransaction"));
        Assertions.assertEquals(numRequests, traceProcessor.getCountAndRemoveSpan("accumulatingRequest"));
        Assertions.assertEquals(numRequests, traceProcessor.getCountAndRemoveSpan("accumulatingResponse"));
        // 1 transformation span per request from HttpJsonTransformingConsumer, plus 4 per request
        // from ParsedHttpMessagesAsDicts parsing (source req, source resp, target req, target resp)
        Assertions.assertEquals(numRequests * 5, traceProcessor.getCountAndRemoveSpan("transformation"));
        Assertions.assertEquals(numRequests, traceProcessor.getCountAndRemoveSpan("targetTransaction"));
        Assertions.assertEquals(numRequests * 2, traceProcessor.getCountAndRemoveSpan("scheduled"));
        Assertions.assertEquals(numRequests, traceProcessor.getCountAndRemoveSpan("requestSending"));
        Assertions.assertEquals(1, traceProcessor.getCountAndRemoveSpan("requestConnecting"));
        Assertions.assertEquals(numRequests, traceProcessor.getCountAndRemoveSpan("comparingResults"));

        Assertions.assertTrue(traceProcessor.getCountAndRemoveSpan("waitingForResponse") > 0);
        Assertions.assertTrue(traceProcessor.getCountAndRemoveSpan("readNextTrafficStreamChunk") > 0);
        // ideally, we'd be getting these back too, but our requests are malformed, so the server closes, which
        // may occur before we've started to accumulate the response. So - just ignore these, but make sure that
        // there isn't anything else that we've missed.
        traceProcessor.getCountAndRemoveSpan("receivingResponse");

        Assertions.assertEquals("", traceProcessor.getRemainingItemsString());
    }
}

*/
// REBUILD-LIMBO-END(G10)
