package org.opensearch.migrations.bulkload.worker;

import org.opensearch.migrations.bulkload.common.SnapshotDocumentCounts;
import org.opensearch.migrations.bulkload.pipeline.model.MigrationMetrics;

import lombok.Value;

/**
 * The last position a worker has durably committed for its work item.
 *
 * <p>The cursor is opaque: it comes from the source that produced the documents and is only ever
 * handed back to that source, or written into a successor work item.
 */
@Value
public class WorkItemCursor {
    String cursor;
    MigrationMetrics metrics;
    boolean shardComplete;
    SnapshotDocumentCounts snapshotDocumentCounts;

    public WorkItemCursor(String cursor) {
        this(cursor, MigrationMetrics.ZERO, false, null);
    }

    public WorkItemCursor(String cursor, MigrationMetrics metrics,
                          boolean shardComplete, SnapshotDocumentCounts snapshotDocumentCounts) {
        this.cursor = cursor;
        this.metrics = java.util.Objects.requireNonNull(metrics);
        this.shardComplete = shardComplete;
        this.snapshotDocumentCounts = snapshotDocumentCounts;
    }
}
