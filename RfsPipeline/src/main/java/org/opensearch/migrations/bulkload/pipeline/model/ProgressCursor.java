package org.opensearch.migrations.bulkload.pipeline.model;

import java.util.Objects;

/**
 * Progress cursor emitted after each batch is written. Enables resumability —
 * a pipeline can restart from the last successful cursor.
 *
 * <p>The position and the document count are deliberately separate fields. They were previously
 * one, which made resume incorrect on nested indices: a count was written to the checkpoint and
 * then read back as a seek position.
 *
 * @param partition             the partition this cursor belongs to, must not be null
 * @param lastDocProcessed      source position of the last document in this batch, from
 *                              {@link Document#position()}. This is what readers seek with; it is
 *                              not a document count, because positions are consumed without
 *                              emitting anything for nested child documents.
 * @param cumulativeDocsEmitted running total of documents emitted for this partition, including
 *                              any total carried in from a prior lease generation. Not a seek
 *                              position.
 * @param docsInBatch           the number of documents in this batch
 * @param bytesInBatch          the total bytes of document sources in this batch
 */
public record ProgressCursor(
    Partition partition,
    long lastDocProcessed,
    long cumulativeDocsEmitted,
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
        if (cumulativeDocsEmitted < 0) {
            throw new IllegalArgumentException(
                "cumulativeDocsEmitted must be >= 0, got " + cumulativeDocsEmitted);
        }
    }
}
