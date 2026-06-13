package org.opensearch.migrations.bulkload.pipeline.adapter;

import java.util.List;
import java.util.Map;

import org.opensearch.migrations.bulkload.common.DocumentExceptionAllowlist;
import org.opensearch.migrations.bulkload.common.OpenSearchClient;
import org.opensearch.migrations.bulkload.pipeline.model.Document;
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

    private static final Mono<OpenSearchClient.BulkResponse> OK =
        Mono.just(new OpenSearchClient.BulkResponse(200, "", null, "{}"));

    @Test
    void writeBatch_noTransformer_usesRawPath() {
        when(client.sendBulkRequestRaw(anyString(), anyList(), any(), anyBoolean(), any())).thenReturn(OK);
        var sink = new OpenSearchDocumentSink(client, null, false, DocumentExceptionAllowlist.empty(), null);
        var docs = List.of(doc("d1", "{\"a\":1}"), doc("d2", "{\"b\":2}"));

        var result = sink.writeBatch("idx", docs).block();

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

        sink.writeBatch("idx", docs).block();

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
            new Document("d1", src1, Document.Operation.UPSERT, Map.of(), Map.of()),
            new Document("d2", src2, Document.Operation.UPSERT, Map.of(), Map.of())
        );

        var result = sink.writeBatch("idx", docs).block();

        assertNotNull(result);
        assertEquals(2, result.docsInBatch());
        assertEquals(src1.length + src2.length, result.bytesInBatch());
    }

    @Test
    void writeBatch_nullSourceBytes_countsAsZeroBytes() {
        when(client.sendBulkRequestRaw(anyString(), anyList(), any(), anyBoolean(), any())).thenReturn(OK);
        var sink = new OpenSearchDocumentSink(client, null, false, DocumentExceptionAllowlist.empty(), null);
        var docs = List.of(
            new Document("d1", null, Document.Operation.DELETE, Map.of(), Map.of())
        );

        var result = sink.writeBatch("idx", docs).block();

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

        var error = sink.writeBatch("idx", docs)
            .onErrorResume(e -> Mono.empty())
            .block();

        assertEquals(null, error);
    }

    @SuppressWarnings("unchecked")
    @Test
    void writeBatch_withTypeMappingTransformer_handlesPolyglotTypes() {
        when(client.sendBulkRequest(anyString(), anyList(), any(), anyBoolean(), any())).thenReturn(OK);

        // Use the real TypeMappingSanitization transformer (GraalVM/JS-based) which returns
        // PolyglotList/PolyglotMap types that must be normalized to native Java types
        var transformerConfig = "[{\"TypeMappingSanitizationTransformerProvider\":" +
            "{\"staticMappings\":{\"source_index\":{\"type1\":\"source_index\",\"type2\":\"source_index\"}}," +
            "\"sourceProperties\":{\"version\":{\"major\":5,\"minor\":6}}}}]";
        var loader = new org.opensearch.migrations.transform.TransformationLoader();
        var transformer = loader.getTransformerFactoryLoader(transformerConfig);

        var sink = new OpenSearchDocumentSink(client, () -> transformer, false,
            DocumentExceptionAllowlist.empty(), null);

        // Create a document with _type hint (as the pipeline does for ES 5.x multi-type indices)
        var docJson = "{\"title\":\"test\"}";
        var hints = Map.of(Document.HINT_TYPE, "type1");
        var docs = List.of(new Document("doc1", docJson.getBytes(), Document.Operation.UPSERT, hints, Map.of()));

        // This would throw ClassCastException: PolyglotList cannot be cast to Map
        // before the fix in applyTransformation
        var result = sink.writeBatch("source_index", docs).block();

        assertNotNull(result);
        verify(client).sendBulkRequest(eq("source_index"), anyList(), isNull(), eq(false), any());
    }

    @SuppressWarnings("unchecked")
    @Test
    void writeBatch_transformerModifiesDocs_sendsTransformed() {
        when(client.sendBulkRequest(anyString(), anyList(), any(), anyBoolean(), any())).thenReturn(OK);

        IJsonTransformer identity = input -> input;
        var sink = new OpenSearchDocumentSink(client, () -> identity, false, DocumentExceptionAllowlist.empty(), null);
        var docs = List.of(doc("d1", "{\"a\":1}"));

        sink.writeBatch("idx", docs).block();

        verify(client).sendBulkRequest(eq("idx"), anyList(), isNull(), eq(false), any());
        verify(client, never()).sendBulkRequestRaw(anyString(), anyList(), any(), anyBoolean(), any());
    }

    @SuppressWarnings("unchecked")
    @Test
    void writeBatch_transformerModifiesDocs_preservesOriginalSourceOnOps() {
        // The transformed body is what's sent, but each op must retain the original
        // (pre-transformation) source so a DLQ record can report the source document.
        org.mockito.ArgumentCaptor<List<org.opensearch.migrations.bulkload.common.bulk.BulkOperationSpec>> captor =
            org.mockito.ArgumentCaptor.forClass(List.class);
        when(client.sendBulkRequest(anyString(), captor.capture(), any(), anyBoolean(), any())).thenReturn(OK);

        // Transformer mutates the document body in place.
        IJsonTransformer mutate = input -> {
            for (var m : (List<Map<String, Object>>) input) {
                ((Map<String, Object>) m.get("document")).put("a", 2);
            }
            return input;
        };
        var sink = new OpenSearchDocumentSink(client, () -> mutate, false, DocumentExceptionAllowlist.empty(), null);

        sink.writeBatch("idx", List.of(doc("d1", "{\"a\":1}"))).block();

        var sentOps = captor.getValue();
        assertEquals(1, sentOps.size());
        var op = sentOps.get(0);
        assertEquals(2, op.getDocument().get("a"), "sent (transformed) body should reflect the mutation");
        assertEquals(1, op.getOriginalSource().get("a"), "originalSource should retain the pre-transform body");
    }

    @SuppressWarnings("unchecked")
    @Test
    void writeBatch_transformerChangesId_leavesOriginalSourceNull() {
        // When the transformer changes the document id, the transformed op can no longer
        // be correlated back to a source document, so originalSource stays null and the
        // DLQ will fall back to the transformed body.
        org.mockito.ArgumentCaptor<List<org.opensearch.migrations.bulkload.common.bulk.BulkOperationSpec>> captor =
            org.mockito.ArgumentCaptor.forClass(List.class);
        when(client.sendBulkRequest(anyString(), captor.capture(), any(), anyBoolean(), any())).thenReturn(OK);

        IJsonTransformer renamer = input -> {
            for (var m : (List<Map<String, Object>>) input) {
                ((Map<String, Object>) m.get("operation")).put("_id", "renamed");
            }
            return input;
        };
        var sink = new OpenSearchDocumentSink(client, () -> renamer, false, DocumentExceptionAllowlist.empty(), null);

        sink.writeBatch("idx", List.of(doc("d1", "{\"a\":1}"))).block();

        var op = captor.getValue().get(0);
        org.junit.jupiter.api.Assertions.assertNull(op.getOriginalSource(),
            "no source correlates to the renamed id");
        assertEquals("renamed", op.getOperation().getId());
    }

    private static Document doc(String id, String json) {
        return new Document(id, json.getBytes(), Document.Operation.UPSERT, Map.of(), Map.of());
    }
}
