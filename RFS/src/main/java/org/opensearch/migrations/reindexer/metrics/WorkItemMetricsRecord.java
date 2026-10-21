package org.opensearch.migrations.reindexer.metrics;

import org.opensearch.migrations.bulkload.common.SnapshotDocumentCounts;
import org.opensearch.migrations.bulkload.pipeline.model.MigrationMetrics;

import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class WorkItemMetricsRecord {
    @Builder.Default
    int schemaVersion = 1;
    String snapshotName;
    String indexName;
    int shardNumber;
    long fromPosition;
    long nextPosition;
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
