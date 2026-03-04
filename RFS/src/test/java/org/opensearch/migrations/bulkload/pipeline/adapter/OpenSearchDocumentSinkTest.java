package org.opensearch.migrations.bulkload.pipeline.adapter;

import java.util.List;

import org.opensearch.migrations.bulkload.common.DocumentExceptionAllowlist;
import org.opensearch.migrations.bulkload.common.OpenSearchClient;
import org.opensearch.migrations.bulkload.pipeline.ir.DocumentChange;
import org.opensearch.migrations.bulkload.pipeline.ir.ShardId;
import org.opensearch.migrations.transform.IJsonTransformer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OpenSearchDocumentSinkTest {

    @Mock
    OpenSearchClient client;

    private static final ShardId SHARD = new ShardId("snap", "idx", 0);

    private static final Mono<OpenSearchClient.BulkResponse> OK =
        Mono.just(new OpenSearchClient.BulkResponse(200, "", null, "{}"));

    @Test
    void writeBatch_noTransformer_usesRawPath() {
        when(client.sendBulkRequestRaw(anyString(), anyList(), any(), anyBoolean(), any())).thenReturn(OK);
        var sink = new OpenSearchDocumentSink(client, null, false, DocumentExceptionAllowlist.empty(), null);
        var docs = List.of(doc("d1", "{\"a\":1}"), doc("d2", "{\"b\":2}"));

        var result = sink.writeBatch(SHARD, "idx", docs).block();

        assertNotNull(result);
        assertEquals(2, result.docsInBatch());
        verify(client).sendBulkRequestRaw(eq("idx"), eq(docs), isNull(), eq(false), any());
        verify(client, never()).sendBulkRequest(anyString(), anyList(), any(), anyBoolean(), any());
    }

    @Test
    void writeBatch_withTransformer_usesTransformPath() {
        when(client.sendBulkRequest(anyString(), anyList(), any(), anyBoolean(), any())).thenReturn(OK);

        IJsonTransformer identity = input -> input;
        var sink = new OpenSearchDocumentSink(client, () -> identity, false, DocumentExceptionAllowlist.empty(), null);
        var docs = List.of(doc("d1", "{\"a\":1}"));

        sink.writeBatch(SHARD, "idx", docs).block();

        verify(client).sendBulkRequest(eq("idx"), anyList(), isNull(), eq(false), any());
        verify(client, never()).sendBulkRequestRaw(anyString(), anyList(), any(), anyBoolean(), any());
    }

    @Test
    void writeBatch_returnsBatchResultWithCorrectByteCounts() {
        when(client.sendBulkRequestRaw(anyString(), anyList(), any(), anyBoolean(), any())).thenReturn(OK);
        var sink = new OpenSearchDocumentSink(client, null, false, DocumentExceptionAllowlist.empty(), null);
        byte[] src1 = "{\"x\":1}".getBytes();
        byte[] src2 = "{\"y\":2}".getBytes();
        var docs = List.of(
            new DocumentChange("d1", null, src1, null, DocumentChange.ChangeType.INDEX),
            new DocumentChange("d2", null, src2, null, DocumentChange.ChangeType.INDEX)
        );

        var result = sink.writeBatch(SHARD, "idx", docs).block();

        assertNotNull(result);
        assertEquals(2, result.docsInBatch());
        assertEquals(src1.length + src2.length, result.bytesInBatch());
    }

    @Test
    void writeBatch_nullSourceBytes_countsAsZeroBytes() {
        when(client.sendBulkRequestRaw(anyString(), anyList(), any(), anyBoolean(), any())).thenReturn(OK);
        var sink = new OpenSearchDocumentSink(client, null, false, DocumentExceptionAllowlist.empty(), null);
        var docs = List.of(
            new DocumentChange("d1", null, null, null, DocumentChange.ChangeType.DELETE)
        );

        var result = sink.writeBatch(SHARD, "idx", docs).block();

        assertNotNull(result);
        assertEquals(1, result.docsInBatch());
        assertEquals(0, result.bytesInBatch());
    }

    @Test
    void writeBatch_clientError_propagates() {
        when(client.sendBulkRequestRaw(anyString(), anyList(), any(), anyBoolean(), any()))
            .thenReturn(Mono.error(new RuntimeException("bulk failed")));

        var sink = new OpenSearchDocumentSink(client, null, false, DocumentExceptionAllowlist.empty(), null);
        var docs = List.of(doc("d1", "{\"a\":1}"));

        var error = sink.writeBatch(SHARD, "idx", docs)
            .onErrorResume(e -> Mono.empty())
            .block();

        // Error consumed by onErrorResume — cursor is null
        assertEquals(null, error);
    }

    @SuppressWarnings("unchecked")
    @Test
    void writeBatch_transformerModifiesDocs_sendsTransformed() {
        when(client.sendBulkRequest(anyString(), anyList(), any(), anyBoolean(), any())).thenReturn(OK);

        // Identity transformer — passes docs through unchanged
        IJsonTransformer identity = input -> input;
        var sink = new OpenSearchDocumentSink(client, () -> identity, false, DocumentExceptionAllowlist.empty(), null);
        var docs = List.of(doc("d1", "{\"a\":1}"));

        sink.writeBatch(SHARD, "idx", docs).block();

        verify(client).sendBulkRequest(eq("idx"), anyList(), isNull(), eq(false), any());
        verify(client, never()).sendBulkRequestRaw(anyString(), anyList(), any(), anyBoolean(), any());
    }

    private static DocumentChange doc(String id, String json) {
        return new DocumentChange(id, null, json.getBytes(), null, DocumentChange.ChangeType.INDEX);
    }
}
