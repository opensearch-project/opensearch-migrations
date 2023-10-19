package org.opensearch.migrations.replay;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufHolder;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.http.FullHttpResponse;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.opensearch.migrations.replay.datatypes.UniqueRequestKey;
import org.opensearch.migrations.replay.util.DiagnosticTrackableCompletableFuture;
import org.opensearch.migrations.testutils.HttpFirstLine;
import org.opensearch.migrations.testutils.SimpleHttpResponse;
import org.opensearch.migrations.testutils.SimpleHttpServer;
import org.opensearch.migrations.testutils.WrapWithNettyLeakDetection;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@Slf4j
@WrapWithNettyLeakDetection(repetitions = 1)
class RequestSenderOrchestratorTest {

    public static final int NUM_REQUESTS_TO_SCHEDULE = 20;
    public static final int NUM_REPEATS = 2;

    @Test
    @Tag("longTest")
    public void testThatSchedulingWorks() throws Exception {
        var httpServer = SimpleHttpServer.makeServer(false,
                r -> TestHttpServerContext.makeResponse(r, Duration.ofMillis(100)));
        var testServerUri = httpServer.localhostEndpoint();
        var clientConnectionPool = new ClientConnectionPool(testServerUri, null, 1);
        var senderOrchestrator = new RequestSenderOrchestrator(clientConnectionPool);
        var baseTime = Instant.now();
        Instant lastEndTime = baseTime;
        var scheduledItems = new ArrayList<DiagnosticTrackableCompletableFuture<String,AggregatedRawResponse>>();
        for (int i = 0; i<NUM_REQUESTS_TO_SCHEDULE; ++i) {
            var rKey = new UniqueRequestKey(TestTrafficStreamKey.instance, i);
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
                new UniqueRequestKey(TestTrafficStreamKey.instance, NUM_REQUESTS_TO_SCHEDULE),
                lastEndTime.plus(Duration.ofMillis(100)));

        Assertions.assertEquals(NUM_REQUESTS_TO_SCHEDULE, scheduledItems.size());
        for (int i=0; i<scheduledItems.size(); ++i) {
            var cf = scheduledItems.get(i);
            var arr = cf.get();
            Assertions.assertEquals(null, arr.error);
            Assertions.assertTrue(arr.responseSizeInBytes > 0);
            var httpMessage = PrettyPrinter.parseHttpMessageFromBufs(PrettyPrinter.HttpMessageType.Response,
                    arr.responsePackets.stream().map(kvp->Unpooled.wrappedBuffer(kvp.getValue())));
            try {
                var response = (FullHttpResponse) httpMessage;
                Assertions.assertEquals(200, response.status().code());
                var body = response.content();
                Assertions.assertEquals(TestHttpServerContext.SERVER_RESPONSE_BODY_PREFIX +
                                TestHttpServerContext.getUriForIthRequest(i / NUM_REPEATS),
                        new String(body.duplicate().toString(StandardCharsets.UTF_8)));
            } finally {
                Optional.ofNullable((httpMessage instanceof ByteBufHolder)?(ByteBufHolder)httpMessage:null)
                        .ifPresent(bbh-> bbh.content().release());
            }
        }
        closeFuture.get();
    }

    private List<ByteBuf> makeRequest(int i) {
        // uncomment/swap for a simpler test case to run
        return //List.of(Unpooled.wrappedBuffer(getRequestString(i).getBytes()));
               TestHttpServerContext.getRequestString(i).chars().mapToObj(c->Unpooled.wrappedBuffer(new byte[]{(byte) c}))
                       .collect(Collectors.toList());
    }
}