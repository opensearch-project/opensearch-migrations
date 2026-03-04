package org.opensearch.migrations.bulkload.pipeline.ir;

import java.util.Objects;

/**
 * Identifies a shard within a snapshot. Clean IR — no Lucene or repo-access details.
 *
 * @param snapshotName the snapshot name, must not be null
 * @param indexName    the index name, must not be null
 * @param shardNumber  the shard number, must be non-negative
 */
public record ShardId(
    String snapshotName,
    String indexName,
    int shardNumber
) {
    public ShardId {
        Objects.requireNonNull(snapshotName, "snapshotName must not be null");
        Objects.requireNonNull(indexName, "indexName must not be null");
        if (shardNumber < 0) {
            throw new IllegalArgumentException("shardNumber must be >= 0, got " + shardNumber);
        }
    }

    @Override
    public String toString() {
        return snapshotName + "/" + indexName + "/" + shardNumber;
    }
}
