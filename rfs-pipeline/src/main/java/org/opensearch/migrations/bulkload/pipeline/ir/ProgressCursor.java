package org.opensearch.migrations.bulkload.pipeline.ir;

import java.util.Objects;

/**
 * Progress cursor emitted after each batch is written. Enables resumability —
 * a pipeline can restart from the last successful cursor.
 *
 * <p>When returned by a {@link org.opensearch.migrations.bulkload.pipeline.sink.DocumentSink},
 * {@code lastDocProcessed} is the batch-local count. The pipeline overwrites this with the
 * cumulative offset from the start of the shard for resumability.
 *
 * @param shardId          the shard this cursor belongs to, must not be null
 * @param lastDocProcessed cumulative offset of the last document processed (set by pipeline)
 * @param docsInBatch      the number of documents in this batch
 * @param bytesInBatch     the total bytes of document sources in this batch
 */
public record ProgressCursor(
    ShardId shardId,
    long lastDocProcessed,
    long docsInBatch,
    long bytesInBatch
) {
    public ProgressCursor {
        Objects.requireNonNull(shardId, "shardId must not be null");
        if (docsInBatch < 0) {
            throw new IllegalArgumentException("docsInBatch must be >= 0, got " + docsInBatch);
        }
        if (bytesInBatch < 0) {
            throw new IllegalArgumentException("bytesInBatch must be >= 0, got " + bytesInBatch);
        }
    }
}
