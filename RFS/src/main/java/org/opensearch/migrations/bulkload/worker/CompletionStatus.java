package org.opensearch.migrations.bulkload.worker;

public enum CompletionStatus {
    NOTHING_DONE,
    WORK_COMPLETED,
    /** Nothing incomplete remains anywhere in the work-coordination index. */
    ALL_WORK_DRAINED,
    /** The worker's memory tier already holds as many live leases as its published cap allows. */
    LEASE_CAP_REACHED
}
