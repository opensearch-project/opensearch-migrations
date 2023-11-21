package org.opensearch.migrations.replay.kafka;

import lombok.extern.slf4j.Slf4j;

import java.util.Optional;
import java.util.PriorityQueue;

/**
 * This uses a PriorityQueue to find the MINIMUM offset that has yet to be 'committed'.
 * This class assumes that add() will be called with ascending offsets and that
 * removeAndReturnNewHead may be called in any order.  removeAndReturnNewHead returns
 * the new commit offset for the partition that this object is associated with.
 * It's also assumed that callers MUST call removeAndReturnNewHead for every offset
 * that was previously added for commit points to be advanced.
 */
@Slf4j
class OffsetLifecycleTracker {
    private final PriorityQueue<Long> pQueue = new PriorityQueue<>();
    private long cursorHighWatermark;
    final int consumerConnectionGeneration;

    OffsetLifecycleTracker(int generation) {
        this.consumerConnectionGeneration = generation;
    }

    boolean isEmpty() {
        return pQueue.isEmpty();
    }

    void add(long offset) {
        cursorHighWatermark = offset;
        pQueue.add(offset);
    }

    Optional<Long> removeAndReturnNewHead(long offsetToRemove) {
        var topCursor = pQueue.peek();
        var didRemove = pQueue.remove(offsetToRemove);
        assert didRemove : "Expected all live records to have an entry and for them to be removed only once";
        if (topCursor == offsetToRemove) {
            topCursor = Optional.ofNullable(pQueue.peek())
                    .orElse(cursorHighWatermark + 1); // most recent cursor was previously popped
            log.atDebug().setMessage("Commit called for " + offsetToRemove + ", and new topCursor=" + topCursor).log();
            return Optional.of(topCursor);
        } else {
            log.atDebug().setMessage("Commit called for " + offsetToRemove + ", but topCursor=" + topCursor).log();
            return Optional.empty();
        }
    }
}
