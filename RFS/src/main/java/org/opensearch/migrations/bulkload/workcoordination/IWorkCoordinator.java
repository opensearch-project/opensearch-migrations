package org.opensearch.migrations.bulkload.workcoordination;

import java.io.IOException;
import java.io.Serializable;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.function.Supplier;
import java.util.regex.Pattern;

import org.opensearch.migrations.bulkload.tracing.IWorkCoordinationContexts;

import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NonNull;
import lombok.ToString;

/**
 * Multiple workers can create an instance of this class to coordinate what work each of them
 * should be handling.  Implementations of this class must be thread-safe, even when the threads
 * are run in a distributed environment on different hosts.
 *
 * This class allows for the creation of work items that can be claimed by a single worker.
 * Workers will complete the work or let their leases lapse by default with the passage of time.
 * The class guarantees that only one worker may own a lease on a work item at a given time.
 *
 * Work items being acquired may be specific, or from a pool of unassigned items.  The latter is
 * used to distribute large quantities of work, with one phase adding many work items that are
 * unassigned, followed by workers grabbing (leasing) items from that pool and completing them.
 */
public interface IWorkCoordinator extends AutoCloseable {

    Clock getClock();

    /**
     * Initialize any external and internal state so that the subsequent calls will work appropriately.
     * This method must be resilient if there are multiple callers and act as if there were only one.
     * After this method returns, all of the other methods in this class are valid.  There is no way
     * to reverse any stateful actions that were performed by setup.  This is a one-way function.
     * @throws IOException
     * @throws InterruptedException
     */
    void setup(Supplier<IWorkCoordinationContexts.IInitializeCoordinatorStateContext> contextSupplier)
        throws IOException, InterruptedException;

    /**
     * @param workItemId - the name of the document/resource to create.
     *                   This value will be used as a key to other methods that update leases and to close work out.
     * @return true if the document was created and false if it was already present
     * @throws IOException if the document was not successfully create for any other reason
     */
    boolean createUnassignedWorkItem(
        String workItemId,
        Supplier<IWorkCoordinationContexts.ICreateUnassignedWorkItemContext> contextSupplier
    ) throws IOException;

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
    @NonNull
    WorkAcquisitionOutcome createOrUpdateLeaseForWorkItem(
        String workItemId,
        Duration leaseDuration,
        Supplier<IWorkCoordinationContexts.IAcquireSpecificWorkContext> contextSupplier
    ) throws IOException, InterruptedException;
    
    /**
     * Scan the created work items that have not yet had leases acquired and have not yet finished.
     * One of those work items will be returned along with a lease for how long this process may continue
     * to work on it.  There is no way to extend a lease.  After the caller has completed the work,
     * completeWorkItem should be called.  If completeWorkItem isn't called and the lease expires, the
     * caller must ensure that no more work will be undertaken for this work item and the work item
     * itself will be leased out to a future caller of acquireNextWorkItem.  Each subsequent time that
     * a lease is acquired for a work item, the lease period will be doubled.
     * @param leaseDuration
     * @return
     * @throws IOException
     * @throws InterruptedException
     */
    WorkAcquisitionOutcome acquireNextWorkItem(
        Duration leaseDuration,
        Supplier<IWorkCoordinationContexts.IAcquireNextWorkItemContext> contextSupplier
    ) throws IOException, InterruptedException;

    /**
     * Mark the work item as completed.  After this succeeds, the work item will never be leased out
     * to any callers.
     * @param workItemId
     * @throws IOException
     */
    void completeWorkItem(
        String workItemId,
        Supplier<IWorkCoordinationContexts.ICompleteWorkItemContext> contextSupplier
    ) throws IOException, InterruptedException;

    /**
     * Release the lease on a work item without marking it complete, so that another worker can
     * pick it up immediately.  Intended for the case where the current worker is shutting down
     * before any progress (i.e. no checkpoint cursor) has been made on the item — staying on the
     * lease until natural expiration would needlessly block the work.  This is a no-op if the
     * work item is already completed or if the lease has already rolled to a different worker.
     * The lease exponent is intentionally left untouched: a release implies the work hasn't
     * been done yet and the next acquirer should still benefit from any prior backoff signal.
     */
    void releaseWorkItem(
        String workItemId,
        Supplier<IWorkCoordinationContexts.IReleaseWorkItemContext> contextSupplier
    ) throws IOException, InterruptedException;

    /**
     * Add the list of successor items to the work item, create new work items for each of the successors, and mark the
     * original work item as completed.
     * @param workItemId the work item that is being completed
     * @param successorWorkItemIds the list of successor work items that will be created
     * @throws IOException
     * @throws InterruptedException
     */
    void createSuccessorWorkItemsAndMarkComplete(
        String workItemId,
        List<String> successorWorkItemIds,
        int initialNextAcquisitionLeaseExponent,
        Supplier<IWorkCoordinationContexts.ICreateSuccessorWorkItemsContext> contextSupplier
    ) throws IOException, InterruptedException;

    /**
     * Same as {@link #createSuccessorWorkItemsAndMarkComplete} but aborts retries if they would exceed the deadline.
     */
    void createSuccessorWorkItemsAndMarkComplete(
        String workItemId,
        List<String> successorWorkItemIds,
        int initialNextAcquisitionLeaseExponent,
        Instant deadline,
        Supplier<IWorkCoordinationContexts.ICreateSuccessorWorkItemsContext> contextSupplier
    ) throws IOException, InterruptedException;

    /**
     * @return the number of items that are not yet complete.  This will include items with and without claimed leases.
     * @throws IOException
     * @throws InterruptedException
     */
    int numWorkItemsNotYetComplete(Supplier<IWorkCoordinationContexts.IPendingWorkItemsContext> contextSupplier)
        throws IOException, InterruptedException;

    /**
     * @return true if there are any work items that are not yet complete.
     * @throws IOException
     * @throws InterruptedException
     */
    boolean workItemsNotYetComplete(Supplier<IWorkCoordinationContexts.IPendingWorkItemsContext> contextSupplier)
        throws IOException, InterruptedException;

    /**
     * Used as a discriminated union of different outputs that can be returned from acquiring a lease.
     * Exceptions are too difficult/unsafe to deal with when going across lambdas and lambdas seem
     * critical to glue different components together in a readable fashion.
     */
    interface WorkAcquisitionOutcome {
        <T> T visit(WorkAcquisitionOutcomeVisitor<T> v) throws IOException, InterruptedException;
    }

    interface WorkAcquisitionOutcomeVisitor<T> {
        T onAlreadyCompleted() throws IOException;

        T onNoAvailableWorkToBeDone() throws IOException;

        T onAcquiredWork(WorkItemAndDuration workItem) throws IOException, InterruptedException;
    }

    /**
     * This represents that a work item was already completed.
     */
    class AlreadyCompleted implements WorkAcquisitionOutcome {
        @Override
        public <T> T visit(WorkAcquisitionOutcomeVisitor<T> v) throws IOException {
            return v.onAlreadyCompleted();
        }
    }

    /**
     * This will occur when some other process is holding a lease and there's no other work item(s)
     * available for this proocess to take on given the request.
     */
    class NoAvailableWorkToBeDone implements WorkAcquisitionOutcome {
        @Override
        public <T> T visit(WorkAcquisitionOutcomeVisitor<T> v) throws IOException {
            return v.onNoAvailableWorkToBeDone();
        }
    }

    /**
     * This represents when the lease wasn't acquired because another process already owned the
     * lease.
     */
    class LeaseLockHeldElsewhereException extends RuntimeException {}

    /**
     * What's the id of the work item (which is determined by calls to createUnassignedWorkItem or
     * createOrUpdateLeaseForWorkItem) and at what time should this worker that has obtained the
     * lease need to relinquish control?  After the leaseExpirationTime, other processes may be
     * able to acquire their own lease on this work item.
     */
    @Getter
    @AllArgsConstructor
    @ToString
    class WorkItemAndDuration implements WorkAcquisitionOutcome {
        final Instant leaseExpirationTime;
        final WorkItem workItem;

        @Override
        public <T> T visit(WorkAcquisitionOutcomeVisitor<T> v) throws IOException, InterruptedException {
            return v.onAcquiredWork(this);
        }

        /**
         * A unit of leasable work: one partition of one collection, resuming after a cursor.
         *
         * <p>The partition carries the name the source gave it, not an ordinal into a list another
         * process would have to re-derive in the same order. The cursor is opaque.
         */
        @EqualsAndHashCode
        @Getter
        public static class WorkItem implements Serializable {
            /**
             * Separator between the three encoded segments. Outside the base64url alphabet
             * ({@code A-Z a-z 0-9 - _}), so no segment can contain it. {@code __} would not do:
             * base64url can start or end with {@code _}, making {@code "a_"+"__"+"_b"} and
             * {@code "a"+"__"+"__b"} both render as {@code a____b}.
             */
            private static final String SEPARATOR = ".";
            private static final Pattern SEPARATOR_PATTERN = Pattern.compile(Pattern.quote(SEPARATOR));
            private static final String SHARD_SETUP_SENTINEL = "shard_setup";
            private static final Base64.Encoder SEGMENT_ENCODER = Base64.getUrlEncoder().withoutPadding();
            private static final Base64.Decoder SEGMENT_DECODER = Base64.getUrlDecoder();

            String indexName;
            String partitionName;
            /** Opaque source cursor to resume after; null means start at the beginning. */
            String cursor;

            public WorkItem(String indexName, String partitionName, String cursor) {
                this.indexName = indexName;
                this.partitionName = partitionName;
                this.cursor = cursor;
            }

            /**
             * Serializes this work item to the id string stored in the work-coordination index as
             * {@code base64url(indexName).base64url(partitionName).base64url(cursor)}.
             *
             * <p>All three segments are encoded: each is an arbitrary string, so none can be
             * trusted to avoid the separator. The {@code shard_setup} sentinel is preserved
             * verbatim so existing bootstrap logic is unaffected.
             */
            @Override
            public String toString() {
                if (SHARD_SETUP_SENTINEL.equals(indexName) && partitionName == null && cursor == null) {
                    return SHARD_SETUP_SENTINEL;
                }
                return encode(indexName) + SEPARATOR + encode(partitionName) + SEPARATOR + encode(cursor);
            }

            private static String encode(String segment) {
                return segment == null ? "" : SEGMENT_ENCODER.encodeToString(segment.getBytes(StandardCharsets.UTF_8));
            }

            private static String decode(String segment, String input, String label) {
                if (segment.isEmpty()) {
                    return null;
                }
                try {
                    return new String(SEGMENT_DECODER.decode(segment), StandardCharsets.UTF_8);
                } catch (IllegalArgumentException e) {
                    throw new IllegalArgumentException("Illegal work item: '" + input
                            + "' (" + label + " segment is not valid base64url)", e);
                }
            }

            public static WorkItem valueFromWorkItemString(String input) {
                if (SHARD_SETUP_SENTINEL.equals(input)) {
                    return new WorkItem(input, null, null);
                }
                var components = SEPARATOR_PATTERN.split(input, -1);
                if (components.length != 3) {
                    throw new IllegalArgumentException("Illegal work item: '" + input + "'");
                }
                return new WorkItem(
                    decode(components[0], input, "index name"),
                    decode(components[1], input, "partition name"),
                    decode(components[2], input, "cursor"));
            }
        }
    }

    @Override
    default void close() throws Exception {
    }
}
