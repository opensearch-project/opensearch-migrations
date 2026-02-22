package org.opensearch.migrations.bulkload.pipeline.sink;

import java.util.List;

import org.opensearch.migrations.bulkload.pipeline.ir.DocumentChange;
import org.opensearch.migrations.bulkload.pipeline.ir.IndexMetadataSnapshot;
import org.opensearch.migrations.bulkload.pipeline.ir.ProgressCursor;
import org.opensearch.migrations.bulkload.pipeline.ir.ShardId;

import reactor.core.publisher.Mono;

/**
 * Port for writing documents to any target — OpenSearch cluster, file, or test collector.
 *
 * <p>Consumes the clean IR types. Never sees Lucene, snapshot formats, or source-specific details.
 *
 * <p>Implementations must be safe for concurrent batch writes across different shards.
 */
public interface DocumentSink extends AutoCloseable {

    /**
     * Create an index on the target with the given metadata.
     * Idempotent — calling with the same metadata twice should not fail.
     */
    Mono<Void> createIndex(IndexMetadataSnapshot metadata);

    /**
     * Write a batch of documents to the target.
     *
     * @param shardId   the shard these documents belong to
     * @param indexName the target index name
     * @param batch     the documents to write, must not be empty
     * @return a progress cursor indicating what was written
     */
    Mono<ProgressCursor> writeBatch(ShardId shardId, String indexName, List<DocumentChange> batch);

    @Override
    default void close() throws Exception {
        // Default no-op
    }
}
