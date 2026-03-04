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
import org.opensearch.migrations.bulkload.pipeline.ir.DocumentChange;
import org.opensearch.migrations.bulkload.pipeline.ir.IndexMetadataSnapshot;
import org.opensearch.migrations.bulkload.pipeline.ir.ProgressCursor;
import org.opensearch.migrations.bulkload.pipeline.ir.ShardId;
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
    private final IJsonTransformer transformer;
    private final boolean allowServerGeneratedIds;
    private final DocumentExceptionAllowlist allowlist;
    private final Supplier<IRfsContexts.IRequestContext> requestContextSupplier;

    /**
     * Full constructor with all options.
     *
     * @param client                    the OpenSearch client
     * @param transformerSupplier       supplier for document transformers, null for no transformation
     * @param allowServerGeneratedIds   whether to strip document IDs for server-generated IDs
     * @param allowlist                 exception types to treat as success during bulk writes
     * @param requestContextSupplier    supplier for request contexts (enables HTTP metering), null to skip
     */
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
    public Mono<Void> createIndex(IndexMetadataSnapshot metadata) {
        return Mono.fromRunnable(() -> OpenSearchIndexCreator.createIndex(client, metadata, OBJECT_MAPPER))
            .subscribeOn(Schedulers.boundedElastic())
            .then();
    }

    @Override
    public Mono<ProgressCursor> writeBatch(ShardId shardId, String indexName, List<DocumentChange> batch) {
        long bytesInBatch = batch.stream()
            .mapToLong(doc -> doc.source() != null ? doc.source().length : 0)
            .sum();

        Mono<OpenSearchClient.BulkResponse> bulkMono;
        if (transformer == null) {
            // Fast path: skip byte[]→Map→byte[] round-trip, write raw source bytes directly
            bulkMono = client.sendBulkRequestRaw(indexName, batch,
                requestContextSupplier != null ? requestContextSupplier.get() : null,
                allowServerGeneratedIds, allowlist);
        } else {
            var bulkOps = batch.stream()
                .map(doc -> toBulkOp(doc, indexName))
                .collect(Collectors.toList());
            List<BulkOperationSpec> opsToSend = applyTransformation(bulkOps);
            bulkMono = client.sendBulkRequest(indexName, opsToSend,
                requestContextSupplier != null ? requestContextSupplier.get() : null,
                allowServerGeneratedIds, allowlist);
        }

        return bulkMono.then(Mono.just(new ProgressCursor(
            shardId,
            (long) batch.size(),
            batch.size(),
            bytesInBatch
        )));
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

    private static BulkOperationSpec toBulkOp(DocumentChange doc, String indexName) {
        return BulkOperationConverter.fromDocumentChange(doc, indexName);
    }
}
