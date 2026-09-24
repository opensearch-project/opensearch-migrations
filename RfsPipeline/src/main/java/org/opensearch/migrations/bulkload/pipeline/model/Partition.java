package org.opensearch.migrations.bulkload.pipeline.model;

/**
 * Source-agnostic partition — represents a unit of parallel work within a collection.
 *
 * <p>Different sources partition data differently:
 * <ul>
 *   <li>ES snapshots: shards ({@code EsShardPartition})</li>
 *   <li>S3: key prefix ranges</li>
 *   <li>Solr: collection shards</li>
 * </ul>
 *
 * <p>The pipeline core uses this interface for progress tracking and work coordination
 * without knowing the source-specific partitioning strategy.
 *
 * <p>Deliberately not {@link Comparable}: partitions are addressed by name, so enumeration order
 * never matters and an accidental sort should be a compile error rather than a silent reordering.
 */
public interface Partition {

    /**
     * Identifies this partition within its collection. Unique among the partitions a source
     * returns for one collection, and stable enough to be recorded in a work item and resolved
     * later by {@code PartitionEnumerator.findPartition}.
     */
    String name();

    /** The collection this partition belongs to. */
    String collectionName();
}
