package org.opensearch.migrations.bulkload.common;

import java.time.Instant;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicReference;

import org.opensearch.migrations.bulkload.common.bulk.BulkOperationSpec;
import org.opensearch.migrations.bulkload.common.bulk.metadata.BaseMetadata;
import org.opensearch.migrations.bulkload.pipeline.model.MigrationMetrics;
import org.opensearch.migrations.bulkload.tracing.IRfsContexts;
import org.opensearch.migrations.parsing.BulkResponseParser.ItemFailure;
import org.opensearch.migrations.reindexer.faileddocumentstream.FailedDocumentStreamRecord;
import org.opensearch.migrations.reindexer.faileddocumentstream.FailedDocumentStreamSink;
import org.opensearch.migrations.reindexer.faileddocumentstream.FailureClass;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class FailedDocumentReporter {
    private static final ObjectMapper OBJECT_MAPPER = OpenSearchClient.OBJECT_MAPPER;
    private static final Logger log = LoggerFactory.getLogger(OpenSearchClient.class);
    private static final String DOCUMENT_ID_FIELD = "documentId";
    private static final String INDEX_OPERATION = "index";

    private final AtomicReference<FailedDocumentStreamSink> sink = new AtomicReference<>();
    private volatile String sessionId = "unknown-session";
    private volatile String workerId = "unknown-worker";
    private volatile String workItemId;

    void setContext(FailedDocumentStreamSink failedDocumentStreamSink, String newSessionId, String newWorkerId) {
        sink.set(failedDocumentStreamSink);
        sessionId = newSessionId;
        workerId = newWorkerId;
    }

    void setWorkItem(String newWorkItemId) {
        workItemId = newWorkItemId;
    }

    FailedDocumentStreamSink getSink() {
        return sink.get();
    }

    void rejectOversizedDocument(
        String indexName,
        BulkOperationSpec op,
        long size,
        long limit,
        String measured,
        IRfsContexts.IRequestContext context
    ) {
        var docId = extractDocumentId(op);
        log.atWarn()
            .setMessage("Not sending document '{}' for index '{}': its {} is {} bytes, over maxDocumentSizeBytes {}")
            .addArgument(docId)
            .addArgument(indexName)
            .addArgument(measured)
            .addArgument(size)
            .addArgument(limit)
            .log();
        if (sink.get() == null) {
            log.atError()
                .setMessage(
                    "Document '{}' for index '{}' was skipped for its size and, with no failed document "
                        + "stream configured, is not recorded anywhere"
                )
                .addArgument(docId)
                .addArgument(indexName)
                .log();
        }
        var detail = OBJECT_MAPPER.createObjectNode()
            .put("size", size)
            .put("limit", limit)
            .put("measured", measured);
        emit(
            indexName,
            op,
            new ItemFailure(0, docId, OpenSearchClient.DOCUMENT_TOO_LARGE, detail.toString()),
            FailureClass.NON_RETRYABLE
        );
        if (context != null) {
            context.addDocumentsRejectedForSize(1);
            context.addBulkDocumentsFailed(FailureClass.NON_RETRYABLE.name(), 1);
        }
    }

    void reportInvalidDocumentSource(
        String indexName,
        String docId,
        byte[] source,
        Exception cause,
        IRfsContexts.IRequestContext context
    ) {
        log.atWarn()
            .setCause(cause)
            .setMessage("Skipping document {} for index '{}': its source cannot be sent as JSON")
            .addArgument(docId)
            .addArgument(indexName)
            .log();
        if (context != null) {
            context.addBulkDocumentsFailed(FailureClass.NON_RETRYABLE.name(), 1);
        }
        var currentSink = sink.get();
        if (currentSink == null) {
            return;
        }
        try {
            var sourceItem = OBJECT_MAPPER.createObjectNode();
            sourceItem.put(DOCUMENT_ID_FIELD, docId);
            if (source != null) {
                sourceItem.put("bodyBase64", Base64.getEncoder().encodeToString(source));
            }
            var targetItem = OBJECT_MAPPER.createObjectNode();
            targetItem.put("effectiveIndex", indexName);
            targetItem.put(DOCUMENT_ID_FIELD, docId);
            targetItem.put("operationType", INDEX_OPERATION);
            targetItem.putObject("action").put("_index", indexName).put("_id", docId);
            var responseItem = OBJECT_MAPPER.createObjectNode();
            responseItem.putObject(INDEX_OPERATION)
                .put("_index", indexName)
                .put("_id", docId)
                .put("status", 400)
                .putObject("error")
                .put("type", OpenSearchClient.INVALID_SOURCE_FORMAT)
                .put("reason", cause.getMessage());
            currentSink.write(
                FailedDocumentStreamRecord.builder()
                    .sessionId(sessionId)
                    .workerId(workerId)
                    .workItemId(workItemId)
                    .targetIndex(indexName)
                    .documentId(docId)
                    .failureType(OpenSearchClient.INVALID_SOURCE_FORMAT)
                    .failureClass(FailureClass.NON_RETRYABLE)
                    .timestamp(Instant.now().toString())
                    .source(sourceItem)
                    .target(targetItem)
                    .responseItem(responseItem)
                    .build()
            ).subscribe(
                v -> {},
                err -> log.atError()
                    .setCause(err)
                    .setMessage("Failed to buffer failed document stream record for index {}")
                    .addArgument(indexName)
                    .log()
            );
        } catch (Exception e) {
            log.atError()
                .setCause(e)
                .setMessage("Unexpected error while emitting failed document stream record for index {}")
                .addArgument(indexName)
                .log();
        }
    }

    void emit(String indexName, BulkOperationSpec op, ItemFailure failure, FailureClass failureClass) {
        var currentSink = sink.get();
        if (currentSink == null) {
            return;
        }
        try {
            String docId = failure != null && failure.getDocumentId() != null
                ? failure.getDocumentId()
                : extractDocumentId(op);
            String effectiveIndex = effectiveIndexOf(op, indexName);
            JsonNode responseItem = failure != null && failure.getResponseItemJson() != null
                ? OBJECT_MAPPER.readTree(failure.getResponseItemJson())
                : null;
            String failureType = failure != null ? failure.getErrorType() : null;
            var streamRecord = FailedDocumentStreamRecord.builder()
                .sessionId(sessionId)
                .workerId(workerId)
                .workItemId(workItemId)
                .targetIndex(effectiveIndex)
                .documentId(docId)
                .failureType(failureType)
                .failureClass(failureClass)
                .timestamp(Instant.now().toString())
                .source(sourcePortion(op))
                .target(targetPortion(op, effectiveIndex, docId))
                .responseItem(responseItem)
                .build();
            currentSink.write(streamRecord).subscribe(
                v -> {},
                err -> log.atError()
                    .setCause(err)
                    .setMessage("Failed to buffer failed document stream record for index {}: {}")
                    .addArgument(indexName)
                    .addArgument(err.getMessage())
                    .log()
            );
        } catch (Exception e) {
            log.atError()
                .setCause(e)
                .setMessage("Unexpected error while emitting failed document stream record for index {}")
                .addArgument(indexName)
                .log();
        }
    }

    static MigrationMetrics.Failures failuresOf(FailureClass failureClass, long count) {
        return failureClass == FailureClass.NON_RETRYABLE
            ? new MigrationMetrics.Failures(count, 0)
            : new MigrationMetrics.Failures(0, count);
    }

    static String effectiveIndexOf(BulkOperationSpec op, String requestIndex) {
        var index = op != null && op.getOperation() instanceof BaseMetadata meta ? meta.getIndex() : null;
        return index != null && !index.isBlank() ? index : requestIndex;
    }

    private static JsonNode sourcePortion(BulkOperationSpec op) {
        var original = op != null ? op.getOriginalSource() : null;
        if (original == null) {
            return null;
        }
        var source = OBJECT_MAPPER.createObjectNode();
        source.put(DOCUMENT_ID_FIELD, extractDocumentId(op));
        source.set("body", OBJECT_MAPPER.valueToTree(original));
        return source;
    }

    private static JsonNode targetPortion(BulkOperationSpec op, String effectiveIndex, String documentId) {
        if (op == null) {
            return null;
        }
        var target = OBJECT_MAPPER.createObjectNode();
        target.put("effectiveIndex", effectiveIndex);
        target.put(DOCUMENT_ID_FIELD, documentId);
        target.put("operationType", String.valueOf(op.getOperationType()));
        target.set("action", OBJECT_MAPPER.valueToTree(op.getOperation()));
        if (op.isIncludeDocument()) {
            target.set("body", OBJECT_MAPPER.valueToTree(op.getDocument()));
        }
        return target;
    }

    static String extractDocumentId(BulkOperationSpec op) {
        if (op == null) {
            return null;
        }
        try {
            JsonNode tree = OBJECT_MAPPER.valueToTree(op);
            if (tree == null) {
                return null;
            }
            JsonNode meta = tree.path("operation").path("_id");
            if (meta.isMissingNode() || meta.isNull()) {
                meta = tree.path(INDEX_OPERATION).path("_id");
            }
            if (meta.isMissingNode() || meta.isNull()) {
                meta = tree.path("create").path("_id");
            }
            if (meta.isMissingNode() || meta.isNull()) {
                meta = tree.path("update").path("_id");
            }
            return meta.isMissingNode() || meta.isNull() ? null : meta.asText();
        } catch (Exception e) {
            return null;
        }
    }
}
