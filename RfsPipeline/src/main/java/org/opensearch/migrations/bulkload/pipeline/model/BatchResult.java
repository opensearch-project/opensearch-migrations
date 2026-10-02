package org.opensearch.migrations.bulkload.pipeline.model;

import java.util.Objects;

/**
 * Result of writing a single batch to the sink.
 *
 * <p>{@code cursorAfter} is the source cursor the sink wrote through. The pipeline cannot derive it
 * arithmetically — an opaque cursor has no arithmetic — so the sink reports it.
 *
 * @param docsInBatch  the number of documents written in this batch
 * @param bytesInBatch the total source bytes written in this batch
 * @param cursorAfter  the cursor that resumes after this batch, must not be null
 */
public record BatchResult(
    long docsInBatch,
    long bytesInBatch,
    String cursorAfter
) {
    public BatchResult {
        if (docsInBatch < 0) {
            throw new IllegalArgumentException("docsInBatch must be >= 0, got " + docsInBatch);
        }
        if (bytesInBatch < 0) {
            throw new IllegalArgumentException("bytesInBatch must be >= 0, got " + bytesInBatch);
        }
        Objects.requireNonNull(cursorAfter, "cursorAfter must not be null");
    }
}
