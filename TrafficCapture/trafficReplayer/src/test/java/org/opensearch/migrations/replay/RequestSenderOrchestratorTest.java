package org.opensearch.migrations.replay;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import lombok.extern.slf4j.Slf4j;
import org.apache.hc.client5.http.async.methods.SimpleHttpRequest;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.opensearch.migrations.replay.datatypes.UniqueRequestKey;
import org.opensearch.migrations.replay.util.DiagnosticTrackableCompletableFuture;
import org.opensearch.migrations.testutils.PortFinder;
import org.opensearch.migrations.testutils.SimpleHttpResponse;
import org.opensearch.migrations.testutils.SimpleHttpServer;

import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

@Slf4j
class RequestSenderOrchestratorTest {

    final static String EXPECTED_REQUEST_STRING =
            "GET / HTTP/1.1\r\n" +
                    "Connection: Keep-Alive\r\n" +
                    "Host: localhost\r\n" +
                    "User-Agent: UnitTest\r\n" +
                    "Connection: Keep-Alive\r\n" +
                    "\r\n";
    public static final int NUM_REQUESTS_TO_SCHEDULE = 1;

    private static String SERVER_RESPONSE_BODY = "Boring Response.";
    private static Duration SERVER_RESPONSE_LATENCY = Duration.ofMillis(100);

    private static SimpleHttpResponse makeResponse() {
        try {
            Thread.sleep(SERVER_RESPONSE_LATENCY.toMillis());
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
        var headers = Map.of(
                "Content-Type", "text/plain",
                "Funtime", "checkIt!",
                "Content-Transfer-Encoding", "chunked");
        var payloadBytes = SERVER_RESPONSE_BODY.getBytes(StandardCharsets.UTF_8);
        return new SimpleHttpResponse(headers, payloadBytes, "OK", 200);
    }

    @Test
    public void testThatSchedulingWorks() throws Exception {
        var httpServer = SimpleHttpServer.makeServer(false, r->makeResponse());
        var testServerUri = httpServer.localhostEndpoint();
        var clientConnectionPool = new ClientConnectionPool(testServerUri, null, 1);
        var senderOrchestrator = new RequestSenderOrchestrator(clientConnectionPool, 1);
        var baseTime = Instant.now();
        var scheduledItems = new ArrayList<DiagnosticTrackableCompletableFuture<String,AggregatedRawResponse>>();
        for (int i = 0; i< NUM_REQUESTS_TO_SCHEDULE; ++i) {
            var rKey = new UniqueRequestKey("TEST", i);
            var endTime = baseTime.plus(Duration.ofMillis(i));
            var arr = senderOrchestrator.scheduleRequest(rKey, baseTime, endTime, makeRequest(i));
            scheduledItems.add(arr);
        }
        Assertions.assertEquals(NUM_REQUESTS_TO_SCHEDULE, scheduledItems.size());
        for (int i=0; i<scheduledItems.size(); ++i) {
            var cf = scheduledItems.get(i);
            var arr = cf.get();
            log.atError().setCause(arr.error).log("error");
            Assertions.assertEquals(null, arr.error);
            Assertions.assertTrue(arr.responseSizeInBytes > 0);
            var httpMessage = (HttpResponse) Utils.parseHttpMessage(arr.responsePackets.stream()
                    .map(kvp->Unpooled.wrappedBuffer(kvp.getValue())));
            Assertions.assertEquals(200, httpMessage.statusCode());
            var body = httpMessage.body();
            Assertions.assertNotNull(body);
        }
    }

    private Stream<ByteBuf> makeRequest(int i) {
        return Stream.of(Unpooled.wrappedBuffer(EXPECTED_REQUEST_STRING.getBytes(StandardCharsets.UTF_8)));
    }
}