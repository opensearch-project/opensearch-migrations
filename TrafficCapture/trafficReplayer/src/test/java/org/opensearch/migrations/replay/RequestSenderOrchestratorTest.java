package org.opensearch.migrations.replay;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.http.FullHttpMessage;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpResponse;
import lombok.extern.slf4j.Slf4j;
import org.apache.hc.client5.http.async.methods.SimpleHttpRequest;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.opensearch.migrations.replay.datatypes.UniqueRequestKey;
import org.opensearch.migrations.replay.util.DiagnosticTrackableCompletableFuture;
import org.opensearch.migrations.testutils.PortFinder;
import org.opensearch.migrations.testutils.SimpleHttpResponse;
import org.opensearch.migrations.testutils.SimpleHttpServer;

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
    public static final int NUM_REQUESTS_TO_SCHEDULE = 2;

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
        var senderOrchestrator = new RequestSenderOrchestrator(clientConnectionPool);
        var baseTime = Instant.now();
        var scheduledItems = new ArrayList<DiagnosticTrackableCompletableFuture<String,AggregatedRawResponse>>();
        for (int i = 0; i< NUM_REQUESTS_TO_SCHEDULE; ++i) {
            var rKey = new UniqueRequestKey("TEST", i);
            var arr = senderOrchestrator.scheduleRequest(rKey, baseTime, Duration.ofMillis(1), makeRequest(i));
            scheduledItems.add(arr);
        }
        Assertions.assertEquals(NUM_REQUESTS_TO_SCHEDULE, scheduledItems.size());
        for (int i=0; i<scheduledItems.size(); ++i) {
            var cf = scheduledItems.get(i);
            var arr = cf.get();
            log.atError().setCause(arr.error).log("error");
            Assertions.assertEquals(null, arr.error);
            Assertions.assertTrue(arr.responseSizeInBytes > 0);
            var httpMessage = (FullHttpResponse) Utils.parseHttpMessage(Utils.HttpMessageType.Response,
                    arr.responsePackets.stream().map(kvp->Unpooled.wrappedBuffer(kvp.getValue())));
            Assertions.assertEquals(200, httpMessage.status().code());
            var body = httpMessage.content();
            Assertions.assertEquals(SERVER_RESPONSE_BODY, new String(body.duplicate().toString(StandardCharsets.UTF_8)));
        }
    }

    private Stream<ByteBuf> makeRequest(int i) {
        return Stream.of(Unpooled.wrappedBuffer(EXPECTED_REQUEST_STRING.getBytes(StandardCharsets.UTF_8)));
    }
}