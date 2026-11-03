package org.opensearch.migrations.bulkload.common;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.opensearch.migrations.Version;
import org.opensearch.migrations.bulkload.common.http.CompressionMode;
import org.opensearch.migrations.bulkload.common.http.ConnectionContext;
import org.opensearch.migrations.bulkload.common.http.HttpResponse;
import org.opensearch.migrations.bulkload.common.retry.AdaptiveLimit;
import org.opensearch.migrations.bulkload.common.retry.BulkRetryPolicy;
import org.opensearch.migrations.bulkload.http.BulkRequestGenerator;
import org.opensearch.migrations.bulkload.pipeline.model.Document;
import org.opensearch.migrations.bulkload.tracing.IRfsContexts;
import org.opensearch.migrations.bulkload.version_os_2_11.OpenSearchClient_OS_2_11;
import org.opensearch.migrations.reindexer.FailedRequestsLogger;
import org.opensearch.migrations.reindexer.faileddocumentstream.CapturingFailedDocumentStreamSink;
import org.opensearch.migrations.reindexer.faileddocumentstream.FailedDocumentStreamRecord;
import org.opensearch.migrations.reindexer.faileddocumentstream.FailureClass;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasSize;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Whole-request classification in {@link OpenSearchClient}'s bulk retry loop: status before body, splitting to
 * isolate an operation, and what happens to operations that can't be written.
 */
class OpenSearchClientBulkClassificationTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String INDEX = "idx";
    // Enough for a single document's whole no-response streak: OpenSearchClient.MAX_NO_RESPONSE_RETRIES.
    private static final int RETRIES = 8;

    private final List<List<String>> requests = new CopyOnWriteArrayList<>();
    private final List<byte[]> requestBodies = new CopyOnWriteArrayList<>();
    private final FailedRequestsLogger failedRequestsLogger = mock(FailedRequestsLogger.class);
    private final CapturingFailedDocumentStreamSink stream = new CapturingFailedDocumentStreamSink();
    private RestClient restClient;
    private OpenSearchClient client;

    @BeforeEach
    void setUp() {
        restClient = mock(RestClient.class);
        var connectionContext = mock(ConnectionContext.class);
        when(connectionContext.getUri()).thenReturn(URI.create("http://localhost/"));
        when(restClient.getConnectionContext()).thenReturn(connectionContext);
        client = new OpenSearchClient_OS_2_11(restClient, failedRequestsLogger, Version.fromString("OS 2.11"),
            CompressionMode.UNCOMPRESSED, 1000, 10_000_000) {
            @Override
            protected Retry getBulkRetryStrategy(BulkRetryPolicy.Batch batch) {
                return Retry.fixedDelay(RETRIES, Duration.ofMillis(1));
            }
        };
        // A healthy target, unless a test says otherwise: it answers a probe.
        client.setTargetProbe(() -> Mono.just(true));
    }

    /** Answer each bulk request with {@code server}, recording the document ids it carried. */
    private void serve(Function<List<String>, Mono<HttpResponse>> server) {
        when(restClient.postAsyncBytes(any(), any(), any(), any())).thenAnswer(invocation -> {
            byte[] body = invocation.getArgument(1);
            var ids = idsIn(body);
            requests.add(ids);
            requestBodies.add(body);
            return server.apply(ids);
        });
    }

    private void withStream() {
        client.setFailedDocumentStreamContext(stream, "session", "worker");
    }

    private Mono<OpenSearchClient.BulkResponse> send(String... ids) {
        return send(null, ids);
    }

    private Mono<OpenSearchClient.BulkResponse> send(IRfsContexts.IRequestContext context, String... ids) {
        var docs = new ArrayList<Document>();
        for (var id : ids) {
            docs.add(new Document(id, ("{\"field\":\"" + id + "\"}").getBytes(StandardCharsets.UTF_8),
                Document.Operation.UPSERT, Map.of(), Map.of()));
        }
        return client.sendBulkRequestRaw(INDEX, docs, context, false, DocumentExceptionAllowlist.empty());
    }

    private static List<String> idsIn(byte[] body) {
        var ids = new ArrayList<String>();
        var lines = new String(body, StandardCharsets.UTF_8).split("\n");
        for (int i = 0; i < lines.length; i += 2) {
            try {
                ids.add(MAPPER.readTree(lines[i]).path("index").path("_id").asText());
            } catch (IOException e) {
                throw new AssertionError(e);
            }
        }
        return ids;
    }

    private static Mono<HttpResponse> allCreated(List<String> ids) {
        return Mono.just(new HttpResponse(200, "OK", Map.of(), BulkRequestGenerator.bulkItemResponse(false,
            ids.stream().map(BulkRequestGenerator::itemEntry).toList())));
    }

    private static Mono<HttpResponse> status(int code, String body) {
        return Mono.just(new HttpResponse(code, "", Map.of("content-type", "text/html"), body));
    }

    private static final String HTML_413 =
        "<html><head><title>413 Request Entity Too Large</title></head><body>too large</body></html>";

    @Test
    void aValueOverTheTargetsXContentLimitIsRecordedOnTheFirstAttemptInsteadOfRetried() {
        withStream();
        // What OpenSearch 2.19 returns for a string over opensearch.xcontent.string.length.max when the index has an
        // ingest pipeline: the deterministic stream_constraints_exception is the cause of a parse_exception.
        var overLimit = "{\"index\":{\"_index\":\"idx\",\"_id\":\"big\",\"status\":400,\"error\":{"
            + "\"type\":\"parse_exception\",\"reason\":\"Failed to parse content to map\",\"caused_by\":{"
            + "\"type\":\"stream_constraints_exception\",\"reason\":\"String value length exceeds the maximum\"}}}}";
        serve(ids -> Mono.just(new HttpResponse(200, "OK", Map.of(), BulkRequestGenerator.bulkItemResponse(true,
            List.of(BulkRequestGenerator.itemEntry("a"),
                BulkRequestGenerator.BulkItemResponseEntry.builder().raw(overLimit).build())))));

        send("a", "big").block(Duration.ofSeconds(10));

        assertThat(requests, hasSize(1));
        assertThat(recordIds(), contains("big"));
        assertEquals(FailureClass.NON_RETRYABLE, stream.getRecords().get(0).getFailureClass());
        assertEquals("parse_exception", stream.getRecords().get(0).getFailureType());
    }

    private List<String> recordIds() {
        return stream.getRecords().stream().map(FailedDocumentStreamRecord::getDocumentId).toList();
    }

    @Test
    void entityTooLargeOnABatchSplitsUntilTheOffendingDocumentIsAlone() {
        withStream();
        serve(ids -> ids.contains("big") ? status(413, HTML_413) : allCreated(ids));

        var response = send("a", "b", "big", "d").block();

        assertEquals(List.of(List.of("a", "b", "big", "d"), List.of("a", "b"), List.of("big", "d"),
            List.of("big"), List.of("d")), requests);
        assertThat(recordIds(), contains("big"));
        var rec = stream.getRecords().get(0);
        assertEquals(FailureClass.NON_RETRYABLE, rec.getFailureClass());
        assertEquals("http_413", rec.getFailureType());
        assertEquals(413, rec.getResponseItem().path("status").asInt());
        assertThat(rec.getResponseItem().path("body").asText(), containsString("Request Entity Too Large"));
        assertEquals(4, response.target.upserts());
        assertEquals(5, response.requests.total());
        assertEquals(3, response.requests.batches());
        assertEquals(response.requests.total() - response.requests.batches(), response.requests.retries());
    }

    private OpenSearchClient clientWithTheRealRetryPolicy() {
        return new OpenSearchClient_OS_2_11(restClient, failedRequestsLogger,
            Version.fromString("OS 2.11"), CompressionMode.UNCOMPRESSED, 1000, 10_000_000);
    }

    private static List<Document> oneDoc() {
        return List.of(new Document("a", "{}".getBytes(StandardCharsets.UTF_8), Document.Operation.UPSERT, Map.of(),
            Map.of()));
    }

    @Test
    void aThrottledBulkWaitsForRetryAfterBeforeItsRetry() {
        var real = clientWithTheRealRetryPolicy();
        serve(ids -> requests.size() == 1
            ? Mono.just(new HttpResponse(429, "Too Many Requests", Map.of("Retry-After", "3"), "{}"))
            : allCreated(ids));

        long start = System.nanoTime();
        real.sendBulkRequestRaw(INDEX, oneDoc(), null, false, DocumentExceptionAllowlist.empty())
            .block(Duration.ofSeconds(30));

        assertThat(requests, hasSize(2));
        org.junit.jupiter.api.Assertions.assertTrue(Duration.ofNanos(System.nanoTime() - start).toMillis() >= 3_000);
    }

    @Test
    void aForbiddenBulkIsNotRetried() {
        var real = clientWithTheRealRetryPolicy();
        serve(ids -> status(403, "{\"error\":\"forbidden\"}"));

        assertThrows(RuntimeException.class, () -> real.sendBulkRequestRaw(INDEX, oneDoc(), null, false,
            DocumentExceptionAllowlist.empty()).block(Duration.ofSeconds(30)));

        assertThat(requests, hasSize(1));
    }

    @Test
    void aRetryThatSucceedsGetsItsBudgetBack() {
        var real = clientWithTheRealRetryPolicy();
        var budget = new org.opensearch.migrations.bulkload.common.retry.RetryBudget(10);
        real.setBulkRetryBudget(budget);
        serve(ids -> requests.size() == 1 ? status(503, "Service Unavailable") : allCreated(ids));

        real.sendBulkRequestRaw(INDEX, oneDoc(), null, false, DocumentExceptionAllowlist.empty())
            .block(Duration.ofSeconds(30));

        assertThat(requests, hasSize(2));
        // The retry spent all 10; without the refund only a token or two would have trickled back.
        assertEquals(10, budget.tokens());
    }

    private OpenSearchClient clientWithLimit(int max, Duration retryDelay) {
        var limited = new OpenSearchClient_OS_2_11(restClient, failedRequestsLogger, Version.fromString("OS 2.11"),
            CompressionMode.UNCOMPRESSED, 1000, 10_000_000) {
            @Override
            protected Retry getBulkRetryStrategy(BulkRetryPolicy.Batch batch) {
                return Retry.fixedDelay(RETRIES, retryDelay);
            }
        };
        limited.setBulkInFlightLimit(new AdaptiveLimit(max));
        return limited;
    }

    private Mono<OpenSearchClient.BulkResponse> sendWith(OpenSearchClient c, String id) {
        return c.sendBulkRequestRaw(INDEX, List.of(new Document(id, "{}".getBytes(StandardCharsets.UTF_8),
            Document.Operation.UPSERT, Map.of(), Map.of())), null, false, DocumentExceptionAllowlist.empty());
    }

    @Test
    void bulkRequestsInFlightNeverExceedTheLimit() {
        var limited = clientWithLimit(2, Duration.ofMillis(1));
        // The limit starts at 1 and grows by one per success while it is full, so 2 is reached quickly.
        var inFlight = new java.util.concurrent.atomic.AtomicInteger();
        var peak = new java.util.concurrent.atomic.AtomicInteger();
        serve(ids -> Mono.defer(() -> {
            peak.accumulateAndGet(inFlight.incrementAndGet(), Math::max);
            // Counted down before the response reaches the client, which releases its slot as it receives it.
            return allCreated(ids).delayElement(Duration.ofMillis(50)).doOnNext(r -> inFlight.decrementAndGet());
        }));

        reactor.core.publisher.Flux.range(0, 8).flatMap(i -> sendWith(limited, "d" + i), 8)
            .collectList().block(Duration.ofSeconds(30));

        assertThat(requests, hasSize(8));
        assertEquals(2, peak.get());
        assertEquals(0, limited.getBulkInFlightLimit().inFlight());
    }

    @Test
    void aBatchWaitingToRetryHoldsNoSlot() {
        var limited = clientWithLimit(1, Duration.ofMillis(300));
        serve(ids -> ids.equals(List.of("a")) && requests.size() == 1 ? status(503, "busy") : allCreated(ids));

        Mono.zip(sendWith(limited, "a"), sendWith(limited, "b").delaySubscription(Duration.ofMillis(50)))
            .block(Duration.ofSeconds(30));

        // b is sent while a waits to retry, even though only one request may be in flight.
        assertThat(requests, contains(List.of("a"), List.of("b"), List.of("a")));
    }

    @Test
    void throttlesAndTimeoutsAreThrottleSignalsForTheLimit() {
        assertEquals(AdaptiveLimit.Signal.THROTTLED_429,
            OpenSearchClient.limitSignal(new HttpResponse(429, "", Map.of(), "{}")));
        assertEquals(AdaptiveLimit.Signal.ADMISSION_REJECTED, OpenSearchClient.limitSignal(new HttpResponse(200, "", Map.of(),
            BulkRequestGenerator.bulkItemResponse(true, List.of(BulkRequestGenerator.itemEntryRetryableFailure("a"))))));
        assertEquals(AdaptiveLimit.Signal.SUCCESS, OpenSearchClient.limitSignal(new HttpResponse(200, "", Map.of(),
            BulkRequestGenerator.bulkItemResponse(true, List.of(BulkRequestGenerator.itemEntryFailure("a"))))));
        assertEquals(AdaptiveLimit.Signal.OTHER_FAILURE,
            OpenSearchClient.limitSignal(new HttpResponse(503, "", Map.of(), "")));
        assertEquals(AdaptiveLimit.Signal.TIMEOUT, OpenSearchClient.limitSignal(
            new OpenSearchClient.NoResponseException(io.netty.handler.timeout.ReadTimeoutException.INSTANCE)));
        assertEquals(AdaptiveLimit.Signal.OTHER_FAILURE, OpenSearchClient.limitSignal(
            new OpenSearchClient.NoResponseException(new IOException("Connection reset"))));
    }

    @Test
    void documentsMovedPastAreCountedByFailureClass() {
        withStream();
        serve(ids -> ids.contains("big") ? status(413, HTML_413)
            : Mono.just(new HttpResponse(200, "OK", Map.of(), BulkRequestGenerator.bulkItemResponse(true,
                ids.stream().map(id -> id.equals("conflict")
                    ? BulkRequestGenerator.itemEntryFailure(id)
                    : BulkRequestGenerator.itemEntry(id)).toList()))));
        var context = mock(IRfsContexts.IRequestContext.class);

        send(context, "a", "big", "conflict").block();

        verify(context, times(2)).addBulkDocumentsFailed(FailureClass.NON_RETRYABLE.name(), 1);
    }

    @ParameterizedTest
    @ValueSource(ints = {400, 413})
    void aSingleRejectedDocumentIsNotRetried(int code) {
        withStream();
        serve(ids -> status(code, HTML_413));

        send("only").block();

        assertThat(requests, hasSize(1));
        assertThat(recordIds(), contains("only"));
        assertEquals("http_" + code, stream.getRecords().get(0).getFailureType());
        verify(failedRequestsLogger).logBulkFailure(any(), any(), any(), any());
    }

    @Test
    void aSingleRejectedDocumentIsCountedAndMovedPastWithoutAStream() {
        serve(ids -> status(413, HTML_413));
        var context = mock(IRfsContexts.IRequestContext.class);

        // Without a stream nothing is recorded, but the failure is classified and counted the same way.
        send(context, "only").block();

        assertThat(requests, hasSize(1));
        verify(context).addBulkDocumentsFailed(FailureClass.NON_RETRYABLE.name(), 1);
    }

    @Test
    void plainText429StormThenSuccessRetriesTheWholeBatchWithIdenticalBytes() {
        withStream();
        var attempts = new int[1];
        serve(ids -> ++attempts[0] <= 3 ? status(429, "Too Many Requests") : allCreated(ids));

        var response = send("a", "b").block();

        assertThat(requests, everyItem(contains("a", "b")));
        assertThat(requests, hasSize(4));
        for (var body : requestBodies) {
            assertArrayEquals(requestBodies.get(0), body);
        }
        assertThat(stream.getRecords(), empty());
        assertEquals(3, response.requests.retries());
        verify(failedRequestsLogger, never()).logBulkFailure(any(), any(), any(), any());
    }

    @Test
    void aResponseMissingItemsRetriesEveryOperationInsteadOfCountingThemAsSucceeded() {
        withStream();
        var attempts = new int[1];
        serve(ids -> ++attempts[0] == 1
            // Only one item for two operations: positions can't be trusted.
            ? Mono.just(new HttpResponse(200, "OK", Map.of(), BulkRequestGenerator.bulkItemResponse(true,
                List.of(BulkRequestGenerator.itemEntryRetryableFailure(ids.get(0))))))
            : allCreated(ids));

        send("a", "b").block();

        assertEquals(List.of(List.of("a", "b"), List.of("a", "b")), requests);
    }

    @Test
    void anEmptySuccessBodyIsRetriedRatherThanTrusted() {
        withStream();
        var attempts = new int[1];
        serve(ids -> ++attempts[0] == 1 ? Mono.just(new HttpResponse(200, "OK", Map.of(), "")) : allCreated(ids));

        send("a").block();

        assertThat(requests, hasSize(2));
    }

    @Test
    void repeatedNoResponseSplitsTheBatchAndIsolatesTheDocument() {
        withStream();
        serve(ids -> ids.contains("slow")
            ? Mono.error(new TimeoutException("response timeout"))
            : allCreated(ids));

        send("a", "slow", "c", "d").block();

        var split = OpenSearchClient.FAILED_ATTEMPTS_BEFORE_SPLIT;
        var giveUp = OpenSearchClient.MAX_NO_RESPONSE_RETRIES + 1;
        var expected = new ArrayList<List<String>>();
        for (int i = 0; i < split; i++) {
            expected.add(List.of("a", "slow", "c", "d"));
        }
        for (int i = 0; i < split; i++) {
            expected.add(List.of("a", "slow"));
        }
        expected.add(List.of("a"));
        for (int i = 0; i < giveUp; i++) {
            expected.add(List.of("slow"));
        }
        expected.add(List.of("c", "d"));
        assertEquals(expected, requests);
        assertThat(recordIds(), contains("slow"));
        var rec = stream.getRecords().get(0);
        assertEquals(FailureClass.RETRYABLE_EXHAUSTED, rec.getFailureClass());
        assertEquals("no_response", rec.getFailureType());
        assertEquals(TimeoutException.class.getName(), rec.getResponseItem().path("exception").asText());
    }

    @Test
    void aRefusedCredentialStopsTheWorkerAndFailsNoDocument() {
        var real = clientWithTheRealRetryPolicy();
        real.setFailedDocumentStreamContext(stream, "session", "worker");
        serve(ids -> status(401, "Unauthorized"));

        var error = assertThrows(RuntimeException.class, () -> real.sendBulkRequestRaw(INDEX, oneDoc(), null, false,
            DocumentExceptionAllowlist.empty()).block(Duration.ofSeconds(30)));

        assertThat(requests, hasSize(1));
        assertThat(stream.getRecords(), empty());
        assertEquals(OpenSearchClient.TargetAuthenticationFailed.class,
            reactor.core.Exceptions.unwrap(error).getClass());
    }

    @Test
    void aSingleDocumentWithAnUnknownStatusIsGivenUpAfterItsRetries() {
        withStream();
        serve(ids -> ids.contains("bad") ? status(500, "Internal Server Error") : allCreated(ids));

        send("a", "bad").block();

        // Two failures split the batch; "a" goes through alone and "bad" fails 4 times (3 retries) by itself.
        var bad = requests.stream().filter(r -> r.equals(List.of("bad"))).count();
        assertEquals(OpenSearchClient.MAX_UNKNOWN_FAILURE_RETRIES + 1, bad);
        assertThat(recordIds(), contains("bad"));
        assertEquals(FailureClass.RETRYABLE_EXHAUSTED, stream.getRecords().get(0).getFailureClass());
    }

    @Test
    void anItemThatKeepsFailingForAnotherReasonIsGivenUpAfterItsRetries() {
        withStream();
        // Only "stuck" has an item failure; make it a retryable one that isn't a throttle.
        serve(ids -> Mono.just(new HttpResponse(200, "OK", Map.of(), BulkRequestGenerator.bulkItemResponse(true,
            ids.stream().map(id -> id.equals("stuck")
                ? BulkRequestGenerator.itemEntryRetryableFailure(id)
                : BulkRequestGenerator.itemEntry(id)).toList())
            .replace("es_rejected_execution_exception", "illegal_state_exception"))));

        var response = send("a", "stuck").block();

        assertThat(requests, hasSize(OpenSearchClient.MAX_ITEM_FAILURE_RETRIES + 1));
        assertThat(recordIds(), contains("stuck"));
        assertEquals(FailureClass.RETRYABLE_EXHAUSTED, stream.getRecords().get(0).getFailureClass());
        assertEquals("illegal_state_exception", stream.getRecords().get(0).getFailureType());
        assertEquals(2, response.target.upserts());
    }

    @Test
    void anItemThatKeepsFailingWithoutAStreamIsCountedAndMovedPast() {
        serve(ids -> Mono.just(new HttpResponse(200, "OK", Map.of(), BulkRequestGenerator.bulkItemResponse(true,
            ids.stream().map(id -> id.equals("stuck")
                ? BulkRequestGenerator.itemEntryRetryableFailure(id)
                : BulkRequestGenerator.itemEntry(id)).toList())
            .replace("es_rejected_execution_exception", "illegal_state_exception"))));
        var context = mock(IRfsContexts.IRequestContext.class);

        // Disabling the stream changes only whether the failure is kept, not how it is classified or counted.
        send(context, "a", "stuck").block();

        assertThat(requests, hasSize(OpenSearchClient.MAX_ITEM_FAILURE_RETRIES + 1));
        verify(context).addBulkDocumentsFailed(FailureClass.RETRYABLE_EXHAUSTED.name(), 1);
    }

    @Test
    void everyTerminalFailureIsInTheResponseOnceByClassIncludingThoseBeforeASplit() {
        withStream();
        // The first attempt rejects "conflict" and throttles "slow" and "d"; after that, any request carrying "slow"
        // gets no response, so the batch splits and "slow", alone, is given up.
        var answers = new java.util.concurrent.atomic.AtomicInteger();
        serve(ids -> {
            if (answers.getAndIncrement() == 0) {
                return Mono.just(new HttpResponse(200, "OK", Map.of(), BulkRequestGenerator.bulkItemResponse(true,
                    ids.stream().map(id -> switch (id) {
                        case "conflict" -> BulkRequestGenerator.itemEntryFailure(id);
                        case "slow", "d" -> BulkRequestGenerator.itemEntryRetryableFailure(id);
                        default -> BulkRequestGenerator.itemEntry(id);
                    }).toList())));
            }
            return ids.contains("slow") ? Mono.error(new TimeoutException("response timeout")) : allCreated(ids);
        });

        var response = send("a", "conflict", "slow", "d").block();

        assertThat(requests, org.hamcrest.Matchers.hasItem(List.of("slow", "d")));
        assertThat(recordIds(), org.hamcrest.Matchers.containsInAnyOrder("conflict", "slow"));
        assertEquals(new org.opensearch.migrations.bulkload.pipeline.model.MigrationMetrics.Failures(1, 1),
            response.failures);
    }

    @Test
    void aWholeRequestGiveUpIsInTheResponseFailures() {
        withStream();
        serve(ids -> status(413, HTML_413));

        var response = send("only").block();

        assertEquals(new org.opensearch.migrations.bulkload.pipeline.model.MigrationMetrics.Failures(1, 0),
            response.failures);
    }

    @Test
    void timeBetweenAttemptsIsReported() {
        withStream();
        var gaps = new CopyOnWriteArrayList<Long>();
        client.setBulkFlowMetrics(new org.opensearch.migrations.bulkload.common.retry.BulkFlowMetrics() {
            @Override
            public void onAttemptGap(long millis) {
                gaps.add(millis);
            }
        });
        // Two throttles, then success: two retries, each sent some time after the previous attempt ended.
        var answers = new java.util.concurrent.atomic.AtomicInteger();
        serve(ids -> answers.getAndIncrement() < 2 ? status(429, "Too Many Requests") : allCreated(ids));
        send("a").block();
        assertEquals(2, gaps.size(), gaps.toString());
        assertTrue(gaps.stream().allMatch(g -> g >= 0), gaps.toString());
    }

    @Test
    void aBatchsOwnThrottleIsNotEvidenceAgainstItsNextFailure() {
        withStream();
        // The batch's own 429 ends its streak; it isn't another request's throttle, so the 500s after it all count.
        var answers = new java.util.concurrent.atomic.AtomicInteger();
        serve(ids -> answers.getAndIncrement() == 1 ? status(429, "Too Many Requests")
            : status(500, "Internal Server Error"));

        send("bad").block();

        assertThat(requests, hasSize(1 + 1 + OpenSearchClient.MAX_UNKNOWN_FAILURE_RETRIES + 1));
        assertThat(recordIds(), contains("bad"));
    }

    @Test
    void unavailableItemsAreNeverGivenUp() {
        withStream();
        serve(ids -> Mono.just(new HttpResponse(200, "OK", Map.of(), BulkRequestGenerator.bulkItemResponse(true,
            ids.stream().map(id -> id.equals("stuck")
                ? BulkRequestGenerator.itemEntryRetryableFailure(id)
                : BulkRequestGenerator.itemEntry(id)).toList())
            .replace("es_rejected_execution_exception", "unavailable_shards_exception"))));

        // The shard isn't ready: the document waits for it however long that takes (here, until the schedule ends).
        assertThrows(RuntimeException.class, () -> send("a", "stuck").block());

        assertThat(requests, hasSize(RETRIES + 1));
        assertThat(stream.getRecords(), empty());
    }

    @Test
    void anItemForAMissingIndexIsRecordedAtOnce() {
        withStream();
        serve(ids -> Mono.just(new HttpResponse(200, "OK", Map.of(), BulkRequestGenerator.bulkItemResponse(true,
            ids.stream().map(BulkRequestGenerator::itemEntryRetryableFailure).toList())
            .replace("es_rejected_execution_exception", "index_not_found_exception"))));

        send("lost").block();

        assertThat(requests, hasSize(1));
        assertThat(recordIds(), contains("lost"));
        assertEquals(FailureClass.NON_RETRYABLE, stream.getRecords().get(0).getFailureClass());
    }

    @Test
    void blockedWritesAreRetriedNotTakenForRefusedCredentials() {
        // The real policy, which stops on refused credentials (this class's fixed schedule retries anything).
        var real = clientWithTheRealRetryPolicy();
        real.setFailedDocumentStreamContext(stream, "session", "worker");
        var answers = new java.util.concurrent.atomic.AtomicInteger();
        serve(ids -> answers.getAndIncrement() < 2
            ? status(403, "{\"error\":{\"type\":\"cluster_block_exception\",\"reason\":\"index [idx] blocked by: "
                + "[FORBIDDEN/8/index write (api)];\"},\"status\":403}")
            : allCreated(ids));

        real.sendBulkRequestRaw(INDEX, oneDoc(), null, false, DocumentExceptionAllowlist.empty())
            .block(Duration.ofSeconds(30));

        assertThat(requests, hasSize(3));
        assertThat(stream.getRecords(), empty());
    }

    @Test
    void aLoneDocumentsFailureCountsOnlyIfTheTargetAnswersAProbe() {
        withStream();
        serve(ids -> status(500, "Internal Server Error"));
        var probes = new java.util.concurrent.atomic.AtomicInteger();
        client.setTargetProbe(() -> {
            probes.incrementAndGet();
            return Mono.just(false);
        });

        // No other requests, and the target doesn't answer a probe: it may be the target, so nothing counts.
        assertThrows(RuntimeException.class, () -> send("bad").block());

        assertThat(requests, hasSize(RETRIES + 1));
        assertEquals(RETRIES + 1, probes.get());
        assertThat(stream.getRecords(), empty());
    }

    @Test
    void throttledItemsAreNeverGivenUp() {
        withStream();
        serve(ids -> Mono.just(new HttpResponse(200, "OK", Map.of(), BulkRequestGenerator.bulkItemResponse(true,
            ids.stream().map(id -> id.equals("stuck")
                ? BulkRequestGenerator.itemEntryRetryableFailure(id)
                : BulkRequestGenerator.itemEntry(id)).toList()))));

        // Only this test's limited schedule ends it, and without a counted give-up that fails the work item.
        assertThrows(RuntimeException.class, () -> send("a", "stuck").block());

        assertThat(requests, hasSize(RETRIES + 1));
        assertThat(stream.getRecords(), empty());
    }

    @Test
    void retryableItemFailuresThatNeverClearFailTheBatchWithoutAStream() {
        serve(ids -> Mono.just(new HttpResponse(200, "OK", Map.of(), BulkRequestGenerator.bulkItemResponse(true,
            ids.stream().map(BulkRequestGenerator::itemEntryRetryableFailure).toList()))));

        var error = assertThrows(RuntimeException.class, () -> send("stuck").block());

        assertThat(error.getMessage(), containsString("Retries exhausted"));
        verify(failedRequestsLogger).logBulkFailure(any(), any(), any(), any());
    }

    @Test
    void a429StormIsNeverRecordedEvenWhenOtherRequestsAreAccepted() {
        withStream();
        serve(ids -> ids.contains("hot") ? status(429, "Too Many Requests") : allCreated(ids));

        // The first batch is accepted, so the target is up, while the second keeps getting 429s.
        var docs = List.of("a", "hot").stream()
            .map(id -> new Document(id, "{}".getBytes(StandardCharsets.UTF_8), Document.Operation.UPSERT,
                Map.of(), Map.of()))
            .collect(Collectors.toList());
        var oneDocPerRequest = new OpenSearchClient_OS_2_11(restClient, failedRequestsLogger,
            Version.fromString("OS 2.11"), CompressionMode.UNCOMPRESSED, 1, 10_000_000) {
            @Override
            protected Retry getBulkRetryStrategy(BulkRetryPolicy.Batch batch) {
                return Retry.fixedDelay(RETRIES, Duration.ofMillis(1));
            }
        };
        oneDocPerRequest.setFailedDocumentStreamContext(stream, "session", "worker");

        assertThrows(RuntimeException.class, () -> oneDocPerRequest.sendBulkRequestRaw(INDEX, docs, null, false,
            DocumentExceptionAllowlist.empty()).block());

        assertThat(stream.getRecords(), empty());
    }
}
