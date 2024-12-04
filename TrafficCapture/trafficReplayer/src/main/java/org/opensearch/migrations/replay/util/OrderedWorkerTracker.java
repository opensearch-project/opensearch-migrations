package org.opensearch.migrations.replay.util;

import java.util.Comparator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.stream.Stream;

import org.opensearch.migrations.replay.TrafficReplayerTopLevel;
import org.opensearch.migrations.replay.datatypes.UniqueReplayerRequestKey;
import org.opensearch.migrations.utils.TrackedFuture;

import lombok.AllArgsConstructor;
import lombok.Getter;

public class OrderedWorkerTracker<T> implements TrafficReplayerTopLevel.IStreamableWorkTracker<T> {
    @AllArgsConstructor
    static class TimeKeyAndFuture<U> {
        @Getter
        final long nanoTimeKey;
        final TrackedFuture<String, U> future;
    }

    ConcurrentHashMap<UniqueReplayerRequestKey, TimeKeyAndFuture<T>> primaryMap = new ConcurrentHashMap<>();
    ConcurrentSkipListSet<TimeKeyAndFuture<T>> orderedSet = new ConcurrentSkipListSet<>(
        Comparator.comparingLong(TimeKeyAndFuture<T>::getNanoTimeKey).thenComparingLong(System::identityHashCode)
    );

    @Override
    public void put(UniqueReplayerRequestKey uniqueReplayerRequestKey, TrackedFuture<String, T> completableFuture) {
        var timedValue = new TimeKeyAndFuture<>(System.nanoTime(), completableFuture);
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
        return primaryMap.isEmpty();
    }

    @Override
    public int size() {
        return primaryMap.size();
    }

    public Stream<Map.Entry<UniqueReplayerRequestKey, TrackedFuture<String, T>>> getRemainingItems() {
        return primaryMap.entrySet().stream().map(kvp -> Map.entry(kvp.getKey(), kvp.getValue().future));
    }
}
