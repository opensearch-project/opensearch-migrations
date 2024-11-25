package org.opensearch.migrations.replay.http.retries;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import org.opensearch.migrations.replay.AggregatedRawResponse;
import org.opensearch.migrations.replay.HttpByteBufFormatter;
import org.opensearch.migrations.replay.RequestSenderOrchestrator;
import org.opensearch.migrations.utils.TextTrackedFuture;

import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class OpenSearchDefaultRetryTest {

    @ParameterizedTest
    @CsvSource(value = {
        "200, 200, DONE",
        "200, 404, RETRY",
        "404, 200, DONE",
        "200, 401, DONE",
        "200, 403, DONE"
    })
    public void testStatusCodeResults(int sourceStatusCode, int targetStatusCode,
                                      RequestSenderOrchestrator.RetryDirective expectedDirective)
        throws Exception
    {
        var retryChecker = new OpenSearchDefaultRetry();
        var sourceBytes = RetryTestUtils.makeSlashResponse(sourceStatusCode).getBytes(StandardCharsets.UTF_8);
        var targetBytes = RetryTestUtils.makeSlashResponse(targetStatusCode).getBytes(StandardCharsets.UTF_8);
        var aggregatedResponse = AggregatedRawResponse.builder(Instant.now())
            .addHttpParsedResponseObject(
                HttpByteBufFormatter.parseHttpResponseFromBufs(Stream.of(Unpooled.wrappedBuffer(targetBytes)), 0))
            .addResponsePacket(targetBytes)
            .build();
        var requestBytes = RetryTestUtils.GET_SLASH_REQUEST.getBytes(StandardCharsets.UTF_8);
        var determination = retryChecker.shouldRetry(Unpooled.wrappedBuffer(requestBytes),
            List.of(),
            aggregatedResponse,
            TextTrackedFuture.completedFuture(new RetryTestUtils.TestRequestResponsePair(sourceBytes),
                () -> "test future"));
        Assertions.assertEquals(expectedDirective, determination.get());
    }

    private static final String BULK_REQUEST =
        "GET /_bulk HTTP/1.1\r\n" +
            "Host: localhost\r\n" +
            "Content-Type: application/x-ndjson\r\n" +
            "User-Agent: Unit Test\r\n" +
            "Content-Length: 224\r\n\r\n" +
            "{ \"index\": { \"_index\": \"test\", \"_id\": \"1\" } }\n" +
            "{ \"field1\": \"value1\", \"field2\": \"value2\" }\n" +
            "{ \"update\": { \"_id\": \"1\", \"_index\": \"test\" } }\n" +
            "{ \"doc\": { \"field1\": \"updated_value1\" } }\n" +
            "{ \"delete\": { \"_index\": \"test\", \"_id\": \"2\" } }";

    private static String makeBulkResponse(int statusCode, Boolean error) {
        var body = "{\n" +
            "  \"took\": 123,\n" +
            Optional.ofNullable(error).map(e -> "  \"errors\": " + e + ",\n").orElse("") +
            "  \"items\": []\n" +
            "}\n";
        return "HTTP/1.1 " + statusCode + " OK\r\n" +
            "Content-Length: " + body.length() + "\r\n" +
            "Content-Type: text/plain\r\n\r\n" +
            body;
    }

    private static Boolean parseBoolean(String s) {
        return s.equals("null") ? null : Boolean.parseBoolean(s);
    }

    @ParameterizedTest
    @CsvSource(value = {
        "200, 200, false, false, 0, DONE",
        "200, 200, null,  null,  0, DONE",
        "200, 200, null,  false, 0, DONE",
        "200, 200, true,  true,  0, DONE",
        "200, 200, true,  null,  0, DONE",
        "200, 200, true,  false, 0, DONE",
        "200, 200, false, true,  0, RETRY",
        "200, 404, false, false, 0, RETRY",
        "200, 404, true,  true,  0, RETRY",

        "200, 200, false, true,  5, DONE",
        "200, 404, false, false, 5, DONE",
        "200, 404, true,  true,  5, DONE",

        "404, 200, false, false, 0, DONE",
        "200, 401, false, false, 0, DONE",
        "200, 403, false, false, 0, DONE"
    })
    public void testBulkResults(int sourceStatus, int targetStatus,
                                String sourceErrorStr, String targetErrorStr, int previousAttempts,
                                RequestSenderOrchestrator.RetryDirective expectedDirective)
        throws Exception
    {
        var retryChecker = new OpenSearchDefaultRetry();
        var sourceBytes = makeBulkResponse(sourceStatus, parseBoolean(sourceErrorStr)).getBytes(StandardCharsets.UTF_8);
        var targetBytes = makeBulkResponse(targetStatus, parseBoolean(targetErrorStr)).getBytes(StandardCharsets.UTF_8);
        var aggregatedResponse = AggregatedRawResponse.builder(Instant.now())
            .addHttpParsedResponseObject(
                HttpByteBufFormatter.parseHttpResponseFromBufs(Stream.of(Unpooled.wrappedBuffer(targetBytes)), 0))
            .addResponsePacket(targetBytes)
            .build();
        var determination = retryChecker.shouldRetry(Unpooled.wrappedBuffer(BULK_REQUEST.getBytes(StandardCharsets.UTF_8)),
            IntStream.range(0, previousAttempts).mapToObj(i->(AggregatedRawResponse)null).collect(Collectors.toList()),
            aggregatedResponse,
            TextTrackedFuture.completedFuture(new RetryTestUtils.TestRequestResponsePair(sourceBytes),
                () -> "test future"));
        Assertions.assertEquals(expectedDirective, determination.get());
    }


    private static final String REGULAR_REQUEST =
        "GET /something HTTP/1.1\r\n" +
            "Host: localhost\r\n" +
            "Content-Type: application/x-ndjson\r\n" +
            "User-Agent: Unit Test\r\n" +
            "Content-Length: 55\r\n\r\n" +
            "{ \"index\": { \"_index\": \"test\", \"_id\": \"1\" } }";

    private static String makeRegularResponse(int statusCode) {
        var body = "{\n" +
            "  \"took\": 123\n" +
            "}\n";
        return "HTTP/1.1 " + statusCode + " OK\r\n" +
            "Content-Length: " + body.length() + "\r\n" +
            "Content-Type: text/plain\r\n\r\n" +
            body;
    }

    @ParameterizedTest
    @CsvSource(value = {
        "200, 200, DONE",
        "200, 404, RETRY",
        "404, 200, DONE",
        "200, 401, DONE",
        "200, 403, DONE"
    })
    public void testNonBulkResults(int sourceStatus, int targetStatus,
                                   RequestSenderOrchestrator.RetryDirective expectedDirective)
        throws Exception
    {
        var retryChecker = new OpenSearchDefaultRetry();
        var sourceBytes = makeRegularResponse(sourceStatus).getBytes(StandardCharsets.UTF_8);
        var targetBytes = makeRegularResponse(targetStatus).getBytes(StandardCharsets.UTF_8);
        var aggregatedResponse = AggregatedRawResponse.builder(Instant.now())
            .addHttpParsedResponseObject(
                HttpByteBufFormatter.parseHttpResponseFromBufs(Stream.of(Unpooled.wrappedBuffer(targetBytes)), 0))
            .addResponsePacket(targetBytes)
            .build();
        for (int previousAttempts : new int[]{0, 5}) {
            var determination = retryChecker.shouldRetry(Unpooled.wrappedBuffer(REGULAR_REQUEST.getBytes(StandardCharsets.UTF_8)),
                IntStream.range(0, previousAttempts).mapToObj(i -> (AggregatedRawResponse) null).collect(Collectors.toList()),
                aggregatedResponse,
                TextTrackedFuture.completedFuture(new RetryTestUtils.TestRequestResponsePair(sourceBytes),
                    () -> "test future"));
            Assertions.assertEquals(previousAttempts == 0 ?
                    expectedDirective : RequestSenderOrchestrator.RetryDirective.DONE,
                determination.get());
        }
    }
}
