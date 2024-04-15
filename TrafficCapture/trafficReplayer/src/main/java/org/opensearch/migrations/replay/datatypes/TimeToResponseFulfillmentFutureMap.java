package org.opensearch.migrations.replay.datatypes;

import org.opensearch.migrations.replay.util.DiagnosticTrackableCompletableFuture;
import org.opensearch.migrations.replay.util.StringTrackableCompletableFuture;

import java.time.Instant;
import java.util.AbstractMap;
import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Map;
import java.util.StringJoiner;
import java.util.TreeMap;

public class TimeToResponseFulfillmentFutureMap {

    public static class FutureWorkPoint {
        public final DiagnosticTrackableCompletableFuture<String, Void> scheduleFuture;
        private final ChannelTaskType channelTaskType;
        public FutureWorkPoint(Instant forTime, ChannelTaskType taskType) {
            scheduleFuture = new StringTrackableCompletableFuture<>("scheduled start for " + forTime);
            channelTaskType = taskType;
        }
    }

    TreeMap<Instant, ArrayDeque<FutureWorkPoint>> timeToRunnableMap = new TreeMap<>();

    public FutureWorkPoint appendTaskTrigger(Instant start, ChannelTaskType taskType) {
        assert timeToRunnableMap.keySet().stream().allMatch(t->!t.isAfter(start));
        var existing = timeToRunnableMap.computeIfAbsent(start, k->new ArrayDeque<>());
        var fpp = new FutureWorkPoint(start, taskType);
        existing.offer(fpp);
        return fpp;
    }

    public Map.Entry<Instant, FutureWorkPoint> peekFirstItem() {
        var e = timeToRunnableMap.firstEntry();
        return e == null ? null : new AbstractMap.SimpleEntry<>(e.getKey(), e.getValue().peek());
    }

    public Instant removeFirstItem() {
        var e = timeToRunnableMap.firstEntry();
        if (e != null) {
            var q = e.getValue();
            q.remove();
            if (q.isEmpty()) {
                timeToRunnableMap.remove(e.getKey());
            }
            return e.getKey();
        } else {
            return null;
        }
    }

    public boolean isEmpty() {
        return timeToRunnableMap.isEmpty();
    }

    public boolean hasPendingTransmissions() {
        if (timeToRunnableMap.isEmpty()) {
            return false;
        } else {
            return timeToRunnableMap.values().stream()
                    .flatMap(Collection::stream)
                    .anyMatch(fwp->fwp.channelTaskType==ChannelTaskType.TRANSMIT);
        }
    }

    public long calculateSizeSlowly() {
        return timeToRunnableMap.values().stream().map(ArrayDeque::size).mapToInt(x->x).sum();
    }

    @Override
    public String toString() {
        return "[" + this.calculateSizeSlowly() + "]: {" + formatBookends() + "}";
    }

    private String formatBookends() {
        if (timeToRunnableMap.isEmpty()) {
            return "";
        } else if (timeToRunnableMap.size() == 1) {
            return timeToRunnableMap.firstKey().toString();
        } else {
            return new StringJoiner("...")
                    .add(timeToRunnableMap.firstKey().toString())
                    .add(timeToRunnableMap.lastKey().toString())
                    .toString();
        }
    }
}
