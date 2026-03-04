package org.opensearch.migrations.bulkload.pipeline.ir;

import java.util.Objects;

/**
 * Lucene-agnostic document change — the clean IR boundary between reading (any source)
 * and writing (any target).
 *
 * <p>Unlike {@code LuceneDocumentChange}, this type has no {@code luceneDocNumber} or other
 * implementation-specific fields. Progress tracking is handled separately via {@link ProgressCursor}.
 *
 * <p>This is a value type: two {@code DocumentChange} instances with the same fields are equal.
 *
 * @param id        the document identifier ({@code _id}), must not be null
 * @param type      the document type ({@code _type}), nullable (absent in ES 7+)
 * @param source    the document body ({@code _source}), nullable for DELETE operations
 * @param routing   custom shard routing, nullable
 * @param operation the change type (INDEX or DELETE), must not be null
 */
public record DocumentChange(
    String id,
    String type,
    byte[] source,
    String routing,
    ChangeType operation
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
