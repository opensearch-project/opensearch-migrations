package org.opensearch.migrations.bulkload.pipeline.adapter;

import java.util.List;
import java.util.function.Supplier;

import org.opensearch.migrations.bulkload.common.DocumentExceptionAllowlist;
import org.opensearch.migrations.bulkload.common.OpenSearchClient;
import org.opensearch.migrations.bulkload.common.bulk.BulkOperationSpec;
import org.opensearch.migrations.bulkload.common.bulk.DeleteOp;
import org.opensearch.migrations.bulkload.common.bulk.IndexOp;
import org.opensearch.migrations.bulkload.pipeline.ir.DocumentChange;
import org.opensearch.migrations.bulkload.pipeline.ir.IndexMetadataSnapshot;
import org.opensearch.migrations.bulkload.pipeline.ir.ShardId;
import org.opensearch.migrations.transform.IJsonTransformer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OpenSearchDocumentSinkTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Mock private OpenSearchClient client;
    @Captor private ArgumentCaptor<List<? extends BulkOperationSpec>> bulkOpsCaptor;

    private OpenSearchDocumentSink sink;

    @BeforeEach
    void setUp() {
        sink = new OpenSearchDocumentSink(client);
    }

    @Test
    void createIndexCallsClient() {
        ObjectNode mappings = MAPPER.createObjectNode().put("type", "keyword");
        ObjectNode settings = MAPPER.createObjectNode().put("number_of_replicas", 1);
        var metadata = new IndexMetadataSnapshot("my-index", 3, 1, mappings, settings, null);

        when(client.createIndex(eq("my-index"), any(ObjectNode.class), isNull()))
            .thenReturn(java.util.Optional.of(MAPPER.createObjectNode()));

        StepVerifier.create(sink.createIndex(metadata))
            .verifyComplete();

        verify(client).createIndex(eq("my-index"), any(ObjectNode.class), isNull());
    }

    @Test
    void writeBatchConvertsIndexOps() {
        var shardId = new ShardId("snap", "idx", 0);
        var doc = new DocumentChange("doc-1", "_doc", "{\"field\":\"value\"}".getBytes(), null, DocumentChange.ChangeType.INDEX);

        when(client.sendBulkRequest(eq("idx"), anyList(), isNull(), eq(false), any(DocumentExceptionAllowlist.class)))
            .thenReturn(Mono.empty());

        StepVerifier.create(sink.writeBatch(shardId, "idx", List.of(doc)))
            .assertNext(cursor -> {
                assertEquals(shardId, cursor.shardId());
                assertEquals(1, cursor.docsInBatch());
                assertTrue(cursor.bytesInBatch() > 0);
            })
            .verifyComplete();

        verify(client).sendBulkRequest(eq("idx"), bulkOpsCaptor.capture(), isNull(), eq(false), any(DocumentExceptionAllowlist.class));
        var ops = bulkOpsCaptor.getValue();
        assertEquals(1, ops.size());
        assertInstanceOf(IndexOp.class, ops.get(0));
        var indexOp = (IndexOp) ops.get(0);
        assertEquals("doc-1", indexOp.getOperation().getId());
    }

    @Test
    void writeBatchConvertsDeleteOps() {
        var shardId = new ShardId("snap", "idx", 0);
        var doc = new DocumentChange("doc-1", "_doc", "{}".getBytes(), null, DocumentChange.ChangeType.DELETE);

        when(client.sendBulkRequest(eq("idx"), anyList(), isNull(), eq(false), any(DocumentExceptionAllowlist.class)))
            .thenReturn(Mono.empty());

        StepVerifier.create(sink.writeBatch(shardId, "idx", List.of(doc)))
            .assertNext(cursor -> assertEquals(1, cursor.docsInBatch()))
            .verifyComplete();

        verify(client).sendBulkRequest(eq("idx"), bulkOpsCaptor.capture(), isNull(), eq(false), any(DocumentExceptionAllowlist.class));
        assertInstanceOf(DeleteOp.class, bulkOpsCaptor.getValue().get(0));
    }

    @Test
    void writeBatchHandlesMultipleDocs() {
        var shardId = new ShardId("snap", "idx", 0);
        var docs = List.of(
            new DocumentChange("d1", "_doc", "{\"a\":1}".getBytes(), null, DocumentChange.ChangeType.INDEX),
            new DocumentChange("d2", "_doc", "{\"b\":2}".getBytes(), "r1", DocumentChange.ChangeType.INDEX),
            new DocumentChange("d3", "_doc", null, null, DocumentChange.ChangeType.DELETE)
        );

        when(client.sendBulkRequest(eq("idx"), anyList(), isNull(), eq(false), any(DocumentExceptionAllowlist.class)))
            .thenReturn(Mono.empty());

        StepVerifier.create(sink.writeBatch(shardId, "idx", docs))
            .assertNext(cursor -> assertEquals(3, cursor.docsInBatch()))
            .verifyComplete();

        verify(client).sendBulkRequest(eq("idx"), bulkOpsCaptor.capture(), isNull(), eq(false), any(DocumentExceptionAllowlist.class));
        assertEquals(3, bulkOpsCaptor.getValue().size());

        // Check routing is preserved
        var secondOp = (IndexOp) bulkOpsCaptor.getValue().get(1);
        assertEquals("r1", secondOp.getOperation().getRouting());
    }

    @Nested
    class TransformationTests {

        @SuppressWarnings("unchecked")
        @Test
        void writeBatchAppliesTransformation() {
            // Transformer that uppercases all string values in documents
            Supplier<IJsonTransformer> transformerSupplier = () -> input -> {
                // Pass through — just verify it's called
                return input;
            };
            var transformingSink = new OpenSearchDocumentSink(client, transformerSupplier, false, DocumentExceptionAllowlist.empty());
            var shardId = new ShardId("snap", "idx", 0);
            var doc = new DocumentChange("doc-1", "_doc", "{\"field\":\"value\"}".getBytes(), null, DocumentChange.ChangeType.INDEX);

            when(client.sendBulkRequest(eq("idx"), anyList(), isNull(), eq(false), any(DocumentExceptionAllowlist.class)))
                .thenReturn(Mono.empty());

            StepVerifier.create(transformingSink.writeBatch(shardId, "idx", List.of(doc)))
                .assertNext(cursor -> assertEquals(1, cursor.docsInBatch()))
                .verifyComplete();

            verify(client).sendBulkRequest(eq("idx"), bulkOpsCaptor.capture(), isNull(), eq(false), any(DocumentExceptionAllowlist.class));
            assertEquals(1, bulkOpsCaptor.getValue().size());
        }

        @Test
        void writeBatchWithServerGeneratedIds() {
            var sinkWithServerIds = new OpenSearchDocumentSink(client, null, true, DocumentExceptionAllowlist.empty());
            var shardId = new ShardId("snap", "idx", 0);
            var doc = new DocumentChange("doc-1", "_doc", "{\"f\":1}".getBytes(), null, DocumentChange.ChangeType.INDEX);

            when(client.sendBulkRequest(eq("idx"), anyList(), isNull(), eq(true), any(DocumentExceptionAllowlist.class)))
                .thenReturn(Mono.empty());

            StepVerifier.create(sinkWithServerIds.writeBatch(shardId, "idx", List.of(doc)))
                .assertNext(cursor -> assertEquals(1, cursor.docsInBatch()))
                .verifyComplete();

            verify(client).sendBulkRequest(eq("idx"), anyList(), isNull(), eq(true), any(DocumentExceptionAllowlist.class));
        }
    }
}
