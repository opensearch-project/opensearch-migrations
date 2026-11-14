package org.opensearch.migrations.reindexer.faileddocumentstream;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.Builder;
import lombok.Value;

/**
 * One terminal document failure persisted to the failed document stream.
 *
 * <p>v1 intentionally carries the full per-item OpenSearch response object verbatim
 * in {@link #responseItem} so we don't lose information before we know which fields
 * customers actually need. Bounding/truncation can be added later without changing
 * the schema by reducing field contents server-side.
 */
@Value
@Builder
public class FailedDocumentStreamRecord {
    /** Identifier of the RFS run (workflow UID / session id). Drives the S3 prefix. */
    String sessionId;
    /** Worker process identifier (pod name on EKS). */
    String workerId;
    /** RFS work-coordination work item id (index + shard + checkpoint). May be null. */
    String workItemId;
    /**
     * The index the failed operation was written to: the transformed operation's own index when it has one, else
     * the request's. The stream is partitioned by it.
     */
    String targetIndex;
    /** Document _id when present in the bulk response item; null for server-assigned ids. */
    String documentId;
    /** OpenSearch error type, e.g. "mapper_parsing_exception". */
    String failureType;
    /** Whether this is a non-retryable or retry-exhausted failure. */
    FailureClass failureClass;
    /** When the record was emitted, ISO-8601 (e.g. "2026-05-14T12:00:00Z"). Stored as
     * a String so it round-trips through any JSON consumer without a JSR310 module. */
    String timestamp;
    /**
     * The source document the failed operation came from: {@code documentId} and {@code body}. Present only when
     * the operation could be linked to exactly one source document of its batch by id; a transform that changes ids
     * or makes several operations with one id leaves it null.
     */
    JsonNode source;
    /**
     * The operation as it was sent, after any transformation: {@code effectiveIndex}, {@code documentId},
     * {@code operationType}, the bulk {@code action} metadata, and the {@code body}.
     */
    JsonNode target;
    /** The full per-item response object from the OpenSearch bulk response, as JSON. */
    JsonNode responseItem;
}
