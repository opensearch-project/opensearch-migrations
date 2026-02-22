package org.opensearch.migrations.bulkload.pipeline.adapter;

import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import org.opensearch.migrations.bulkload.common.DocumentExceptionAllowlist;
import org.opensearch.migrations.bulkload.common.ObjectMapperFactory;
import org.opensearch.migrations.bulkload.common.OpenSearchClient;
import org.opensearch.migrations.bulkload.common.bulk.BulkOperationSpec;
import org.opensearch.migrations.bulkload.common.bulk.DeleteOp;
import org.opensearch.migrations.bulkload.common.bulk.IndexOp;
import org.opensearch.migrations.bulkload.common.bulk.operations.DeleteOperationMeta;
import org.opensearch.migrations.bulkload.common.bulk.operations.IndexOperationMeta;
import org.opensearch.migrations.bulkload.pipeline.ir.DocumentChange;
import org.opensearch.migrations.bulkload.pipeline.ir.IndexMetadataSnapshot;
import org.opensearch.migrations.bulkload.pipeline.ir.ProgressCursor;
import org.opensearch.migrations.bulkload.pipeline.ir.ShardId;
import org.opensearch.migrations.bulkload.pipeline.sink.DocumentSink;
import org.opensearch.migrations.transform.IJsonTransformer;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

/**
 * Real {@link DocumentSink} adapter that writes documents to an OpenSearch cluster
 * via the existing {@link OpenSearchClient}.
 *
 * <p>Converts clean pipeline IR ({@link DocumentChange}) to bulk API operations
 * ({@link BulkOperationSpec}) and sends them via {@link OpenSearchClient#sendBulkRequest}.
 *
 * <p>Supports optional document transformation via {@link IJsonTransformer} and
 * configurable exception allowlisting for idempotent migrations.
 */
@Slf4j
public class OpenSearchDocumentSink implements DocumentSink {

    private static final ObjectMapper OBJECT_MAPPER = ObjectMapperFactory.createDefaultMapper();

    private final OpenSearchClient client;
    private final Supplier<IJsonTransformer> transformerSupplier;
    private final boolean allowServerGeneratedIds;
    private final DocumentExceptionAllowlist allowlist;

    /** Simple constructor — no transformation, no server-generated IDs, no allowlist. */
    public OpenSearchDocumentSink(OpenSearchClient client) {
        this(client, null, false, DocumentExceptionAllowlist.empty());
    }

    /**
     * Full constructor with all options.
     *
     * @param client                  the OpenSearch client
     * @param transformerSupplier     supplier for document transformers, null for no transformation
     * @param allowServerGeneratedIds whether to strip document IDs for server-generated IDs
     * @param allowlist               exception types to treat as success during bulk writes
     */
    public OpenSearchDocumentSink(
        OpenSearchClient client,
        Supplier<IJsonTransformer> transformerSupplier,
        boolean allowServerGeneratedIds,
        DocumentExceptionAllowlist allowlist
    ) {
        this.client = client;
        this.transformerSupplier = transformerSupplier;
        this.allowServerGeneratedIds = allowServerGeneratedIds;
        this.allowlist = allowlist != null ? allowlist : DocumentExceptionAllowlist.empty();
    }

    @Override
    public Mono<Void> createIndex(IndexMetadataSnapshot metadata) {
        return Mono.fromCallable(() -> {
            ObjectNode body = OBJECT_MAPPER.createObjectNode();
            if (metadata.mappings() != null) {
                body.set("mappings", metadata.mappings());
            }
            if (metadata.settings() != null) {
                body.set("settings", metadata.settings());
            }
            if (metadata.aliases() != null) {
                body.set("aliases", metadata.aliases());
            }
            client.createIndex(metadata.indexName(), body, null);
            return null;
        }).then();
    }

    @Override
    public Mono<ProgressCursor> writeBatch(ShardId shardId, String indexName, List<DocumentChange> batch) {
        var bulkOps = batch.stream()
            .map(doc -> toBulkOp(doc, indexName))
            .collect(Collectors.toList());

        // Apply transformation if configured
        List<BulkOperationSpec> opsToSend = applyTransformation(bulkOps);

        long bytesInBatch = batch.stream()
            .mapToLong(doc -> doc.source() != null ? doc.source().length : 0)
            .sum();

        return client.sendBulkRequest(indexName, opsToSend, null, allowServerGeneratedIds, allowlist)
            .then(Mono.just(new ProgressCursor(
                shardId,
                batch.size(),
                batch.size(),
                bytesInBatch
            )));
    }

    @SuppressWarnings("unchecked")
    private List<BulkOperationSpec> applyTransformation(List<BulkOperationSpec> ops) {
        if (transformerSupplier == null) {
            return ops;
        }
        var transformer = transformerSupplier.get();
        var asMaps = ops.stream()
            .map(op -> OBJECT_MAPPER.convertValue(op, Map.class))
            .toList();
        var transformed = transformer.transformJson(asMaps);
        if (transformed instanceof List) {
            return ((List<Map<String, Object>>) transformed).stream()
                .map(item -> OBJECT_MAPPER.convertValue(item, BulkOperationSpec.class))
                .collect(Collectors.toList());
        }
        return ops;
    }

    @SneakyThrows
    private static BulkOperationSpec toBulkOp(DocumentChange doc, String indexName) {
        Map<String, Object> document = doc.source() != null
            ? OBJECT_MAPPER.readValue(doc.source(), new TypeReference<>() {})
            : Map.of();

        if (doc.operation() == DocumentChange.ChangeType.DELETE) {
            return DeleteOp.builder()
                .operation(DeleteOperationMeta.builder()
                    .id(doc.id())
                    .index(indexName)
                    .type(doc.type())
                    .routing(doc.routing())
                    .build())
                .document(document)
                .build();
        } else {
            return IndexOp.builder()
                .operation(IndexOperationMeta.builder()
                    .id(doc.id())
                    .index(indexName)
                    .type(doc.type())
                    .routing(doc.routing())
                    .build())
                .document(document)
                .build();
        }
    }
}
