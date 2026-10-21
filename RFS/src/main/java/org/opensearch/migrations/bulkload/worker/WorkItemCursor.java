package org.opensearch.migrations.bulkload.worker;

import org.opensearch.migrations.bulkload.pipeline.model.MigrationMetrics;

import lombok.Value;

@Value
public class WorkItemCursor {
    long progressCheckpointNum;
    MigrationMetrics metrics;
    boolean shardComplete;
    Long liveLuceneDocCount;
    Long previousLiveLuceneDocCount;

    public WorkItemCursor(long progressCheckpointNum) {
        this(progressCheckpointNum, MigrationMetrics.ZERO, false, null);
    }

    public WorkItemCursor(long progressCheckpointNum, MigrationMetrics metrics,
                          boolean shardComplete, Long liveLuceneDocCount) {
        this(progressCheckpointNum, metrics, shardComplete, liveLuceneDocCount, null);
    }

    public WorkItemCursor(long progressCheckpointNum, MigrationMetrics metrics,
                          boolean shardComplete, Long liveLuceneDocCount, Long previousLiveLuceneDocCount) {
        this.progressCheckpointNum = progressCheckpointNum;
        this.metrics = java.util.Objects.requireNonNull(metrics);
        this.shardComplete = shardComplete;
        this.liveLuceneDocCount = liveLuceneDocCount;
        this.previousLiveLuceneDocCount = previousLiveLuceneDocCount;
    }
}
