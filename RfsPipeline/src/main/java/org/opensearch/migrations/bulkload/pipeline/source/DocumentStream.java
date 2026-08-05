package org.opensearch.migrations.bulkload.pipeline.source;

import org.opensearch.migrations.bulkload.pipeline.model.Partition;
import org.opensearch.migrations.bulkload.pipeline.model.PositionedDocument;

import reactor.core.publisher.Flux;

/**
 * Streams the documents of a single partition.
 *
 * <h3>Resume contract</h3>
 * Resuming from the cursor emitted with document {@code d} must yield every document the source
 * would have emitted after {@code d}, and must not re-emit {@code d} or anything before it.
 *
 * <p>Duplicate <em>delivery</em> is still expected — a lease that expires after documents were sent
 * but before their cursor was recorded will re-send them. That is the watermark lagging, not the
 * source re-reading.
 */
public interface DocumentStream {

    /**
     * Documents for a partition, resuming after the given cursor ({@code null} starts at the
     * beginning). Returns a cold {@link Flux} — subscription triggers the read, and the same
     * cursor replays identically.
     *
     * <p>May block on I/O; the pipeline subscribes on {@code boundedElastic}. The returned
     * {@code Flux} must honor backpressure.
     */
    Flux<PositionedDocument> readDocuments(Partition partition, String startingCursor);
}
