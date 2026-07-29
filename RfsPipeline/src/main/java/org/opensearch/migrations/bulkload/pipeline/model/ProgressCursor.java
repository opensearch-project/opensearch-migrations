package org.opensearch.migrations.bulkload.pipeline.model;

import java.util.Objects;

/**
 * Progress cursor emitted after each batch is written. Enables resumability —
 * a pipeline can restart from the last successful cursor.
 *
 * <p>Constructed by the pipeline from the last {@link Document} in the batch plus the
 * sink's {@link BatchResult} stats.
 *
 * <h3>Two distinct quantities</h3>
 * {@code lastDocProcessed} and {@code cumulativeDocsEmitted} are deliberately separate.
 * They were previously one field, which is what made resume incorrect on nested indices:
 * a count was written to the checkpoint and then read back as a seek position.
 *
 * @param partition             the partition this cursor belongs to, must not be null
 * @param lastDocProcessed      SEEK POSITION — the source-defined position of the last
 *                              document processed in this batch. For Lucene-backed sources
 *                              this is the Lucene doc number (see
 *                              {@link Document#SOURCE_META_LUCENE_DOC_NUMBER}). This is the
 *                              only value that can be resumed from: readers seek with it
 *                              directly ({@code LuceneReader.readDocsFromSegment} computes
 *                              {@code position - segmentDocBase}, and
 *                              {@code getSegmentsFromStartingSegment} binary-searches it
 *                              against segment doc bases). It is NOT a document count —
 *                              readers consume positions without emitting anything for
 *                              nested child documents, which carry no stored {@code _id}.
 * @param cumulativeDocsEmitted PROGRESS/ACCOUNTING — running total of documents actually
 *                              emitted to the sink for this partition, including any
 *                              carried-in total from a prior lease generation. Monotonic and
 *                              independent of source-position density, so it is the right
 *                              value for reporting and for the live non-nested doc count.
 *                              Not usable as a seek position.
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
