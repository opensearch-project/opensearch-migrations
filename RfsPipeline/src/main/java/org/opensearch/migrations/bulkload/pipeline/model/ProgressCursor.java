package org.opensearch.migrations.bulkload.pipeline.model;

import java.util.Objects;

/**
 * Progress cursor emitted after each batch is written. Enables resumability — a pipeline can
 * restart from the last successful cursor.
 *
 * <p>{@code cursorAfter} comes from the source by way of {@link BatchResult}; the pipeline only
 * forwards it. Resuming a partition from it must not re-emit anything already covered.
 *
 * @param partition    the partition this cursor belongs to, must not be null
 * @param cursorAfter  the source-defined cursor that resumes after this batch, must not be null
 * @param docsInBatch  the number of documents in this batch
 * @param bytesInBatch the total bytes of document sources in this batch
 */
public record ProgressCursor(
    Partition partition,
    String cursorAfter,
    long docsInBatch,
    long bytesInBatch
) {
    public ProgressCursor {
        Objects.requireNonNull(partition, "partition must not be null");
        Objects.requireNonNull(cursorAfter, "cursorAfter must not be null");
        if (docsInBatch < 0) {
            throw new IllegalArgumentException("docsInBatch must be >= 0, got " + docsInBatch);
        }
        if (bytesInBatch < 0) {
            throw new IllegalArgumentException("bytesInBatch must be >= 0, got " + bytesInBatch);
        }
    }
}
