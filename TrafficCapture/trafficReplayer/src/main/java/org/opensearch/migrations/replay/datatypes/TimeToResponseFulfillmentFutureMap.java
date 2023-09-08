package org.opensearch.migrations.replay.datatypes;

import java.time.Instant;
import java.util.AbstractMap;
import java.util.ArrayDeque;
import java.util.Map;
import java.util.StringJoiner;
import java.util.TreeMap;

public class TimeToResponseFulfillmentFutureMap {
    TreeMap<Instant, ArrayDeque<Runnable>> timeToRunnableMap = new TreeMap<>();

    public void appendTask(Instant start, Runnable packetSender) {
        assert timeToRunnableMap.keySet().stream().allMatch(t->!t.isAfter(start));
        var existing = timeToRunnableMap.get(start);
        if (existing == null) {
            existing = new ArrayDeque<>();
            timeToRunnableMap.put(start, existing);
        }
        existing.offer(packetSender);
    }

    public Map.Entry<Instant, Runnable> peekFirstItem() {
        var e = timeToRunnableMap.firstEntry();
        return e == null ? null : new AbstractMap.SimpleEntry(e.getKey(), e.getValue().peek());
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

    public long calculateSizeSlowly() {
        return timeToRunnableMap.values().stream().map(q->q.size()).mapToInt(x->x).sum();
    }

    @Override
    public String toString() {
        return "[" + this.calculateSizeSlowly() + "]: {" + formatBookends() + "}";
    }

    private String formatBookends() {
        if (timeToRunnableMap.size() == 0) {
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
