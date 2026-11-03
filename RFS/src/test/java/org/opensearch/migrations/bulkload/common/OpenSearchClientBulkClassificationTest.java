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
    private static final int RETRIES = 5;

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
            protected Retry getBulkRetryStrategy() {
                return Retry.fixedDelay(RETRIES, Duration.ofMillis(1));
            }
        };
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
    void aSingleRejectedDocumentFailsTheBatchWithoutAStream() {
        serve(ids -> status(413, HTML_413));

        var error = assertThrows(RuntimeException.class, () -> send("only").block());

        assertThat(requests, hasSize(1));
        assertThat(error.getMessage(), containsString("413"));
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

        var split = OpenSearchClient.NO_RESPONSE_ATTEMPTS_BEFORE_SPLIT;
        var giveUp = OpenSearchClient.NO_RESPONSE_ATTEMPTS_BEFORE_EXHAUSTION;
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
    void noResponseWhileTheTargetAcceptsNothingFailsInsteadOfFillingTheStream() {
        withStream();
        serve(ids -> Mono.error(new IOException("Connection reset by peer")));

        assertThrows(RuntimeException.class, () -> send("a").block());

        assertThat(requests, hasSize(OpenSearchClient.NO_RESPONSE_ATTEMPTS_BEFORE_EXHAUSTION));
        assertThat(stream.getRecords(), empty());
    }

    @Test
    void a5xxStormWhileTheTargetAcceptsNothingFailsInsteadOfFillingTheStream() {
        withStream();
        serve(ids -> status(503, "Service Unavailable"));

        assertThrows(RuntimeException.class, () -> send("a", "b").block());

        assertThat(requests, hasSize(RETRIES + 1));
        assertThat(stream.getRecords(), empty());
    }

    @Test
    void retryableItemFailuresThatNeverClearAreRecordedAndTheBatchCompletes() {
        withStream();
        serve(ids -> Mono.just(new HttpResponse(200, "OK", Map.of(), BulkRequestGenerator.bulkItemResponse(true,
            ids.stream().map(id -> id.equals("stuck")
                ? BulkRequestGenerator.itemEntryRetryableFailure(id)
                : BulkRequestGenerator.itemEntry(id)).toList()))));

        var response = send("a", "stuck").block();

        assertThat(requests, hasSize(RETRIES + 1));
        assertThat(recordIds(), contains("stuck"));
        assertEquals(FailureClass.RETRYABLE_EXHAUSTED, stream.getRecords().get(0).getFailureClass());
        assertEquals("es_rejected_execution_exception", stream.getRecords().get(0).getFailureType());
        assertEquals(2, response.target.upserts());
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
    void a429StormIsRecordedAfterExhaustionWhenOtherRequestsAreAccepted() {
        withStream();
        serve(ids -> ids.contains("hot") ? status(429, "Too Many Requests") : allCreated(ids));

        // The first batch is accepted, so the target is known to be up while the second keeps getting 429s.
        var docs = List.of("a", "hot").stream()
            .map(id -> new Document(id, "{}".getBytes(StandardCharsets.UTF_8), Document.Operation.UPSERT,
                Map.of(), Map.of()))
            .collect(Collectors.toList());
        var oneDocPerRequest = new OpenSearchClient_OS_2_11(restClient, failedRequestsLogger,
            Version.fromString("OS 2.11"), CompressionMode.UNCOMPRESSED, 1, 10_000_000) {
            @Override
            protected Retry getBulkRetryStrategy() {
                return Retry.fixedDelay(RETRIES, Duration.ofMillis(1));
            }
        };
        oneDocPerRequest.setFailedDocumentStreamContext(stream, "session", "worker");

        oneDocPerRequest.sendBulkRequestRaw(INDEX, docs, null, false, DocumentExceptionAllowlist.empty()).block();

        assertThat(recordIds(), contains("hot"));
        assertEquals("http_429", stream.getRecords().get(0).getFailureType());
        assertEquals(FailureClass.RETRYABLE_EXHAUSTED, stream.getRecords().get(0).getFailureClass());
    }
}
