package org.opensearch.migrations.bulkload.worker;

import org.opensearch.migrations.bulkload.common.SnapshotDocumentCounts;
import org.opensearch.migrations.bulkload.pipeline.model.MigrationMetrics;

import lombok.Value;

@Value
public class WorkItemCursor {
    long progressCheckpointNum;
    MigrationMetrics metrics;
    boolean shardComplete;
    SnapshotDocumentCounts snapshotDocumentCounts;

    public WorkItemCursor(long progressCheckpointNum) {
        this(progressCheckpointNum, MigrationMetrics.ZERO, false, null);
    }

    public WorkItemCursor(long progressCheckpointNum, MigrationMetrics metrics,
                          boolean shardComplete, SnapshotDocumentCounts snapshotDocumentCounts) {
        this.progressCheckpointNum = progressCheckpointNum;
        this.metrics = java.util.Objects.requireNonNull(metrics);
        this.shardComplete = shardComplete;
        this.snapshotDocumentCounts = snapshotDocumentCounts;
    }
}
