package org.opensearch.migrations.bulkload.pipeline.metrics;

import org.opensearch.migrations.bulkload.pipeline.source.SourceType;

/**
 * Metrics interface for tracking source extraction performance.
 *
 * <p>Implementations can report to OpenTelemetry, Micrometer, or any other metrics backend.
 * The pipeline calls these methods during document extraction to track parsing performance
 * across different source types.
 */
public interface SourceExtractionMetrics {

    /** Record that a document was successfully parsed from the source. */
    void recordDocumentParsed(SourceType sourceType, long bytesRead);

    /** Record a parsing error. */
    void recordParseError(SourceType sourceType, String errorType);

    /** Record the time taken to read a batch of documents. */
    void recordBatchReadDuration(SourceType sourceType, long durationMs, int docCount);

    /** Record shard-level extraction start. */
    void recordShardExtractionStarted(SourceType sourceType, String indexName, int shardNumber);

    /** Record shard-level extraction completion. */
    void recordShardExtractionCompleted(SourceType sourceType, String indexName, int shardNumber, long totalDocs);

    /** A no-op implementation for when metrics are disabled. */
    SourceExtractionMetrics NOOP = new SourceExtractionMetrics() {
        @Override public void recordDocumentParsed(SourceType sourceType, long bytesRead) {}
        @Override public void recordParseError(SourceType sourceType, String errorType) {}
        @Override public void recordBatchReadDuration(SourceType sourceType, long durationMs, int docCount) {}
        @Override public void recordShardExtractionStarted(SourceType sourceType, String indexName, int shardNumber) {}
        @Override public void recordShardExtractionCompleted(SourceType sourceType, String indexName, int shardNumber, long totalDocs) {}
    };
}
