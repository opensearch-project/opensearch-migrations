package org.opensearch.migrations.bulkload.worker;

public enum CompletionStatus {
    NOTHING_DONE,
    WORK_COMPLETED,
    /** Nothing incomplete remains anywhere in the work-coordination index. */
    ALL_WORK_DRAINED,
    /** The worker's memory tier already holds as many live leases as its published cap allows. */
    LEASE_CAP_REACHED;

    /**
     * True when the call acquired no work item — either nothing is currently eligible for this
     * worker (NOTHING_DONE) or the coordinator has no incomplete items left at all
     * (ALL_WORK_DRAINED). Callers looping on migrateOneShard should stop on this, not on
     * NOTHING_DONE alone.
     */
    public boolean acquiredNoWork() {
        return this == NOTHING_DONE || this == ALL_WORK_DRAINED;
    }
}
