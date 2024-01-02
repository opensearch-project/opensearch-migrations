package org.opensearch.migrations.replay.datatypes;

import java.time.Instant;
import java.util.AbstractMap;
import java.util.ArrayDeque;
import java.util.Map;
import java.util.StringJoiner;
import java.util.TreeMap;

public class TimeToResponseFulfillmentFutureMap {

    TreeMap<Instant, ArrayDeque<ChannelTask>> timeToRunnableMap = new TreeMap<>();

    public void appendTask(Instant start, ChannelTask task) {
        assert timeToRunnableMap.keySet().stream().allMatch(t->!t.isAfter(start));
        var existing = timeToRunnableMap.computeIfAbsent(start, k->new ArrayDeque<>());
        existing.offer(task);
    }

    public Map.Entry<Instant, ChannelTask> peekFirstItem() {
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
                    .flatMap(d->d.stream())
                    .filter(ct->ct.kind==ChannelTaskType.TRANSMIT)
                    .findAny().isPresent();
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
