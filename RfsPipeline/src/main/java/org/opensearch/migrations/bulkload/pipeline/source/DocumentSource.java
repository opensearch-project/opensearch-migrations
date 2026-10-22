package org.opensearch.migrations.bulkload.pipeline.source;

import java.util.Optional;

import org.opensearch.migrations.bulkload.common.SnapshotDocumentCounts;
import org.opensearch.migrations.bulkload.pipeline.model.CollectionMetadata;
import org.opensearch.migrations.bulkload.pipeline.model.Partition;

/**
 * Port for reading documents from any source — snapshot, remote cluster, S3, Solr, or synthetic
 * test data. A source is a {@link PartitionEnumerator} plus a {@link DocumentStream}.
 *
 * <p>This is the key abstraction enabling N+M testing:
 * <ul>
 *   <li>Source-side tests: real snapshot → assert IR correctness</li>
 *   <li>Sink-side tests: {@code SyntheticDocumentSource} (in testFixtures) → real target → assert cluster state</li>
 * </ul>
 *
 * <p><b>Implementation contract.</b> A source may be subscribed concurrently for different
 * partitions and must be safe under that.
 */
public interface DocumentSource extends PartitionEnumerator, DocumentStream, AutoCloseable {

    /**
     * Read metadata for the given collection.
     *
     * <p>Not part of the source contract — it serves the uncoordinated "describe then create" path,
     * which is retained through Phase 1 and removed after.
     */
    CollectionMetadata readCollectionMetadata(String collectionName);

    /** Whole-partition snapshot counts, independent of resume position; empty until available. */
    default Optional<SnapshotDocumentCounts> snapshotDocumentCounts(Partition partition) {
        return Optional.empty();
    }

    /** Whether operations on the same document can depend on an earlier batch. */
    default boolean requiresOrderedWrites() {
        return false;
    }

    @Override
    default void close() throws Exception {
        // Default no-op for sources that don't hold resources
    }
}
