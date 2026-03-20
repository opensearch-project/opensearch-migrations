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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class OpenSearchDefaultRetryTest {

    @ParameterizedTest
    @CsvSource(value = {
        "200, 200, DONE",
        "200, 404, RETRY",
        "200, 500, RETRY",
        "200, 429, RETRY",
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
        return makeBulkResponse(statusCode, error, null);
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

    private static Boolean parseBoolean(String s) {
        return s.equals("null") ? null : Boolean.parseBoolean(s);
    }

    @ParameterizedTest
    @CsvSource(value = {
        // No errors on either side
        "200, 200, false, false, 0, DONE",
        "200, 200, null,  null,  0, DONE",
        "200, 200, null,  false, 0, DONE",
        // Target has no errors
        "200, 200, true,  false, 0, DONE",

        // Non-bulk status codes
        "200, 404, false, false, 0, RETRY",
        "200, 404, true,  true,  0, RETRY",
        "404, 200, false, false, 0, DONE",
        "200, 401, false, false, 0, DONE",
        "200, 403, false, false, 0, DONE",
        "200, 500, false, false, 0, RETRY",
        "200, 429, false, false, 0, RETRY",

        // Max retries exceeded
        "200, 404, false, false, 5, DONE",
        "200, 404, true,  true,  5, DONE",
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

    @ParameterizedTest
    @CsvSource(value = {
        // Retryable item error -> RETRY
        "unavailable_shards_exception, RETRY",
        // Non-retryable item error -> DONE
        "version_conflict_engine_exception, DONE",
        // Retryable error even when source also had errors -> still RETRY
        "unavailable_shards_exception, RETRY",
    })
    public void testBulkWithItemErrors(String targetErrorType,
                                       RequestSenderOrchestrator.RetryDirective expectedDirective)
        throws Exception
    {
        var retryChecker = new OpenSearchDefaultRetry();
        var sourceBytes = makeBulkResponse(200, false).getBytes(StandardCharsets.UTF_8);
        var targetBytes = makeBulkResponse(200, true, new String[]{targetErrorType})
            .getBytes(StandardCharsets.UTF_8);
        var aggregatedResponse = AggregatedRawResponse.builder(Instant.now())
            .addHttpParsedResponseObject(
                HttpByteBufFormatter.parseHttpResponseFromBufs(Stream.of(Unpooled.wrappedBuffer(targetBytes)), 0))
            .addResponsePacket(targetBytes)
            .build();
        var determination = retryChecker.shouldRetry(Unpooled.wrappedBuffer(BULK_REQUEST.getBytes(StandardCharsets.UTF_8)),
            List.of(),
            aggregatedResponse,
            TextTrackedFuture.completedFuture(new RetryTestUtils.TestRequestResponsePair(sourceBytes),
                () -> "test future"));
        Assertions.assertEquals(expectedDirective, determination.get());
    }

    @Test
    public void testBulkWithRetryableItemErrorExceedsMaxRetries() throws Exception {
        var retryChecker = new OpenSearchDefaultRetry();
        var sourceBytes = makeBulkResponse(200, false).getBytes(StandardCharsets.UTF_8);
        var targetBytes = makeBulkResponse(200, true, new String[]{"unavailable_shards_exception"})
            .getBytes(StandardCharsets.UTF_8);
        var aggregatedResponse = AggregatedRawResponse.builder(Instant.now())
            .addHttpParsedResponseObject(
                HttpByteBufFormatter.parseHttpResponseFromBufs(Stream.of(Unpooled.wrappedBuffer(targetBytes)), 0))
            .addResponsePacket(targetBytes)
            .build();
        var determination = retryChecker.shouldRetry(Unpooled.wrappedBuffer(BULK_REQUEST.getBytes(StandardCharsets.UTF_8)),
            IntStream.range(0, 5).mapToObj(i -> (AggregatedRawResponse) null).collect(Collectors.toList()),
            aggregatedResponse,
            TextTrackedFuture.completedFuture(new RetryTestUtils.TestRequestResponsePair(sourceBytes),
                () -> "test future"));
        Assertions.assertEquals(RequestSenderOrchestrator.RetryDirective.DONE, determination.get());
    }
    @ParameterizedTest
    @CsvSource(value = {
        // Only version_conflict errors -> non-retryable -> DONE
        "version_conflict_engine_exception, DONE",
        // Only mapper_parsing errors -> non-retryable -> DONE
        "mapper_parsing_exception, DONE",
        // Only unavailable_shards -> retryable -> RETRY (source has no errors)
        "unavailable_shards_exception, RETRY",
        // Mix: version_conflict + unavailable_shards -> has retryable -> RETRY
        "version_conflict_engine_exception;unavailable_shards_exception, RETRY",
        // es_rejected_execution -> retryable -> RETRY
        "es_rejected_execution_exception, RETRY",
        // cluster_block_exception -> retryable -> RETRY
        "cluster_block_exception, RETRY",
        // circuit_breaking_exception -> retryable -> RETRY
        "circuit_breaking_exception, RETRY",
        // strict_dynamic_mapping_exception -> non-retryable -> DONE
        "strict_dynamic_mapping_exception, DONE",
        // document_missing_exception -> non-retryable -> DONE
        "document_missing_exception, DONE",
        // Mix of multiple non-retryable -> DONE
        "version_conflict_engine_exception;mapper_parsing_exception, DONE",
        // Unknown error type -> retryable (fail-open) -> RETRY
        "some_unknown_exception, RETRY",
    })
    public void testBulkItemLevelErrorClassification(String errorTypesStr,
                                                      RequestSenderOrchestrator.RetryDirective expectedDirective)
        throws Exception
    {
        var retryChecker = new OpenSearchDefaultRetry();
        var errorTypes = errorTypesStr.split(";");
        var targetBytes = makeBulkResponse(200, true, errorTypes).getBytes(StandardCharsets.UTF_8);
        // Source has no errors -- so if target has retryable errors, we should retry
        var sourceBytes = makeBulkResponse(200, false, null).getBytes(StandardCharsets.UTF_8);
        var aggregatedResponse = AggregatedRawResponse.builder(Instant.now())
            .addHttpParsedResponseObject(
                HttpByteBufFormatter.parseHttpResponseFromBufs(Stream.of(Unpooled.wrappedBuffer(targetBytes)), 0))
            .addResponsePacket(targetBytes)
            .build();
        var determination = retryChecker.shouldRetry(
            Unpooled.wrappedBuffer(BULK_REQUEST.getBytes(StandardCharsets.UTF_8)),
            List.of(),
            aggregatedResponse,
            TextTrackedFuture.completedFuture(new RetryTestUtils.TestRequestResponsePair(sourceBytes),
                () -> "test future"));
        Assertions.assertEquals(expectedDirective, determination.get());
    }

    @Test
    public void testBulkMixedSuccessAndNonRetryableErrors() throws Exception {
        var retryChecker = new OpenSearchDefaultRetry();
        // One success item, one version_conflict -> only non-retryable errors -> DONE
        var targetBytes = makeBulkResponse(200, true, new String[]{null, "version_conflict_engine_exception"})
            .getBytes(StandardCharsets.UTF_8);
        var sourceBytes = makeBulkResponse(200, false, null).getBytes(StandardCharsets.UTF_8);
        var aggregatedResponse = AggregatedRawResponse.builder(Instant.now())
            .addHttpParsedResponseObject(
                HttpByteBufFormatter.parseHttpResponseFromBufs(Stream.of(Unpooled.wrappedBuffer(targetBytes)), 0))
            .addResponsePacket(targetBytes)
            .build();
        var determination = retryChecker.shouldRetry(
            Unpooled.wrappedBuffer(BULK_REQUEST.getBytes(StandardCharsets.UTF_8)),
            List.of(),
            aggregatedResponse,
            TextTrackedFuture.completedFuture(new RetryTestUtils.TestRequestResponsePair(sourceBytes),
                () -> "test future"));
        Assertions.assertEquals(RequestSenderOrchestrator.RetryDirective.DONE, determination.get());
    }

    @Test
    public void testBulkMixedSuccessAndRetryableErrors() throws Exception {
        var retryChecker = new OpenSearchDefaultRetry();
        // One success, one version_conflict (non-retryable), one unavailable_shards (retryable) -> RETRY
        var targetBytes = makeBulkResponse(200, true,
            new String[]{null, "version_conflict_engine_exception", "unavailable_shards_exception"})
            .getBytes(StandardCharsets.UTF_8);
        var sourceBytes = makeBulkResponse(200, false, null).getBytes(StandardCharsets.UTF_8);
        var aggregatedResponse = AggregatedRawResponse.builder(Instant.now())
            .addHttpParsedResponseObject(
                HttpByteBufFormatter.parseHttpResponseFromBufs(Stream.of(Unpooled.wrappedBuffer(targetBytes)), 0))
            .addResponsePacket(targetBytes)
            .build();
        var determination = retryChecker.shouldRetry(
            Unpooled.wrappedBuffer(BULK_REQUEST.getBytes(StandardCharsets.UTF_8)),
            List.of(),
            aggregatedResponse,
            TextTrackedFuture.completedFuture(new RetryTestUtils.TestRequestResponsePair(sourceBytes),
                () -> "test future"));
        Assertions.assertEquals(RequestSenderOrchestrator.RetryDirective.RETRY, determination.get());
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
        "200, 500, RETRY",
        "200, 429, RETRY",
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
