package org.opensearch.migrations.bulkload.common;

/** Whole-shard Lucene counts; nested documents are a subset of live documents. */
public record SnapshotDocumentCounts(long liveDocuments, long nestedDocuments, long tombstonedDocuments) {
    public SnapshotDocumentCounts {
        if (liveDocuments < 0 || nestedDocuments < 0 || nestedDocuments > liveDocuments || tombstonedDocuments < 0) {
            throw new IllegalArgumentException("Invalid snapshot document counts");
        }
    }
}
