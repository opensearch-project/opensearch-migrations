package org.opensearch.migrations.bulkload.pipeline;

import org.opensearch.migrations.bulkload.pipeline.sink.DocumentSink;
import org.opensearch.migrations.bulkload.pipeline.source.DocumentSource;

/**
 * Groups the pipeline-specific parameters for document migration:
 * source, sink, and batching configuration.
 *
 * @param source           the document source
 * @param sink             the document sink
 * @param maxDocsPerBatch  max documents per bulk batch
 * @param maxBytesPerBatch max bytes per bulk batch
 * @param batchConcurrency max concurrent bulk writes in flight
 */
public record PipelineConfig(
    DocumentSource source,
    DocumentSink sink,
    int maxDocsPerBatch,
    long maxBytesPerBatch,
    int batchConcurrency
) {}
