package org.opensearch.migrations.bulkload.worker;

import java.util.OptionalLong;

import lombok.Value;

/** A resume position and the source-document total strictly before it. */
@Value
public class WorkItemCursor {
    long progressCheckpointNum;
    OptionalLong docsBeforeCheckpoint;

    public WorkItemCursor(long progressCheckpointNum) {
        this(progressCheckpointNum, OptionalLong.empty());
    }

    public WorkItemCursor(long progressCheckpointNum, OptionalLong docsBeforeCheckpoint) {
        this.progressCheckpointNum = progressCheckpointNum;
        this.docsBeforeCheckpoint = java.util.Objects.requireNonNull(docsBeforeCheckpoint);
    }
}
