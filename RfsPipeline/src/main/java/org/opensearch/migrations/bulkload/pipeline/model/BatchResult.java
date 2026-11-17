package org.opensearch.migrations.bulkload.pipeline.model;

/**
 * Result of writing a single batch to the sink. Contains batch-local stats only —
 * the pipeline is responsible for tracking cumulative offsets via {@link ProgressCursor}.
 *
 * @param docsInBatch  the number of documents written in this batch
 * @param bytesInBatch the total source bytes written in this batch
 * @param failures     the batch's target operations that became terminal failures
 */
public record BatchResult(
    long docsInBatch,
    long bytesInBatch,
    MigrationMetrics.Operations target,
    MigrationMetrics.Requests requests,
    MigrationMetrics.Failures failures
) {
    public BatchResult(long docsInBatch, long bytesInBatch) {
        this(docsInBatch, bytesInBatch, MigrationMetrics.Requests.ZERO);
    }

    public BatchResult(long docsInBatch, long bytesInBatch, MigrationMetrics.Requests requests) {
        this(docsInBatch, bytesInBatch, MigrationMetrics.Operations.ZERO, requests);
    }

    public BatchResult(long docsInBatch, long bytesInBatch, MigrationMetrics.Operations target,
                       MigrationMetrics.Requests requests) {
        this(docsInBatch, bytesInBatch, target, requests, MigrationMetrics.Failures.ZERO);
    }

    public BatchResult {
        java.util.Objects.requireNonNull(target);
        java.util.Objects.requireNonNull(requests);
        java.util.Objects.requireNonNull(failures);
        if (docsInBatch < 0) {
            throw new IllegalArgumentException("docsInBatch must be >= 0, got " + docsInBatch);
        }
        if (bytesInBatch < 0) {
            throw new IllegalArgumentException("bytesInBatch must be >= 0, got " + bytesInBatch);
        }
    }
}
