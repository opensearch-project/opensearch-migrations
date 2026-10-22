package org.opensearch.migrations.reindexer.faileddocumentstream.source;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;

import org.opensearch.migrations.reindexer.faileddocumentstream.FailureClass;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** Builds sessions the way {@code S3FailedDocumentStreamSink} lays them out. */
public final class FailedDocumentStreamFixtures {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private FailedDocumentStreamFixtures() {}

    /** A bulk operation under {@code requestItem}, plus diagnostics. */
    public static String record(String targetIndex, String documentId, FailureClass failureClass) {
        return record(targetIndex, documentId, failureClass, "mapper_parsing_exception",
            MAPPER.createObjectNode().put("field", "value-for-" + documentId));
    }

    public static String record(
        String targetIndex,
        String documentId,
        FailureClass failureClass,
        String failureType,
        ObjectNode body
    ) {
        var operation = MAPPER.createObjectNode();
        if (documentId != null) {
            operation.put("_id", documentId);
        }
        operation.put("_index", targetIndex);

        var requestItem = MAPPER.createObjectNode();
        requestItem.put("operation_type", "index");
        requestItem.put("include_document", true);
        requestItem.set("operation", operation);
        requestItem.set("document", body);

        var record = MAPPER.createObjectNode();
        record.put("sessionId", "test-session");
        record.put("workerId", "worker-1");
        record.put("targetIndex", targetIndex);
        if (documentId != null) {
            record.put("documentId", documentId);
        }
        record.put("failureType", failureType);
        record.put("failureClass", failureClass.name());
        record.put("timestamp", "2026-05-14T12:00:00Z");
        record.set("requestItem", requestItem);
        record.set("responseItem", MAPPER.createObjectNode().put("status", 400));
        return writeLine(record);
    }

    /** The shape a reader must refuse rather than skip. */
    public static String recordWithoutRequestItem(String targetIndex, String documentId) {
        var record = MAPPER.createObjectNode();
        record.put("targetIndex", targetIndex);
        record.put("documentId", documentId);
        record.put("failureClass", FailureClass.NON_RETRYABLE.name());
        return writeLine(record);
    }

    /** Where one rotation lands. */
    public static String objectKey(String prefix, String sessionId, String index, String worker, int seq) {
        return FailedDocumentStreamLayout.sessionPrefix(prefix, sessionId)
            + "index=" + index + "/worker=" + worker
            + "/failed-document-stream-20260514T120000Z-" + seq + FailedDocumentStreamLayout.RECORD_SUFFIX;
    }

    /** Writes one rotation and returns its key. */
    public static String putRotation(
        InMemoryFailedDocumentStreamObjectStore store,
        String prefix,
        String sessionId,
        String index,
        String worker,
        int seq,
        List<String> records
    ) {
        var key = objectKey(prefix, sessionId, index, worker, seq);
        store.putRecordObject(key, records);
        return key;
    }

    private static String writeLine(ObjectNode node) {
        try {
            return MAPPER.writeValueAsString(node);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
