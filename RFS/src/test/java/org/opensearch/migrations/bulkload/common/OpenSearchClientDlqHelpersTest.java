package org.opensearch.migrations.bulkload.common;

import java.net.URI;

import org.opensearch.migrations.Version;
import org.opensearch.migrations.bulkload.common.bulk.IndexOp;
import org.opensearch.migrations.bulkload.common.bulk.operations.IndexOperationMeta;
import org.opensearch.migrations.bulkload.common.http.CompressionMode;
import org.opensearch.migrations.bulkload.common.http.ConnectionContext;
import org.opensearch.migrations.bulkload.version_os_2_11.OpenSearchClient_OS_2_11;
import org.opensearch.migrations.parsing.BulkResponseParser;
import org.opensearch.migrations.reindexer.FailedRequestsLogger;
import org.opensearch.migrations.reindexer.dlq.DlqRecord;
import org.opensearch.migrations.reindexer.dlq.DlqSink;
import org.opensearch.migrations.reindexer.dlq.FailureClass;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Mock.Strictness;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Covers the package-private DLQ helpers on {@link OpenSearchClient}:
 * {@link OpenSearchClient#emitDlqRecord} and
 * {@link OpenSearchClient#extractDocumentId}. The end-to-end DLQ flow has
 * separate coverage in {@link RfsDlqIntegrationTest}; these tests focus on
 * the branchy edge cases (null op, null failure, allowlist fallback, malformed
 * responseItemJson) that are awkward to drive through the integration path.
 */
@ExtendWith(MockitoExtension.class)
class OpenSearchClientDlqHelpersTest {

    @Mock(strictness = Strictness.LENIENT)
    RestClient restClient;

    @Mock(strictness = Strictness.LENIENT)
    ConnectionContext connectionContext;

    @Mock
    FailedRequestsLogger failedRequestLogger;

    @Mock
    DlqSink dlqSink;

    OpenSearchClient client;

    @BeforeEach
    void setUp() {
        when(connectionContext.getUri()).thenReturn(URI.create("http://localhost/"));
        when(restClient.getConnectionContext()).thenReturn(connectionContext);
        client = new OpenSearchClient_OS_2_11(restClient, failedRequestLogger,
            Version.fromString("OS 2.11"), CompressionMode.UNCOMPRESSED);
    }

    private static IndexOp indexOpWithId(String id) {
        return IndexOp.builder()
            .operation(IndexOperationMeta.builder().id(id).build())
            .build();
    }

    // ---- emitDlqRecord ---------------------------------------------------

    @Test
    void emitDlqRecord_noSinkConfigured_isNoOp() {
        // dlqSink not installed via setDlqContext — the helper must short-circuit.
        client.emitDlqRecord("idx", indexOpWithId("d-1"), null, FailureClass.NON_RETRYABLE);
        verify(dlqSink, never()).write(any());
    }

    @Test
    void emitDlqRecord_passesIdSessionWorkerAndIndexThrough() {
        when(dlqSink.write(any())).thenReturn(Mono.empty());
        client.setDlqContext(dlqSink, "sess-A", "worker-1");
        var op = indexOpWithId("doc-42");
        var failure = new BulkResponseParser.ItemFailure(0, "doc-42", "boom", "{\"raw\":\"json\"}");

        client.emitDlqRecord("movies", op, failure, FailureClass.NON_RETRYABLE);

        ArgumentCaptor<DlqRecord> rec = ArgumentCaptor.forClass(DlqRecord.class);
        verify(dlqSink).write(rec.capture());
        var r = rec.getValue();
        assertThat(r.getSessionId(), equalTo("sess-A"));
        assertThat(r.getWorkerId(), equalTo("worker-1"));
        assertThat(r.getTargetIndex(), equalTo("movies"));
        assertThat(r.getDocumentId(), equalTo("doc-42"));
        assertThat(r.getFailureType(), equalTo("boom"));
        assertThat(r.getFailureClass(), is(FailureClass.NON_RETRYABLE));
        assertThat(r.getRequestItem(), notNullValue());
        assertThat(r.getResponseItem(), notNullValue());
        assertThat(r.getTimestamp(), notNullValue());
    }

    @Test
    void emitDlqRecord_failureWithoutDocId_fallsBackToOpId() {
        // When the bulk-response item didn't carry an "_id" (e.g. malformed
        // response), the helper should fall back to the document id encoded
        // on the op we sent. That way the DLQ record still has *some* handle
        // back to the original document.
        when(dlqSink.write(any())).thenReturn(Mono.empty());
        client.setDlqContext(dlqSink, "s", "w");
        var op = indexOpWithId("from-op");
        var failure = new BulkResponseParser.ItemFailure(0, null, "err", "{}");

        client.emitDlqRecord("idx", op, failure, FailureClass.NON_RETRYABLE);

        ArgumentCaptor<DlqRecord> rec = ArgumentCaptor.forClass(DlqRecord.class);
        verify(dlqSink).write(rec.capture());
        assertThat(rec.getValue().getDocumentId(), equalTo("from-op"));
    }

    @Test
    void emitDlqRecord_nullFailure_stillEmitsRecord() {
        // The retry-exhausted path can call us with failure=null when the
        // last response didn't include a matching item for this position.
        when(dlqSink.write(any())).thenReturn(Mono.empty());
        client.setDlqContext(dlqSink, "s", "w");
        var op = indexOpWithId("doc-1");

        client.emitDlqRecord("idx", op, null, FailureClass.RETRYABLE_EXHAUSTED);

        ArgumentCaptor<DlqRecord> rec = ArgumentCaptor.forClass(DlqRecord.class);
        verify(dlqSink).write(rec.capture());
        var r = rec.getValue();
        assertThat(r.getDocumentId(), equalTo("doc-1"));
        assertThat(r.getFailureType(), nullValue());
        assertThat(r.getResponseItem(), nullValue());
        assertThat(r.getFailureClass(), is(FailureClass.RETRYABLE_EXHAUSTED));
    }

    @Test
    void emitDlqRecord_nullOp_stillEmitsRecord() {
        // If the op fell off pendingDocs (compactPendingDocs can pass null),
        // the helper still emits a DLQ entry with what info we have.
        when(dlqSink.write(any())).thenReturn(Mono.empty());
        client.setDlqContext(dlqSink, "s", "w");
        var failure = new BulkResponseParser.ItemFailure(0, "doc-only", "err", "{}");

        client.emitDlqRecord("idx", null, failure, FailureClass.NON_RETRYABLE);

        ArgumentCaptor<DlqRecord> rec = ArgumentCaptor.forClass(DlqRecord.class);
        verify(dlqSink).write(rec.capture());
        var r = rec.getValue();
        assertThat(r.getDocumentId(), equalTo("doc-only"));
        assertThat(r.getRequestItem(), nullValue());
    }

    @Test
    void emitDlqRecord_malformedResponseItemJson_doesNotPropagate() {
        // OBJECT_MAPPER.readTree on bad JSON throws — the helper must swallow
        // it and keep going (so the bulk path isn't taken down by a
        // best-effort DLQ side-effect).
        client.setDlqContext(dlqSink, "s", "w");
        var op = indexOpWithId("doc-1");
        var failure = new BulkResponseParser.ItemFailure(
            0, "doc-1", "err", "this is not json at all");

        // The helper should NOT propagate the JsonProcessingException.
        assertDoesNotThrow(() ->
            client.emitDlqRecord("idx", op, failure, FailureClass.NON_RETRYABLE));
        // It also shouldn't write a record when JSON construction failed.
        verify(dlqSink, never()).write(any());
    }

    @Test
    void emitDlqRecord_sinkWriteErrorIsSwallowed() {
        // The sink may fail (S3 down, etc.). The helper subscribes to the Mono
        // and logs the error rather than blowing up the bulk path.
        when(dlqSink.write(any())).thenReturn(
            Mono.error(new RuntimeException("S3 5xx")));
        client.setDlqContext(dlqSink, "s", "w");
        var op = indexOpWithId("doc-1");
        var failure = new BulkResponseParser.ItemFailure(0, "doc-1", "err", "{}");

        // Must not throw despite the upstream Mono error.
        assertDoesNotThrow(() ->
            client.emitDlqRecord("idx", op, failure, FailureClass.NON_RETRYABLE));
    }

    // ---- extractDocumentId ----------------------------------------------

    @Test
    void extractDocumentId_returnsNullForNullOp() {
        assertThat(OpenSearchClient.extractDocumentId(null), nullValue());
    }

    @Test
    void extractDocumentId_pullsIdFromOperationField() {
        // IndexOp serializes to {"operation": {"_id": "..."}}: this is the
        // primary path the extractor was written for.
        var op = indexOpWithId("doc-primary");
        assertThat(OpenSearchClient.extractDocumentId(op), equalTo("doc-primary"));
    }

    @Test
    void extractDocumentId_returnsNullWhenNoIdAnywhere() {
        // op without an id — every fallback path misses, expect null.
        var op = IndexOp.builder()
            .operation(IndexOperationMeta.builder().build())
            .build();
        assertThat(OpenSearchClient.extractDocumentId(op), nullValue());
    }
}
