package org.opensearch.migrations.bulkload.pipeline.source;

import java.util.List;
import java.util.Optional;

import org.opensearch.migrations.bulkload.pipeline.model.Partition;

/**
 * Enumerates the collections and partitions a source can offer.
 *
 * <p>Partitions are addressed by name, not position: a work item records the name and resolves it
 * later, in another process. Enumeration order carries no meaning.
 */
public interface PartitionEnumerator {

    /** All collection names available from this source. Deterministic. */
    List<String> listCollections();

    /**
     * Partitions in a collection. Names are unique within the collection; order is not significant.
     */
    List<Partition> listPartitions(String collectionName);

    /**
     * Resolve a partition by the name recorded in a work item. An empty result means the partition
     * is gone — an error for the caller, not an empty read.
     */
    Optional<Partition> findPartition(String collectionName, String partitionName);
}
