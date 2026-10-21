package org.opensearch.migrations.reindexer.metrics;

import org.opensearch.migrations.bulkload.pipeline.model.MigrationMetrics;

import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class WorkItemMetricsRecord {
    @Builder.Default
    int schemaVersion = 1;
    String workItemId;
    String snapshotName;
    String indexName;
    int shardNumber;
    long fromPosition;
    long nextPosition;
    boolean shardComplete;
    String previousSnapshotName;
    String deltaMode;
    Metrics metrics;

    public record Metrics(MigrationMetrics.Source source, MigrationMetrics.Requests requests, Snapshot snapshot) {}

    public record Snapshot(Long liveLuceneDocCount, Long previousLiveLuceneDocCount) {}
}
