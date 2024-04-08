package org.opensearch.migrations.replay.util;


import lombok.AllArgsConstructor;
import lombok.Getter;
import org.opensearch.migrations.replay.TrafficReplayerTopLevel;
import org.opensearch.migrations.replay.datatypes.UniqueReplayerRequestKey;

import java.util.Comparator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.stream.Stream;

public class OrderedWorkerTracker implements TrafficReplayerTopLevel.IStreamableWorkTracker {
    @AllArgsConstructor
    static class TimeKeyAndFuture {
        @Getter
        final long timeKey;
        final DiagnosticTrackableCompletableFuture<String,Void> future;
    }
    ConcurrentHashMap<UniqueReplayerRequestKey, TimeKeyAndFuture> primaryMap = new ConcurrentHashMap<>();
    ConcurrentSkipListSet<TimeKeyAndFuture> orderedSet =
            new ConcurrentSkipListSet<>(Comparator.comparingLong(TimeKeyAndFuture::getTimeKey)
                    .thenComparingLong(i->System.identityHashCode(i)));

    @Override
    public void put(UniqueReplayerRequestKey uniqueReplayerRequestKey, DiagnosticTrackableCompletableFuture<String, Void> completableFuture) {
        var timedValue = new TimeKeyAndFuture(System.nanoTime(), completableFuture);
        primaryMap.put(uniqueReplayerRequestKey, timedValue);
        orderedSet.add(timedValue);
    }

    @Override
    public void remove(UniqueReplayerRequestKey uniqueReplayerRequestKey) {
        var timedValue = primaryMap.remove(uniqueReplayerRequestKey);
        assert timedValue != null;
        orderedSet.remove(timedValue);
    }

    @Override
    public boolean isEmpty() {
        return primaryMap. isEmpty();
    }

    @Override
    public int size() {
        return primaryMap.size();
    }

    public Stream<Map.Entry<UniqueReplayerRequestKey, DiagnosticTrackableCompletableFuture<String,Void>>>
    getRemainingItems() {
        return primaryMap.entrySet().stream().map(kvp->Map.entry(kvp.getKey(), kvp.getValue().future));
    }
}