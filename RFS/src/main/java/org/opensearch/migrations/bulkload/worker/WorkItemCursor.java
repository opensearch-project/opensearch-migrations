package org.opensearch.migrations.bulkload.worker;

import org.opensearch.migrations.bulkload.workcoordination.WorkItemDocCounts;

import lombok.AllArgsConstructor;
import lombok.Value;

@Value
@AllArgsConstructor
public class WorkItemCursor {
    long progressCheckpointNum;
    /** Cumulative document outcomes for this work item up to {@link #progressCheckpointNum}. */
    WorkItemDocCounts docCounts;

    public WorkItemCursor(long progressCheckpointNum) {
        this(progressCheckpointNum, WorkItemDocCounts.NONE);
    }
}
