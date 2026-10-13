package org.opensearch.migrations.bulkload.pipeline.model;

import java.util.Map;
import java.util.Objects;

/**
 * Progress cursor emitted after each batch is written. Enables resumability —
 * a pipeline can restart from the last successful cursor.
 *
 * <p>Constructed by the pipeline with cumulative offset tracking. The sink returns
 * {@link BatchResult} with batch-local stats, and the pipeline wraps it into a cursor.
 *
 * @param partition        the partition this cursor belongs to, must not be null
 * @param lastDocProcessed cumulative offset of the last document processed
 * @param docsInBatch      the number of documents in this batch
 * @param bytesInBatch     the total bytes of document sources in this batch
 * @param docsSucceeded    the number of bulk items in this batch the target acknowledged
 * @param docsFailed       the number of bulk items in this batch that failed terminally
 * @param failedByType     {@code docsFailed} broken down by bulk error type
 */
public record ProgressCursor(
    Partition partition,
    long lastDocProcessed,
    long docsInBatch,
    long bytesInBatch,
    long docsSucceeded,
    long docsFailed,
    Map<String, Long> failedByType
) {
    public ProgressCursor {
        Objects.requireNonNull(partition, "partition must not be null");
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

    /** Every document in the batch is treated as succeeded. */
    public ProgressCursor(Partition partition, long lastDocProcessed, long docsInBatch, long bytesInBatch) {
        this(partition, lastDocProcessed, docsInBatch, bytesInBatch, docsInBatch, 0, Map.of());
    }
}
