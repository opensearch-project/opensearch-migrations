package org.opensearch.migrations.bulkload.pipeline.adapter;

import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import org.opensearch.migrations.bulkload.common.DocumentExceptionAllowlist;
import org.opensearch.migrations.bulkload.common.ObjectMapperFactory;
import org.opensearch.migrations.bulkload.common.OpenSearchClient;
import org.opensearch.migrations.bulkload.common.bulk.BulkOperationConverter;
import org.opensearch.migrations.bulkload.common.bulk.BulkOperationSpec;
import org.opensearch.migrations.bulkload.pipeline.ir.BatchResult;
import org.opensearch.migrations.bulkload.pipeline.ir.CollectionMetadata;
import org.opensearch.migrations.bulkload.pipeline.ir.Document;
import org.opensearch.migrations.bulkload.pipeline.sink.DocumentSink;
import org.opensearch.migrations.bulkload.tracing.IRfsContexts;
import org.opensearch.migrations.transform.IJsonTransformer;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Real {@link DocumentSink} adapter that writes documents to an OpenSearch cluster
 * via the existing {@link OpenSearchClient}.
 *
 * <p>Converts clean pipeline IR ({@link Document}) to bulk API operations
 * ({@link BulkOperationSpec}) and sends them via {@link OpenSearchClient#sendBulkRequest}.
 *
 * <p>Supports optional document transformation via {@link IJsonTransformer} and
 * configurable exception allowlisting for idempotent migrations.
 */
@Slf4j
public class OpenSearchDocumentSink implements DocumentSink {

    private static final ObjectMapper OBJECT_MAPPER = ObjectMapperFactory.createDefaultMapper();

    private final OpenSearchClient client;
    private final IJsonTransformer transformer;
    private final boolean allowServerGeneratedIds;
    private final DocumentExceptionAllowlist allowlist;
    private final Supplier<IRfsContexts.IRequestContext> requestContextSupplier;

    public OpenSearchDocumentSink(
        OpenSearchClient client,
        Supplier<IJsonTransformer> transformerSupplier,
        boolean allowServerGeneratedIds,
        DocumentExceptionAllowlist allowlist,
        Supplier<IRfsContexts.IRequestContext> requestContextSupplier
    ) {
        this.client = client;
        this.transformer = transformerSupplier != null ? transformerSupplier.get() : null;
        this.allowServerGeneratedIds = allowServerGeneratedIds;
        this.allowlist = allowlist != null ? allowlist : DocumentExceptionAllowlist.empty();
        this.requestContextSupplier = requestContextSupplier;
    }

    @Override
    public Mono<Void> createCollection(CollectionMetadata metadata) {
        return Mono.fromRunnable(() -> {
            var sourceConfig = metadata.sourceConfig();
            if (sourceConfig.containsKey("es.numberOfShards")) {
                // ES→ES path: reconstruct IndexMetadataSnapshot from sourceConfig
                var esMetadata = new IndexMetadataSnapshot(
                    metadata.name(),
                    (int) sourceConfig.get("es.numberOfShards"),
                    sourceConfig.containsKey("es.numberOfReplicas")
                        ? (int) sourceConfig.get("es.numberOfReplicas") : 0,
                    (com.fasterxml.jackson.databind.node.ObjectNode) sourceConfig.get("es.mappings"),
                    (com.fasterxml.jackson.databind.node.ObjectNode) sourceConfig.get("es.settings"),
                    (com.fasterxml.jackson.databind.node.ObjectNode) sourceConfig.get("es.aliases")
                );
                OpenSearchIndexCreator.createIndex(client, esMetadata, OBJECT_MAPPER, null);
            } else {
                // Non-ES source: create index with defaults
                client.createIndex(metadata.name(), OBJECT_MAPPER.createObjectNode(), null);
            }
        })
            .subscribeOn(Schedulers.boundedElastic())
            .then();
    }

    @Override
    public Mono<BatchResult> writeBatch(String collectionName, List<Document> batch) {
        long bytesInBatch = batch.stream()
            .mapToLong(Document::sourceLength)
            .sum();
        var requestContext = requestContextSupplier != null ? requestContextSupplier.get() : null;

        Mono<OpenSearchClient.BulkResponse> bulkMono;
        if (transformer == null) {
            // Fast path: skip byte[]→Map→byte[] round-trip, write raw source bytes directly
            bulkMono = client.sendBulkRequestRaw(collectionName, batch,
                requestContext, allowServerGeneratedIds, allowlist);
        } else {
            var bulkOps = batch.stream()
                .map(doc -> BulkOperationConverter.fromDocument(doc, collectionName))
                .collect(Collectors.toList());
            List<BulkOperationSpec> opsToSend = applyTransformation(bulkOps);
            bulkMono = client.sendBulkRequest(collectionName, opsToSend,
                requestContext, allowServerGeneratedIds, allowlist);
        }

        return bulkMono.then(Mono.just(new BatchResult(batch.size(), bytesInBatch)));
    }

    @SuppressWarnings("unchecked")
    private List<BulkOperationSpec> applyTransformation(List<BulkOperationSpec> ops) {
        if (transformer == null) {
            return ops;
        }
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
}
