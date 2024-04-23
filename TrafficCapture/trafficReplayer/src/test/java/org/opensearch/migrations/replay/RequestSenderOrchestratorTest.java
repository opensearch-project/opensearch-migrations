package org.opensearch.migrations.replay;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.http.FullHttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.AbstractMap.SimpleEntry;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.opensearch.migrations.replay.util.DiagnosticTrackableCompletableFuture;
import org.opensearch.migrations.replay.util.NettyUtils;
import org.opensearch.migrations.replay.util.RefSafeHolder;
import org.opensearch.migrations.testutils.SimpleHttpServer;
import org.opensearch.migrations.testutils.WrapWithNettyLeakDetection;
import org.opensearch.migrations.tracing.InstrumentationTest;

@Slf4j
@WrapWithNettyLeakDetection(repetitions = 1)
class RequestSenderOrchestratorTest extends InstrumentationTest {

    public static final int NUM_REQUESTS_TO_SCHEDULE = 20;
    public static final int NUM_REPEATS = 2;

    @Test
    @Tag("longTest")
    @Execution(ExecutionMode.SAME_THREAD)
    public void testThatSchedulingWorks() throws Exception {
        try (var httpServer = SimpleHttpServer.makeServer(false,
                r -> TestHttpServerContext.makeResponse(r, Duration.ofMillis(100)))) {
            var testServerUri = httpServer.localhostEndpoint();
            var clientConnectionPool = TrafficReplayerTopLevel.makeClientConnectionPool(testServerUri, false,
                    1, "targetConnectionPool for testThatSchedulingWorks",
                    Duration.ofSeconds(30));
            var senderOrchestrator = new RequestSenderOrchestrator(clientConnectionPool);
            var baseTime = Instant.now();
            Instant lastEndTime = baseTime;
            var scheduledItems = new ArrayList<DiagnosticTrackableCompletableFuture<String, AggregatedRawResponse>>();
            for (int i = 0; i < NUM_REQUESTS_TO_SCHEDULE; ++i) {
                var requestContext = rootContext.getTestConnectionRequestContext(i);
                // half the time schedule at the same time as the last one, the other half, 10ms later than the previous
                var perPacketShift = Duration.ofMillis(10 * i / NUM_REPEATS);
                var startTimeForThisRequest = baseTime.plus(perPacketShift);
                var requestPackets = makeRequest(i / NUM_REPEATS);
                var arr = senderOrchestrator.scheduleRequest(requestContext.getReplayerRequestKey(), requestContext,
                        startTimeForThisRequest, Duration.ofMillis(1), requestPackets.stream());
                log.info("Scheduled item to run at " + startTimeForThisRequest);
                scheduledItems.add(arr);
                lastEndTime = startTimeForThisRequest.plus(perPacketShift.multipliedBy(requestPackets.size()));
            }
            var connectionCtx = rootContext.getTestConnectionRequestContext(NUM_REQUESTS_TO_SCHEDULE);
            var closeFuture = senderOrchestrator.scheduleClose(
                    connectionCtx.getLogicalEnclosingScope(), NUM_REQUESTS_TO_SCHEDULE, 0,
                    lastEndTime.plus(Duration.ofMillis(100)));

            Assertions.assertEquals(NUM_REQUESTS_TO_SCHEDULE, scheduledItems.size());
            for (int i = 0; i < scheduledItems.size(); ++i) {
                var cf = scheduledItems.get(i);
                var arr = cf.get();
                Assertions.assertNull(arr.error);
                Assertions.assertTrue(arr.responseSizeInBytes > 0);
                var packetBytesArr = arr.responsePackets.stream().map(SimpleEntry::getValue).collect(Collectors.toList());
                try (var bufStream = NettyUtils.createRefCntNeutralCloseableByteBufStream(packetBytesArr);
                    var messageHolder = RefSafeHolder.create(
                        HttpByteBufFormatter.parseHttpMessageFromBufs(HttpByteBufFormatter.HttpMessageType.RESPONSE,
                            bufStream))) {
                    var message = messageHolder.get();
                    Assertions.assertNotNull(message);
                    var response = (FullHttpResponse) message;
                    Assertions.assertEquals(200, response.status().code());
                    var body = response.content();
                    Assertions.assertEquals(TestHttpServerContext.SERVER_RESPONSE_BODY_PREFIX +
                                    TestHttpServerContext.getUriForIthRequest(i / NUM_REPEATS),
                        body.duplicate().toString(StandardCharsets.UTF_8));
                }
            }
            closeFuture.get();
        }
    }

    private List<ByteBuf> makeRequest(int i) {
        // uncomment/swap for a simpler test case to run
        return //List.of(Unpooled.wrappedBuffer(getRequestString(i).getBytes()));
               TestHttpServerContext.getRequestString(i).chars().mapToObj(c->Unpooled.wrappedBuffer(new byte[]{(byte) c}))
                       .collect(Collectors.toList());
    }
}