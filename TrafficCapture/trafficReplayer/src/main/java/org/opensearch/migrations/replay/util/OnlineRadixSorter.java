package org.opensearch.migrations.replay.util;

import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.opensearch.migrations.replay.datatypes.FutureTransformer;

import java.util.Comparator;
import java.util.Optional;
import java.util.PriorityQueue;

/**
 * This provides a simple implementation to sort incoming elements that are ordered by a sequence
 * of unique and contiguous integers.  This implementation uses a PriorityQueue for staging out of order
 * elements and the memory utilization will be O(total number of items to be sequenced) in the worst case,
 * but O(1) when the items are arriving in order.
 *
 * After the item has been added, if other items were waiting for it, all the next currently sequenced
 * items are signaled.  This allows the calling context to visit the items in the natural
 * order as opposed to the order that items were added.
 *
 * When an item is next to run, that 'slot' signals via the completion of a CompletableFuture.  The future
 * signaled is the same one that was passed to the processor function in addFutureWork.  That processor
 * is responsible for setting up any work necessary when the future is signaled (compose, whenComplete, etc)
 * and returning the resultant future.  That resultant future's completion will block the OnlineRadixSorter
 * instance from proceeding to signal any subsequent signals.
 */
@Slf4j
public class OnlineRadixSorter {
    @AllArgsConstructor
    private static class IndexedWork {
        public final int index;
        public final DiagnosticTrackableCompletableFuture<String,Void> signalingFuture;
        public final DiagnosticTrackableCompletableFuture<String,Void> workCompletedFuture;
    }

    private final PriorityQueue<IndexedWork> items;
    int currentOffset;

    public OnlineRadixSorter(int startingOffset) {
        items = new PriorityQueue<>(Comparator.comparingInt(iw->iw.index));
        currentOffset = startingOffset;
    }

    /**
     * Add a new future that will be responsible for triggering some work now or in the future once all
     * prior indices of work have been completed.  Once the work is ready to be run, a future is marked
     * as complete.  It is the responsibility of the caller to supply a processor function that takes the
     * completed future and supplies further processing upon its completion, returning the new future.
     * Both futures will be tracked by this class with the first future acting as a signal while the
     * second future returned by processor acts as a gate that prevents the triggering of subsequent
     * work from happening until it has completed.
     * @param index
     * @param processor
     * @return
     */
    public <T> DiagnosticTrackableCompletableFuture<String,T>
    addFutureForWork(int index, FutureTransformer<T> processor) {
        var signalFuture = new StringTrackableCompletableFuture<Void>("signaling future");
        var continueFuture = processor.apply(signalFuture);

        // purposefully use getDeferredFutureThroughHandle to do type erasure on T to get it back to Void
        // since the caller is creating a DCF<T> for their needs.  However, type T will only come up again
        // as per the work that was set within the processor.  There's no benefit to making the underlying
        // datastore aware of that T, hence the erasure.
        var workBundle = new IndexedWork(index, signalFuture,
                continueFuture.thenApply(v->{
                            log.atDebug().setMessage(()->"Increasing currentOffset to " + currentOffset +
                                    " for " + System.identityHashCode(this)).log();
                    items.remove();
                    ++currentOffset;
                    pullNextWorkItemOrDoNothing();
                    return null;
                        }, () -> "Bumping currentOffset and checking if the next items should be signaled"));
        items.add(workBundle);
        if (index == this.currentOffset) {
            pullNextWorkItemOrDoNothing();
        }
        return continueFuture;
    }

    private void pullNextWorkItemOrDoNothing() {
        Optional.ofNullable(items.isEmpty() ? null : items.peek())
                .filter(indexedWork -> indexedWork.index == currentOffset)
                .ifPresent(indexedWork -> {
                    var firstSignal = indexedWork.signalingFuture.future.complete(null);
                    assert firstSignal : "expected only this function to signal completion of the signaling future " +
                            "and for it to only be called once";
                });
    }

    public boolean hasPending() { return !items.isEmpty(); }

    @Override
    public String toString() {
        final StringBuilder sb = new StringBuilder("OnlineRadixSorter{");
        sb.append("id=").append(System.identityHashCode(this));
        sb.append("items=").append(items);
        sb.append(", currentOffset=").append(currentOffset);
        sb.append('}');
        return sb.toString();
    }

    public long numPending() {
        return items.size();
    }

    public boolean isEmpty() { return items.isEmpty(); }

    public int size() { return items.size(); }
}
