package org.opensearch.migrations.reindexer.doccount;

import lombok.Builder;
import lombok.Value;

/**
 * The live non-nested document count for one shard, emitted once when a worker finishes it.
 *
 * <p>{@link #liveDocCount} spans every lease generation the shard needed, so for a completed shard
 * it is directly comparable to the source's {@code <index>/_count}. Nested child documents are
 * excluded because they carry no stored {@code _id} and are never sent to the target, which is also
 * why this is lower than {@code _cat/indices docs.count} on an index containing nested documents.
 */
@Value
@Builder
public class ShardDocCountRecord {
    String sessionId;
    String workerId;
    String workItemId;
    String indexName;
    int shardNumber;
    long liveDocCount;
    long docsThisGeneration;
    long docsPriorGenerations;
    /**
     * False when emitted from a generation that stopped early, in which case
     * {@link #liveDocCount} is partial and a successor will emit a later, higher one.
     */
    boolean shardComplete;
    /** ISO-8601, stored as a String so it round-trips without a JSR310 module. */
    String timestamp;
}
