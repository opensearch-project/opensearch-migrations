package org.opensearch.migrations.bulkload.pipeline.model;

/**
 * Result of writing a single batch to the sink. Contains batch-local stats only —
 * the pipeline is responsible for tracking cumulative offsets via {@link ProgressCursor}.
 *
 * @param docsInBatch  the number of documents written in this batch
 * @param bytesInBatch the total source bytes written in this batch
 */
public record BatchResult(
    long docsInBatch,
    long bytesInBatch,
    MigrationMetrics.Requests requests
) {
    public BatchResult(long docsInBatch, long bytesInBatch) {
        this(docsInBatch, bytesInBatch, MigrationMetrics.Requests.ZERO);
    }

    public BatchResult {
        java.util.Objects.requireNonNull(requests);
        if (docsInBatch < 0) {
            throw new IllegalArgumentException("docsInBatch must be >= 0, got " + docsInBatch);
        }
        if (bytesInBatch < 0) {
            throw new IllegalArgumentException("bytesInBatch must be >= 0, got " + bytesInBatch);
        }
    }
}
