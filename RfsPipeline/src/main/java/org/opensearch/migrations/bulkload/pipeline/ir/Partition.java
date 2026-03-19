package org.opensearch.migrations.bulkload.pipeline.ir;

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
 */
public interface Partition extends Comparable<Partition> {

    /** Human-readable name for logging and progress tracking. */
    String name();

    /** The collection this partition belongs to. */
    String collectionName();

    @Override
    default int compareTo(Partition other) {
        return name().compareTo(other.name());
    }
}
