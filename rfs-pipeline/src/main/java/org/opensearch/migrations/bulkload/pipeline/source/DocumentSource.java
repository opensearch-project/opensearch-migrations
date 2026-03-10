package org.opensearch.migrations.bulkload.pipeline.source;

import java.util.List;

import org.opensearch.migrations.bulkload.pipeline.ir.Document;
import org.opensearch.migrations.bulkload.pipeline.ir.IndexMetadataSnapshot;
import org.opensearch.migrations.bulkload.pipeline.ir.Partition;

import reactor.core.publisher.Flux;

/**
 * Port for reading documents from any source — snapshot, remote cluster, S3, Solr, or synthetic test data.
 *
 * <p>This is the key abstraction enabling N+M testing:
 * <ul>
 *   <li>Source-side tests: real snapshot → assert IR correctness</li>
 *   <li>Sink-side tests: {@code SyntheticDocumentSource} → real target → assert cluster state</li>
 * </ul>
 *
 * <p>Implementations must be safe for sequential partition-by-partition access. Concurrent access
 * across partitions is handled by the pipeline, not the source.
 */
public interface DocumentSource extends AutoCloseable {

    /** List all available collection names. */
    List<String> listCollections();

    /** List all partitions for the given collection. */
    List<Partition> listPartitions(String collectionName);

    /** Read metadata for the given collection. */
    IndexMetadataSnapshot readCollectionMetadata(String collectionName);

    /**
     * Stream documents for a partition, starting from the given offset.
     * Returns a cold {@link Flux} — subscription triggers the read.
     *
     * @param partition         the partition to read
     * @param startingDocOffset the document offset to resume from (0 for start)
     * @return a cold Flux of documents
     */
    Flux<Document> readDocuments(Partition partition, long startingDocOffset);

    @Override
    default void close() throws Exception {
        // Default no-op for sources that don't hold resources
    }
}
