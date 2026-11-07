package org.opensearch.migrations.reindexer.metrics;

import org.opensearch.migrations.bulkload.common.SnapshotDocumentCounts;
import org.opensearch.migrations.bulkload.pipeline.model.MigrationMetrics;

import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class WorkItemMetricsRecord {
    @Builder.Default
    int schemaVersion = 2;
    String snapshotName;
    String indexName;
    String partitionName;
    String fromCursor;
    String nextCursor;
    boolean shardComplete;
    String previousSnapshotName;
    String deltaMode;
    Metrics metrics;

    @SuppressWarnings({"java:S100", "java:S1186"})
    public record Metrics(
        MigrationMetrics.Operations source, MigrationMetrics.Operations target, MigrationMetrics.Requests requests,
        WorkItem workItem, SnapshotDocumentCounts snapshot
    ) {}

    @SuppressWarnings({"java:S100", "java:S1186"})
    public record WorkItem(long durationMillis) {}

}
