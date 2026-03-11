package org.opensearch.migrations.bulkload.pipeline.adapter;

import reactor.core.publisher.Mono;

/**
 * ES-specific sink for global metadata (templates) and per-index metadata.
 *
 * <p>This is an optional capability — only ES/OpenSearch sinks implement it.
 * Non-ES sinks do not have global metadata and should not implement this.
 * The core pipeline contract ({@link org.opensearch.migrations.bulkload.pipeline.sink.DocumentSink})
 * uses {@link org.opensearch.migrations.bulkload.pipeline.ir.CollectionMetadata} instead.
 */
public interface GlobalMetadataSink extends AutoCloseable {

    /** Write global metadata (templates) to the target. */
    Mono<Void> writeGlobalMetadata(GlobalMetadataSnapshot metadata);

    /**
     * Create an index on the target with ES-specific metadata.
     * Idempotent — calling with the same metadata twice should not fail.
     */
    Mono<Void> createIndex(IndexMetadataSnapshot metadata);

    @Override
    default void close() throws Exception {
        // Default no-op
    }
}
