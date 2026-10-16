package org.opensearch.migrations.bulkload.worker;

import lombok.Value;

/**
 * Checkpoint handed from the document pipeline to the work coordinator after each batch.
 *
 * <p>{@code progressCheckpointNum} is the source position a successor resumes from.
 * {@code docsEmitted} is the shard's running total of emitted documents — for ES/OS sources the
 * live non-nested count, since nested children are never emitted. {@code docsInCheckpointBatch} is
 * subtracted when carrying the total forward, because a successor restarts at the checkpoint and
 * re-emits that batch.
 */
@Value
public class WorkItemCursor {
    long progressCheckpointNum;
    long docsEmitted;
    long docsInCheckpointBatch;

    /** Position-only checkpoint; 0 totals mean "unknown", not "zero documents". */
    public WorkItemCursor(long progressCheckpointNum) {
        this(progressCheckpointNum, 0L, 0L);
    }

    public WorkItemCursor(long progressCheckpointNum, long docsEmitted, long docsInCheckpointBatch) {
        this.progressCheckpointNum = progressCheckpointNum;
        this.docsEmitted = docsEmitted;
        this.docsInCheckpointBatch = docsInCheckpointBatch;
    }
}
