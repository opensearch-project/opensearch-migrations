package org.opensearch.migrations.reindexer.metrics;

import org.opensearch.migrations.bulkload.pipeline.model.MigrationMetrics;

import lombok.Builder;
import lombok.Value;

/** A prepared export; committed only when its commitId is recorded by the work coordinator. */
@Value
@Builder
public class WorkItemMetricsRecord {
    @Builder.Default
    int schemaVersion = 1;
    String commitId;
    String sessionId;
    String workerId;
    String workItemId;
    String snapshotName;
    String indexName;
    int shardNumber;
    long fromPosition;
    long nextPosition;
    boolean shardComplete;
    String previousSnapshotName;
    String deltaMode;
    MigrationMetrics metrics;
    /** Includes nested children; null when unavailable, zero for an empty shard. */
    Long liveLuceneDocCount;
    Long previousLiveLuceneDocCount;
    String timestamp;
}
