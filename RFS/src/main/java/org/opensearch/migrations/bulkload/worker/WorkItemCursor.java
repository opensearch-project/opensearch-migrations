package org.opensearch.migrations.bulkload.worker;

import lombok.Value;

/**
 * Checkpoint handed from the document pipeline to the work coordinator after each batch.
 *
 * <p>Carries two deliberately-separate quantities. Collapsing them into one is what made
 * resume incorrect on nested indices: a document count was written to the checkpoint and
 * then read back as a seek position.
 *
 * <ul>
 *   <li>{@code progressCheckpointNum} — SEEK POSITION. The source position (Lucene doc
 *       number) of the last processed document. This is what a successor work item resumes
 *       from, and the only value readers can seek with.</li>
 *   <li>{@code docsEmitted} — ACCOUNTING. Partition-wide running total of documents
 *       actually emitted to the target, carried across lease generations. For ES/OS sources
 *       this is the count of live non-nested (root) documents, because nested child
 *       documents are read but never emitted (they carry no stored {@code _id}).</li>
 * </ul>
 */
@Value
public class WorkItemCursor {
    long progressCheckpointNum;
    long docsEmitted;

    /**
     * Position-only checkpoint, for callers that do not track document totals.
     * {@code docsEmitted} is reported as 0 (unknown), which readers treat as "no count
     * available" rather than "zero documents".
     */
    public WorkItemCursor(long progressCheckpointNum) {
        this(progressCheckpointNum, 0L);
    }

    public WorkItemCursor(long progressCheckpointNum, long docsEmitted) {
        this.progressCheckpointNum = progressCheckpointNum;
        this.docsEmitted = docsEmitted;
    }
}
