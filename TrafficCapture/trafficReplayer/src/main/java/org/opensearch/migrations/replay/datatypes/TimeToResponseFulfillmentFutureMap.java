package org.opensearch.migrations.replay.datatypes;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.StringJoiner;

import org.opensearch.migrations.utils.TextTrackedFuture;
import org.opensearch.migrations.utils.TrackedFuture;

public class TimeToResponseFulfillmentFutureMap {

    public static class FutureWorkPoint {
        public final Instant startTime;
        public final TrackedFuture<String, Void> scheduleFuture;
        private final ChannelTaskType channelTaskType;

        public FutureWorkPoint(Instant forTime, ChannelTaskType taskType) {
            startTime = forTime;
            scheduleFuture = new TextTrackedFuture<>("scheduled start for " + forTime);
            channelTaskType = taskType;
        }
    }

    Deque<FutureWorkPoint> timeToRunnableMap = new ArrayDeque<>();

    public FutureWorkPoint appendTaskTrigger(Instant start, ChannelTaskType taskType) {
        assert timeToRunnableMap.stream().map(fwp -> fwp.startTime).allMatch(t -> !t.isAfter(start));
        var fpp = new FutureWorkPoint(start, taskType);
        timeToRunnableMap.offer(fpp);
        return fpp;
    }

    public FutureWorkPoint peekFirstItem() {
        return timeToRunnableMap.peekFirst();
    }

    public Instant removeFirstItem() {
        return timeToRunnableMap.isEmpty() ? null : timeToRunnableMap.pop().startTime;
    }

    public boolean isEmpty() {
        return timeToRunnableMap.isEmpty();
    }

    public void clear() {
        timeToRunnableMap.clear();
    }

    /**
     * Completes all pending scheduleFuture entries exceptionally, then clears the map.
     * This allows the OnlineRadixSorter to drain immediately rather than stalling on
     * orphaned futures when a session is cancelled.
     */
    public void drainWithCancellation(java.util.concurrent.CancellationException cause) {
        timeToRunnableMap.forEach(fwp -> fwp.scheduleFuture.future.completeExceptionally(cause));
        timeToRunnableMap.clear();
    }

    public boolean hasPendingTransmissions() {
        if (timeToRunnableMap.isEmpty()) {
            return false;
        } else {
            return timeToRunnableMap.stream().anyMatch(fwp -> fwp.channelTaskType == ChannelTaskType.TRANSMIT);
        }
    }

    @Override
    public String toString() {
        return "[" + (long) timeToRunnableMap.size() + "]: {" + formatBookends() + "}";
    }

    private String formatBookends() {
        if (timeToRunnableMap.isEmpty()) {
            return "";
        } else if (timeToRunnableMap.size() == 1) {
            return timeToRunnableMap.peekFirst().startTime.toString();
        } else {
            return new StringJoiner("...").add(timeToRunnableMap.peekFirst().startTime.toString())
                .add(timeToRunnableMap.peekLast().toString())
                .toString();
        }
    }
}
