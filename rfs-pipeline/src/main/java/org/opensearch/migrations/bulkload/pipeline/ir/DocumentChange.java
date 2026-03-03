package org.opensearch.migrations.bulkload.pipeline.ir;

import java.util.Objects;

/**
 * Document change — the IR boundary between reading (any source) and writing (any target).
 *
 * <p>Carries an optional {@code luceneDocNumber} for Lucene-based sources, used by the pipeline
 * to track progress as the minimum doc number across in-flight batches. Non-Lucene sources
 * should set this to -1.
 *
 * <p>This is a value type: two {@code DocumentChange} instances with the same fields are equal.
 *
 * @param id              the document identifier ({@code _id}), must not be null
 * @param type            the document type ({@code _type}), nullable (absent in ES 7+)
 * @param source          the document body ({@code _source}), nullable for DELETE operations
 * @param routing         custom shard routing, nullable
 * @param operation       the change type (INDEX or DELETE), must not be null
 * @param luceneDocNumber the Lucene doc number (segmentDocBase + docId), or -1 if not from Lucene
 */
public record DocumentChange(
    String id,
    String type,
    byte[] source,
    String routing,
    ChangeType operation,
    int luceneDocNumber
) {
    /** The type of document change. */
    public enum ChangeType {
        /** Index (create or update) a document. */
        INDEX,
        /** Delete a document. */
        DELETE
    }

    /**
     * Compact constructor with validation.
     */
    public DocumentChange {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(operation, "operation must not be null");
    }
}
