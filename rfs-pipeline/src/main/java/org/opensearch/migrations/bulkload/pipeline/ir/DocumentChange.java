package org.opensearch.migrations.bulkload.pipeline.ir;

import java.util.Objects;

/**
 * Lucene-agnostic document change — the clean IR boundary between reading (any source)
 * and writing (any target).
 *
 * <p>Unlike {@code LuceneDocumentChange}, this type has no {@code luceneDocNumber} or other
 * implementation-specific fields. Progress tracking is handled separately via {@link ProgressCursor}.
 *
 * <p>Note: this record contains a {@code byte[]} field ({@code source}), so the default
 * record {@code equals()}/{@code hashCode()} use reference equality for that field.
 * Use {@link java.util.Arrays#equals(byte[], byte[])} for deep comparison when needed.
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
