package org.opensearch.migrations.bulkload.pipeline.model;

import java.util.Objects;

/**
 * Progress cursor emitted after each batch is written. Enables resumability —
 * a pipeline can restart from the last successful cursor.
 *
 * <p>The checkpoint is the source document's position, independent of batch statistics.
 * Sparse Lucene positions include nested children and deleted documents that are not emitted.
 *
 * @param partition        the partition this cursor belongs to, must not be null
 * @param lastDocProcessed inclusive source position, or exclusive ordinal for a positionless source
 * @param docsInBatch      the number of documents in this batch
 * @param bytesInBatch     the total bytes of document sources in this batch
 */
public record ProgressCursor(
    Partition partition,
    long lastDocProcessed,
    long docsInBatch,
    long bytesInBatch
) {
    public ProgressCursor {
        Objects.requireNonNull(partition, "partition must not be null");
        if (docsInBatch < 0) {
            throw new IllegalArgumentException("docsInBatch must be >= 0, got " + docsInBatch);
        }
        if (bytesInBatch < 0) {
            throw new IllegalArgumentException("bytesInBatch must be >= 0, got " + bytesInBatch);
        }
    }
}
