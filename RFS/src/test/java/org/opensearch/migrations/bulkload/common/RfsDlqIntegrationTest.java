package org.opensearch.migrations.bulkload.common;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.zip.GZIPInputStream;

import org.opensearch.migrations.Version;
import org.opensearch.migrations.bulkload.common.bulk.BulkOperationSpec;
import org.opensearch.migrations.bulkload.common.bulk.IndexOp;
import org.opensearch.migrations.bulkload.common.bulk.enums.OperationType;
import org.opensearch.migrations.bulkload.common.bulk.operations.IndexOperationMeta;
import org.opensearch.migrations.bulkload.common.http.CompressionMode;
import org.opensearch.migrations.bulkload.common.http.ConnectionContext;
import org.opensearch.migrations.bulkload.common.http.HttpResponse;
import org.opensearch.migrations.bulkload.tracing.IRfsContexts;
import org.opensearch.migrations.bulkload.version_os_2_11.OpenSearchClient_OS_2_11;
import org.opensearch.migrations.reindexer.FailedRequestsLogger;
import org.opensearch.migrations.reindexer.dlq.S3DlqSink;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Mock.Strictness;
import org.mockito.junit.jupiter.MockitoExtension;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;
import software.amazon.awssdk.core.async.AsyncRequestBody;
import software.amazon.awssdk.services.s3.S3AsyncClient;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

/**
 * Acceptance-criteria integration test for issue #2975.
 *
 * <p>Drives the real {@code OpenSearchClient.executeBulkWithRetry} loop with a mock
 * RestClient that returns a hand-crafted sequence of bulk responses, and a real
 * {@link S3DlqSink} backed by a mock {@code S3AsyncClient} that captures NDJSON.gz
 * uploads. Then reads the captured S3 objects back.
 *
 * <p>Sub-criterion → assertion mapping for
 * {@link #forcesFailures_andDlqMatches_andSessionsAreIsolated()}:
 *
 * <ol>
 *   <li><b>Successful documents still migrate.</b> Per-attempt bulk-request bodies
 *       are captured via {@link ArgumentCaptor}: the first attempt contains
 *       {@code ok-1} (and {@code ok-2}), and subsequent retry attempts contain
 *       <i>only</i> {@code throttled} — positive proof that ok-1's success ack
 *       was honored and the doc was not re-sent.</li>
 *   <li><b>Failed documents are persisted to the DLQ.</b> The captured S3 bytes
 *       are decoded and asserted to contain {@code bad-map} (NON_RETRYABLE) and
 *       {@code throttled} (RETRYABLE_EXHAUSTED) with the correct
 *       {@code failureClass} / {@code failureType}.</li>
 *   <li><b>Reported failure count matches DLQ contents.</b>
 *       {@code assertThat(sessionARecords, hasSize(reportedTerminalFailures))}
 *       where {@code reportedTerminalFailures = 2} is the number of forced
 *       terminal failures — i.e. record count == failure count, no double-count
 *       and no drops.</li>
 *   <li><b>Records from a prior run are not incorrectly reported for a later
 *       run.</b> Session B runs after A with a different session id; A's prefix
 *       is re-read and still has only A's two records, B's prefix has only B's
 *       one record, and a cross-check confirms B's records never reference A's
 *       doc ids.</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
class RfsDlqIntegrationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Mock(strictness = Strictness.LENIENT)
    RestClient restClient;

    @Mock(strictness = Strictness.LENIENT)
    ConnectionContext connectionContext;

    @Test
    void forcesFailures_andDlqMatches_andSessionsAreIsolated() throws Exception {
        when(connectionContext.getUri()).thenReturn(URI.create("http://localhost/"));
        when(restClient.getConnectionContext()).thenReturn(connectionContext);

        // ─── Session A: drive one bulk with all four flavors of items ───────────────
        // Doc identifiers cover the three buckets:
        //   "ok-1"      → success (server result=created)
        //   "ok-2"      → allowed exception (version_conflict_engine_exception, in allowlist)
        //   "bad-map"   → non-retryable (mapper_parsing_exception)
        //   "throttled" → retryable (es_rejected_execution_exception), fails on every retry
        //
        // First response: ok-1 succeeds, ok-2 is allowlisted-success, bad-map non-retry,
        //                 throttled is the lone retryable failure remaining.
        // Second response onward: only "throttled" remains in pendingOps, and it keeps
        //                 failing with the same retryable type until retries are exhausted.
        var firstResponse = buildBulkResponse(List.of(
            successItem("ok-1"),
            allowlistedItem("ok-2"),
            nonRetryableItem("bad-map"),
            retryableItem("throttled")
        ));
        var subsequentResponse = buildBulkResponse(List.of(retryableItem("throttled")));

        when(restClient.postAsyncBytes(any(), any(), any(), any()))
            .thenReturn(Mono.just(new HttpResponse(200, "", null, firstResponse)))
            .thenReturn(Mono.just(new HttpResponse(200, "", null, subsequentResponse)));

        // Capture every S3 PutObject so we can reconstruct what was persisted.
        var s3Captured = new S3Capture();
        var s3Client = s3Captured.mockClient();

        // Real S3DlqSink against the captured client — same code path as production.
        var dlqSinkA = S3DlqSink.builder()
            .s3Client(s3Client)
            .bucket("rfs-bucket")
            .prefix("rfs-dlq/")
            .sessionId("session-A")
            .workerId("worker-1")
            .policy(S3DlqSink.RotationPolicy.onePerFlush())
            .build();

        var allowlist = new DocumentExceptionAllowlist(Set.of("version_conflict_engine_exception"));
        var failedRequestsLogger = mock(FailedRequestsLogger.class);
        var openSearchClient = spy(new OpenSearchClient_OS_2_11(
            restClient, failedRequestsLogger, Version.fromString("OS 2.11"), CompressionMode.UNCOMPRESSED));
        openSearchClient.setDlqContext(dlqSinkA, "session-A", "worker-1");
        // Speed up the test: 2 retries, 1 ms backoff — enough to exhaust retries on "throttled".
        doReturn(Retry.fixedDelay(2, Duration.ofMillis(1))).when(openSearchClient).getBulkRetryStrategy();

        var bulkDocs = List.of(
            createBulkDoc("ok-1"),
            createBulkDoc("ok-2"),
            createBulkDoc("bad-map"),
            createBulkDoc("throttled")
        );

        // The bulk eventually fails because "throttled" can't recover.
        assertThrows(Exception.class, () -> openSearchClient.sendBulkRequest(
            "movies",
            bulkDocs,
            mock(IRfsContexts.IRequestContext.class),
            false,
            allowlist
        ).block());

        // Mirror the workflow's lease-completion contract: flush before "completing" the work.
        dlqSinkA.flush().block();
        dlqSinkA.close();

        // ─── Sub-criterion 1: successful documents still migrate ────────────────────
        // Positive proof: capture every bulk request body and verify that
        //   - the first attempt sent all four docs (ok-1, ok-2, bad-map, throttled), and
        //   - subsequent retry attempts sent only "throttled".
        // i.e., the cluster's success ack for ok-1 was honored and the doc was NOT
        // re-sent on retry — together with the negative assertion below that ok-1
        // never lands in the DLQ, that's end-to-end "successful docs migrate".
        ArgumentCaptor<byte[]> bodyCaptor = ArgumentCaptor.forClass(byte[].class);
        verify(restClient, atLeast(2)).postAsyncBytes(any(), bodyCaptor.capture(), any(), any());
        var capturedBodies = bodyCaptor.getAllValues();
        var firstAttemptIds = extractDocIds(capturedBodies.get(0));
        assertThat("first attempt should send all four docs", firstAttemptIds,
            containsInAnyOrder("ok-1", "ok-2", "bad-map", "throttled"));
        for (int i = 1; i < capturedBodies.size(); i++) {
            var retryIds = extractDocIds(capturedBodies.get(i));
            assertThat("retry attempt " + i + " must contain only 'throttled' "
                + "(ok-1 + ok-2 + bad-map dropped from the retry loop)",
                retryIds, contains("throttled"));
        }

        // ─── Assertions for session A ───────────────────────────────────────────────
        var sessionAObjects = s3Captured.objectsUnder("rfs-dlq/session=session-A/");
        assertThat("DLQ wrote at least one object for session A", sessionAObjects.size(),
            greaterThanOrEqualTo(1));

        var sessionARecords = sessionAObjects.stream()
            .flatMap(o -> decode(o.bytes).stream())
            .toList();

        // Successful and allowlisted docs are NOT in the DLQ.
        var dlqDocIds = sessionARecords.stream().map(r -> r.path("documentId").asText()).toList();
        assertThat(dlqDocIds, not(contains("ok-1")));
        assertThat(dlqDocIds, not(contains("ok-2")));

        // Terminal failures ARE in the DLQ, exactly once each.
        assertThat(dlqDocIds, containsInAnyOrder("bad-map", "throttled"));

        // Each failure carries the right classification.
        var byId = recordsById(sessionARecords);
        assertThat(byId.get("bad-map").path("failureClass").asText(), equalTo("NON_RETRYABLE"));
        assertThat(byId.get("bad-map").path("failureType").asText(), equalTo("mapper_parsing_exception"));
        assertThat(byId.get("throttled").path("failureClass").asText(), equalTo("RETRYABLE_EXHAUSTED"));
        assertThat(byId.get("throttled").path("failureType").asText(), equalTo("es_rejected_execution_exception"));

        // Every record carries the session/worker context.
        for (var rec : sessionARecords) {
            assertThat(rec.path("sessionId").asText(), equalTo("session-A"));
            assertThat(rec.path("workerId").asText(), equalTo("worker-1"));
            assertThat(rec.path("targetIndex").asText(), equalTo("movies"));
            assertThat(rec.path("timestamp").asText(), not(equalTo("")));
        }

        // Reported failure count matches DLQ contents: we forced exactly 2 terminal failures.
        int reportedTerminalFailures = 2;
        assertThat(sessionARecords, hasSize(reportedTerminalFailures));

        // The customer-visible location matches the prefix we expect.
        assertThat(dlqSinkA.getLocation(), equalTo("s3://rfs-bucket/rfs-dlq/session=session-A/"));

        // ─── Session B: rerun with a new session id ─────────────────────────────────
        // Reset the response queue: a different doc fails this time so we can prove
        // session B's prefix has B's records and NOT A's.
        var sessionBResponse = buildBulkResponse(List.of(
            successItem("ok-3"),
            nonRetryableItem("new-bad")
        ));
        when(restClient.postAsyncBytes(any(), any(), any(), any()))
            .thenReturn(Mono.just(new HttpResponse(200, "", null, sessionBResponse)));

        var dlqSinkB = S3DlqSink.builder()
            .s3Client(s3Client)
            .bucket("rfs-bucket")
            .prefix("rfs-dlq/")
            .sessionId("session-B")
            .workerId("worker-1")
            .policy(S3DlqSink.RotationPolicy.onePerFlush())
            .build();
        openSearchClient.setDlqContext(dlqSinkB, "session-B", "worker-1");

        openSearchClient.sendBulkRequest(
            "movies",
            List.of(createBulkDoc("ok-3"), createBulkDoc("new-bad")),
            mock(IRfsContexts.IRequestContext.class),
            false,
            DocumentExceptionAllowlist.empty()
        ).block();
        dlqSinkB.flush().block();
        dlqSinkB.close();

        // Session A's prefix still contains only the original two records — no bleed.
        var sessionAAfter = s3Captured.objectsUnder("rfs-dlq/session=session-A/").stream()
            .flatMap(o -> decode(o.bytes).stream())
            .toList();
        assertThat(sessionAAfter, hasSize(reportedTerminalFailures));
        assertThat(sessionAAfter.stream().map(r -> r.path("documentId").asText()).toList(),
            containsInAnyOrder("bad-map", "throttled"));

        // Session B's prefix has exactly its one new failure.
        var sessionBRecords = s3Captured.objectsUnder("rfs-dlq/session=session-B/").stream()
            .flatMap(o -> decode(o.bytes).stream())
            .toList();
        assertThat(sessionBRecords, hasSize(1));
        assertThat(sessionBRecords.get(0).path("documentId").asText(), equalTo("new-bad"));
        assertThat(sessionBRecords.get(0).path("sessionId").asText(), equalTo("session-B"));

        // And session B's records never reference session A's doc ids.
        for (var rec : sessionBRecords) {
            String id = rec.path("documentId").asText();
            assertThat(id, not(equalTo("bad-map")));
            assertThat(id, not(equalTo("throttled")));
        }
    }

    /**
     * Regression test for the acceptance criterion that allowlisted exception
     * types are excluded from DLQ records and failure counts, specifically in
     * the retry-exhaust path.
     *
     * <p>Constructs a worst-case position-misalignment scenario: a single
     * attempt (0 retries) whose response interleaves an allowlisted item at
     * position 0 with a retryable failure at position 1. The position 0 entry
     * is dropped by {@code compactPendingDocs}, so the post-compaction
     * {@code pendingOps} has one item at index 0 — which corresponds to
     * response position 1, not 0. A naive position lookup in
     * {@code emitRetryExhaustedToDlq} would mis-attribute the allowlisted
     * item's {@code failureType} to the surviving retryable doc; this test
     * asserts that doesn't happen.
     */
    @Test
    void allowlistedExceptionsAreNeverWrittenToDlq_evenOnRetryExhaust() throws Exception {
        when(connectionContext.getUri()).thenReturn(URI.create("http://localhost/"));
        when(restClient.getConnectionContext()).thenReturn(connectionContext);

        // One response containing both an allowlisted item and a retryable failure.
        // The allowlisted item is at position 0 (before the retryable failure)
        // so the post-compaction pendingOps will have a different size than the
        // response — exactly the position-shift case we care about.
        var response = buildBulkResponse(List.of(
            allowlistedItem("dup-1"),
            retryableItem("throttled-1")
        ));
        when(restClient.postAsyncBytes(any(), any(), any(), any()))
            .thenReturn(Mono.just(new HttpResponse(200, "", null, response)));

        var s3Captured = new S3Capture();
        var dlqSink = S3DlqSink.builder()
            .s3Client(s3Captured.mockClient())
            .bucket("rfs-bucket")
            .prefix("rfs-dlq/")
            .sessionId("session-exhaust")
            .workerId("worker-1")
            .policy(S3DlqSink.RotationPolicy.onePerFlush())
            .build();

        var allowlist = new DocumentExceptionAllowlist(Set.of("version_conflict_engine_exception"));
        var failedRequestsLogger = mock(FailedRequestsLogger.class);
        var openSearchClient = spy(new OpenSearchClient_OS_2_11(
            restClient, failedRequestsLogger, Version.fromString("OS 2.11"), CompressionMode.UNCOMPRESSED));
        openSearchClient.setDlqContext(dlqSink, "session-exhaust", "worker-1");
        // 0 retries: any error from attempt 1 exhausts immediately, taking the
        // doOnError → emitRetryExhaustedToDlq path with the original response
        // (which still contains the allowlisted item at position 0).
        doReturn(Retry.fixedDelay(0, Duration.ofMillis(1))).when(openSearchClient).getBulkRetryStrategy();

        assertThrows(Exception.class, () -> openSearchClient.sendBulkRequest(
            "movies",
            List.of(createBulkDoc("dup-1"), createBulkDoc("throttled-1")),
            mock(IRfsContexts.IRequestContext.class),
            false,
            allowlist
        ).block());
        dlqSink.flush().block();
        dlqSink.close();

        var records = s3Captured.objectsUnder("rfs-dlq/session=session-exhaust/").stream()
            .flatMap(o -> decode(o.bytes).stream())
            .toList();

        // Failure count: exactly one record (the retryable doc). The allowlisted
        // doc must not contribute to the count.
        assertThat("Only the retryable doc should be in the DLQ", records, hasSize(1));

        var rec = records.get(0);
        assertThat(rec.path("documentId").asText(), equalTo("throttled-1"));
        assertThat(rec.path("failureClass").asText(), equalTo("RETRYABLE_EXHAUSTED"));

        // Critical assertion: no allowlisted exception type appears anywhere in
        // the DLQ records — neither as the docId of an allowlisted item nor as
        // the failureType attached to a real failure (the position-shift bug).
        for (var r : records) {
            assertThat(r.path("documentId").asText(), not(equalTo("dup-1")));
            assertThat(r.path("failureType").asText(),
                not(equalTo("version_conflict_engine_exception")));
        }
    }


    // ─── Helpers ────────────────────────────────────────────────────────────────────

    private static String buildBulkResponse(List<String> items) {
        return "{\"took\":1,\"errors\":true,\"items\":[" + String.join(",", items) + "]}";
    }

    private static String successItem(String id) {
        return "{\"index\":{\"_index\":\"movies\",\"_id\":\"" + id
            + "\",\"result\":\"created\",\"status\":201}}";
    }

    private static String allowlistedItem(String id) {
        return "{\"index\":{\"_index\":\"movies\",\"_id\":\"" + id + "\",\"status\":409,"
            + "\"error\":{\"type\":\"version_conflict_engine_exception\",\"reason\":\"dup\"}}}";
    }

    private static String nonRetryableItem(String id) {
        return "{\"index\":{\"_index\":\"movies\",\"_id\":\"" + id + "\",\"status\":400,"
            + "\"error\":{\"type\":\"mapper_parsing_exception\",\"reason\":\"bad\"}}}";
    }

    private static String retryableItem(String id) {
        return "{\"index\":{\"_index\":\"movies\",\"_id\":\"" + id + "\",\"status\":429,"
            + "\"error\":{\"type\":\"es_rejected_execution_exception\",\"reason\":\"queue full\"}}}";
    }

    private static BulkOperationSpec createBulkDoc(String docId) {
        var bulkDoc = mock(IndexOp.class, withSettings().strictness(org.mockito.quality.Strictness.LENIENT));
        var operation = mock(IndexOperationMeta.class);
        when(operation.getId()).thenReturn(docId);
        when(bulkDoc.getOperation()).thenReturn(operation);
        when(bulkDoc.getOperationType()).thenReturn(OperationType.INDEX);
        when(bulkDoc.isIncludeDocument()).thenReturn(true);
        when(bulkDoc.getDocument()).thenReturn(Map.of("field", "value"));
        return bulkDoc;
    }

    private static List<JsonNode> decode(byte[] gz) {
        try (var stream = new GZIPInputStream(new ByteArrayInputStream(gz))) {
            var body = new String(stream.readAllBytes());
            var out = new ArrayList<JsonNode>();
            for (var line : body.split("\n")) {
                line = line.strip();
                if (line.isEmpty()) continue;
                out.add(MAPPER.readTree(line));
            }
            return out;
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static Map<String, JsonNode> recordsById(List<JsonNode> records) {
        var out = new java.util.HashMap<String, JsonNode>();
        for (var r : records) {
            out.put(r.path("documentId").asText(), r);
        }
        return out;
    }

    /**
     * Extract the document ids from a captured bulk-API request body. Bulk NDJSON
     * alternates action lines ({@code {"index":{"_index":"...","_id":"..."}}}) and
     * source lines; we read every other line as the action and pull {@code _id}.
     */
    private static List<String> extractDocIds(byte[] body) {
        var ids = new ArrayList<String>();
        var lines = new String(body).split("\n");
        for (int i = 0; i < lines.length; i += 2) {
            var line = lines[i].strip();
            if (line.isEmpty()) continue;
            try {
                var node = MAPPER.readTree(line);
                // Each action line is an object with a single op key (index/create/...).
                var entry = node.fields().next();
                var id = entry.getValue().path("_id").asText(null);
                if (id != null) ids.add(id);
            } catch (IOException e) {
                throw new RuntimeException("Failed to parse bulk action line: " + line, e);
            }
        }
        return ids;
    }

    private static <T> org.hamcrest.Matcher<T> not(org.hamcrest.Matcher<T> matcher) {
        return org.hamcrest.core.IsNot.not(matcher);
    }

    /** Holds the bytes uploaded to S3, keyed by the requested S3 key. */
    private static class S3Capture {
        private final List<CapturedObject> objects = new ArrayList<>();

        S3AsyncClient mockClient() {
            var s3 = mock(S3AsyncClient.class);
            when(s3.putObject(any(PutObjectRequest.class), any(AsyncRequestBody.class)))
                .thenAnswer(invocation -> {
                    PutObjectRequest req = invocation.getArgument(0);
                    AsyncRequestBody body = invocation.getArgument(1);
                    var future = new CompletableFuture<PutObjectResponse>();
                    body.subscribe(new Subscriber<ByteBuffer>() {
                        private final java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
                        @Override public void onSubscribe(Subscription s) { s.request(Long.MAX_VALUE); }
                        @Override public void onNext(ByteBuffer bb) {
                            byte[] arr = new byte[bb.remaining()];
                            bb.get(arr);
                            baos.write(arr, 0, arr.length);
                        }
                        @Override public void onError(Throwable t) { future.completeExceptionally(t); }
                        @Override public void onComplete() {
                            synchronized (objects) {
                                objects.add(new CapturedObject(req.key(), baos.toByteArray()));
                            }
                            future.complete(PutObjectResponse.builder().build());
                        }
                    });
                    return future;
                });
            return s3;
        }

        List<CapturedObject> objectsUnder(String prefix) {
            synchronized (objects) {
                var out = new ArrayList<CapturedObject>();
                for (var o : objects) {
                    if (o.key.startsWith(prefix)) out.add(o);
                }
                return out;
            }
        }
    }

    private record CapturedObject(String key, byte[] bytes) {}
}
