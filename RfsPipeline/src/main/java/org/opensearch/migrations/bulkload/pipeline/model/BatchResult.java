package org.opensearch.migrations.bulkload.pipeline.model;

import java.util.Map;

/**
 * Result of writing a single batch to the sink. Contains batch-local stats only —
 * the pipeline is responsible for tracking cumulative offsets via {@link ProgressCursor}.
 *
 * @param docsInBatch   the number of source documents read for this batch (drives the progress offset)
 * @param bytesInBatch  the total source bytes written in this batch
 * @param docsSucceeded the number of bulk items the target acknowledged (including allowlisted errors)
 * @param docsFailed    the number of bulk items that failed terminally (non-retryable)
 * @param failedByType  {@code docsFailed} broken down by bulk error type
 */
public record BatchResult(
    long docsInBatch,
    long bytesInBatch,
    long docsSucceeded,
    long docsFailed,
    Map<String, Long> failedByType
) {
    public BatchResult {
        if (docsInBatch < 0) {
            throw new IllegalArgumentException("docsInBatch must be >= 0, got " + docsInBatch);
        }
        if (bytesInBatch < 0) {
            throw new IllegalArgumentException("bytesInBatch must be >= 0, got " + bytesInBatch);
        }
        if (docsSucceeded < 0) {
            throw new IllegalArgumentException("docsSucceeded must be >= 0, got " + docsSucceeded);
        }
        if (docsFailed < 0) {
            throw new IllegalArgumentException("docsFailed must be >= 0, got " + docsFailed);
        }
        failedByType = failedByType == null ? Map.of() : Map.copyOf(failedByType);
    }

    /** For sinks that don't report per-item outcomes: every document in the batch is treated as succeeded. */
    public BatchResult(long docsInBatch, long bytesInBatch) {
        this(docsInBatch, bytesInBatch, docsInBatch, 0, Map.of());
    }
}
