package org.opensearch.migrations.bulkload.pipeline.ir;

/**
 * Result of writing a single batch to the sink. Contains batch-local stats only —
 * the pipeline is responsible for tracking cumulative offsets via {@link ProgressCursor}.
 *
 * @param docsInBatch  the number of documents written in this batch
 * @param bytesInBatch the total source bytes written in this batch
 */
public record BatchResult(
    long docsInBatch,
    long bytesInBatch
) {
    public BatchResult {
        if (docsInBatch < 0) {
            throw new IllegalArgumentException("docsInBatch must be >= 0, got " + docsInBatch);
        }
        if (bytesInBatch < 0) {
            throw new IllegalArgumentException("bytesInBatch must be >= 0, got " + bytesInBatch);
        }
    }
}
