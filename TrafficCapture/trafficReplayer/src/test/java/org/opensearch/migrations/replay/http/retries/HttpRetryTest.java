package org.opensearch.migrations.replay.http.retries;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.opensearch.migrations.replay.RequestSenderOrchestrator;
import org.opensearch.migrations.replay.TestHttpServerContext;
import org.opensearch.migrations.replay.TrafficReplayerTopLevel;
import org.opensearch.migrations.replay.TransformedTargetRequestAndResponseList;
import org.opensearch.migrations.replay.datahandlers.NettyPacketToHttpConsumer;
import org.opensearch.migrations.replay.datatypes.ByteBufList;
import org.opensearch.migrations.replay.datatypes.HttpRequestTransformationStatus;
import org.opensearch.migrations.replay.datatypes.TransformedOutputAndResult;
import org.opensearch.migrations.replay.util.TextTrackedFuture;
import org.opensearch.migrations.replay.util.TrackedFuture;
import org.opensearch.migrations.testutils.SimpleHttpResponse;
import org.opensearch.migrations.testutils.SimpleHttpServer;
import org.opensearch.migrations.testutils.WrapWithNettyLeakDetection;
import org.opensearch.migrations.tracing.TestContext;

import io.netty.buffer.Unpooled;
import lombok.Lombok;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import static org.opensearch.migrations.replay.datahandlers.NettyPacketToHttpConsumerTest.REGULAR_RESPONSE_TIMEOUT;

@Slf4j
@WrapWithNettyLeakDetection()
public class HttpRetryTest {

    private ByteBufList makeRequest() {
        return new ByteBufList(Unpooled.wrappedBuffer(TestHttpServerContext.getRequestStringForSimpleGet("/")
            .getBytes(StandardCharsets.UTF_8)));
    }

    public static SimpleHttpResponse makeTransientErrorResponse(Duration responseWaitTime) {
        try {
            Thread.sleep(responseWaitTime.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw Lombok.sneakyThrow(e);
        }
        return new SimpleHttpResponse(Map.of(), null, "Not Found", 404);
    }

    private TrackedFuture<String, TransformedTargetRequestAndResponseList>
    getResponseList(URI testServerUri)
        throws Exception
    {
        try (var rootContext = TestContext.noOtelTracking()) {
            var retryFactory = new RetryCollectingVisitorFactory(new DefaultRetry());
            var clientConnectionPool = TrafficReplayerTopLevel.makeClientConnectionPool(
                testServerUri,
                false,
                1,
                "targetConnectionPool for testTransientRequestFailuresAreRetried"
            );
            var senderOrchestrator = new RequestSenderOrchestrator(
                clientConnectionPool,
                (replaySession, ctx) -> new NettyPacketToHttpConsumer(replaySession, ctx, REGULAR_RESPONSE_TIMEOUT)
            );
            var baseTime = Instant.now();
            var requestContext = rootContext.getTestConnectionRequestContext(0);
            var startTimeForThisRequest = baseTime.plus(Duration.ofMillis(10));
            var sourceRequestPackets = makeRequest();
            var sourceResponseBytes = RetryTestUtils.makeSlashResponse(200).getBytes(StandardCharsets.UTF_8);
            var retryVisitor = retryFactory.getRetryCheckVisitor(
                new TransformedOutputAndResult<>(sourceRequestPackets, HttpRequestTransformationStatus.skipped()),
                TextTrackedFuture.completedFuture(new RetryTestUtils.TestRequestResponsePair(sourceResponseBytes),
                    () -> "static rrp"));
            log.info("Scheduling item to run at " + startTimeForThisRequest);
            return senderOrchestrator.scheduleRequest(
                requestContext.getReplayerRequestKey(),
                requestContext,
                startTimeForThisRequest,
                Duration.ofMillis(1),
                sourceRequestPackets,
                retryVisitor
            );
        }
    }

    private TransformedTargetRequestAndResponseList
    runServerAndGetResponse(int numFailuresBeforeSuccess) throws Exception
    {
        var requestsReceivedCounter = new AtomicInteger();
        try (
            var httpServer = SimpleHttpServer.makeServer(
                false,
                r -> requestsReceivedCounter.incrementAndGet() > numFailuresBeforeSuccess
                    ? TestHttpServerContext.makeResponse(r, Duration.ofMillis(100))
                    : makeTransientErrorResponse(Duration.ofMillis(100))
            );
        ) {
            var responseFuture = getResponseList(httpServer.localhostEndpoint());
            return Assertions.assertDoesNotThrow(() -> responseFuture.get());
        }
    }

    @Test
    public void testTransientRequestFailuresAreRetried() throws Exception {
        var response = runServerAndGetResponse(DefaultRetry.MAX_RETRIES-1);
        Assertions.assertNotNull(response.responses());
        Assertions.assertFalse(response.responses().isEmpty());
        Assertions.assertEquals(DefaultRetry.MAX_RETRIES, response.responses().size());
        Assertions.assertEquals(200,
            response.responses().get(DefaultRetry.MAX_RETRIES-1).getRawResponse().status().code());
        Assertions.assertTrue(response.responses().stream()
            .limit(DefaultRetry.MAX_RETRIES-1)
            .map(r -> r.getRawResponse().status().code())
            .allMatch(c -> 404 == c));
    }

    @Test
    public void testPersistentRequestFailuresAreRetriedThenFailed() throws Exception {
        var response = runServerAndGetResponse(DefaultRetry.MAX_RETRIES+1);
        Assertions.assertNotNull(response.responses());
        Assertions.assertFalse(response.responses().isEmpty());
        Assertions.assertEquals(DefaultRetry.MAX_RETRIES+1, response.responses().size());
        Assertions.assertTrue(response.responses().stream()
            .map(r -> r.getRawResponse().status().code())
            .allMatch(c -> 404 == c));
    }

    @Disabled
    @Test
    @WrapWithNettyLeakDetection(disableLeakChecks = true) // code is forcibly terminated so leaks are expected
    public void testConnectionFailuresNeverGiveUp() throws Exception {
        URI serverUri;
        try (var server = SimpleHttpServer.makeServer(false, r -> makeTransientErrorResponse(Duration.ZERO))) {
            // do nothing but close it back down
            serverUri = server.localhostEndpoint();
        }

        var responseFuture = getResponseList(serverUri);
        var e = Assertions.assertThrows(Exception.class, responseFuture::get);
        log.atInfo().setCause(e).setMessage(()->"exception: ").log();
//        Assertions.assertFalse(response.responses().isEmpty());
//        Assertions.assertEquals(DefaultRetry.MAX_RETRIES+1, response.responses().size());
//        Assertions.assertTrue(response.responses().stream()
//            .map(r -> r.getRawResponse().status().code())
//            .allMatch(c -> 404 == c));
    }
}
