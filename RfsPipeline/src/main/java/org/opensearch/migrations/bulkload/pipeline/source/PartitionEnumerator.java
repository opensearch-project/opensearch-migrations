package org.opensearch.migrations.bulkload.pipeline.source;

import java.util.List;
import java.util.Optional;

import org.opensearch.migrations.bulkload.pipeline.model.Partition;

/**
 * Enumerates the collections and partitions a source can offer.
 *
 * <p>Partitions are addressed by name, not position, and enumeration order carries no meaning. A name
 * returned during work preparation must stay resolvable by {@link #findPartition} when the same source
 * data is reopened by another process.
 */
public interface PartitionEnumerator {

    /**
     * All collection names available from this source. Names are unique; order is unspecified.
     * Unchanged data must yield the same set from every call and from a freshly built source.
     */
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
