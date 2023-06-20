package org.opensearch.migrations.replay;

import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.function.BiPredicate;
import java.util.stream.Stream;

/**
 * This object manages the lifecycle of Accumulation objects, creating new ones and (eventually, once implemented)
 * expiring old entries.  Callers are expected to modify the Accumulation values themselves, but to proxy every
 * distinct interaction through this class along with the timestamp of the observed interaction.  That (will)
 * allow this class to expunge outdated entries. Notice that a callback (or calling) mechanism still needs to
 * be implemented so that the calling context is aware that items have been expired.
 *
 * This doesn't use more typical out-of-the-box LRU mechanisms.  Our requirements are a little bit different.
 * First, we're fine buffering a variable number of items and secondly, this should be threadsafe an able to
 * be used in highly concurrent contexts.
 */
@Slf4j
public class ExpiringTrafficStreamMap {

    public static final int DEFAULT_NUM_TIMESTAMP_UPDATE_ATTEMPTS = 2;
    public static final int ACCUMULATION_DEAD_SENTINEL = Integer.MAX_VALUE;
    public static final int ACCUMULATION_TIMESTAMP_NOT_SET_YET_SENTINEL = 0;

    @EqualsAndHashCode
    public static class EpochMillis implements Comparable<EpochMillis> {
        final long millis;
        public EpochMillis(Instant i) {
            millis = i.toEpochMilli();
        }
        public EpochMillis(long ms) {
            this.millis = ms;
        }

        public boolean test(EpochMillis referenceTimestamp, BiPredicate<Long,Long> c) {
            return c.test(this.millis, referenceTimestamp.millis);
        }

        public boolean test(Instant referenceTimestamp, BiPredicate<Long,Long> c) {
            return c.test(this.millis, referenceTimestamp.toEpochMilli());
        }

        public Instant toInstant() {
            return Instant.ofEpochMilli(millis);
        }
        @Override
        public String toString() { return Long.toString(millis); }

        @Override
        public int compareTo(EpochMillis o) {
            return Long.valueOf(this.millis).compareTo(o.millis);
        }
    }

    /**
     * I should look up what this is called in the Gang of Four book.
     * In my mind, this is a metaprogramming policy mixin.
     */
    public static class BehavioralPolicy {
        public void onDataArrivingBeforeTheStartOfTheCurrentProcessingWindow(
                String partitionId, String connectionId, Instant timestamp, Instant endOfWindow) {
            log.error("Could not update the expiration of an object whose timestamp is before the " +
                    "oldest point in time that packets are still being processed for this partition.  " +
                    "This means that there was larger than expected temporal jitter in packets.  " +
                    "That means that traffic for this connection may have already been reported as expired " +
                    "and processed as such and that this data will not be properly handled due to other data " +
                    "within the connection being prematurely expired.  Trying to send the data through a new " +
                    "instance of this object with a minimumGuaranteedLifetime of " +
                    Duration.between(timestamp, endOfWindow) + " will allow for this packet to be properly " +
                    "accumulated for (" + partitionId + "," + connectionId + ")");
        }

        public void onNewDataArrivingAfterItsAccumulationHasBeenExpired(
                String partitionId, String connectionId, Instant lastPacketTimestamp, Instant endOfWindow) {
            log.error("New data has arrived, but during the processing of this Accumulation object, " +
                    "the Accumulation was expired.  This indicates that the minimumGuaranteedLifetime " +
                    "must be set to at least " + Duration.between(lastPacketTimestamp, endOfWindow) +
                    ".  The beginning of the valid time window is currently " + endOfWindow +
                    " for (" + partitionId + "," + connectionId + ") and the last timestamp of the " +
                    "Accumulation object that was being assembled was");
        }

        public void onNewDataArrivingWithATimestampThatIsAlreadyExpired(
                String partitionId, String connectionId, Instant timestamp, Instant endOfWindow) {
            log.error("New data has arrived, but during the processing of this Accumulation object, " +
                    "the Accumulation was expired.  This indicates that the minimumGuaranteedLifetime " +
                    "must be set to at least " + Duration.between(timestamp, endOfWindow) +
                    ".  The beginning of the valid time window is currently " + endOfWindow +
                    " for (" + partitionId + "," + connectionId + ") and the last timestamp of the " +
                    "Accumulation object that was being assembled was");
        }

        public boolean shouldRetryAfterAccumulationTimestampRaceDetected(String partitionId, String connectionId,
                                                                         Instant timestamp, Accumulation accumulation,
                                                                         int attempts) {
            if (attempts > DEFAULT_NUM_TIMESTAMP_UPDATE_ATTEMPTS) {
                log.error("A race condition was detected while trying to update the most recent timestamp " +
                        "(" + timestamp + ") of " + "accumulation (" + accumulation + ") for " +
                        partitionId + "/" + connectionId + ".  Giving up after " + attempts + " attempts.  " +
                        "Data for this connection may be corrupted.");
                return false;
            } else {
                return true;
            }
        }

        public void onExpireAccumulation(String partitionId, String connectionId, Accumulation accumulation) {
            // do nothing by default
        }
    }

    @AllArgsConstructor
    @EqualsAndHashCode
    private static class ScopedConnectionIdKey {
        public final String nodeId;
        public final String connectionId;
    }

    private static class AccumulatorMap extends ConcurrentHashMap<ScopedConnectionIdKey, Accumulation> {}

    /**
     * This is a sequence of (concurrent) hashmaps segmented by time.  Each element in the sequence is
     * composed of a timestamp and a map.  The timestamp at each element is guaranteed to be greater
     * than all items within all maps that preceded it.
     *
     * Notice that this class DOES use some values from the surrounding class (granularity)
     */
    private class ExpiringKeyQueue extends
            ConcurrentSkipListMap<EpochMillis,ConcurrentHashMap<String,Boolean>> {
        private final String partitionId;

        ExpiringKeyQueue(String partitionId, EpochMillis startingTimestamp) {
            this.partitionId = partitionId;
            addNewSet(startingTimestamp);
        }

        public Instant getLatestPossibleKeyValue() {
            return lastKey().toInstant().plus(granularity);
        }

        private ConcurrentHashMap<String,Boolean> addNewSet(EpochMillis timestampMillis) {
            var accumulatorMap = new ConcurrentHashMap<String,Boolean>();
            this.put(timestampMillis, accumulatorMap);
            return accumulatorMap;
        }

        /**
         * Returns null if the requested timestamp is in the expired range of timestamps,
         * otherwise this returns the appropriate bucket.  It either finds it within the map
         * or creates a new one and inserts it into the map (atomically).
         * @param timestamp
         * @return
         */
        private ConcurrentHashMap<String, Boolean> getHashSetForTimestamp(EpochMillis timestamp) {
            return Optional.ofNullable(this.floorEntry(timestamp))
                    .map(kvp-> {
                        var shiftedKey = kvp.getKey().toInstant().plus(granularity);
                        if (timestamp.test(shiftedKey, (boundary, ref) -> boundary>=ref)) {
                            try {
                                return createNewSlot(timestamp, kvp.getKey());
                            } finally {
                                expireOldSlots(timestamp);
                            }
                        }
                        return kvp.getValue();
                    })
                    .orElse(null);
        }

        /**
         * We don't want to have a race condition where many nearby keys could be created.  This could happen
         * if many requests come in with slightly different timestamps, but were being processed before the
         * new bucket was created.  Since we're dealing with a map, the simplest way around this is to reduce,
         * or quantize, the range of potential keys so that each key will uniquely identify an entire range.
         * It should be impossible for two keys to have any overlap over the granularity window.
         *
         * That allows us to put a new entry ONLY IF it isn't already there, which results in a uniqueness
         * invariant that makes a lot of other things easier to reason with.
         */
        private ConcurrentHashMap<String, Boolean> createNewSlot(EpochMillis timestamp, EpochMillis referenceKey)  {
            var granularityMs = granularity.toMillis();
            var quantizedDifference = (timestamp.millis - referenceKey.millis) / granularityMs;
            var newKey = referenceKey.millis + (quantizedDifference * granularityMs);
            var newMap = new ConcurrentHashMap<String, Boolean>();
            var priorMap = putIfAbsent(new EpochMillis(newKey), newMap);
            return priorMap == null ? newMap : priorMap;
        }

        private void expireOldSlots(EpochMillis largestCurrentObservedTimestamp) {
            var startOfWindow =
                    new EpochMillis(largestCurrentObservedTimestamp.toInstant().minus(minimumGuaranteedLifetime));
            for (var kvp = firstEntry();
                 kvp.getKey().test(startOfWindow, (first, windowStart)->first<windowStart);
                 kvp = firstEntry()) {
                expireItemsBefore(kvp.getValue(), startOfWindow);
                remove(kvp.getKey());
            }
        }

        private void expireItemsBefore(ConcurrentHashMap<String, Boolean> keyMap, EpochMillis earlierTimesToPreserve) {
            log.debug("Expiring entries before " + earlierTimesToPreserve);
            for (var connectionId : keyMap.keySet()) {
                var key = new ScopedConnectionIdKey(partitionId, connectionId);
                var accumulation = connectionAccumulationMap.get(key);
                if (accumulation != null &&
                        accumulation.newestPacketTimestampInMillis.get() < earlierTimesToPreserve.millis) {
                    var priorValue = connectionAccumulationMap.remove(key);
                    if (priorValue != null) {
                        priorValue.newestPacketTimestampInMillis.set(ACCUMULATION_DEAD_SENTINEL);
                        behavioralPolicy.onExpireAccumulation(partitionId, connectionId, accumulation);
                    }
                }
            }
        }
    }

    protected final AccumulatorMap connectionAccumulationMap;
    protected final ConcurrentHashMap<String, ExpiringKeyQueue> nodeToExpiringBucketMap;
    protected final Duration minimumGuaranteedLifetime;
    protected final Duration granularity;
    protected final BehavioralPolicy behavioralPolicy;

    public ExpiringTrafficStreamMap(Duration minimumGuaranteedLifetime) {
        this(minimumGuaranteedLifetime, Duration.ofSeconds(1), new BehavioralPolicy());
    }

    public ExpiringTrafficStreamMap(Duration minimumGuaranteedLifetime,
                                    Duration granularity,
                                    BehavioralPolicy behavioralPolicy) {
        connectionAccumulationMap = new AccumulatorMap();
        this.granularity = granularity;
        this.minimumGuaranteedLifetime = minimumGuaranteedLifetime;
        this.nodeToExpiringBucketMap = new ConcurrentHashMap<>();
        this.behavioralPolicy = behavioralPolicy;
    }

    private ExpiringKeyQueue getOrCreateNodeMap(String partitionId, EpochMillis timestamp) {
        var newMap = new ExpiringKeyQueue(partitionId, timestamp);
        var priorMap = nodeToExpiringBucketMap.putIfAbsent(partitionId, newMap);
        return priorMap == null ? newMap : priorMap;
    }

    /**
     *
     * @param partitionId
     * @param connectionId
     * @param observedTimestampMillis
     * @param accumulation
     * @param attempts
     * @return false if the expiration couldn't be updated because the item was already expired.
     */
    private boolean updateExpirationTrackers(String partitionId, String connectionId,
                                             EpochMillis observedTimestampMillis,
                                             Accumulation accumulation, int attempts) {
        var expiringQueue = getOrCreateNodeMap(partitionId, observedTimestampMillis);
        // for expiration tracking purposes, push incoming packets' timestamps to be monotonic?
        var timestampMillis = new EpochMillis(Math.max(observedTimestampMillis.millis, expiringQueue.lastKey().millis));

        var lastPacketTimestamp = new EpochMillis(accumulation.newestPacketTimestampInMillis.get());
        if (lastPacketTimestamp.millis == ACCUMULATION_DEAD_SENTINEL) {
            behavioralPolicy.onNewDataArrivingAfterItsAccumulationHasBeenExpired(partitionId, connectionId,
                    lastPacketTimestamp.toInstant(), expiringQueue.getLatestPossibleKeyValue());
            return false;
        }
        if (timestampMillis.test(lastPacketTimestamp, (newTs, lastTs) -> newTs > lastTs)) {
            var witnessValue = accumulation.newestPacketTimestampInMillis.compareAndExchange(lastPacketTimestamp.millis,
                    timestampMillis.millis);
            if (lastPacketTimestamp.millis != witnessValue) {
                ++attempts;
                if (behavioralPolicy.shouldRetryAfterAccumulationTimestampRaceDetected(partitionId, connectionId,
                        timestampMillis.toInstant(), accumulation, attempts)) {
                    return updateExpirationTrackers(partitionId, connectionId, timestampMillis,
                            accumulation, attempts);
                } else {
                    return false;
                }
            }
        }

        var targetBucketHashSet = expiringQueue.getHashSetForTimestamp(timestampMillis);
        if (targetBucketHashSet == null) {
            var startOfWindow = expiringQueue.firstKey().toInstant();
            assert !timestampMillis.test(startOfWindow, (ts, windowStart) -> ts < windowStart) :
                    "Only expected to not find the target bucket when the incoming timestamp was before the " +
                            "expiring queue's time window";
            behavioralPolicy.onDataArrivingBeforeTheStartOfTheCurrentProcessingWindow(partitionId, connectionId,
                    timestampMillis.toInstant(), expiringQueue.getLatestPossibleKeyValue());
            return false;
        }
        if (lastPacketTimestamp.millis > ACCUMULATION_TIMESTAMP_NOT_SET_YET_SENTINEL) {
            var sourceBucket = expiringQueue.getHashSetForTimestamp(lastPacketTimestamp);
            if (sourceBucket != targetBucketHashSet) {
                if (sourceBucket == null) {
                    behavioralPolicy.onNewDataArrivingAfterItsAccumulationHasBeenExpired(partitionId, connectionId,
                            timestampMillis.toInstant(), expiringQueue.getLatestPossibleKeyValue());
                    return false;
                }
                // this will do nothing if it was already removed, such as in the previous recursive run
                sourceBucket.remove(connectionId);
            }
        }
        targetBucketHashSet.put(connectionId, Boolean.TRUE);
        return true;
    }

    Accumulation get(String partitionId, String connectionId, Instant timestamp) {
        var accumulation = connectionAccumulationMap.get(new ScopedConnectionIdKey(partitionId, connectionId));
        if (accumulation == null) {
            return null;
        }
        if (!updateExpirationTrackers(partitionId, connectionId, new EpochMillis(timestamp), accumulation, 0)) {
            return null;
        }
        return accumulation;
    }

    Accumulation getOrCreate(String partitionId, String connectionId, Instant timestamp) {
        var key = new ScopedConnectionIdKey(partitionId, connectionId);
        var accumulation = connectionAccumulationMap.computeIfAbsent(key, k->new Accumulation());
        if (!updateExpirationTrackers(partitionId, connectionId, new EpochMillis(timestamp), accumulation, 0)) {
            connectionAccumulationMap.remove(key);
            return null;
        }
        return accumulation;
    }

    Accumulation remove(String partitionId, String id) {
        var accum = connectionAccumulationMap.remove(new ScopedConnectionIdKey(partitionId, id));
        if (accum != null) {
            accum.newestPacketTimestampInMillis.set(ACCUMULATION_DEAD_SENTINEL);
        }
        return accum;
        // Once the accumulations are gone from the connectionAccumulationMap map, upon the normal expiration
        // sweep, we'll dispose all the keys in the expired bucket anyway, so there's no reason to be too
        // proactive to strike it now.  Doing it now would require us to find out which bucket it's in.
        // TODO: Verify with profiling that this isn't going to be an impediment (notice that 20K active
        // connections would take up 1MB of key characters + map overhead)
    }

    Stream<Accumulation> values() {
        return connectionAccumulationMap.values().stream();
    }

    void clear() {
        nodeToExpiringBucketMap.clear();
        // leave everything else fall aside, like we do for remove()
    }
}
