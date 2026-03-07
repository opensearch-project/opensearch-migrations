package org.opensearch.migrations.replay;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;

import org.opensearch.migrations.replay.datatypes.PojoTrafficStreamKeyAndContext;
import org.opensearch.migrations.replay.traffic.expiration.BehavioralPolicy;
import org.opensearch.migrations.replay.traffic.expiration.ExpiringTrafficStreamMap;
import org.opensearch.migrations.tracing.InstrumentationTest;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Tests for expiry bugs where connections become orphaned in the ExpiringTrafficStreamMap
 * and are never expired despite being older than the timeout.
 */
class ExpiringTrafficStreamMapOrphanTest extends InstrumentationTest {

    private static final String NODE_ID = "test_node";

    private PojoTrafficStreamKeyAndContext makeTsk(String connId) {
        return PojoTrafficStreamKeyAndContext.build(NODE_ID, connId, 0, rootContext::createTrafficStreamContextForTest);
    }

    @Test
    void testConnectionNotOrphanedByUnevenStartOfWindow() {
        var expired = new ArrayList<String>();
        var map = new ExpiringTrafficStreamMap(Duration.ofSeconds(5), Duration.ofSeconds(1),
            new BehavioralPolicy() {
                @Override
                public void onExpireAccumulation(String partitionId, Accumulation accumulation) {
                    expired.add(accumulation.trafficChannelKey.getConnectionId());
                }
            });

        // Place connStale at T=10.0
        var tskStale = makeTsk("connStale");
        var accumStale = map.getOrCreateWithoutExpiration(tskStale, k -> new Accumulation(tskStale, 0));
        map.expireOldEntries(tskStale, accumStale, Instant.ofEpochMilli(10_000));

        // Place connVictim at T=10.700 — same bucket [10] but 700ms into it
        var tskVictim = makeTsk("connVictim");
        var accumVictim = map.getOrCreateWithoutExpiration(tskVictim, k -> new Accumulation(tskVictim, 0));
        map.expireOldEntries(tskVictim, accumVictim, Instant.ofEpochMilli(10_700));

        // Trigger sweep at T=15.600 → unquantized startOfWindow=10.600
        // With the bug: bucket [10] swept, connVictim (10.700 >= 10.600) orphaned
        // With the fix: quantized startOfWindow=10.000, bucket [10] NOT swept yet
        var tskTrigger = makeTsk("connTrigger");
        var accumTrigger = map.getOrCreateWithoutExpiration(tskTrigger, k -> new Accumulation(tskTrigger, 0));
        map.expireOldEntries(tskTrigger, accumTrigger, Instant.ofEpochMilli(15_600));

        // connVictim should NOT be expired yet (it's only 4.9s old relative to trigger)
        Assertions.assertFalse(expired.contains("connVictim"),
            "connVictim should not have been expired — it's within the timeout window");

        // Now trigger at T=16.100 → quantized startOfWindow=11.000 → bucket [10] swept
        var tskTrigger2 = makeTsk("connTrigger2");
        var accumTrigger2 = map.getOrCreateWithoutExpiration(tskTrigger2, k -> new Accumulation(tskTrigger2, 0));
        map.expireOldEntries(tskTrigger2, accumTrigger2, Instant.ofEpochMilli(16_100));

        // NOW both should be expired
        Assertions.assertTrue(expired.contains("connStale"), "connStale should be expired");
        Assertions.assertTrue(expired.contains("connVictim"), "connVictim should be expired");
    }

    /**
     * Mid-operation sweep orphans connection into older bucket.
     *
     * updateExpirationTrackers does CAS-update of newestPacketTs BEFORE finding the
     * target bucket. Finding the target bucket can trigger a sweep. The sweep sees the
     * freshly-updated (high) newestPacketTs, so the connection survives, but its OLD
     * bucket is deleted. The connection is now stranded in a deleted bucket.
     *
     * Setup: connA is active at T=10, then goes idle. Other connections advance time.
     * When connA gets a new observation much later, the monotonic adjustment pushes its
     * timestamp forward, but the sweep triggered mid-operation deletes its old bucket
     * while it still has a reference there.
     *
     * The fix: don't delete buckets that still have live (unexpired) entries.
     */
    @Test
    void testConnectionNotOrphanedByMidOperationSweep() {
        var expired = new ArrayList<String>();
        var map = new ExpiringTrafficStreamMap(Duration.ofSeconds(5), Duration.ofSeconds(1),
            new BehavioralPolicy() {
                @Override
                public void onExpireAccumulation(String partitionId, Accumulation accumulation) {
                    expired.add(accumulation.trafficChannelKey.getConnectionId());
                }
            });

        // connIdle placed at T=10.0
        var tskIdle = makeTsk("connIdle");
        var accumIdle = map.getOrCreateWithoutExpiration(tskIdle, k -> new Accumulation(tskIdle, 0));
        map.expireOldEntries(tskIdle, accumIdle, Instant.ofEpochMilli(10_000));

        // Other connections advance time to T=14.0 (connIdle still in bucket [10])
        for (int t = 11; t <= 14; t++) {
            var tsk = makeTsk("connOther_" + t);
            var accum = map.getOrCreateWithoutExpiration(tsk, k -> new Accumulation(tsk, 0));
            map.expireOldEntries(tsk, accum, Instant.ofEpochSecond(t));
        }

        // connIdle gets a new observation at T=10.5 (its actual capture time),
        // but monotonic adjustment pushes it to max(10.5, lastKey=14.0) = 14.0
        // The CAS updates newestPacketTs from 10.0 to 14.0
        // getHashSetForTimestamp may trigger a sweep with startOfWindow ~= 9.0
        // The sweep sees connIdle's newestPacketTs=14.0 → survives
        // But bucket [10] is deleted → connIdle stranded
        map.expireOldEntries(tskIdle, accumIdle, Instant.ofEpochMilli(10_500));

        // connIdle should NOT be expired — it was just active
        Assertions.assertFalse(expired.contains("connIdle"),
            "connIdle should not have been expired");

        // Verify it CAN still be expired later — it's not orphaned
        // Advance time well past the timeout
        for (int t = 20; t <= 25; t++) {
            var tsk = makeTsk("connLate_" + t);
            var accum = map.getOrCreateWithoutExpiration(tsk, k -> new Accumulation(tsk, 0));
            map.expireOldEntries(tsk, accum, Instant.ofEpochSecond(t));
        }

        Assertions.assertTrue(expired.contains("connIdle"),
            "connIdle should eventually be expired — must not be orphaned");
    }
}
