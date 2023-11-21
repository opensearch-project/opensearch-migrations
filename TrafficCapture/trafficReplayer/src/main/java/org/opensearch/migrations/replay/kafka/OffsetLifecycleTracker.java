package org.opensearch.migrations.replay.kafka;

import lombok.extern.slf4j.Slf4j;

import java.util.Optional;
import java.util.PriorityQueue;

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

    Optional<Long> removeAndReturnNewHead(KafkaCommitOffsetData kafkaRecord) {
        var offsetToRemove = kafkaRecord.getOffset();
        var topCursor = pQueue.peek();
        var didRemove = pQueue.remove(offsetToRemove);
        assert didRemove : "Expected all live records to have an entry and for them to be removed only once";
        if (topCursor == offsetToRemove) {
            topCursor = Optional.ofNullable(pQueue.peek())
                    .orElse(cursorHighWatermark + 1); // most recent cursor was previously popped
            log.atDebug().setMessage("Commit called for {}, and new topCursor={}")
                    .addArgument(offsetToRemove).addArgument(topCursor).log();
            return Optional.of(topCursor);
        } else {
            log.atDebug().setMessage("Commit called for {}, but topCursor={}")
                    .addArgument(offsetToRemove).addArgument(topCursor).log();
            return Optional.empty();
        }
    }
}
