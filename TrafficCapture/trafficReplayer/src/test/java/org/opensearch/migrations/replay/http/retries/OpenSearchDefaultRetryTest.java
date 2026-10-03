package org.opensearch.migrations.replay.http.retries;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.AbstractMap;
import java.util.List;
import java.util.Optional;

import org.opensearch.migrations.replay.AggregatedRawResponse;
import org.opensearch.migrations.replay.HttpMessageAndTimestamp;
import org.opensearch.migrations.replay.datahandlers.NettyPacketToHttpConsumer;
import org.opensearch.migrations.replay.datatypes.ByteBufList;
import org.opensearch.migrations.replay.datatypes.ByteBufListProducer;
import org.opensearch.migrations.replay.lifecycle.ReplayOutcomes.RetryDecision;
import org.opensearch.migrations.replay.lifecycle.RequestReplayOwner;

import io.netty.buffer.Unpooled;
import io.netty.handler.codec.http.DefaultHttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;


class OpenSearchDefaultRetryTest {
    @Test
    void testStatusCodeResults() {
        record StatusCase(
            int sourceStatus,
            int targetStatus,
            boolean requiresSourceResponse,
            Class<? extends RetryDecision> expectedDecision
        ) {}

        var cases = List.of(
            new StatusCase(200, 200, false, RetryDecision.TargetServerAttemptsFinished.class),
            new StatusCase(200, 404, true, RetryDecision.RetryRequired.class),
            new StatusCase(200, 500, true, RetryDecision.RetryRequired.class),
            new StatusCase(200, 429, true, RetryDecision.RetryRequired.class),
            new StatusCase(404, 200, false, RetryDecision.TargetServerAttemptsFinished.class),
            new StatusCase(404, 404, true, RetryDecision.TargetServerAttemptsFinished.class),
            new StatusCase(200, 401, false, RetryDecision.TargetServerAttemptsFinished.class),
            new StatusCase(200, 403, false, RetryDecision.TargetServerAttemptsFinished.class)
        );
        var policy = new OpenSearchDefaultRetry();
        try (var prepared = prepared("GET /document HTTP/1.1\r\nHost: target\r\n\r\n")) {
            for (var testCase : cases) {
                var target = response(
                    testCase.targetStatus(),
                    rawResponse(testCase.targetStatus(), "")
                );
                Assertions.assertEquals(
                    testCase.requiresSourceResponse(),
                    policy.requiresSourceResponse(prepared, target),
                    testCase.toString()
                );
                Assertions.assertInstanceOf(
                    testCase.expectedDecision(),
                    policy.decide(
                        prepared,
                        target,
                        completeSourceResponse(testCase.sourceStatus())
                    ),
                    testCase.toString()
                );
            }
        }
    }

    @Test
    void refactoredBulkDecisionCasesUseCurrentPolicyBoundary() {
        record BulkCase(
            String name,
            String body,
            Class<? extends RetryDecision> expectedDecision
        ) {}

        var cases = List.of(
            new BulkCase(
                "missing errors and items finishes",
                "{\"took\":1}",
                RetryDecision.TargetServerAttemptsFinished.class
            ),
            new BulkCase(
                "success mixed with non-retryable errors finishes",
                """
                {"errors":true,"items":[
                  {"index":{"_id":"1","result":"created","status":201}},
                  {"index":{"_id":"2","status":409,
                    "error":{"type":"version_conflict_engine_exception"}}}
                ]}
                """,
                RetryDecision.TargetServerAttemptsFinished.class
            ),
            new BulkCase(
                "any retryable item in a mixed result retries",
                """
                {"errors":true,"items":[
                  {"index":{"_id":"1","status":409,
                    "error":{"type":"version_conflict_engine_exception"}}},
                  {"index":{"_id":"2","status":503,
                    "error":{"type":"unavailable_shards_exception"}}}
                ]}
                """,
                RetryDecision.RetryRequired.class
            ),
            new BulkCase(
                "error object without a type retries",
                """
                {"errors":true,"items":[
                  {"index":{"_id":"1","status":500,"error":{"reason":"missing type"}}}
                ]}
                """,
                RetryDecision.RetryRequired.class
            ),
            new BulkCase(
                "scalar error retries",
                """
                {"errors":true,"items":[
                  {"index":{"_id":"1","status":500,"error":"internal error"}}
                ]}
                """,
                RetryDecision.RetryRequired.class
            ),
            new BulkCase(
                "errors after items still retries",
                """
                {"items":[
                  {"index":{"_id":"1","status":503,
                    "error":{"type":"unavailable_shards_exception"}}}
                ],"errors":true}
                """,
                RetryDecision.RetryRequired.class
            ),
            new BulkCase(
                "errors false after successful items finishes",
                """
                {"items":[
                  {"index":{"_id":"1","result":"created","status":201}}
                ],"errors":false}
                """,
                RetryDecision.TargetServerAttemptsFinished.class
            )
        );
        var policy = new OpenSearchDefaultRetry();
        try (var prepared = prepared(
            "POST /_bulk HTTP/1.1\r\nHost: target\r\nContent-Length: 0\r\n\r\n"
        )) {
            for (var testCase : cases) {
                var target = response(200, rawResponse(200, testCase.body()));
                Assertions.assertFalse(
                    policy.requiresSourceResponse(prepared, target),
                    testCase.name()
                );
                Assertions.assertInstanceOf(
                    testCase.expectedDecision(),
                    policy.decide(
                        prepared,
                        target,
                        new RequestReplayOwner.SourceResponseUnavailableForRetry<>()
                    ),
                    testCase.name()
                );
            }
        }
    }

    @Test
    void retryableBulkResponseThenSuccessProducesExactPolicySequence() {
        var policy = new OpenSearchDefaultRetry();
        try (var prepared = prepared(
            "POST /_bulk HTTP/1.1\r\nHost: target\r\nContent-Length: 0\r\n\r\n"
        )) {
            var retryable = response(
                200,
                rawResponse(
                    200,
                    """
                    {"errors":true,"items":[
                      {"index":{"error":{"type":"unavailable_shards_exception"}}}
                    ]}
                    """
                )
            );
            var successful = response(
                200,
                rawResponse(200, "{\"errors\":false,\"items\":[]}")
            );
            var unavailable = new RequestReplayOwner.SourceResponseUnavailableForRetry<
                HttpMessageAndTimestamp.Response
            >();

            Assertions.assertInstanceOf(
                RetryDecision.RetryRequired.class,
                policy.decide(prepared, retryable, unavailable)
            );
            Assertions.assertInstanceOf(
                RetryDecision.TargetServerAttemptsFinished.class,
                policy.decide(prepared, successful, unavailable)
            );
        }
    }

    @Test
    void bulk429AndServerErrorsRetryWithoutWaitingForSourceResponse() {
        var policy = new OpenSearchDefaultRetry();
        try (var prepared = prepared(
            "POST /_bulk HTTP/1.1\r\nHost: target\r\nContent-Length: 0\r\n\r\n"
        )) {
            for (var status : List.of(429, 500, 503)) {
                var target = response(status, rawResponse(status, "not JSON"));
                Assertions.assertFalse(
                    policy.requiresSourceResponse(prepared, target),
                    "status " + status
                );
                Assertions.assertInstanceOf(
                    RetryDecision.RetryRequired.class,
                    policy.decide(
                        prepared,
                        target,
                        new RequestReplayOwner.SourceResponseUnavailableForRetry<>()
                    ),
                    "status " + status
                );
            }
        }
    }

    @Test
    void transformedBulkUriIsClassifiedOnceForRetryPolicy() {
        try (var prepared = prepared(
            "POST /prefix/_bulk HTTP/1.1\r\n"
                + "Host: target\r\n"
                + "Content-Length: 0\r\n\r\n"
        )) {
            Assertions.assertEquals(
                NettyPacketToHttpConsumer.PreparedRequest.RetryRequestKind.BULK,
                prepared.retryRequestKind()
            );
        }
    }

    @Test
    void bulkItemFailureRetriesWithoutWaitingForSourceResponse() {
        var policy = new OpenSearchDefaultRetry();
        try (var prepared = prepared(
            "POST /_bulk HTTP/1.1\r\n"
                + "Host: target\r\n"
                + "Content-Length: 0\r\n\r\n"
        )) {
            var response = response(
                200,
                """
                HTTP/1.1 200 OK\r
                Content-Type: application/json\r
                \r
                {"errors":true,"items":[{"index":{"error":{"type":"unavailable_shards_exception"}}}]}
                """
            );
            Assertions.assertFalse(
                policy.requiresSourceResponse(prepared, response)
            );
            Assertions.assertInstanceOf(
                RetryDecision.RetryRequired.class,
                policy.decide(
                    prepared,
                    response,
                    new RequestReplayOwner.SourceResponseUnavailableForRetry<>()
                )
            );
        }
    }

    @Test
    void ordinaryTargetFailureRetainsSourceStatusComparison() {
        var policy = new OpenSearchDefaultRetry();
        try (var prepared = prepared(
            "GET /document HTTP/1.1\r\nHost: target\r\n\r\n"
        )) {
            var target = response(
                404,
                "HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\n\r\n"
            );
            Assertions.assertTrue(policy.requiresSourceResponse(prepared, target));
            Assertions.assertInstanceOf(
                RetryDecision.RetryRequired.class,
                policy.decide(prepared, target, completeSourceResponse(200))
            );
            Assertions.assertInstanceOf(
                RetryDecision.TargetServerAttemptsFinished.class,
                policy.decide(prepared, target, completeSourceResponse(404))
            );
        }
    }

    @Test
    void inheritedRetryBackoffStartsAtOneHundredMillisecondsAndCapsAtFiveMinutes() {
        var policy = new OpenSearchDefaultRetry();

        Assertions.assertEquals(Duration.ofMillis(100), policy.retryDelay(1));
        Assertions.assertEquals(Duration.ofMillis(200), policy.retryDelay(2));
        Assertions.assertEquals(Duration.ofSeconds(300), policy.retryDelay(64));
    }

    private static NettyPacketToHttpConsumer.PreparedRequest prepared(String request) {
        var bytes = Unpooled.copiedBuffer(request, StandardCharsets.UTF_8);
        var packets = new ByteBufList(bytes);
        bytes.release();
        return new NettyPacketToHttpConsumer.PreparedRequest(
            ByteBufListProducer.of(packets),
            Duration.ZERO
        );
    }

    private static AggregatedRawResponse response(int status, String rawResponse) {
        return new AggregatedRawResponse(
            new DefaultHttpResponse(
                HttpVersion.HTTP_1_1,
                HttpResponseStatus.valueOf(status)
            ),
            rawResponse.length(),
            Duration.ZERO,
            List.of(new AbstractMap.SimpleEntry<>(
                Instant.EPOCH,
                rawResponse.getBytes(StandardCharsets.UTF_8)
            )),
            null
        );
    }

    private static String rawResponse(int status, String body) {
        return "HTTP/1.1 "
            + status
            + " status\r\nContent-Type: application/json\r\nContent-Length: "
            + body.getBytes(StandardCharsets.UTF_8).length
            + "\r\n\r\n"
            + body;
    }

    private static RequestReplayOwner.CompleteSourceResponseForRetry<
        HttpMessageAndTimestamp.Response
    > completeSourceResponse(int status) {
        var response = new HttpMessageAndTimestamp.Response(Instant.EPOCH);
        response.add(
            (
                "HTTP/1.1 "
                    + status
                    + " status\r\nContent-Length: 0\r\n\r\n"
            ).getBytes(StandardCharsets.UTF_8)
        );
        response.setLastPacketTimestamp(Instant.EPOCH);
        return new RequestReplayOwner.CompleteSourceResponseForRetry<>(response);
    }

    /**
     * Build a bulk response with optional item-level errors.
     * @param errorTypes if non-null, generates items with these error types (null entry = success item)
     */
    private static String makeBulkResponse(int statusCode, Boolean error, String[] errorTypes) {
        StringBuilder items = new StringBuilder();
        if (errorTypes != null) {
            for (int i = 0; i < errorTypes.length; i++) {
                if (i > 0) items.append(",\n");
                if (errorTypes[i] == null) {
                    items.append("    {\"index\": {\"_id\": \"" + i + "\", \"result\": \"created\", \"status\": 201}}");
                } else {
                    items.append("    {\"index\": {\"_id\": \"" + i + "\", \"status\": 400, " +
                        "\"error\": {\"type\": \"" + errorTypes[i] + "\", \"reason\": \"test\"}}}");
                }
            }
        }
        var body = "{\n" +
            "  \"took\": 123,\n" +
            Optional.ofNullable(error).map(e -> "  \"errors\": " + e + ",\n").orElse("") +
            "  \"items\": [\n" + items + "\n  ]\n" +
            "}\n";
        return "HTTP/1.1 " + statusCode + " OK\r\n" +
            "Content-Length: " + body.length() + "\r\n" +
            "Content-Type: text/plain\r\n\r\n" +
            body;
    }


    @Test
    public void testBulkMalformedJsonFallsToSuperclass() throws Exception {
        var retryChecker = new OpenSearchDefaultRetry();
        var malformedResponse = "HTTP/1.1 200 OK\r\nContent-Length: 12\r\n\r\nnot valid {{";
        var analysis = retryChecker.analyzeBulkResponse(
            Unpooled.wrappedBuffer(malformedResponse.getBytes(StandardCharsets.UTF_8)));
        Assertions.assertNull(analysis, "Unparseable body should return null to fall through to superclass");
    }

    @Test
    public void testBulkResponseMissingItemsFieldWithErrorsTrue() throws Exception {
        var retryChecker = new OpenSearchDefaultRetry();
        var body = "{\"errors\": true, \"took\": 1}";
        var response = "HTTP/1.1 200 OK\r\nContent-Length: " + body.length() + "\r\n\r\n" + body;
        var analysis = retryChecker.analyzeBulkResponse(
            Unpooled.wrappedBuffer(response.getBytes(StandardCharsets.UTF_8)));
        Assertions.assertEquals(OpenSearchDefaultRetry.BulkResponseAnalysis.HAS_RETRYABLE_ERRORS, analysis);
    }

    @Test
    public void testBulkResponseMissingItemsFieldWithErrorsFalse() throws Exception {
        var retryChecker = new OpenSearchDefaultRetry();
        var body = "{\"errors\": false, \"took\": 1}";
        var response = "HTTP/1.1 200 OK\r\nContent-Length: " + body.length() + "\r\n\r\n" + body;
        var analysis = retryChecker.analyzeBulkResponse(
            Unpooled.wrappedBuffer(response.getBytes(StandardCharsets.UTF_8)));
        Assertions.assertEquals(OpenSearchDefaultRetry.BulkResponseAnalysis.NO_ERRORS, analysis);
    }

    @Test
    public void testBulkResponseAllSuccessItems() throws Exception {
        var retryChecker = new OpenSearchDefaultRetry();
        // errors=true but all items actually succeeded (edge case) -> trust items over errors field
        var targetBytes = makeBulkResponse(200, true, new String[]{null, null})
            .getBytes(StandardCharsets.UTF_8);
        var analysis = retryChecker.analyzeBulkResponse(Unpooled.wrappedBuffer(targetBytes));
        // No error items found but errors=true -> HAS_RETRYABLE_ERRORS (trust top-level field)
        Assertions.assertEquals(OpenSearchDefaultRetry.BulkResponseAnalysis.HAS_RETRYABLE_ERRORS, analysis);
    }

    @Test
    public void testBulkResponseIgnoresInterimContinue() {
        var retryChecker = new OpenSearchDefaultRetry();
        var targetBytes = ("HTTP/1.1 100 Continue\r\n\r\n"
            + makeBulkResponse(200, true, new String[] { "unavailable_shards_exception" }))
            .getBytes(StandardCharsets.UTF_8);

        var analysis = retryChecker.analyzeBulkResponse(Unpooled.wrappedBuffer(targetBytes));

        Assertions.assertEquals(OpenSearchDefaultRetry.BulkResponseAnalysis.HAS_RETRYABLE_ERRORS, analysis);
    }

}
