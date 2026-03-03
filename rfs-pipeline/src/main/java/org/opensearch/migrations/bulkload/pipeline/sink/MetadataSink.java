package org.opensearch.migrations.bulkload.pipeline.sink;

import org.opensearch.migrations.bulkload.pipeline.ir.GlobalMetadataSnapshot;
import org.opensearch.migrations.bulkload.pipeline.ir.IndexMetadataSnapshot;

import reactor.core.publisher.Mono;

/**
 * Port for writing metadata to any target — OpenSearch cluster or test collector.
 *
 * <p>Separated from {@link DocumentSink} because metadata migration and document
 * migration are independent operations with different lifecycles.
 */
public interface MetadataSink extends AutoCloseable {

    /** Write global metadata (templates) to the target. */
    Mono<Void> writeGlobalMetadata(GlobalMetadataSnapshot metadata);

    /**
     * Create an index on the target with the given metadata.
     * Idempotent — calling with the same metadata twice should not fail.
     */
    Mono<Void> createIndex(IndexMetadataSnapshot metadata);

    @Override
    default void close() throws Exception {
        // Default no-op
    }
}
