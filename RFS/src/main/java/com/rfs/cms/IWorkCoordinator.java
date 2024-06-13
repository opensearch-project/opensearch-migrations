package com.rfs.cms;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NonNull;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;

public interface IWorkCoordinator extends AutoCloseable {
    void setup() throws IOException;

    /**
     * This represents when the lease wasn't acquired because another process already owned the
     * lease or the workItem was already marked as completed.
     */
    class LeaseNotAcquiredException extends Exception {
        public LeaseNotAcquiredException() {}
    }

    @Getter
    @AllArgsConstructor
    class WorkItemAndDuration {
        public final String workItemId;
        public final Instant leaseExpirationTime;
    }
    WorkItemAndDuration acquireNextWorkItem() throws IOException;

    /**
     * @param workItemId - the name of the document/resource to create.
     *                     This value will be used as a key to other methods that update leases and to close work out.
     * @throws IOException if the document was not successfully create for any reason
     */
    void createUnassignedWorkItem(String workItemId) throws IOException;

    /**
     * @param workItemId the item that the caller is trying to take ownership of
     * @param leaseDuration the initial amount of time that the caller would like to own the lease for.
     *                      Notice if other attempts have been made on this workItem, the lease will be
     *                      greater than the requested amount.
     * @return a tuple that contains the expiration time of the lease, at which point,
     * this process must completely yield all work on the item
     * @throws IOException if there was an error resolving the lease ownership
     * @throws LeaseNotAcquiredException if the lease is owned by another process
     */
    @NonNull WorkItemAndDuration createOrUpdateLeaseForWorkItem(String workItemId, Duration leaseDuration)
            throws IOException, LeaseNotAcquiredException;

    void completeWorkItem(String workItemId);
}
