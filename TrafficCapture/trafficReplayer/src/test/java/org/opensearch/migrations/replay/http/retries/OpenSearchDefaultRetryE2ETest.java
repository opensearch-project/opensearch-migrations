package org.opensearch.migrations.replay.http.retries;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.opensearch.migrations.replay.RequestSenderOrchestrator;
import org.opensearch.migrations.replay.TrafficReplayerTopLevel;
import org.opensearch.migrations.replay.TransformedTargetRequestAndResponseList;
import org.opensearch.migrations.replay.datahandlers.NettyPacketToHttpConsumer;
import org.opensearch.migrations.replay.datatypes.ByteBufList;
import org.opensearch.migrations.replay.datatypes.ByteBufListProducer;
import org.opensearch.migrations.replay.datatypes.HttpRequestTransformationStatus;
import org.opensearch.migrations.replay.datatypes.TransformedOutputAndResult;
import org.opensearch.migrations.testutils.SimpleHttpResponse;
import org.opensearch.migrations.testutils.SimpleHttpServer;
import org.opensearch.migrations.testutils.WrapWithNettyLeakDetection;
import org.opensearch.migrations.tracing.TestContext;
import org.opensearch.migrations.utils.TextTrackedFuture;
import org.opensearch.migrations.utils.TrackedFuture;

import io.netty.buffer.Unpooled;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import static org.opensearch.migrations.replay.datahandlers.NettyPacketToHttpConsumerTest.REGULAR_RESPONSE_TIMEOUT;

@Slf4j
@WrapWithNettyLeakDetection(repetitions = 1)
public class OpenSearchDefaultRetryE2ETest {

    private static final String BULK_BODY =
        "{\"index\":{\"_index\":\"test\",\"_id\":\"1\"}}\n" +
        "{\"field\":\"value\"}\n";

    private static final String BULK_REQUEST =
        "POST /_bulk HTTP/1.1\r\n" +
        "Host: localhost\r\n" +
        "Content-Type: application/x-ndjson\r\n" +
        "User-Agent: UnitTest\r\n" +
        "Connection: Keep-Alive\r\n" +
        "Content-Length: " + BULK_BODY.length() + "\r\n\r\n" +
        BULK_BODY;

    private static SimpleHttpResponse makeBulkJsonResponse(String body) {
        return new SimpleHttpResponse(
            Map.of("Content-Type", "application/json", "Content-Length", "" + body.length()),
            body.getBytes(StandardCharsets.UTF_8), "OK", 200);
    }

    private static String bulkResponseNoErrors() {
        return "{\"took\":1,\"errors\":false,\"items\":[" +
            "{\"index\":{\"_id\":\"1\",\"result\":\"created\",\"status\":201}}" +
            "]}";
    }

    private static String bulkResponseWithRetryableError() {
        return "{\"took\":1,\"errors\":true,\"items\":[" +
            "{\"index\":{\"_id\":\"1\",\"status\":503," +
            "\"error\":{\"type\":\"unavailable_shards_exception\",\"reason\":\"primary shard not available\"}}}" +
            "]}";
    }

    private static String bulkResponseWithNonRetryableError() {
        return "{\"took\":1,\"errors\":true,\"items\":[" +
            "{\"index\":{\"_id\":\"1\",\"status\":409," +
            "\"error\":{\"type\":\"version_conflict_engine_exception\",\"reason\":\"version conflict\"}}}" +
            "]}";
    }

    private static String bulkResponseWithMixedErrors() {
        return "{\"took\":1,\"errors\":true,\"items\":[" +
            "{\"index\":{\"_id\":\"1\",\"status\":409," +
            "\"error\":{\"type\":\"version_conflict_engine_exception\",\"reason\":\"version conflict\"}}}," +
            "{\"index\":{\"_id\":\"2\",\"status\":503," +
            "\"error\":{\"type\":\"unavailable_shards_exception\",\"reason\":\"primary shard not available\"}}}" +
            "]}";
    }

    private TrackedFuture<String, TransformedTargetRequestAndResponseList>
    sendBulkRequest(SimpleHttpServer httpServer, TestContext rootContext) {
        var clientConnectionPool = TrafficReplayerTopLevel.makeNettyPacketConsumerConnectionPool(
            httpServer.localhostEndpoint(), false, 1,
            "targetConnectionPool for OpenSearchDefaultRetryE2ETest");
        var retryFactory = new RetryCollectingVisitorFactory(new OpenSearchDefaultRetry());
        var senderOrchestrator = new RequestSenderOrchestrator(clientConnectionPool,
            (replaySession, ctx) -> new NettyPacketToHttpConsumer(replaySession, ctx, REGULAR_RESPONSE_TIMEOUT));
        var requestContext = rootContext.getTestConnectionRequestContext(0);
        var sourceRequestPackets = new ByteBufList(
            Unpooled.wrappedBuffer(BULK_REQUEST.getBytes(StandardCharsets.UTF_8)));
        var sourceResponseBytes = bulkResponseNoErrors().getBytes(StandardCharsets.UTF_8);
        var packetProducer = ByteBufListProducer.of(sourceRequestPackets);
        var retryVisitor = retryFactory.getRetryCheckVisitor(
            new TransformedOutputAndResult<>(packetProducer, HttpRequestTransformationStatus.skipped()),
            TextTrackedFuture.completedFuture(
                new RetryTestUtils.TestRequestResponsePair(sourceResponseBytes), () -> "static rrp"));
        return senderOrchestrator.scheduleRequest(
            requestContext.getReplayerRequestKey(), requestContext,
            Instant.now().plus(Duration.ofMillis(10)), Duration.ofMillis(1),
            packetProducer, retryVisitor)
            .whenComplete((v, t) -> {
                requestContext.close();
                clientConnectionPool.shutdownNow();
            }, () -> "cleanup");
    }

    /**
     * Gets the result and exercises the TrackedFuture diagnostic supplier chain
     * (via toString) to ensure no exceptions in the tracing lambdas.
     */
    private TransformedTargetRequestAndResponseList getResultAndVerifyDiagnostics(
        TrackedFuture<String, TransformedTargetRequestAndResponseList> future) throws Exception
    {
        var result = future.get();
        // Force evaluation of all diagnostic suppliers in the future chain
        Assertions.assertDoesNotThrow(() -> future.toString());
        return result;
    }

    @Test
    public void testRetryableErrorIsRetriedThenSucceeds() throws Exception {
        var requestCount = new AtomicInteger();
        try (var rootContext = TestContext.withAllTracking();
             var httpServer = SimpleHttpServer.makeServer(false, r ->
                 requestCount.incrementAndGet() <= 2
                     ? makeBulkJsonResponse(bulkResponseWithRetryableError())
                     : makeBulkJsonResponse(bulkResponseNoErrors())))
        {
            var result = getResultAndVerifyDiagnostics(sendBulkRequest(httpServer, rootContext));
            Assertions.assertEquals(3, result.responses().size());
            // First 2 responses have errors, 3rd succeeds
            Assertions.assertEquals(200, result.responses().get(2).getRawResponse().status().code());
        }
    }

    @Test
    public void testNonRetryableErrorIsNotRetried() throws Exception {
        var requestCount = new AtomicInteger();
        try (var rootContext = TestContext.withAllTracking();
             var httpServer = SimpleHttpServer.makeServer(false, r -> {
                 requestCount.incrementAndGet();
                 return makeBulkJsonResponse(bulkResponseWithNonRetryableError());
             }))
        {
            var result = getResultAndVerifyDiagnostics(sendBulkRequest(httpServer, rootContext));
            // Should NOT retry — only 1 request sent
            Assertions.assertEquals(1, result.responses().size());
            Assertions.assertEquals(1, requestCount.get());
        }
    }

    @Test
    public void testMixedErrorsWithRetryableIsRetried() throws Exception {
        var requestCount = new AtomicInteger();
        try (var rootContext = TestContext.withAllTracking();
             var httpServer = SimpleHttpServer.makeServer(false, r ->
                 requestCount.incrementAndGet() <= 1
                     ? makeBulkJsonResponse(bulkResponseWithMixedErrors())
                     : makeBulkJsonResponse(bulkResponseNoErrors())))
        {
            var result = getResultAndVerifyDiagnostics(sendBulkRequest(httpServer, rootContext));
            // First response has mixed errors (including retryable) -> retried, second succeeds
            Assertions.assertEquals(2, result.responses().size());
        }
    }

    @Test
    public void testRetryableErrorExhaustsMaxRetries() throws Exception {
        var requestCount = new AtomicInteger();
        try (var rootContext = TestContext.withAllTracking();
             var httpServer = SimpleHttpServer.makeServer(false, r -> {
                 requestCount.incrementAndGet();
                 return makeBulkJsonResponse(bulkResponseWithRetryableError());
             }))
        {
            var result = getResultAndVerifyDiagnostics(sendBulkRequest(httpServer, rootContext));
            // Should retry up to MAX_RETRIES then give up
            Assertions.assertEquals(DefaultRetry.MAX_RETRIES, result.responses().size());
            Assertions.assertEquals(DefaultRetry.MAX_RETRIES, requestCount.get());
        }
    }

    @Test
    public void testSuccessfulBulkResponseIsNotRetried() throws Exception {
        var requestCount = new AtomicInteger();
        try (var rootContext = TestContext.withAllTracking();
             var httpServer = SimpleHttpServer.makeServer(false, r -> {
                 requestCount.incrementAndGet();
                 return makeBulkJsonResponse(bulkResponseNoErrors());
             }))
        {
            var result = getResultAndVerifyDiagnostics(sendBulkRequest(httpServer, rootContext));
            Assertions.assertEquals(1, result.responses().size());
            Assertions.assertEquals(1, requestCount.get());
        }
    }

    @Test
    public void testBulk429IsRetried() throws Exception {
        var requestCount = new AtomicInteger();
        try (var rootContext = TestContext.withAllTracking();
             var httpServer = SimpleHttpServer.makeServer(false, r ->
                 requestCount.incrementAndGet() <= 1
                     ? new SimpleHttpResponse(Map.of(), "rate limited".getBytes(StandardCharsets.UTF_8), "Too Many Requests", 429)
                     : makeBulkJsonResponse(bulkResponseNoErrors())))
        {
            var result = getResultAndVerifyDiagnostics(sendBulkRequest(httpServer, rootContext));
            Assertions.assertEquals(2, result.responses().size());
            Assertions.assertEquals(429, result.responses().get(0).getRawResponse().status().code());
            Assertions.assertEquals(200, result.responses().get(1).getRawResponse().status().code());
        }
    }

    @Test
    public void testBulkErrorWithMissingTypeFieldIsRetryable() throws Exception {
        // Error object without a "type" field -> unknown error -> retryable
        var requestCount = new AtomicInteger();
        String noTypeError = "{\"took\":1,\"errors\":true,\"items\":[" +
            "{\"index\":{\"_id\":\"1\",\"status\":500,\"error\":{\"reason\":\"no type field here\"}}}" +
            "]}";
        try (var rootContext = TestContext.withAllTracking();
             var httpServer = SimpleHttpServer.makeServer(false, r ->
                 requestCount.incrementAndGet() <= 1
                     ? makeBulkJsonResponse(noTypeError)
                     : makeBulkJsonResponse(bulkResponseNoErrors())))
        {
            var result = getResultAndVerifyDiagnostics(sendBulkRequest(httpServer, rootContext));
            Assertions.assertEquals(2, result.responses().size());
        }
    }

    @Test
    public void testBulk500IsRetried() throws Exception {
        var requestCount = new AtomicInteger();
        try (var rootContext = TestContext.withAllTracking();
             var httpServer = SimpleHttpServer.makeServer(false, r ->
                 requestCount.incrementAndGet() <= 1
                     ? new SimpleHttpResponse(Map.of(), "error".getBytes(StandardCharsets.UTF_8), "Internal Server Error", 500)
                     : makeBulkJsonResponse(bulkResponseNoErrors())))
        {
            var result = getResultAndVerifyDiagnostics(sendBulkRequest(httpServer, rootContext));
            Assertions.assertEquals(2, result.responses().size());
            Assertions.assertEquals(500, result.responses().get(0).getRawResponse().status().code());
            Assertions.assertEquals(200, result.responses().get(1).getRawResponse().status().code());
        }
    }

    @Test
    public void testBulk200WithNonJsonBodyFallsToSuperclass() throws Exception {
        // Non-OpenSearch target returns 200 with HTML body. Can't parse JSON, so falls
        // through to superclass which compares source (200) vs target (200) → DONE.
        var requestCount = new AtomicInteger();
        var htmlBody = "<html><body>Service Unavailable</body></html>";
        try (var rootContext = TestContext.withAllTracking();
             var httpServer = SimpleHttpServer.makeServer(false, r -> {
                 requestCount.incrementAndGet();
                 return new SimpleHttpResponse(
                     Map.of("Content-Type", "text/html", "Content-Length", "" + htmlBody.length()),
                     htmlBody.getBytes(StandardCharsets.UTF_8), "OK", 200);
             }))
        {
            var result = getResultAndVerifyDiagnostics(sendBulkRequest(httpServer, rootContext));
            // Source was 200, target is 200 → super says DONE (same status)
            Assertions.assertEquals(1, result.responses().size());
            Assertions.assertEquals(1, requestCount.get());
        }
    }

    @Test
    public void testBulk200WithErrorsTrueButNoItemsFieldIsRetried() throws Exception {
        // Non-OpenSearch target may return 200 with errors:true but no items array.
        // Missing items field with errors=true -> HAS_RETRYABLE_ERRORS -> retry.
        var requestCount = new AtomicInteger();
        String noItems = "{\"took\":1,\"errors\":true}";
        try (var rootContext = TestContext.withAllTracking();
             var httpServer = SimpleHttpServer.makeServer(false, r ->
                 requestCount.incrementAndGet() <= 1
                     ? makeBulkJsonResponse(noItems)
                     : makeBulkJsonResponse(bulkResponseNoErrors())))
        {
            var result = getResultAndVerifyDiagnostics(sendBulkRequest(httpServer, rootContext));
            Assertions.assertEquals(2, result.responses().size());
        }
    }

    @Test
    public void testBulk200WithNoErrorsFieldAndNoItemsIsNotRetried() throws Exception {
        // Non-standard response with neither errors nor items field.
        // No errors field + no items -> NO_ERRORS -> DONE (no retry).
        var requestCount = new AtomicInteger();
        String minimal = "{\"took\":1}";
        try (var rootContext = TestContext.withAllTracking();
             var httpServer = SimpleHttpServer.makeServer(false, r -> {
                 requestCount.incrementAndGet();
                 return makeBulkJsonResponse(minimal);
             }))
        {
            var result = getResultAndVerifyDiagnostics(sendBulkRequest(httpServer, rootContext));
            Assertions.assertEquals(1, result.responses().size());
            Assertions.assertEquals(1, requestCount.get());
        }
    }

    @Test
    public void testStreamingAnalysisShortCircuitsOnErrorsFalse() throws Exception {
        // Verify the analyzer can resolve with "errors":false without needing the full items array.
        // Build a response where "errors":false appears before a large items array.
        var sb = new StringBuilder();
        sb.append("{\"errors\":false,\"took\":1,\"items\":[");
        for (int i = 0; i < 10_000; i++) {
            if (i > 0) sb.append(",");
            sb.append("{\"index\":{\"_id\":\"").append(i).append("\",\"result\":\"created\",\"status\":201}}");
        }
        sb.append("]}");
        var largeBody = sb.toString();

        var requestCount = new AtomicInteger();
        try (var rootContext = TestContext.withAllTracking();
             var httpServer = SimpleHttpServer.makeServer(false, r -> {
                 requestCount.incrementAndGet();
                 return makeBulkJsonResponse(largeBody);
             }))
        {
            var result = getResultAndVerifyDiagnostics(sendBulkRequest(httpServer, rootContext));
            // errors:false -> NO_ERRORS -> DONE, no retry
            Assertions.assertEquals(1, result.responses().size());
            Assertions.assertEquals(1, requestCount.get());
        }
    }

    @Test
    public void testBulkErrorAsStringValueIsRetried() throws Exception {
        // Some non-OpenSearch targets may return "error" as a plain string instead of an object.
        // This should be treated as retryable since we can't classify it.
        var requestCount = new AtomicInteger();
        String errorAsString = "{\"took\":1,\"errors\":true,\"items\":[" +
            "{\"index\":{\"_id\":\"1\",\"status\":500,\"error\":\"internal error occurred\"}}" +
            "]}";
        try (var rootContext = TestContext.withAllTracking();
             var httpServer = SimpleHttpServer.makeServer(false, r ->
                 requestCount.incrementAndGet() <= 1
                     ? makeBulkJsonResponse(errorAsString)
                     : makeBulkJsonResponse(bulkResponseNoErrors())))
        {
            var result = getResultAndVerifyDiagnostics(sendBulkRequest(httpServer, rootContext));
            Assertions.assertEquals(2, result.responses().size());
        }
    }

    @Test
    public void testBulkErrorsFieldAfterItemsStillWorks() throws Exception {
        // JSON field order is not guaranteed. Test that "errors" appearing after "items"
        // is handled correctly via finalizeAnalysis.
        var requestCount = new AtomicInteger();
        String errorsAfterItems = "{\"took\":1,\"items\":[" +
            "{\"index\":{\"_id\":\"1\",\"status\":503," +
            "\"error\":{\"type\":\"unavailable_shards_exception\",\"reason\":\"test\"}}}" +
            "],\"errors\":true}";
        try (var rootContext = TestContext.withAllTracking();
             var httpServer = SimpleHttpServer.makeServer(false, r ->
                 requestCount.incrementAndGet() <= 1
                     ? makeBulkJsonResponse(errorsAfterItems)
                     : makeBulkJsonResponse(bulkResponseNoErrors())))
        {
            var result = getResultAndVerifyDiagnostics(sendBulkRequest(httpServer, rootContext));
            Assertions.assertEquals(2, result.responses().size());
        }
    }

    @Test
    public void testBulkErrorsFalseAfterItemsIsNotRetried() throws Exception {
        // "errors":false after items — finalizeAnalysis should return NO_ERRORS.
        var requestCount = new AtomicInteger();
        String errorsFalseAfterItems = "{\"took\":1,\"items\":[" +
            "{\"index\":{\"_id\":\"1\",\"result\":\"created\",\"status\":201}}" +
            "],\"errors\":false}";
        try (var rootContext = TestContext.withAllTracking();
             var httpServer = SimpleHttpServer.makeServer(false, r -> {
                 requestCount.incrementAndGet();
                 return makeBulkJsonResponse(errorsFalseAfterItems);
             }))
        {
            var result = getResultAndVerifyDiagnostics(sendBulkRequest(httpServer, rootContext));
            Assertions.assertEquals(1, result.responses().size());
            Assertions.assertEquals(1, requestCount.get());
        }
    }

}
