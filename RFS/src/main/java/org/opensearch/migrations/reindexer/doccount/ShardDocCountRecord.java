package org.opensearch.migrations.reindexer.doccount;

import lombok.Builder;
import lombok.Value;

/** The full snapshot shard's baseline for validating the target's _cat/indices docs.count. */
@Value
@Builder
public class ShardDocCountRecord {
    String sessionId;
    String workerId;
    String workItemId;
    String snapshotName;
    String indexName;
    int shardNumber;
    /** Includes nested children; null when unavailable, zero for an empty shard. */
    Long liveLuceneDocCount;
    /** ISO-8601, stored as a String so it round-trips without a JSR310 module. */
    String timestamp;
}
