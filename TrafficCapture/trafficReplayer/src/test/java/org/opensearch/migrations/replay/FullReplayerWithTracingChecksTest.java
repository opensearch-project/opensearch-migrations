package org.opensearch.migrations.replay;

import com.google.protobuf.ByteString;
import com.google.protobuf.Timestamp;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.data.SpanData;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.opensearch.migrations.replay.traffic.source.BlockingTrafficSource;
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

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.stream.Collectors;

@Slf4j
@WrapWithNettyLeakDetection(disableLeakChecks = true)
public class FullReplayerWithTracingChecksTest extends FullTrafficReplayerTest {

    protected TestContext makeInstrumentationContext() { return TestContext.withAllTracking(); }

    @Test
    public void testSingleStreamWithCloseIsCommitted() throws Throwable {
        var random = new Random(1);
        var httpServer = SimpleNettyHttpServer.makeServer(false, Duration.ofMillis(2),
                response -> TestHttpServerContext.makeResponse(random, response));
        var trafficStreamWithJustClose = TrafficStream.newBuilder()
                .setNodeId(TEST_NODE_ID)
                .setConnectionId(TEST_CONNECTION_ID)
                .addSubStream(TrafficObservation.newBuilder()
                        .setClose(CloseObservation.newBuilder().build()).build())
                .build();
        var trafficSourceSupplier = new FullTrafficReplayerTest.ArrayCursorTrafficSourceFactory(rootContext,
                List.of(trafficStreamWithJustClose));
        TrafficReplayerRunner.runReplayerUntilSourceWasExhausted(rootContext, 0,
                httpServer.localhostEndpoint(), new FullTrafficReplayerTest.IndexWatchingListenerFactory(), trafficSourceSupplier);
        Assertions.assertEquals(1, trafficSourceSupplier.nextReadCursor.get());
        log.info("done");
    }

    @ParameterizedTest
    @ValueSource(ints = {1,2})
    public void testStreamWithRequestsWithCloseIsCommittedOnce(int numRequests) throws Throwable {
        var random = new Random(1);
        var httpServer = SimpleNettyHttpServer.makeServer(false, Duration.ofMillis(2),
                response->TestHttpServerContext.makeResponse(random, response));
        var baseTime = Instant.now();
        var fixedTimestamp =
                Timestamp.newBuilder().setSeconds(baseTime.getEpochSecond()).setNanos(baseTime.getNano()).build();
        var tsb = TrafficStream.newBuilder().setConnectionId("C");
        for (int i=0; i<numRequests; ++i) {
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
                new ArrayCursorTrafficCaptureSource(rootContext,
                        new ArrayCursorTrafficSourceFactory(rootContext, List.of(trafficStream)));
        var tr = new TrafficReplayer(rootContext, httpServer.localhostEndpoint(), null,
                new StaticAuthTransformerFactory("TEST"), null,
                true, 10, 10 * 1024);

        var tuplesReceived = new HashSet<String>();
        try (var blockingTrafficSource = new BlockingTrafficSource(trafficSource, Duration.ofMinutes(2))) {
            tr.setupRunAndWaitForReplayWithShutdownChecks(Duration.ofSeconds(70), blockingTrafficSource,
                    new TimeShifter(10 * 1000), (t) -> {
                        var wasNew = tuplesReceived.add(t.getRequestKey().toString());
                        Assertions.assertTrue(wasNew);
                    });
        } finally {
            tr.shutdown(null);
        }

        Assertions.assertEquals(numRequests, tuplesReceived.size());
        checkSpansForSimpleReplayedTransactions(rootContext.inMemoryInstrumentationBundle.testSpanExporter,
                numRequests);
        log.info("done");
    }

    /**
     * This function is written like this rather than with a loop so that the backtrace will show WHICH
     * key was corrupted.
     */
    private void checkSpansForSimpleReplayedTransactions(InMemorySpanExporter testSpanExporter, int numRequests) {
        var byName = testSpanExporter.getFinishedSpanItems().stream().collect(Collectors.groupingBy(SpanData::getName));
        BiConsumer<Integer, String> chk = (i, k) -> {
            Assertions.assertNotNull(byName.get(k));
            Assertions.assertEquals(i, byName.get(k).size());
            byName.remove(k);
        };
        chk.accept(1,"channel");
        chk.accept(1,"tcpConnection");
        chk.accept(1, "trafficStreamLifetime");
        chk.accept(numRequests, "httpTransaction");
        chk.accept(numRequests, "accumulatingRequest");
        chk.accept(numRequests, "accumulatingResponse");
        chk.accept(numRequests, "transformation");
        chk.accept(numRequests, "targetTransaction");
        chk.accept(numRequests*2, "scheduled");
        chk.accept(numRequests, "requestSending");
        chk.accept(numRequests, "comparingResults");

        Consumer<String> chkNonZero = k-> {
            Assertions.assertNotNull(byName.get(k));
            Assertions.assertFalse(byName.get(k).isEmpty());
            byName.remove(k);
        };
        chkNonZero.accept("waitingForResponse");
        chkNonZero.accept("readNextTrafficStreamChunk");
        // ideally, we'd be getting these back too, but our requests are malformed, so the server closes, which
        // may occur before we've started to accumulate the response.  So - just ignore these, but make sure that
        // there isn't anything else that we've missed.
        byName.remove("receivingResponse");

        Assertions.assertEquals("", byName.entrySet().stream()
                .map(kvp->kvp.getKey()+":"+kvp.getValue()).collect(Collectors.joining()));
    }
}
