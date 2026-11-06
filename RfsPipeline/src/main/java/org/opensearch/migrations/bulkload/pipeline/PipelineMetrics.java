package org.opensearch.migrations.bulkload.pipeline;

/**
 * Low-overhead events that expose where documents and bytes wait in the migration pipeline.
 *
 * <p>Implementations must be cheap and thread-safe. Events are emitted once per preparation chunk, packed batch,
 * request group, or checkpoint; never once per document.
 */
public interface PipelineMetrics {
    PipelineMetrics NONE = new PipelineMetrics() {};

    /** Effective pipeline settings for this process, recorded once when the pipeline is constructed. */
    default void onConfigured(
        long maxBatchDocuments,
        long maxBatchBytes,
        long batchConcurrency,
        long preparationConcurrency,
        long preparationChunkDocuments
    ) {}

    /** Source documents have been collected into a chunk and are waiting for a preparation rail. */
    default void onPreparationChunkQueued(long documents, long sourceBytes) {}

    /** A preparation rail started the chunk after {@code queueMillis}. */
    default void onPreparationChunkStarted(long documents, long sourceBytes, double queueMillis) {}

    /**
     * A preparation rail stopped working on the chunk. A successful chunk is held by the ordered fan-in until
     * {@link #onPreparationChunkReleased(long, long, double)}.
     */
    default void onPreparationChunkFinished(
        long documents,
        long sourceBytes,
        long preparedBytes,
        double durationMillis,
        boolean successful
    ) {}

    /** The ordered preparation fan-in released a completed chunk after holding it for {@code orderedWaitMillis}. */
    default void onPreparationChunkReleased(long documents, long preparedBytes, double orderedWaitMillis) {}

    /** The serial exact-size packer consumed one prepared chunk. */
    default void onPackingChunk(long documents, double durationMillis) {}

    /** One exact-size target batch became available to the request fan-out. */
    default void onBatchPacked(long documents, long preparedBytes, double documentFillPercent, double byteFillPercent) {}

    /**
     * The request fan-out subscribed to a group containing {@code batches} packed batches. {@code oldestReadyMillis}
     * is the age of the oldest batch in the group.
     */
    default void onRequestGroupStarted(
        long batches,
        long documents,
        long preparedBytes,
        double oldestReadyMillis
    ) {}

    /** A request group stopped running. */
    default void onRequestGroupFinished(
        long documents,
        long preparedBytes,
        double durationMillis,
        boolean successful
    ) {}

    /** Source progress became eligible to advance past these documents. */
    default void onCheckpoint(long documents, long sourceBytes) {}

    /** Clear gauges when a pipeline terminates or is cancelled. */
    default void onPipelineClosed() {}
}
