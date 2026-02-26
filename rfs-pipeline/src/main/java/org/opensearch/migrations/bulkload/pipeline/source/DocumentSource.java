package org.opensearch.migrations.bulkload.pipeline.source;

import java.util.List;

import org.opensearch.migrations.bulkload.pipeline.ir.DocumentChange;
import org.opensearch.migrations.bulkload.pipeline.ir.IndexMetadataSnapshot;
import org.opensearch.migrations.bulkload.pipeline.ir.ShardId;

import reactor.core.publisher.Flux;

/**
 * Port for reading documents from any source — snapshot, remote cluster, or synthetic test data.
 *
 * <p>This is the key abstraction enabling N+M testing:
 * <ul>
 *   <li>Source-side tests: real snapshot → assert IR correctness</li>
 *   <li>Sink-side tests: {@code SyntheticDocumentSource} → real target → assert cluster state</li>
 * </ul>
 *
 * <p>Implementations must be safe for sequential shard-by-shard access. Concurrent access
 * across shards is handled by the pipeline, not the source.
 */
public interface DocumentSource extends AutoCloseable {

    /** List all available index names. */
    List<String> listIndices();

    /** List all shards for the given index. */
    List<ShardId> listShards(String indexName);

    /** Read metadata for the given index. */
    IndexMetadataSnapshot readIndexMetadata(String indexName);

    /**
     * Stream document changes for a shard, starting from the given offset.
     * Returns a cold {@link Flux} — subscription triggers the read.
     *
     * @param shardId           the shard to read
     * @param startingDocOffset the document offset to resume from (0 for start)
     * @return a cold Flux of document changes
     */
    Flux<DocumentChange> readDocuments(ShardId shardId, int startingDocOffset);

    @Override
    default void close() throws Exception {
        // Default no-op for sources that don't hold resources
    }
}
