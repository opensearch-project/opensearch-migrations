package org.opensearch.migrations.replay;

import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.time.Instant;
import java.util.AbstractMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.stream.Stream;

/**
 * This object manages the lifecycle of Accumulation objects, creating new ones and (eventually, once implemented)
 * expiring old entries.  Callers are expected to modify the Accumulation values themselves, but to proxy every
 * distinct interaction through this class along with the timestamp of the observed interaction.  That (will)
 * allow this class to expunge outdated entries. Notice that a callback (or calling) mechanism still needs to
 * be implemented so that the calling context is aware that items have been expired.
 */
@Slf4j
public class ExpiringTrafficStreamMap {

    public static final int DEFAULT_NUM_TIMESTAMP_UPDATE_ATTEMPTS = 2;

    @EqualsAndHashCode
    public class EpochMillis {
        final long millis;
        public EpochMillis(Instant i) {
            millis = i.toEpochMilli();
        }
        public EpochMillis(long ms) {
            this.millis = ms;
        }

        public boolean isAfter(EpochMillis lastPacketTimestamp) {
            return this.millis > lastPacketTimestamp.millis;
        }

        public Instant toInstant() {
            return Instant.ofEpochMilli(millis);
        }
        @Override
        public String toString() { return Long.toString(millis); }
    }

    public static class ErrorPolicy {
        public void onDataArrivingBeforeTheStartOfTheCurrentProcessingWindow(
                String partitionId, String connectionId, Instant timestamp, Instant beginningOfWindow) {
            log.error("Could not update the expiration of an object whose timestamp is before the " +
                    "oldest point in time that packets are still being processed for this partition.  " +
                    "This means that there was larger than expected temporal jitter in packets.  " +
                    "That means that traffic for this connection may have already been reported as expired " +
                    "and processed as such and that this data will not be properly handled due to other data " +
                    "within the connection being prematurely expired.  Trying to send the data through a new " +
                    "instance of this object with a minimumGuaranteedLifetime of " +
                    Duration.between(timestamp, beginningOfWindow) + " will allow for this packet to be properly " +
                    "accumulated for (" + partitionId + "," + connectionId + ")");
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
    }

    @AllArgsConstructor
    @EqualsAndHashCode
    private class ScopedConnectionIdKey {
        public final String nodeId;
        public final String connectionId;
    }

    private static class AccumulatorMap extends ConcurrentHashMap<ScopedConnectionIdKey, Accumulation> {}

    private class ExpiringKeyQueue extends
            ConcurrentLinkedDeque<AbstractMap.Entry<EpochMillis,ConcurrentHashMap<String,Boolean>>> {
        ExpiringKeyQueue(EpochMillis startingTimestamp) {
            addNewSet(startingTimestamp);
        }

        private ConcurrentHashMap<String,Boolean> addNewSet(EpochMillis timestampMillis) {
            var accumulatorMap = new ConcurrentHashMap<String,Boolean>();
            this.add(new AbstractMap.SimpleEntry<>(timestampMillis, accumulatorMap));
            return accumulatorMap;
        }

        private ConcurrentHashMap<String, Boolean> getHashSetForTimestamp(EpochMillis timestamp) {
            for (var it = this.descendingIterator(); it.hasNext(); ) {
                var set = it.next();
                var boundary = set.getKey();
                if (timestamp.isAfter(boundary) || boundary.equals(timestamp)) {
                    return set.getValue();
                }
            }
            return null;
        }

        private ConcurrentHashMap<String, Boolean> getMostRecentBucket() {
            return this.getLast().getValue();
        }
    }

    final AccumulatorMap connectionAccumulationMap;
    final ConcurrentHashMap<String, ExpiringKeyQueue> nodeToExpiringBucketMap;
    final Duration minimumGuaranteedLifetime;
    final ErrorPolicy errorPolicy;

    public ExpiringTrafficStreamMap(Duration minimumGuaranteedLifetime) {
        this(minimumGuaranteedLifetime, new ErrorPolicy());
    }

    public ExpiringTrafficStreamMap(Duration minimumGuaranteedLifetime, ErrorPolicy errorPolicy) {
        connectionAccumulationMap = new AccumulatorMap();
        this.minimumGuaranteedLifetime = minimumGuaranteedLifetime;
        this.nodeToExpiringBucketMap = new ConcurrentHashMap<>();
        this.errorPolicy = errorPolicy;
    }

    private ExpiringKeyQueue getOrCreateNodeMap(String partitionId, EpochMillis timestamp) {
        return nodeToExpiringBucketMap.computeIfAbsent(partitionId, s -> new ExpiringKeyQueue(timestamp));
    }

    private boolean updateExpirationTrackers(String partitionId, String connectionId, EpochMillis timestampMillis,
                                             Accumulation accumulation, int attempts) {
        var expiringQueue = getOrCreateNodeMap(partitionId, timestampMillis);

        var lastPacketTimestamp = new EpochMillis(accumulation.newestPacketTimestampInMillis.get());
        EpochMillis mostRecentTimestamp;
        if (timestampMillis.isAfter(lastPacketTimestamp)) {
            mostRecentTimestamp = timestampMillis;
            var witnessValue = accumulation.newestPacketTimestampInMillis.compareAndExchange(lastPacketTimestamp.millis,
                    timestampMillis.millis);
            if (lastPacketTimestamp.millis != witnessValue) {
                ++attempts;
                if (errorPolicy.shouldRetryAfterAccumulationTimestampRaceDetected(partitionId, connectionId,
                        timestampMillis.toInstant(), accumulation, attempts)) {
                    updateExpirationTrackers(partitionId, connectionId, timestampMillis,
                            accumulation, attempts);
                } else {
                    return false;
                }
            }
        } else {
            mostRecentTimestamp = lastPacketTimestamp;
        }

        // put all packets received into the latest bucket?  Seems like a better idea -
        // why rush to expire data in a different order, expiring this before data that was previously received
        //var targetBucketHashSet = expiringQueue.getHashSetForTimestamp(mostRecentTimestamp);
        var targetBucketHashSet = expiringQueue.getMostRecentBucket();
        if (targetBucketHashSet == null) {
            var startOfWindow = expiringQueue.getFirst().getKey();
            assert !mostRecentTimestamp.isAfter(startOfWindow):
                    "Only expected to not find the target bucket when the incoming timestamp was before the " +
                            "expiring queue's time window";
            errorPolicy.onDataArrivingBeforeTheStartOfTheCurrentProcessingWindow(partitionId, connectionId,
                    mostRecentTimestamp.toInstant(), startOfWindow.toInstant());
            return false;
        }
        var sourceBucket = expiringQueue.getHashSetForTimestamp(lastPacketTimestamp);
        if (sourceBucket != targetBucketHashSet && sourceBucket != null) {
            // this will do nothing if it was already removed, such as in the previous recursive run
            sourceBucket.remove(connectionId);
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
        var accumulation = connectionAccumulationMap.computeIfAbsent(
                new ScopedConnectionIdKey(partitionId, connectionId),
                k->new Accumulation(timestamp));
        if (!updateExpirationTrackers(partitionId, connectionId, new EpochMillis(timestamp), accumulation, 0)) {
            return null;
        }
        return accumulation;
    }

    Accumulation remove(String partitionId, String id) {
        return connectionAccumulationMap.remove(new ScopedConnectionIdKey(partitionId, id));
        // Once the accumulations are gone from the connectionAccumulationMap map, upon the normal expiration
        // sweep, we'll dispose all the keys in the expired bucket anyway, so there's no reason to be too
        // proactive to strike it now.  Doing it now would require us to find out which bucket it's in.
        // TODO: Verify with profiling that this isn't going to be an impediment (notice that 20K active
        // connections would take up 1MB of key characters + map overhead)
    }

    Accumulation reset(String partitionId, String connectionId, Instant timestamp) {
        var accumulation = new Accumulation(timestamp);
        try {
            return connectionAccumulationMap.put(new ScopedConnectionIdKey(partitionId, connectionId),
                    accumulation);
        } finally {
            updateExpirationTrackers(partitionId, connectionId, new EpochMillis(timestamp), accumulation, 0);
        }
    }

    Stream<Accumulation> values() {
        return connectionAccumulationMap.values().stream();
    }

    void clear() {
        nodeToExpiringBucketMap.clear();
        // leave everything else fall aside, like we do for remove()
    }
}
