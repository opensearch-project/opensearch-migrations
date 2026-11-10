package org.opensearch.migrations.bulkload.pipeline.adapter;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import org.opensearch.migrations.bulkload.common.CollectionRoutedTarget;
import org.opensearch.migrations.bulkload.common.DocumentExceptionAllowlist;
import org.opensearch.migrations.bulkload.common.ObjectMapperFactory;
import org.opensearch.migrations.bulkload.common.OpenSearchClient;
import org.opensearch.migrations.bulkload.common.SourceFormat;
import org.opensearch.migrations.bulkload.common.bulk.BulkNdjson;
import org.opensearch.migrations.bulkload.common.bulk.BulkOperationConverter;
import org.opensearch.migrations.bulkload.common.bulk.BulkOperationSpec;
import org.opensearch.migrations.bulkload.pipeline.model.BatchResult;
import org.opensearch.migrations.bulkload.pipeline.model.CollectionMetadata;
import org.opensearch.migrations.bulkload.pipeline.model.Document;
import org.opensearch.migrations.bulkload.pipeline.model.MigrationMetrics;
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
    private final CollectionRoutedTarget collectionRoutedTarget;

    public OpenSearchDocumentSink(
        OpenSearchClient client,
        Supplier<IJsonTransformer> transformerSupplier,
        boolean allowServerGeneratedIds,
        DocumentExceptionAllowlist allowlist,
        Supplier<IRfsContexts.IRequestContext> requestContextSupplier
    ) {
        this(client, transformerSupplier, allowServerGeneratedIds, allowlist, requestContextSupplier, null);
    }

    /**
     * @param collectionRoutedTarget routes each source index to a serverless collection and decides
     *                               server-generated IDs per collection; null for other targets
     */
    public OpenSearchDocumentSink(
        OpenSearchClient client,
        Supplier<IJsonTransformer> transformerSupplier,
        boolean allowServerGeneratedIds,
        DocumentExceptionAllowlist allowlist,
        Supplier<IRfsContexts.IRequestContext> requestContextSupplier,
        CollectionRoutedTarget collectionRoutedTarget
    ) {
        this.collectionRoutedTarget = collectionRoutedTarget;
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
            var targetClient = collectionRoutedTarget != null
                ? client.forCollection(collectionRoutedTarget.collectionFor(metadata.name()))
                : client;
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
                OpenSearchIndexCreator.createIndex(targetClient, esMetadata, OBJECT_MAPPER, null);
            } else if (sourceConfig.containsKey(CollectionMetadata.ES_MAPPINGS)) {
                // Non-ES source with mappings (e.g. Solr): create index with mappings only
                var body = OBJECT_MAPPER.createObjectNode();
                body.set("mappings", (com.fasterxml.jackson.databind.node.ObjectNode) sourceConfig.get(CollectionMetadata.ES_MAPPINGS));
                targetClient.createIndex(metadata.name(), body, null);
            } else {
                // Unknown source: create index with defaults
                targetClient.createIndex(metadata.name(), OBJECT_MAPPER.createObjectNode(), null);
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
    public boolean supportsPersistentPreparationLanes() {
        return usesRawFastPath();
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
        try {
            var prepared = BulkNdjson.encodeRaw(
                document, collectionName, useServerGeneratedIds(collectionName), OBJECT_MAPPER);
            return new PreparedDocument<>(document, prepared.size(), prepared);
        } catch (SourceFormat.InvalidDocumentSourceException e) {
            // Keep the source flowing to the lane that owns its checkpoint. The lane records and drops it.
            return new PreparedDocument<>(document, document.sourceLength(), null);
        }
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
        // collectionName is the source index; routing by it holds even when a transformer renames _index
        var serverlessCollection = collectionRoutedTarget != null
            ? collectionRoutedTarget.collectionFor(collectionName)
            : null;
        var useServerGeneratedIds = serverlessCollection != null
            ? collectionRoutedTarget.requiresServerGeneratedIds(serverlessCollection)
            : allowServerGeneratedIds;
        var targetClient = serverlessCollection != null ? client.forCollection(serverlessCollection) : client;
        var invalidDocuments = new AtomicLong();
        Mono<OpenSearchClient.BulkResponse> bulkMono;
        if (usesRawFastPath()) {
            // Fast path: skip byte[]→Map→byte[] round-trip, write raw source bytes directly
            var documents = sourceBatches.stream().flatMap(List::stream).toList();
            bulkMono = sendRaw(targetClient, collectionName, documents, requestContext,
                useServerGeneratedIds, splitRequestConcurrency, invalidDocuments);
        } else {
            bulkMono = Flux.fromIterable(sourceBatches)
                .flatMapSequential(batch -> Mono.fromCallable(
                    () -> transformBatch(targetClient, collectionName, batch, invalidDocuments, requestContext))
                    .subscribeOn(Schedulers.parallel()), splitRequestConcurrency, 1)
                .collectList()
                .map(transformedBatches -> transformedBatches.stream().flatMap(List::stream).toList())
                .flatMap(operations -> sendTransformed(targetClient, collectionName, operations,
                    requestContext, useServerGeneratedIds, splitRequestConcurrency));
        }

        return bulkMono.map(response -> new BatchResult(docsInBatch, bytesInBatch, response.target,
            response.requests, response.failures.plus(
                new MigrationMetrics.Failures(invalidDocuments.get(), 0))));
    }

    private Mono<OpenSearchClient.BulkResponse> sendRaw(
        OpenSearchClient targetClient,
        String collectionName,
        List<PreparedDocument<BulkNdjson.EncodedOperation>> documents,
        IRfsContexts.IRequestContext requestContext,
        boolean useServerGeneratedIds,
        int splitRequestConcurrency,
        AtomicLong invalidDocuments
    ) {
        var prepared = new ArrayList<OpenSearchClient.PreparedRawDocument>(documents.size());
        for (var document : documents) {
            try {
                var encoded = document.preparation() != null
                    ? document.preparation()
                    : BulkNdjson.encodeRaw(
                        document.document(), collectionName, useServerGeneratedIds, OBJECT_MAPPER);
                prepared.add(new OpenSearchClient.PreparedRawDocument(document.document(), encoded));
            } catch (SourceFormat.InvalidDocumentSourceException e) {
                targetClient.reportInvalidDocumentSource(
                    collectionName, document.document().id(), document.document().source(), e, requestContext);
                invalidDocuments.incrementAndGet();
            }
        }
        return targetClient.sendBulkRequestPreparedRaw(
            collectionName, prepared, requestContext, allowlist, splitRequestConcurrency);
    }

    private Mono<OpenSearchClient.BulkResponse> sendTransformed(
        OpenSearchClient targetClient,
        String collectionName,
        List<? extends BulkOperationSpec> operations,
        IRfsContexts.IRequestContext requestContext,
        boolean useServerGeneratedIds,
        int splitRequestConcurrency
    ) {
        return splitRequestConcurrency == 1
            ? targetClient.sendBulkRequest(collectionName, operations, requestContext, useServerGeneratedIds, allowlist)
            : targetClient.sendBulkRequest(
                collectionName,
                operations,
                requestContext,
                useServerGeneratedIds,
                allowlist,
                splitRequestConcurrency
            );
    }

    private boolean usesRawFastPath() {
        return transformationMode == TransformationMode.RAW;
    }

    private List<BulkOperationSpec> transformBatch(
        OpenSearchClient targetClient,
        String collectionName,
        List<PreparedDocument<BulkNdjson.EncodedOperation>> batch,
        AtomicLong invalidDocuments,
        IRfsContexts.IRequestContext requestContext
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
            var bulkOps = new ArrayList<BulkOperationSpec>(batch.size());
            for (var prepared : batch) {
                var document = prepared.document();
                try {
                    bulkOps.add(BulkOperationConverter.fromDocument(document, collectionName));
                } catch (SourceFormat.InvalidDocumentSourceException e) {
                    targetClient.reportInvalidDocumentSource(
                        collectionName, document.id(), document.source(), e, requestContext);
                    invalidDocuments.incrementAndGet();
                }
            }
            return applyTransformation(bulkOps, transformerState.transformer());
        } finally {
            releaseTransformer(transformerState);
        }
    }

    private boolean useServerGeneratedIds(String collectionName) {
        if (collectionRoutedTarget == null) {
            return allowServerGeneratedIds;
        }
        return collectionRoutedTarget.requiresServerGeneratedIds(
            collectionRoutedTarget.collectionFor(collectionName));
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
        TransformationMode mode;
        if (nativeStages == null) {
            mode = TransformationMode.JSON;
        } else {
            mode = nativeStages.isEmpty() ? TransformationMode.RAW : TransformationMode.NATIVE;
        }
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

    @SuppressWarnings({"java:S100", "java:S1172", "java:S1186"})
    private record TransformerState(
        IJsonTransformer transformer,
        List<BulkOperationTransformer> nativeTransformers,
        TransformationMode mode
    ) {}
}
