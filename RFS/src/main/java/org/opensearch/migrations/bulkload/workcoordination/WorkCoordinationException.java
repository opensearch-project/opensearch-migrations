package org.opensearch.migrations.bulkload.workcoordination;

import java.io.IOException;

/**
 * Custom exception for work coordination operations with detailed context.
 */
public class WorkCoordinationException extends IOException {
    private final String workItemId;
    private final String operation;

    public WorkCoordinationException(String operation, String workItemId, String message) {
        super(String.format("Operation '%s' failed for work item '%s': %s", operation, workItemId, message));
        this.operation = operation;
        this.workItemId = workItemId;
    }

    public WorkCoordinationException(String operation, String workItemId, String message, Throwable cause) {
        super(String.format("Operation '%s' failed for work item '%s': %s", operation, workItemId, message), cause);
        this.operation = operation;
        this.workItemId = workItemId;
    }

    public String getWorkItemId() {
        return workItemId;
    }

    public String getOperation() {
        return operation;
    }
}
