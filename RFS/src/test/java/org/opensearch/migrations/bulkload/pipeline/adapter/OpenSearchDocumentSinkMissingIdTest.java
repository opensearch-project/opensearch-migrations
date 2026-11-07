package org.opensearch.migrations.bulkload.pipeline.adapter;

import java.util.List;
import java.util.Map;

import org.opensearch.migrations.bulkload.common.DocumentExceptionAllowlist;
import org.opensearch.migrations.bulkload.common.OpenSearchClient;
import org.opensearch.migrations.bulkload.common.bulk.BulkOperationSpec;
import org.opensearch.migrations.bulkload.pipeline.model.Document;
import org.opensearch.migrations.bulkload.pipeline.model.PositionedDocument;
import org.opensearch.migrations.transform.IJsonTransformer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** An operation with no {@code _id} would add a document rather than replace one. */
@ExtendWith(MockitoExtension.class)
class OpenSearchDocumentSinkMissingIdTest {

    @Mock
    OpenSearchClient client;

    private static final Mono<OpenSearchClient.BulkResponse> OK =
        Mono.just(new OpenSearchClient.BulkResponse(200, "", null, "{}"));

    /** Passes operations through unchanged. */
    private static final IJsonTransformer IDENTITY = input -> input;

    private static PositionedDocument doc(String id) {
        return new PositionedDocument(
            new Document(id, "{\"a\":1}".getBytes(), Document.Operation.UPSERT, Map.of(), Map.of()),
            "cursor-after-" + id);
    }

    @SuppressWarnings("unchecked")
    private ArgumentCaptor<List<BulkOperationSpec>> captureTransformedOps() {
        ArgumentCaptor<List<BulkOperationSpec>> captor = ArgumentCaptor.forClass(List.class);
        when(client.sendBulkRequest(anyString(), captor.capture(), any(), anyBoolean(), any())).thenReturn(OK);
        return captor;
    }

    @Test
    void skipsAnOperationWithNoIdAndReportsIt() {
        var captor = captureTransformedOps();
        var sink = new OpenSearchDocumentSink(
            client, () -> IDENTITY, false, false, DocumentExceptionAllowlist.empty(), null);

        var result = sink.writeBatch("idx", List.of(doc("d1"), doc(null), doc("d2"))).block();

        assertThat(captor.getValue(), hasSize(2));
        assertThat(captor.getValue().stream().map(op -> op.getOperation().getId()).toList(),
            contains("d1", "d2"));
        assertThat(sink.getSkippedMissingIdCount(), equalTo(1L));
        assertNotNull(result);
        // Progress still covers skipped documents, so a successor does not revisit them.
        assertThat(result.docsInBatch(), equalTo(3L));
        assertThat(result.cursorAfter(), equalTo("cursor-after-d2"));
    }

    @Test
    void sendsAnOperationWithNoIdWhenTheRunOptsIn() {
        var captor = captureTransformedOps();
        var sink = new OpenSearchDocumentSink(
            client, () -> IDENTITY, false, true, DocumentExceptionAllowlist.empty(), null);

        sink.writeBatch("idx", List.of(doc("d1"), doc(null))).block();

        assertThat(captor.getValue(), hasSize(2));
        assertThat(sink.getSkippedMissingIdCount(), equalTo(0L));
    }

    @Test
    void makesNoRequestWhenEveryOperationInTheBatchWasSkipped() {
        var sink = new OpenSearchDocumentSink(
            client, () -> IDENTITY, false, false, DocumentExceptionAllowlist.empty(), null);

        var result = sink.writeBatch("idx", List.of(doc(null), doc(null))).block();

        verify(client, never()).sendBulkRequest(anyString(), anyList(), any(), anyBoolean(), any());
        assertNotNull(result);
        assertThat(result.cursorAfter(), equalTo("cursor-after-null"));
        assertThat(sink.getSkippedMissingIdCount(), equalTo(2L));
    }

    @Test
    void leavesIdsAloneWhenTheRunStripsThemAnyway() {
        // --server-generated-ids removes every id on purpose.
        when(client.sendBulkRequestRaw(anyString(), anyList(), any(), anyBoolean(), any())).thenReturn(OK);
        var sink = new OpenSearchDocumentSink(
            client, null, true, false, DocumentExceptionAllowlist.empty(), null);

        sink.writeBatch("idx", List.of(doc("d1"), doc(null))).block();

        verify(client).sendBulkRequestRaw(anyString(), anyList(), any(), anyBoolean(), any());
        assertThat(sink.getSkippedMissingIdCount(), equalTo(0L));
    }

    @Test
    void skipsOnTheRawPathToo() {
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Document>> captor = ArgumentCaptor.forClass(List.class);
        when(client.sendBulkRequestRaw(anyString(), captor.capture(), any(), anyBoolean(), any())).thenReturn(OK);
        var sink = new OpenSearchDocumentSink(
            client, null, false, false, DocumentExceptionAllowlist.empty(), null);

        sink.writeBatch("idx", List.of(doc("d1"), doc(null))).block();

        assertThat(captor.getValue().stream().map(Document::id).toList(), contains("d1"));
        assertThat(sink.getSkippedMissingIdCount(), equalTo(1L));
    }
}
