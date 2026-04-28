package org.opensearch.migrations.replay.traffic.expiration;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.stream.Stream;

import org.opensearch.migrations.replay.Accumulation;
import org.opensearch.migrations.replay.datatypes.ITrafficStreamKey;

import lombok.extern.slf4j.Slf4j;

/**
 * This object manages the lifecycle of Accumulation objects, creating new ones and expiring old entries.
 * Callers are expected to modify the Accumulation values themselves, but to proxy every distinct interaction
 * through this class along with the timestamp of the observed interaction.  That allows this class to expunge
 * outdated entries. Notice that a callback (or calling) mechanism is provided so that the calling context is
 * aware of when items have been expired.
 *
 * This doesn't use more typical out-of-the-box LRU mechanisms.  Our requirements are a little bit different.
 * First, we're fine buffering a variable number of items and secondly, this should be threadsafe and able to
 * be used in highly concurrent contexts.
 *
 * NOTE: This uses a single global ExpiringKeyQueue rather than per-nodeId queues. The original per-nodeId
 * design was intended to handle clock skew between capture proxies, but it created a critical blind spot:
 * when a capture node went quiet, its stale connections would never be expired because the sweep only ran
 * on the active node's queue. Since all timestamps share a single monotonic timeline after adjustment,
 * a single queue is correct. If clock skew between capture proxies becomes a problem in the future,
 * the right fix is to detect and evict proxies with excessive skew from the capture fleet rather than
 * trying to maintain independent expiry timelines.
 *
 * Potential TODO - there may be a race condition in the ExpiringTrafficStream maps/sets where items could be expunged
 * from the collections while they're still in use since this doesn't have visibility into how items are used.
 * Requiring collection items to have atomically updated refCounts would mitigate that situation.
 */
@Slf4j
public class ExpiringTrafficStreamMap {

    public static final int DEFAULT_NUM_TIMESTAMP_UPDATE_ATTEMPTS = 2;
    public static final int ACCUMULATION_TIMESTAMP_NOT_SET_YET_SENTINEL = 0;

    protected final AccumulatorMap connectionAccumulationMap;
    protected final ExpiringKeyQueue expiringBucketQueue;
    protected final Duration minimumGuaranteedLifetime;
    protected final Duration granularity;
    protected final BehavioralPolicy behavioralPolicy;
    private final AtomicInteger newConnectionCounter;

    public ExpiringTrafficStreamMap(
        Duration minimumGuaranteedLifetime,
        Duration granularity,
        BehavioralPolicy behavioralPolicy
    ) {
        connectionAccumulationMap = new AccumulatorMap();
        this.granularity = granularity;
        this.minimumGuaranteedLifetime = minimumGuaranteedLifetime;
        this.behavioralPolicy = behavioralPolicy;
        this.newConnectionCounter = new AtomicInteger(0);
        this.expiringBucketQueue = new ExpiringKeyQueue(
            granularity,
            "global",
            new EpochMillis(ACCUMULATION_TIMESTAMP_NOT_SET_YET_SENTINEL));
    }

    public int numberOfConnectionsCreated() {
        return newConnectionCounter.get();
    }

    /**
     * @return false if the expiration couldn't be updated because the item was already expired.
     */
    private boolean updateExpirationTrackers(
        ITrafficStreamKey trafficStreamKey,
        EpochMillis observedTimestampMillis,
        Accumulation accumulation,
        int attempts
    ) {
        var latestPossibleKeyValueAtIncoming = expiringBucketQueue.getLatestPossibleKeyValue();
        // for expiration tracking purposes, push incoming packets' timestamps to be monotonic?
        var timestampMillis =
            new EpochMillis(Math.max(observedTimestampMillis.millis, expiringBucketQueue.lastKey().millis));

        if (accumulation.hasBeenExpired()) {
            behavioralPolicy.onNewDataArrivingAfterItsAccumulationHadBeenRemoved(trafficStreamKey);
            return false;
        }
        var lastPacketTimestamp = new EpochMillis(accumulation.getNewestPacketTimestampInMillisReference().get());
        if (timestampMillis.test(lastPacketTimestamp, (newTs, lastTs) -> newTs > lastTs)) {
            var witnessValue = accumulation.getNewestPacketTimestampInMillisReference()
                .compareAndExchange(lastPacketTimestamp.millis, timestampMillis.millis);
            if (lastPacketTimestamp.millis != witnessValue) {
                ++attempts;
                if (behavioralPolicy.shouldRetryAfterAccumulationTimestampRaceDetected(
                    trafficStreamKey,
                    timestampMillis.toInstant(),
                    accumulation,
                    attempts
                )) {
                    return updateExpirationTrackers(trafficStreamKey, timestampMillis, accumulation, attempts);
                } else {
                    return false;
                }
            }
        }

        var targetBucketHashSet = getHashSet(expiringBucketQueue, timestampMillis);

        if (targetBucketHashSet == null) {
            var startOfWindow = expiringBucketQueue.firstKey().toInstant();
            assert !timestampMillis.test(startOfWindow, (ts, windowStart) -> ts < windowStart)
                : "Only expected the target bucket to be missing when the incoming timestamp was before the "
                    + "expiring queue's time window";
            behavioralPolicy.onDataArrivingBeforeTheStartOfTheCurrentProcessingWindow(
                trafficStreamKey,
                timestampMillis.toInstant(),
                latestPossibleKeyValueAtIncoming
            );
            return false;
        }
        if (lastPacketTimestamp.millis > ACCUMULATION_TIMESTAMP_NOT_SET_YET_SENTINEL) {
            var sourceBucket = getHashSet(expiringBucketQueue, lastPacketTimestamp);
            if (sourceBucket != targetBucketHashSet) {
                if (sourceBucket == null) {
                    behavioralPolicy.onNewDataArrivingAfterItsAccumulationHasBeenExpired(
                        trafficStreamKey,
                        timestampMillis.toInstant(),
                        lastPacketTimestamp.millis,
                        latestPossibleKeyValueAtIncoming,
                        minimumGuaranteedLifetime
                    );
                    return false;
                }
                // this will do nothing if it was already removed, such as in the previous recursive run
                sourceBucket.remove(makeKey(trafficStreamKey));
            }
        }
        targetBucketHashSet.put(makeKey(trafficStreamKey), Boolean.TRUE);

        // Now that the connection is fully settled in its new bucket with updated timestamp,
        // run the deferred expiry sweep. Any sweep triggered here will see a consistent state.
        runDeferredExpiry(expiringBucketQueue, timestampMillis);

        return true;
    }

    private ConcurrentHashMap<ScopedConnectionIdKey, Boolean> getHashSet(
        ExpiringKeyQueue expiringQueue,
        EpochMillis timestampMillis
    ) {
        return expiringQueue.getHashSetForTimestamp(timestampMillis);
    }

    private void runDeferredExpiry(ExpiringKeyQueue expiringQueue, EpochMillis timestampMillis) {
        expiringQueue.expireOldSlots(
            connectionAccumulationMap,
            behavioralPolicy,
            minimumGuaranteedLifetime,
            timestampMillis
        );
    }

    public Accumulation getIfPresent(ITrafficStreamKey trafficStreamKey) {
        return connectionAccumulationMap.get(makeKey(trafficStreamKey));
    }

    public Accumulation getOrCreateWithoutExpiration(
        ITrafficStreamKey trafficStreamKey,
        Function<ITrafficStreamKey, Accumulation> accumulationGenerator
    ) {
        return connectionAccumulationMap.computeIfAbsent(makeKey(trafficStreamKey), k -> {
            newConnectionCounter.incrementAndGet();
            return accumulationGenerator.apply(trafficStreamKey);
        });
    }

    private ScopedConnectionIdKey makeKey(ITrafficStreamKey trafficStreamKey) {
        return new ScopedConnectionIdKey(trafficStreamKey.getNodeId(), trafficStreamKey.getConnectionId());
    }

    public void expireOldEntries(ITrafficStreamKey trafficStreamKey, Accumulation accumulation, Instant timestamp) {
        if (!updateExpirationTrackers(trafficStreamKey, new EpochMillis(timestamp), accumulation, 0)) {
            connectionAccumulationMap.remove(makeKey(trafficStreamKey));
        }
    }

    public Accumulation remove(String partitionId, String id) {
        var accum = connectionAccumulationMap.remove(new ScopedConnectionIdKey(partitionId, id));
        if (accum != null) {
            accum.expire();
        }
        return accum;
        // Once the accumulations are gone from the connectionAccumulationMap map, upon the normal expiration
        // sweep, we'll dispose all the keys in the expired bucket anyway, so there's no reason to be too
        // proactive to strike it now. Doing it now would require us to find out which bucket it's in.
        // TODO: Verify with profiling that this isn't going to be an impediment (notice that 20K active
        // connections would take up 1MB of key characters + map overhead)
    }

    public Stream<Accumulation> values() {
        return connectionAccumulationMap.values().stream();
    }

    public void clear() {
        expiringBucketQueue.clear();
        // leave everything else fall aside, like we do for remove()
    }
}
