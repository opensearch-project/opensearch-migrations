package org.opensearch.migrations.replay;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.http.FullHttpResponse;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.opensearch.migrations.replay.datatypes.UniqueRequestKey;
import org.opensearch.migrations.replay.util.DiagnosticTrackableCompletableFuture;
import org.opensearch.migrations.testutils.HttpFirstLine;
import org.opensearch.migrations.testutils.SimpleHttpResponse;
import org.opensearch.migrations.testutils.SimpleHttpServer;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
class RequestSenderOrchestratorTest {

    static String getUriForIthRequest(int i) {
        return String.format("/%04d", i);
    }

    static String getRequestString(int i) {
        return String.format("GET %s HTTP/1.1\r\n" +
                "Connection: Keep-Alive\r\n" +
                "Host: localhost\r\n" +
                "User-Agent: UnitTest\r\n" +
                "Connection: Keep-Alive\r\n" +
                "\r\n",
                getUriForIthRequest(i));
    }

    public static final int NUM_REQUESTS_TO_SCHEDULE = 20;
    public static final int NUM_REPEATS = 2;

    private static String SERVER_RESPONSE_BODY_PREFIX = "Boring Response to ";
    private static Duration SERVER_RESPONSE_LATENCY = Duration.ofMillis(100);

    private static SimpleHttpResponse makeResponse(HttpFirstLine r) {
        try {
            Thread.sleep(SERVER_RESPONSE_LATENCY.toMillis());
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
        var headers = Map.of(
                "Content-Type", "text/plain",
                "Funtime", "checkIt!",
                "Content-Transfer-Encoding", "chunked");
        String body = SERVER_RESPONSE_BODY_PREFIX + r.path();
        var payloadBytes = body.getBytes(StandardCharsets.UTF_8);
        return new SimpleHttpResponse(headers, payloadBytes, "OK", 200);
    }

    @Test
    public void testThatSchedulingWorks() throws Exception {
        var httpServer = SimpleHttpServer.makeServer(false, RequestSenderOrchestratorTest::makeResponse);
        var testServerUri = httpServer.localhostEndpoint();
        var clientConnectionPool = new ClientConnectionPool(testServerUri, null, 1);
        var senderOrchestrator = new RequestSenderOrchestrator(clientConnectionPool);
        var baseTime = Instant.now();
        Instant lastEndTime = baseTime;
        var scheduledItems = new ArrayList<DiagnosticTrackableCompletableFuture<String,AggregatedRawResponse>>();
        for (int i = 0; i<NUM_REQUESTS_TO_SCHEDULE; ++i) {
            var rKey = new UniqueRequestKey("TEST", i);
            // half the time schedule at the same time as the last one, the other half, 10ms later than the previous
            var perPacketShift = Duration.ofMillis(10*i/NUM_REPEATS);
            var startTimeForThisRequest = baseTime.plus(perPacketShift);
            var requestPackets = makeRequest(i/NUM_REPEATS);
            var arr = senderOrchestrator.scheduleRequest(rKey, startTimeForThisRequest, Duration.ofMillis(1),
                    requestPackets.stream());
            log.info("Scheduled item to run at " + startTimeForThisRequest);
            scheduledItems.add(arr);
            lastEndTime = startTimeForThisRequest.plus(perPacketShift.multipliedBy(requestPackets.size()));
        }
        var closeFuture = senderOrchestrator.scheduleClose(
                new UniqueRequestKey("TEST", NUM_REQUESTS_TO_SCHEDULE),
                lastEndTime.plus(Duration.ofMillis(100)));

        Assertions.assertEquals(NUM_REQUESTS_TO_SCHEDULE, scheduledItems.size());
        for (int i=0; i<scheduledItems.size(); ++i) {
            var cf = scheduledItems.get(i);
            var arr = cf.get();
            Assertions.assertEquals(null, arr.error);
            Assertions.assertTrue(arr.responseSizeInBytes > 0);
            var httpMessage = Utils.parseHttpMessage(Utils.HttpMessageType.Response,
                    arr.responsePackets.stream().map(kvp->Unpooled.wrappedBuffer(kvp.getValue())));
            var response =  (FullHttpResponse) httpMessage;
            Assertions.assertEquals(200, response.status().code());
            var body = response.content();
            Assertions.assertEquals(SERVER_RESPONSE_BODY_PREFIX + getUriForIthRequest(i/NUM_REPEATS),
                    new String(body.duplicate().toString(StandardCharsets.UTF_8)));
        }
        closeFuture.get();
    }

    private List<ByteBuf> makeRequest(int i) {
        // uncomment/swap for a simpler test case to run
        return //List.of(Unpooled.wrappedBuffer(getRequestString(i).getBytes()));
               getRequestString(i).chars().mapToObj(c->Unpooled.wrappedBuffer(new byte[]{(byte) c}))
                       .collect(Collectors.toList());
    }
}