package org.opensearch.migrations.reindexer.doccount;

import lombok.Builder;
import lombok.Value;

/**
 * Counts reported after a shard's source stream finishes. Repeated records are possible if
 * the worker stops after uploading but before completing its work item.
 */
@Value
@Builder
public class ShardDocCountRecord {
    String sessionId;
    String workerId;
    String workItemId;
    String indexName;
    int shardNumber;
    /** Source documents processed before transformation, including terminal target failures.
     * Null if a predecessor did not persist a count. Delta runs count changes, not live roots. */
    Long sourceDocCount;
    /** Live Lucene documents including nested children; null when unavailable, zero for an empty shard. */
    Long liveLuceneDocCount;
    long docsThisGeneration;
    Long docsPriorGenerations;
    /** ISO-8601, stored as a String so it round-trips without a JSR310 module. */
    String timestamp;
}
