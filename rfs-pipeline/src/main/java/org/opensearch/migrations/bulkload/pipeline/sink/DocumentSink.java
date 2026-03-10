package org.opensearch.migrations.bulkload.pipeline.sink;

import java.util.List;

import org.opensearch.migrations.bulkload.pipeline.ir.BatchResult;
import org.opensearch.migrations.bulkload.pipeline.ir.Document;
import org.opensearch.migrations.bulkload.pipeline.ir.IndexMetadataSnapshot;

import reactor.core.publisher.Mono;

/**
 * Port for writing documents to any target — OpenSearch cluster, file, or test collector.
 *
 * <p>Consumes the clean IR types. Never sees source-specific details — the sink doesn't
 * need to know how the source is partitioned.
 *
 * <p>Implementations must be safe for concurrent batch writes.
 */
public interface DocumentSink extends AutoCloseable {

    /**
     * Create a collection on the target with the given metadata.
     * Idempotent — calling with the same metadata twice should not fail.
     */
    Mono<Void> createCollection(IndexMetadataSnapshot metadata);

    /**
     * Write a batch of documents to the target collection.
     *
     * <p>Returns batch-local stats only. The pipeline is responsible for tracking
     * cumulative offsets and constructing {@link org.opensearch.migrations.bulkload.pipeline.ir.ProgressCursor}
     * for resumability.
     *
     * @param collectionName the target collection name
     * @param batch          the documents to write, must not be empty
     * @return batch-local stats (docs written, bytes written)
     */
    Mono<BatchResult> writeBatch(String collectionName, List<Document> batch);

    @Override
    default void close() throws Exception {
        // Default no-op
    }
}
