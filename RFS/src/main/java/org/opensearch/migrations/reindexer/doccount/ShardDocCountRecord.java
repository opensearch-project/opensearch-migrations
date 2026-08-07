package org.opensearch.migrations.reindexer.doccount;

import lombok.Builder;
import lombok.Value;

/**
 * The live non-nested document count for one shard, emitted once when a worker finishes it.
 *
 * <p>Carries both counts a cluster reports. {@link #liveDocCount} spans every lease generation the
 * shard needed and is comparable to the source's {@code <index>/_count}: nested children carry no
 * stored {@code _id}, are never sent to the target, and so are excluded. {@link #liveLuceneDocCount}
 * counts them, and is comparable to {@code _cat/indices docs.count}.
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
    /**
     * Live Lucene documents in the shard, counting nested children — matches
     * {@code _cat/indices docs.count}. 0 when the source cannot report it.
     */
    long liveLuceneDocCount;
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
