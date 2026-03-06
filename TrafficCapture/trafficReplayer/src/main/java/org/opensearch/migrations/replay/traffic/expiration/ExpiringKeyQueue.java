package org.opensearch.migrations.replay.traffic.expiration;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListMap;

import lombok.EqualsAndHashCode;
import lombok.extern.slf4j.Slf4j;

/**
 * This is a sequence of (concurrent) hashmaps segmented by time.  The hashmaps are really just sets,
 * the boolean value is just a dummy because there isn't a concurrent set class.  Each element in the
 * sequence is composed of a timestamp and a map.  The timestamp at each element is guaranteed to be
 * greater than all items within all maps that preceded it.
 */
@Slf4j
@EqualsAndHashCode(callSuper = true)
class ExpiringKeyQueue extends ConcurrentSkipListMap<EpochMillis, ConcurrentHashMap<ScopedConnectionIdKey, Boolean>> {
    private final Duration granularity;

    ExpiringKeyQueue(Duration granularity, String ignored, EpochMillis startingTimestamp) {
        this.granularity = granularity;
        addNewSet(startingTimestamp);
    }

    public Instant getLatestPossibleKeyValue() {
        return lastKey().toInstant().plus(granularity);
    }

    private ConcurrentHashMap<ScopedConnectionIdKey, Boolean> addNewSet(EpochMillis timestampMillis) {
        var accumulatorMap = new ConcurrentHashMap<ScopedConnectionIdKey, Boolean>();
        this.put(timestampMillis, accumulatorMap);
        return accumulatorMap;
    }

    /**
     * Returns null if the requested timestamp is in the expired range of timestamps,
     * otherwise this returns the appropriate bucket.  It either finds it within the map
     * or creates a new one and inserts it into the map (atomically).
     *
     * @param timestamp
     * @return
     */
    ConcurrentHashMap<ScopedConnectionIdKey, Boolean> getHashSetForTimestamp(EpochMillis timestamp) {
        return Optional.ofNullable(this.floorEntry(timestamp)).map(kvp -> {
            var shiftedKey = kvp.getKey().toInstant().plus(granularity);
            if (timestamp.test(shiftedKey, (newTimestamp, computedFloor) -> newTimestamp >= computedFloor)) {
                return createNewSlot(timestamp, kvp.getKey());
            }
            return kvp.getValue();
        }).orElse(null); // floorEntry could be null if the entry was too old
    }

    /**
     * We don't want to have a race condition where many nearby keys could be created.  This could happen
     * if many requests come in with slightly different timestamps, but were being processed before the
     * new bucket was created.  Since we're dealing with a map, the simplest way around this is to reduce,
     * or quantize, the range of potential keys so that each key will uniquely identify an entire range.
     * It should be impossible for two keys to have any overlap over the granularity window.
     * <p>
     * That allows us to put a new entry ONLY IF it isn't already there, which results in a uniqueness
     * invariant that makes a lot of other things easier to reason with.
     */
    private ConcurrentHashMap<ScopedConnectionIdKey, Boolean> createNewSlot(EpochMillis timestamp, EpochMillis referenceKey) {
        var granularityMs = granularity.toMillis();
        var quantizedDifference = (timestamp.millis - referenceKey.millis) / granularityMs;
        var newKey = referenceKey.millis + (quantizedDifference * granularityMs);
        var newMap = new ConcurrentHashMap<ScopedConnectionIdKey, Boolean>();
        var priorMap = putIfAbsent(new EpochMillis(newKey), newMap);
        return priorMap == null ? newMap : priorMap;
    }

    void expireOldSlots(
        AccumulatorMap connectionAccumulatorMap,
        BehavioralPolicy behavioralPolicy,
        Duration minimumGuaranteedLifetime,
        EpochMillis largestCurrentObservedTimestamp
    ) {
        var rawStartOfWindow = largestCurrentObservedTimestamp.millis - minimumGuaranteedLifetime.toMillis();
        var granularityMs = granularity.toMillis();
        var quantizedStartOfWindow = new EpochMillis((rawStartOfWindow / granularityMs) * granularityMs);

        for (var kvp = firstEntry(); kvp.getKey()
            .test(quantizedStartOfWindow, (first, windowStart) -> first < windowStart); kvp = firstEntry()) {
            var orphaned = expireItemsBefore(connectionAccumulatorMap, behavioralPolicy, kvp.getValue(), quantizedStartOfWindow);
            if (orphaned > 0) {
                log.atError().setMessage("Bucket {} has {} connections that survived sweep — " +
                        "this should not happen with deferred expiry. quantizedStartOfWindow={}")
                    .addArgument(kvp.getKey()).addArgument(orphaned).addArgument(quantizedStartOfWindow).log();
            }
            log.atDebug().setMessage("[v3] Swept and removing bucket {}, quantizedStartOfWindow={}")
                .addArgument(kvp.getKey()).addArgument(quantizedStartOfWindow).log();
            remove(kvp.getKey());
        }
    }

    private int expireItemsBefore(
        AccumulatorMap connectionAccumulatorMap,
        BehavioralPolicy behavioralPolicy,
        ConcurrentHashMap<ScopedConnectionIdKey, Boolean> keyMap,
        EpochMillis earlierTimesToPreserve
    ) {
        log.atTrace().setMessage("Expiring entries before {}").addArgument(earlierTimesToPreserve).log();
        int survivorCount = 0;
        for (var scopedKey : keyMap.keySet()) {
            var accumulation = connectionAccumulatorMap.get(scopedKey);
            if (accumulation != null
                && accumulation.getNewestPacketTimestampInMillisReference().get() < earlierTimesToPreserve.millis) {
                var priorValue = connectionAccumulatorMap.remove(scopedKey);
                if (priorValue != null) {
                    priorValue.expire();
                    behavioralPolicy.onExpireAccumulation(scopedKey.nodeId, accumulation);
                }
                keyMap.remove(scopedKey);
            } else if (accumulation != null) {
                survivorCount++;
            } else {
                keyMap.remove(scopedKey);
            }
        }
        return survivorCount;
    }
}
