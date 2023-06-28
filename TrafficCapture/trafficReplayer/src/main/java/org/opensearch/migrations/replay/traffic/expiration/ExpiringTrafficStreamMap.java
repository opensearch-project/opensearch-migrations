package org.opensearch.migrations.replay.traffic.expiration;

import lombok.extern.slf4j.Slf4j;
import org.opensearch.migrations.replay.Accumulation;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
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
 * 
 *  TODO - there will be a race condition in the ExpiringTrafficStream maps/sets where items
 *  could be expunged from the collections while they're still in use.  Adding refCounts to
 *  the collection items that can be checked atomically before purging would mitigate this
 *  situation
 */
@Slf4j
public class ExpiringTrafficStreamMap {

    public static final int DEFAULT_NUM_TIMESTAMP_UPDATE_ATTEMPTS = 2;
    public static final int ACCUMULATION_DEAD_SENTINEL = Integer.MAX_VALUE;
    public static final int ACCUMULATION_TIMESTAMP_NOT_SET_YET_SENTINEL = 0;

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
        // optimistic get - if it's already there, proceed with it.
        var ekq = nodeToExpiringBucketMap.get(partitionId);
        if (ekq != null) {
            return ekq;
        } else {
            var newMap = new ExpiringKeyQueue(this.granularity, partitionId, timestamp);
            var priorMap = nodeToExpiringBucketMap.putIfAbsent(partitionId, newMap);
            return priorMap == null ? newMap : priorMap;
        }
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

        var targetBucketHashSet = getHashSetForTimestampWhileExpiringOldBuckets(expiringQueue, timestampMillis);

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
            var sourceBucket = getHashSetForTimestampWhileExpiringOldBuckets(expiringQueue, lastPacketTimestamp);
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

    private ConcurrentHashMap<String, Boolean>
    getHashSetForTimestampWhileExpiringOldBuckets(ExpiringKeyQueue expiringQueue,
                                                  EpochMillis timestampMillis) {
        return expiringQueue.getHashSetForTimestamp(timestampMillis,
                () -> expiringQueue.expireOldSlots(connectionAccumulationMap,
                        behavioralPolicy, minimumGuaranteedLifetime, timestampMillis)
        );
    }

    public Accumulation get(String partitionId, String connectionId, Instant timestamp) {
        var accumulation = connectionAccumulationMap.get(new ScopedConnectionIdKey(partitionId, connectionId));
        if (accumulation == null) {
            return null;
        }
        if (!updateExpirationTrackers(partitionId, connectionId, new EpochMillis(timestamp), accumulation, 0)) {
            return null;
        }
        return accumulation;
    }

    public Accumulation getOrCreate(String partitionId, String connectionId, Instant timestamp) {
        var key = new ScopedConnectionIdKey(partitionId, connectionId);
        var accumulation = connectionAccumulationMap.computeIfAbsent(key, k->new Accumulation());
        if (!updateExpirationTrackers(partitionId, connectionId, new EpochMillis(timestamp), accumulation, 0)) {
            connectionAccumulationMap.remove(key);
            return null;
        }
        return accumulation;
    }

    public Accumulation remove(String partitionId, String id) {
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

    public Stream<Accumulation> values() {
        return connectionAccumulationMap.values().stream();
    }

    public void clear() {
        nodeToExpiringBucketMap.clear();
        // leave everything else fall aside, like we do for remove()
    }
}
