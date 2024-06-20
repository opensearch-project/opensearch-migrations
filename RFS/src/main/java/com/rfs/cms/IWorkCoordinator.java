package com.rfs.cms;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NonNull;
import lombok.ToString;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;

public interface IWorkCoordinator extends AutoCloseable {

    /**
     * Used as a discriminated union of different outputs that can be returned from acquiring a lease.
     * Exceptions are too difficult/unsafe to deal with when going across lambdas and lambdas seem
     * critical to glue different components together in a readable fashion.
     */
    interface WorkAcquisitionOutcome {
        <T> T visit(WorkAcquisitionOutcomeVisitor<T> v) throws IOException;
    }

    interface WorkAcquisitionOutcomeVisitor<T> {
        T onAlreadyCompleted() throws IOException;
        T onAcquiredWork(WorkItemAndDuration workItem) throws IOException;
    }

    /**
     * This represents when the lease wasn't acquired because another process already owned the
     * lease.
     */
    class LeaseLockHeldElsewhereException extends RuntimeException { }

    /**
     * This represents that a work item was already completed.
     */
    class AlreadyCompleted implements WorkAcquisitionOutcome {
        @Override public <T> T visit(WorkAcquisitionOutcomeVisitor<T> v) throws IOException {
            return v.onAlreadyCompleted();
        }
    }

    @Getter
    @AllArgsConstructor
    @ToString
    class WorkItemAndDuration implements WorkAcquisitionOutcome {
        final String workItemId;
        final Instant leaseExpirationTime;
        @Override public <T> T visit(WorkAcquisitionOutcomeVisitor<T> v) throws IOException {
            return v.onAcquiredWork(this);
        }
    }

    void setup() throws IOException;

    int numWorkItemsArePending() throws IOException, InterruptedException;

    boolean workItemsArePending() throws IOException, InterruptedException;

    WorkItemAndDuration acquireNextWorkItem(Duration leaseDuration) throws IOException, InterruptedException;

    /**
     * @param workItemId - the name of the document/resource to create.
     *                   This value will be used as a key to other methods that update leases and to close work out.
     * @return true if the document was created and false if it was already present
     * @throws IOException if the document was not successfully create for any other reason
     */
    boolean createUnassignedWorkItem(String workItemId) throws IOException;

    /**
     * @param workItemId the item that the caller is trying to take ownership of
     * @param leaseDuration the initial amount of time that the caller would like to own the lease for.
     *                      Notice if other attempts have been made on this workItem, the lease will be
     *                      greater than the requested amount.
     * @return a tuple that contains the expiration time of the lease, at which point,
     * this process must completely yield all work on the item
     * @throws IOException if there was an error resolving the lease ownership
     * @throws LeaseLockHeldElsewhereException if the lease is owned by another process
     */
    @NonNull WorkAcquisitionOutcome createOrUpdateLeaseForWorkItem(String workItemId, Duration leaseDuration)
            throws IOException;

    void completeWorkItem(String workItemId) throws IOException;
}
