package org.opensearch.migrations.cluster;

/**
 * Queryable feature set for a snapshot reader. Replaces scattered version checks
 * with a typed capabilities model that can be consumed by downstream components
 * (e.g., LuceneIndexReader.Factory) without knowing the source version.
 */
public record SnapshotCapabilities(
    LuceneVersion luceneVersion,
    SoftDeleteSupport softDeletes
) {
    /** Which Lucene index format the snapshot uses */
    public enum LuceneVersion {
        LUCENE_5,  // ES 1.x, 2.x
        LUCENE_6,  // ES 5.x
        LUCENE_7,  // ES 6.x
        LUCENE_9   // ES 7.x+, 8.x, OS 1.x, OS 2.x
    }

    /** Whether soft deletes are present in the Lucene index */
    public sealed interface SoftDeleteSupport {
        record None() implements SoftDeleteSupport {}
        record Supported(String fieldName) implements SoftDeleteSupport {}
    }

    /** Convenience: are soft deletes possible? */
    public boolean softDeletesPossible() {
        return softDeletes instanceof SoftDeleteSupport.Supported;
    }

    /** Convenience: get the soft deletes field name, or null if not supported */
    public String softDeletesFieldName() {
        return softDeletes instanceof SoftDeleteSupport.Supported s ? s.fieldName() : null;
    }
}
