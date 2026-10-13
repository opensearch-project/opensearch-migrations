package org.opensearch.migrations.bulkload.workcoordination;

/**
 * Document outcomes for the range of a work item that was actually committed, persisted on the
 * work-item document when it is marked complete.
 *
 * @param succeeded bulk items the target acknowledged (including allowlisted errors)
 * @param failed    bulk items that failed terminally (non-retryable)
 */
public record WorkItemDocCounts(long succeeded, long failed) {
    public static final WorkItemDocCounts NONE = new WorkItemDocCounts(0, 0);

    public WorkItemDocCounts {
        if (succeeded < 0) {
            throw new IllegalArgumentException("succeeded must be >= 0, got " + succeeded);
        }
        if (failed < 0) {
            throw new IllegalArgumentException("failed must be >= 0, got " + failed);
        }
    }
}
