package org.opensearch.migrations.bulkload.common;

import java.util.List;
import java.util.function.Function;

import org.opensearch.migrations.bulkload.common.OpenSearchClient.BulkResponse;
import org.opensearch.migrations.bulkload.common.SourceFormat.InvalidDocumentSourceException;
import org.opensearch.migrations.bulkload.common.bulk.BulkNdjson;
import org.opensearch.migrations.bulkload.common.bulk.BulkOperationConverter;
import org.opensearch.migrations.bulkload.common.bulk.BulkOperationSpec;
import org.opensearch.migrations.bulkload.common.bulk.IndexOp;
import org.opensearch.migrations.bulkload.common.bulk.metadata.VersionControlMetadata;
import org.opensearch.migrations.bulkload.common.bulk.operations.IndexOperationMeta;
import org.opensearch.migrations.bulkload.common.retry.BulkRetryPolicy;
import org.opensearch.migrations.bulkload.pipeline.BatchSizePredicate;
import org.opensearch.migrations.bulkload.pipeline.model.Document;
import org.opensearch.migrations.bulkload.tracing.IRfsContexts;
import org.opensearch.migrations.reindexer.faileddocumentstream.FailureClass;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

import static org.opensearch.migrations.bulkload.common.OpenSearchClient.MEASURED_REQUEST_BODY;
import static org.opensearch.migrations.bulkload.common.OpenSearchClient.OVERSIZED_OPERATION_WARNING_INTERVAL;
import static org.opensearch.migrations.bulkload.common.OpenSearchClient.RAW_PATH;
import static org.opensearch.migrations.bulkload.common.OpenSearchClient.TRANSFORMED_PATH;

final class BulkRequestPreparer {
    private static final ObjectMapper OBJECT_MAPPER = OpenSearchClient.OBJECT_MAPPER;
    private static final Logger log = LoggerFactory.getLogger(OpenSearchClient.class);

    private final int maxDocsPerBulkRequest;
    private final long maxBytesPerBulkRequest;
    private final FailedDocumentReporter failedDocumentReporter;
    private final BulkRuntime bulkRuntime;
    private final BulkRequestExecutor executor;

    BulkRequestPreparer(
        int maxDocsPerBulkRequest,
        long maxBytesPerBulkRequest,
        FailedDocumentReporter failedDocumentReporter,
        BulkRuntime bulkRuntime,
        BulkRequestExecutor executor
    ) {
        this.maxDocsPerBulkRequest = maxDocsPerBulkRequest;
        this.maxBytesPerBulkRequest = maxBytesPerBulkRequest;
        this.failedDocumentReporter = failedDocumentReporter;
        this.bulkRuntime = bulkRuntime;
        this.executor = executor;
    }

    Mono<BulkResponse> sendBulkRequest(
        String indexName,
        List<? extends BulkOperationSpec> docs,
        IRfsContexts.IRequestContext context,
        Function<BulkRetryPolicy.Batch, Retry> retryStrategy
    ) {
        return sendBulkRequest(indexName, docs, context, false, DocumentExceptionAllowlist.empty(), retryStrategy);
    }

    Mono<BulkResponse> sendBulkRequest(
        String indexName,
        List<? extends BulkOperationSpec> docs,
        IRfsContexts.IRequestContext context,
        boolean allowServerGeneratedIds,
        DocumentExceptionAllowlist allowlist,
        Function<BulkRetryPolicy.Batch, Retry> retryStrategy
    ) {
        return sendBulkRequest(
            indexName,
            docs,
            context,
            allowServerGeneratedIds,
            allowlist,
            1,
            retryStrategy
        );
    }

    Mono<BulkResponse> sendBulkRequest(
        String indexName,
        List<? extends BulkOperationSpec> docs,
        IRfsContexts.IRequestContext context,
        boolean allowServerGeneratedIds,
        DocumentExceptionAllowlist allowlist,
        int splitRequestConcurrency,
        Function<BulkRetryPolicy.Batch, Retry> retryStrategy
    ) {
        return sendBulkRequests(indexName, docs, op -> {
            try {
                return new PreparedBulkOperation(
                    () -> op,
                    BulkNdjson.encode(allowServerGeneratedIds ? stripDocumentId(op) : op, OBJECT_MAPPER)
                );
            } catch (InvalidDocumentSourceException e) {
                failedDocumentReporter.reportInvalidDocumentSource(
                    indexName,
                    op.getOperation().getId(),
                    op.getRawDocument(),
                    e,
                    context
                );
                return null;
            }
        }, TRANSFORMED_PATH, context, allowlist, splitRequestConcurrency, retryStrategy);
    }

    Mono<BulkResponse> sendBulkRequestRaw(
        String indexName,
        List<Document> docs,
        IRfsContexts.IRequestContext context,
        boolean allowServerGeneratedIds,
        DocumentExceptionAllowlist allowlist,
        Function<BulkRetryPolicy.Batch, Retry> retryStrategy
    ) {
        return sendBulkRequestRaw(indexName, docs, context, allowServerGeneratedIds, allowlist, 1, retryStrategy);
    }

    Mono<BulkResponse> sendBulkRequestRaw(
        String indexName,
        List<Document> docs,
        IRfsContexts.IRequestContext context,
        boolean allowServerGeneratedIds,
        DocumentExceptionAllowlist allowlist,
        int splitRequestConcurrency,
        Function<BulkRetryPolicy.Batch, Retry> retryStrategy
    ) {
        return sendBulkRequests(indexName, docs, doc -> {
            try {
                return new PreparedBulkOperation(
                    () -> BulkOperationConverter.fromRawDocument(doc, indexName),
                    BulkNdjson.encodeRaw(doc, indexName, allowServerGeneratedIds, OBJECT_MAPPER)
                );
            } catch (InvalidDocumentSourceException e) {
                failedDocumentReporter.reportInvalidDocumentSource(indexName, doc.id(), doc.source(), e, context);
                return null;
            }
        }, RAW_PATH, context, allowlist, splitRequestConcurrency, retryStrategy);
    }

    Mono<BulkResponse> sendBulkRequestPreparedRaw(
        String indexName,
        List<OpenSearchClient.PreparedRawDocument> docs,
        IRfsContexts.IRequestContext context,
        DocumentExceptionAllowlist allowlist,
        int splitRequestConcurrency,
        Function<BulkRetryPolicy.Batch, Retry> retryStrategy
    ) {
        return sendBulkRequests(
            indexName,
            docs,
            prepared -> new PreparedBulkOperation(
                () -> BulkOperationConverter.fromRawDocument(prepared.document(), indexName),
                prepared.prepared()
            ),
            RAW_PATH,
            context,
            allowlist,
            splitRequestConcurrency,
            retryStrategy
        );
    }

    private <T> Mono<BulkResponse> sendBulkRequests(
        String indexName,
        List<T> docs,
        Function<T, PreparedBulkOperation> prepare,
        String path,
        IRfsContexts.IRequestContext context,
        DocumentExceptionAllowlist allowlist,
        int splitRequestConcurrency,
        Function<BulkRetryPolicy.Batch, Retry> retryStrategy
    ) {
        if (splitRequestConcurrency < 1) {
            throw new IllegalArgumentException(
                "splitRequestConcurrency must be >= 1, got " + splitRequestConcurrency
            );
        }
        return Mono.defer(() -> {
            var tooLarge = new long[1];
            var invalidSources = new long[1];
            var batchPreparationStartedNanos = new long[] {System.nanoTime()};
            return Flux.fromIterable(docs)
                .mapNotNull(doc -> {
                    var prepared = prepare.apply(doc);
                    invalidSources[0] += prepared == null ? 1 : 0;
                    return prepared;
                })
                .filter(op -> {
                    boolean rejected = rejectIfTooLarge(indexName, op, context);
                    tooLarge[0] += rejected ? 1 : 0;
                    return !rejected;
                })
                .bufferUntil(
                    new BatchSizePredicate<>(
                        maxDocsPerBulkRequest,
                        maxBytesPerBulkRequest,
                        (PreparedBulkOperation op) -> op.encoded().size()
                    ),
                    true
                )
                .doOnNext(batch -> {
                    long preparedNanos = System.nanoTime();
                    bulkRuntime.bulkFlowMetrics.onBatchPrepared(
                        path,
                        batch.size(),
                        (preparedNanos - batchPreparationStartedNanos[0]) / 1_000_000.0
                    );
                    batchPreparationStartedNanos[0] = preparedNanos;
                    noteOversizedOperation(indexName, batch, path, context);
                })
                .flatMapSequential(
                    batch -> executor.executeBulkWithRetry(indexName, batch, context, allowlist, retryStrategy),
                    splitRequestConcurrency,
                    1
                )
                .reduce(BulkRequestExecutor::combine)
                .defaultIfEmpty(new BulkResponse(200, "OK", java.util.Map.of(), "{\"errors\":false,\"items\":[]}"))
                .map(response -> response.plusFailures(
                    FailedDocumentReporter.failuresOf(
                        FailureClass.NON_RETRYABLE,
                        tooLarge[0] + invalidSources[0]
                    )
                ));
        });
    }

    private boolean rejectIfTooLarge(
        String indexName,
        PreparedBulkOperation operation,
        IRfsContexts.IRequestContext context
    ) {
        var limit = bulkRuntime.maxDocumentSizeBytes;
        var body = operation.encoded().body();
        if (limit <= 0 || body == null || body.length <= limit) {
            return false;
        }
        failedDocumentReporter.rejectOversizedDocument(
            indexName,
            operation.operation().get(),
            body.length,
            limit,
            MEASURED_REQUEST_BODY,
            context
        );
        return true;
    }

    private void noteOversizedOperation(
        String indexName,
        List<PreparedBulkOperation> batch,
        String path,
        IRfsContexts.IRequestContext context
    ) {
        if (batch.size() != 1) {
            return;
        }
        long size = batch.get(0).encoded().size();
        if (size <= maxBytesPerBulkRequest) {
            return;
        }
        long total = bulkRuntime.oversizedOperations.incrementAndGet();
        if (context != null) {
            context.addBulkOversizedOperation(path);
        }
        long now = System.nanoTime();
        long last = bulkRuntime.lastOversizedWarningNanos.get();
        boolean due = total == 1 || now - last >= OVERSIZED_OPERATION_WARNING_INTERVAL.toNanos();
        if (due && bulkRuntime.lastOversizedWarningNanos.compareAndSet(last, now)) {
            long sinceLastWarning = total - bulkRuntime.oversizedOperationsAtLastWarning.getAndSet(total);
            log.atWarn()
                .setMessage(
                    "Sending a {}-byte operation for index '{}' in a request by itself because it exceeds the "
                        + "{}-byte bulk request limit ({} path). {} such operations so far, {} since the last warning"
                )
                .addArgument(size)
                .addArgument(indexName)
                .addArgument(maxBytesPerBulkRequest)
                .addArgument(path)
                .addArgument(total)
                .addArgument(sinceLastWarning)
                .log();
        }
    }

    private static BulkOperationSpec stripDocumentId(BulkOperationSpec original) {
        if (original instanceof IndexOp indexOp) {
            var metadata = indexOp.getOperation();
            var versioning = metadata.getVersioning();
            var unversioned = versioning != null
                && (versioning.getIfSeqNo() != null || versioning.getIfPrimaryTerm() != null)
                    ? VersionControlMetadata.builder()
                        .ifSeqNo(versioning.getIfSeqNo())
                        .ifPrimaryTerm(versioning.getIfPrimaryTerm())
                        .build()
                    : null;
            var newMetadata = IndexOperationMeta.builder()
                .index(metadata.getIndex())
                .type(metadata.getType())
                .routing(metadata.getRouting())
                .write(metadata.getWrite())
                .versioning(unversioned)
                .opType(metadata.getOpType())
                .build();

            return IndexOp.builder()
                .operation(newMetadata)
                .document(original.getRawDocument() == null ? original.getDocument() : null)
                .rawDocument(original.getRawDocument())
                .includeDocument(original.isIncludeDocument())
                .originalSource(original.getOriginalSourceBytes() == null ? original.getOriginalSource() : null)
                .originalSourceBytes(original.getOriginalSourceBytes())
                .sourceHints(original.getSourceHints())
                .sourceMetadata(original.getSourceMetadata())
                .build();
        }
        return original;
    }
}
