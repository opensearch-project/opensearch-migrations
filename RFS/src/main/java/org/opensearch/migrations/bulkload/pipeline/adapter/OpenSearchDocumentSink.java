package org.opensearch.migrations.bulkload.pipeline.adapter;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import org.opensearch.migrations.bulkload.common.DocumentExceptionAllowlist;
import org.opensearch.migrations.bulkload.common.ObjectMapperFactory;
import org.opensearch.migrations.bulkload.common.OpenSearchClient;
import org.opensearch.migrations.bulkload.common.bulk.BulkNdjson;
import org.opensearch.migrations.bulkload.common.bulk.BulkOperationConverter;
import org.opensearch.migrations.bulkload.common.bulk.BulkOperationSpec;
import org.opensearch.migrations.bulkload.pipeline.model.BatchResult;
import org.opensearch.migrations.bulkload.pipeline.model.CollectionMetadata;
import org.opensearch.migrations.bulkload.pipeline.model.Document;
import org.opensearch.migrations.bulkload.pipeline.sink.GroupedDocumentSink;
import org.opensearch.migrations.bulkload.tracing.IRfsContexts;
import org.opensearch.migrations.bulkload.transformers.BulkOperationTransformer;
import org.opensearch.migrations.transform.IJsonTransformer;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Real {@link GroupedDocumentSink} adapter that writes documents to an OpenSearch cluster
 * via the existing {@link OpenSearchClient}.
 *
 * <p>Converts clean pipeline IR ({@link Document}) to bulk API operations
 * ({@link BulkOperationSpec}) and sends them via {@link OpenSearchClient#sendBulkRequest}.
 *
 * <p>Supports optional document transformation via {@link IJsonTransformer} and
 * configurable exception allowlisting for idempotent migrations.
 */
@Slf4j
public class OpenSearchDocumentSink implements GroupedDocumentSink<BulkNdjson.EncodedOperation> {

    private static final ObjectMapper OBJECT_MAPPER = ObjectMapperFactory.createDefaultMapper();
    private static final int SOURCE_BATCHES_PER_REQUEST_GROUP = 8;

    private final OpenSearchClient client;
    private final Supplier<IJsonTransformer> transformerSupplier;
    private final TransformationMode transformationMode;
    private final ArrayDeque<TransformerState> availableTransformers = new ArrayDeque<>();
    private final List<TransformerState> allTransformers = new ArrayList<>();
    private final boolean allowServerGeneratedIds;
    private final DocumentExceptionAllowlist allowlist;
    private final Supplier<IRfsContexts.IRequestContext> requestContextSupplier;
    private boolean closed;

    public OpenSearchDocumentSink(
        OpenSearchClient client,
        Supplier<IJsonTransformer> transformerSupplier,
        boolean allowServerGeneratedIds,
        DocumentExceptionAllowlist allowlist,
        Supplier<IRfsContexts.IRequestContext> requestContextSupplier
    ) {
        this.client = client;
        this.transformerSupplier = transformerSupplier;
        if (transformerSupplier == null) {
            this.transformationMode = TransformationMode.RAW;
        } else {
            var initialState = makeTransformerState(transformerSupplier.get());
            this.transformationMode = initialState.mode();
            this.availableTransformers.add(initialState);
            this.allTransformers.add(initialState);
        }
        this.allowServerGeneratedIds = allowServerGeneratedIds;
        this.allowlist = allowlist != null ? allowlist : DocumentExceptionAllowlist.empty();
        this.requestContextSupplier = requestContextSupplier;
    }

    @Override
    public Mono<Void> createCollection(CollectionMetadata metadata) {
        return Mono.fromRunnable(() -> {
            var sourceConfig = metadata.sourceConfig();
            if (sourceConfig.containsKey(CollectionMetadata.ES_NUMBER_OF_SHARDS)) {
                // ES→ES path: reconstruct IndexMetadataSnapshot from sourceConfig
                var esMetadata = new IndexMetadataSnapshot(
                    metadata.name(),
                    (int) sourceConfig.get(CollectionMetadata.ES_NUMBER_OF_SHARDS),
                    sourceConfig.containsKey(CollectionMetadata.ES_NUMBER_OF_REPLICAS)
                        ? (int) sourceConfig.get(CollectionMetadata.ES_NUMBER_OF_REPLICAS) : 0,
                    (com.fasterxml.jackson.databind.node.ObjectNode) sourceConfig.get(CollectionMetadata.ES_MAPPINGS),
                    (com.fasterxml.jackson.databind.node.ObjectNode) sourceConfig.get(CollectionMetadata.ES_SETTINGS),
                    (com.fasterxml.jackson.databind.node.ObjectNode) sourceConfig.get(CollectionMetadata.ES_ALIASES)
                );
                OpenSearchIndexCreator.createIndex(client, esMetadata, OBJECT_MAPPER, null);
            } else if (sourceConfig.containsKey(CollectionMetadata.ES_MAPPINGS)) {
                // Non-ES source with mappings (e.g. Solr): create index with mappings only
                var body = OBJECT_MAPPER.createObjectNode();
                body.set("mappings", (com.fasterxml.jackson.databind.node.ObjectNode) sourceConfig.get(CollectionMetadata.ES_MAPPINGS));
                client.createIndex(metadata.name(), body, null);
            } else {
                // Unknown source: create index with defaults
                client.createIndex(metadata.name(), OBJECT_MAPPER.createObjectNode(), null);
            }
        })
            .subscribeOn(Schedulers.boundedElastic())
            .then();
    }

    @Override
    public Mono<BatchResult> writeBatch(String collectionName, List<Document> sourceBatch) {
        var preparedBatch = sourceBatch.stream()
            .map(document -> prepareSourceDocument(collectionName, document))
            .toList();
        return writePreparedBatchGroup(collectionName, List.of(preparedBatch), 1);
    }

    @Override
    public int preferredSourceBatchGroupSize() {
        return usesRawFastPath() ? 1 : SOURCE_BATCHES_PER_REQUEST_GROUP;
    }

    @Override
    public long sourceBatchBytes(String collectionName, Document document) {
        return prepareSourceDocument(collectionName, document).sizeBytes();
    }

    @Override
    public PreparedDocument<BulkNdjson.EncodedOperation> prepareSourceDocument(
        String collectionName, Document document
    ) {
        if (!usesRawFastPath()) {
            return new PreparedDocument<>(document, document.sourceLength(), null);
        }
        var prepared = BulkNdjson.encodeRaw(document, collectionName, allowServerGeneratedIds, OBJECT_MAPPER);
        return new PreparedDocument<>(document, prepared.size(), prepared);
    }

    @Override
    public Mono<BatchResult> writeBatchGroup(
        String collectionName,
        List<List<Document>> sourceBatches,
        int requestConcurrency
    ) {
        var preparedBatches = sourceBatches.stream()
            .map(batch -> batch.stream()
                .map(document -> prepareSourceDocument(collectionName, document))
                .toList())
            .toList();
        return writePreparedBatchGroup(collectionName, preparedBatches, requestConcurrency);
    }

    @Override
    public Mono<BatchResult> writePreparedBatchGroup(
        String collectionName,
        List<List<PreparedDocument<BulkNdjson.EncodedOperation>>> sourceBatches,
        int requestConcurrency
    ) {
        long docsInBatch = sourceBatches.stream().mapToLong(List::size).sum();
        long bytesInBatch = sourceBatches.stream()
            .flatMap(List::stream)
            .map(PreparedDocument::document)
            .mapToLong(Document::sourceLength)
            .sum();
        var requestContext = requestContextSupplier != null ? requestContextSupplier.get() : null;
        int splitRequestConcurrency = Math.max(1, Math.min(requestConcurrency, sourceBatches.size()));
        Mono<OpenSearchClient.BulkResponse> bulkMono;
        if (usesRawFastPath()) {
            // Fast path: skip byte[]→Map→byte[] round-trip, write raw source bytes directly
            var documents = sourceBatches.stream().flatMap(List::stream).toList();
            bulkMono = sendRaw(collectionName, documents, requestContext, splitRequestConcurrency);
        } else {
            bulkMono = Flux.fromIterable(sourceBatches)
                .flatMapSequential(batch -> Mono.fromCallable(() -> transformBatch(collectionName, batch))
                    .subscribeOn(Schedulers.parallel()), splitRequestConcurrency, 1)
                .collectList()
                .map(transformedBatches -> transformedBatches.stream().flatMap(List::stream).toList())
                .flatMap(operations ->
                    sendTransformed(collectionName, operations, requestContext, splitRequestConcurrency));
        }

        return bulkMono.map(response -> new BatchResult(docsInBatch, bytesInBatch, response.target,
            response.requests, response.failures));
    }

    private Mono<OpenSearchClient.BulkResponse> sendRaw(
        String collectionName,
        List<PreparedDocument<BulkNdjson.EncodedOperation>> documents,
        IRfsContexts.IRequestContext requestContext,
        int splitRequestConcurrency
    ) {
        var prepared = documents.stream()
            .map(document -> new OpenSearchClient.PreparedRawDocument(
                document.document(),
                document.preparation() != null
                    ? document.preparation()
                    : BulkNdjson.encodeRaw(
                        document.document(), collectionName, allowServerGeneratedIds, OBJECT_MAPPER
                    )))
            .toList();
        return client.sendBulkRequestPreparedRaw(
            collectionName, prepared, requestContext, allowlist, splitRequestConcurrency);
    }

    private Mono<OpenSearchClient.BulkResponse> sendTransformed(
        String collectionName,
        List<? extends BulkOperationSpec> operations,
        IRfsContexts.IRequestContext requestContext,
        int splitRequestConcurrency
    ) {
        return splitRequestConcurrency == 1
            ? client.sendBulkRequest(collectionName, operations, requestContext, allowServerGeneratedIds, allowlist)
            : client.sendBulkRequest(
                collectionName,
                operations,
                requestContext,
                allowServerGeneratedIds,
                allowlist,
                splitRequestConcurrency
            );
    }

    private boolean usesRawFastPath() {
        return transformationMode == TransformationMode.RAW;
    }

    private List<BulkOperationSpec> transformBatch(
        String collectionName,
        List<PreparedDocument<BulkNdjson.EncodedOperation>> batch
    ) {
        var transformerState = acquireTransformer();
        try {
            if (transformationMode == TransformationMode.NATIVE) {
                List<BulkOperationSpec> transformed = batch.stream()
                    .map(PreparedDocument::document)
                    .map(doc -> BulkOperationConverter.fromRawDocument(doc, collectionName))
                    .collect(Collectors.toList());
                for (var nativeTransformer : transformerState.nativeTransformers()) {
                    transformed = nativeTransformer.transformOperations(transformed);
                }
                return transformed;
            }
            var bulkOps = batch.stream()
                .map(PreparedDocument::document)
                .map(doc -> BulkOperationConverter.fromDocument(doc, collectionName))
                .collect(Collectors.toList());
            return applyTransformation(bulkOps, transformerState.transformer());
        } finally {
            releaseTransformer(transformerState);
        }
    }

    private TransformerState acquireTransformer() {
        synchronized (availableTransformers) {
            if (closed) {
                throw new IllegalStateException("Document sink is closed");
            }
            var available = availableTransformers.pollFirst();
            if (available != null) {
                return available;
            }
        }

        var created = makeTransformerState(transformerSupplier.get());
        if (created.mode() != transformationMode) {
            closeTransformer(created.transformer());
            throw new IllegalStateException("Transformer supplier returned inconsistent execution modes");
        }
        synchronized (availableTransformers) {
            if (closed) {
                closeTransformer(created.transformer());
                throw new IllegalStateException("Document sink is closed");
            }
            allTransformers.add(created);
        }
        return created;
    }

    private void releaseTransformer(TransformerState transformerState) {
        synchronized (availableTransformers) {
            if (!closed) {
                availableTransformers.addLast(transformerState);
                return;
            }
        }
        closeTransformer(transformerState.transformer());
    }

    private static TransformerState makeTransformerState(IJsonTransformer transformer) {
        var nonNullTransformer = Objects.requireNonNull(
            transformer,
            "transformerSupplier must not return null"
        );
        var nativeStages = nonNullTransformer.getNativeStages(BulkOperationTransformer.class).orElse(null);
        var mode = nativeStages == null
            ? TransformationMode.JSON
            : nativeStages.isEmpty() ? TransformationMode.RAW : TransformationMode.NATIVE;
        return new TransformerState(nonNullTransformer, nativeStages, mode);
    }

    @Override
    public void close() {
        List<TransformerState> transformers;
        synchronized (availableTransformers) {
            if (closed) {
                return;
            }
            closed = true;
            transformers = List.copyOf(allTransformers);
            availableTransformers.clear();
            allTransformers.clear();
        }
        transformers.forEach(state -> closeTransformer(state.transformer()));
    }

    private static void closeTransformer(IJsonTransformer transformer) {
        try {
            transformer.close();
        } catch (Exception e) {
            log.atWarn().setCause(e).setMessage("Failed to close document transformer").log();
        }
    }

    @SuppressWarnings("unchecked")
    private List<BulkOperationSpec> applyTransformation(
        List<BulkOperationSpec> ops, IJsonTransformer transformer
    ) {
        // Snapshot the original (pre-transformation) document bodies, keyed by document id,
        // so we can re-attach them to the transformed ops below. The transformer round-trips
        // ops through Maps (dropping the in-memory originalSource), and may reorder, add, or
        // drop items, so correlation is by id rather than position.
        Map<String, Map<String, Object>> originalById = new HashMap<>();
        for (BulkOperationSpec op : ops) {
            String id = op.getOriginalSource() != null ? documentIdOf(op) : null;
            if (id != null) {
                originalById.putIfAbsent(id, op.getOriginalSource());
            }
        }
        var asMaps = ops.stream()
            .map(op -> op.toTransformerMap(OBJECT_MAPPER))
            .toList();
        var transformed = transformer.transformJson(asMaps);
        if (transformed instanceof List) {
            return ((List<Map<String, Object>>) transformed).stream()
                .map(item -> {
                    BulkOperationSpec op = OBJECT_MAPPER.convertValue(item, BulkOperationSpec.class);
                    // Re-attach the original source by id. If the transformer changed the id or
                    // synthesized a new document, no original exists; leave it null and the failed document stream
                    // will fall back to the transformed document body.
                    String id = documentIdOf(op);
                    Map<String, Object> original = id != null ? originalById.get(id) : null;
                    if (original != null) {
                        op.setOriginalSource(original);
                    }
                    return op;
                })
                .collect(Collectors.toList());
        }
        return ops;
    }

    private static String documentIdOf(BulkOperationSpec op) {
        var meta = op.getOperation();
        return meta != null ? meta.getId() : null;
    }

    private enum TransformationMode {
        RAW,
        NATIVE,
        JSON
    }

    private record TransformerState(
        IJsonTransformer transformer,
        List<BulkOperationTransformer> nativeTransformers,
        TransformationMode mode
    ) {}
}
