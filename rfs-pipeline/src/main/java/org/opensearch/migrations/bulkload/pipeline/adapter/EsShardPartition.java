package org.opensearch.migrations.bulkload.pipeline.adapter;

import java.util.Objects;

import org.opensearch.migrations.bulkload.pipeline.ir.Partition;

/**
 * ES-specific partition implementation — identifies a shard within a snapshot.
 *
 * @param snapshotName the snapshot name, must not be null
 * @param indexName    the index name, must not be null
 * @param shardNumber  the shard number, must be non-negative
 */
public record EsShardPartition(
    String snapshotName,
    String indexName,
    int shardNumber
) implements Partition {

    public EsShardPartition {
        Objects.requireNonNull(snapshotName, "snapshotName must not be null");
        Objects.requireNonNull(indexName, "indexName must not be null");
        if (shardNumber < 0) {
            throw new IllegalArgumentException("shardNumber must be >= 0, got " + shardNumber);
        }
    }

    @Override
    public String name() {
        return snapshotName + "/" + indexName + "/" + shardNumber;
    }

    @Override
    public String collectionName() {
        return indexName;
    }

    @Override
    public String toString() {
        return name();
    }
}
