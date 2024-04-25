package org.opensearch.migrations.replay.util;

import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.opensearch.migrations.replay.datatypes.FutureTransformer;

import java.util.Optional;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

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
        public final DiagnosticTrackableCompletableFuture<String,Void> signalingToStartFuture;
        public DiagnosticTrackableCompletableFuture<String,? extends Object> workCompletedFuture;
        public final DiagnosticTrackableCompletableFuture<String,Void> signalWorkCompletedFuture;

        public <T> DiagnosticTrackableCompletableFuture<String,T>
        addWorkFuture(FutureTransformer<T> processor, int index) {
            var rval = processor.apply(signalingToStartFuture).propagateCompletionToDependentFuture(signalWorkCompletedFuture,
                    (processedCf, dependentCf) -> dependentCf.complete(null),
                    ()->"Caller-task completion for idx=" + index);
            workCompletedFuture = rval;
            return rval;
        }
    }

    private final SortedMap<Integer,IndexedWork> items;
    int currentOffset;

    public OnlineRadixSorter(int startingOffset) {
        items = new TreeMap<>();
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
    addFutureForWork(final int index, FutureTransformer<T> processor) {
        var oldWorkItem = items.get(index);
        if (oldWorkItem == null) {
            if (index < currentOffset) {
                throw new IllegalArgumentException("index (" + index + ")" +
                        " must be > last processed item (" + currentOffset + ")");
            }
            for (int nextKey = Math.max(currentOffset, items.isEmpty() ? 0 : items.lastKey()+1);
                 nextKey<=index;
                 ++nextKey) {
                int finalNextKey = nextKey;
                var signalFuture = items.isEmpty() ?
                        new StringTrackableCompletableFuture<Void>(
                                CompletableFuture.completedFuture(null),
                                "unlinked signaling future for slot #" + finalNextKey) :
                        items.get(items.lastKey()).signalWorkCompletedFuture
                                .thenAccept(v-> {},
                                        ()->"Kickoff for slot #" + finalNextKey);
                oldWorkItem = new IndexedWork(signalFuture, null,
                        new StringTrackableCompletableFuture<Void>(()->"Work to finish for slot #" + finalNextKey +
                                " is awaiting [" + getAwaitingTextUpTo(index) + "]"));
                oldWorkItem.signalWorkCompletedFuture.whenComplete((v,t)->{
                    ++currentOffset;
                    items.remove(finalNextKey);
                    }, ()->"cleaning up spent work for idx #" + finalNextKey);
                items.put(nextKey, oldWorkItem);
            }
        }
        return oldWorkItem.addWorkFuture(processor, index);
    }

    public String getAwaitingTextUpTo(int upTo) {
        return "slotsOutstanding:" +
                IntStream.range(0, upTo-currentOffset)
                .map(i->upTo-i-1)
                .mapToObj(i -> Optional.ofNullable(items.get(i))
                        .flatMap(wi->Optional.ofNullable(wi.workCompletedFuture))
                        .map(ignored->"")
                        .orElse(i+""))
                .filter(s->!s.isEmpty())
                .collect(Collectors.joining(","));
    }

    @Override
    public String toString() {
        final StringBuilder sb = new StringBuilder("OnlineRadixSorter{");
        sb.append("id=").append(System.identityHashCode(this));
        sb.append("items=").append(items);
        sb.append(", currentOffset=").append(currentOffset);
        sb.append('}');
        return sb.toString();
    }

    public boolean hasPending() { return !items.isEmpty(); }

    public long numPending() {
        return items.size();
    }

    public boolean isEmpty() { return items.isEmpty(); }

    public int size() { return items.size(); }
}
