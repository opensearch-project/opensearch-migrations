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
 * Tests for {@link ExpiringTrafficStreamMap#expireByWallClock()} — the watchdog that
 * breaks the deadlock where blocked polls prevent event-driven expiry from firing.
 *
 * The deadlock scenario: connections are registered in the map, the queue head advances,
 * and then NO further events arrive. The event-driven expiry (triggered inside
 * expireOldEntries) never runs again, so stale connections are never swept.
 * expireByWallClock uses the queue's lastKey as reference and sweeps without needing
 * a new event.
 */
class ExpiringTrafficStreamMapWallClockExpiryTest extends InstrumentationTest {

    private static final String NODE_ID = "test_node";
    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    private static final Duration GRANULARITY = Duration.ofSeconds(1);

    private PojoTrafficStreamKeyAndContext makeTsk(String connId) {
        return PojoTrafficStreamKeyAndContext.build(NODE_ID, connId, 0, rootContext::createTrafficStreamContextForTest);
    }

    @Test
    void doesNothingOnEmptyMap() {
        var map = new ExpiringTrafficStreamMap(TIMEOUT, GRANULARITY, new BehavioralPolicy() {
            @Override
            public void onExpireAccumulation(String partitionId, Accumulation accumulation) {
                Assertions.fail("Should not expire anything on empty map");
            }
        });

        Assertions.assertEquals(0, map.expireByWallClock());
    }

    @Test
    void doesNotExpireFreshConnections() {
        var expired = new ArrayList<String>();
        var map = new ExpiringTrafficStreamMap(TIMEOUT, GRANULARITY, new BehavioralPolicy() {
            @Override
            public void onExpireAccumulation(String partitionId, Accumulation accumulation) {
                expired.add(accumulation.trafficChannelKey.getConnectionId());
            }
        });

        // Place connections at T=100 and T=105
        var tsk1 = makeTsk("conn1");
        var accum1 = map.getOrCreateWithoutExpiration(tsk1, k -> new Accumulation(tsk1, 0));
        map.expireOldEntries(tsk1, accum1, Instant.ofEpochSecond(100));

        var tsk2 = makeTsk("conn2");
        var accum2 = map.getOrCreateWithoutExpiration(tsk2, k -> new Accumulation(tsk2, 0));
        map.expireOldEntries(tsk2, accum2, Instant.ofEpochSecond(105));

        // Queue head at T=105, threshold = 105-10=95
        // Both connections (T=100, T=105) are >= 95, so neither should be expired
        expired.clear();
        Assertions.assertEquals(0, map.expireByWallClock());
        Assertions.assertTrue(expired.isEmpty());
    }

    @Test
    void expiresStaleConnectionWhenNoNewEventsArrive() {
        // This is THE deadlock scenario test.
        // Setup: multiple connections registered, queue head advances,
        // then NO MORE EVENTS ARRIVE. expireByWallClock must sweep the stale ones.
        var expired = new ArrayList<String>();
        var map = new ExpiringTrafficStreamMap(TIMEOUT, GRANULARITY, new BehavioralPolicy() {
            @Override
            public void onExpireAccumulation(String partitionId, Accumulation accumulation) {
                expired.add(accumulation.trafficChannelKey.getConnectionId());
            }
        });

        // Place staleConn at T=100
        var tskStale = makeTsk("staleConn");
        var accumStale = map.getOrCreateWithoutExpiration(tskStale, k -> new Accumulation(tskStale, 0));
        map.expireOldEntries(tskStale, accumStale, Instant.ofEpochSecond(100));

        // Place freshConn at T=108 — within timeout window of staleConn (108-10=98 < 100)
        var tskFresh = makeTsk("freshConn");
        var accumFresh = map.getOrCreateWithoutExpiration(tskFresh, k -> new Accumulation(tskFresh, 0));
        map.expireOldEntries(tskFresh, accumFresh, Instant.ofEpochSecond(108));

        // At this point: event-driven sweep ran with T=108, threshold=98. staleConn at 100 > 98: survives.
        Assertions.assertTrue(expired.isEmpty(),
            "staleConn should NOT be expired by event-driven sweep — within timeout");

        // Queue head is now at T=108.
        // expireByWallClock: lastKey=108, threshold=108-10=98. staleConn at 100 > 98: survives.
        Assertions.assertEquals(0, map.expireByWallClock());

        // NOW: a new event arrives at T=115 which advances the queue head.
        // In the real deadlock, this event would never arrive. But we need to advance the
        // queue head SOMEHOW for expireByWallClock to detect staleness.
        // In production, the queue head was advanced by PRIOR events before the deadlock began.
        var tskAdvancer = makeTsk("advancerConn");
        var accumAdvancer = map.getOrCreateWithoutExpiration(tskAdvancer, k -> new Accumulation(tskAdvancer, 0));
        map.expireOldEntries(tskAdvancer, accumAdvancer, Instant.ofEpochSecond(115));

        // Event-driven sweep at T=115: threshold=115-10=105.
        // staleConn (100) < 105 → expired by event-driven.
        // freshConn (108) >= 105 → survives.
        Assertions.assertTrue(expired.contains("staleConn"),
            "staleConn should be expired by event-driven sweep at T=115");
        Assertions.assertFalse(expired.contains("freshConn"),
            "freshConn should NOT be expired");
        expired.clear();

        // DEADLOCK BEGINS HERE: no more events arrive after T=115.
        // Queue head stays at T=115. freshConn (T=108) is still in the map.
        // threshold = 115-10=105. freshConn at 108 >= 105: survives expireByWallClock.
        Assertions.assertEquals(0, map.expireByWallClock(),
            "freshConn still within timeout — should not be expired");

        // Simulate time passing: we manually adjust freshConn's timestamp to make it appear stale.
        // In production, the queue head would have been advanced by prior events and freshConn
        // would naturally be old. We can't advance the queue without calling expireOldEntries
        // (which would trigger event-driven expiry), so instead we create a scenario where
        // the connection's newestPacketTimestamp is old relative to the queue's lastKey.
        //
        // Place another connection at T=120 to advance the queue head further.
        var tskFinal = makeTsk("finalConn");
        var accumFinal = map.getOrCreateWithoutExpiration(tskFinal, k -> new Accumulation(tskFinal, 0));
        map.expireOldEntries(tskFinal, accumFinal, Instant.ofEpochSecond(120));

        // Event-driven sweep at T=120: threshold=120-10=110.
        // freshConn (108) < 110 → expired by event-driven!
        // advancerConn (115) >= 110 → survives.
        Assertions.assertTrue(expired.contains("freshConn"),
            "freshConn should be expired by event-driven sweep at T=120");
        expired.clear();

        // After all event-driven expiry: only advancerConn (T=115) and finalConn (T=120) remain.
        // expireByWallClock with lastKey=120, threshold=110:
        // advancerConn (115) >= 110 → survives
        // finalConn (120) >= 110 → survives
        Assertions.assertEquals(0, map.expireByWallClock());
    }

    @Test
    void expiresConnectionThatEventDrivenMissed() {
        // The critical test: a connection is added to the map via getOrCreateWithoutExpiration
        // (which does NOT trigger expiry), and its timestamp is set to be old relative to
        // the queue head. This simulates a stale connection from a prior run that holds permits.
        // Only expireByWallClock can clean it up.
        var expired = new ArrayList<String>();
        var map = new ExpiringTrafficStreamMap(TIMEOUT, GRANULARITY, new BehavioralPolicy() {
            @Override
            public void onExpireAccumulation(String partitionId, Accumulation accumulation) {
                expired.add(accumulation.trafficChannelKey.getConnectionId());
            }
        });

        // First, establish the queue head at T=200 via a normal event
        var tskHead = makeTsk("headConn");
        var accumHead = map.getOrCreateWithoutExpiration(tskHead, k -> new Accumulation(tskHead, 0));
        map.expireOldEntries(tskHead, accumHead, Instant.ofEpochSecond(200));

        // Now manually insert a "stale" connection using getOrCreateWithoutExpiration
        // and set its timestamp via the AtomicLong directly — simulating a connection
        // that was registered long ago and whose timestamp is far below the queue head.
        var tskStale = makeTsk("staleFromPriorRun");
        var accumStale = map.getOrCreateWithoutExpiration(tskStale, k -> new Accumulation(tskStale, 0));
        // Manually set its lastPacketTimestamp to T=180 (which is below threshold 200-10=190)
        accumStale.getNewestPacketTimestampInMillisReference().set(
            Instant.ofEpochSecond(180).toEpochMilli()
        );

        // Event-driven expiry will NOT fire for staleFromPriorRun because no new event
        // references it (it was just inserted via getOrCreateWithoutExpiration).
        // The only way it gets expired is via expireByWallClock.
        Assertions.assertTrue(expired.isEmpty(), "Nothing should be expired yet");

        // Call expireByWallClock: lastKey=200, threshold=200-10=190.
        // staleFromPriorRun at T=180 < 190 → EXPIRED!
        // headConn at T=200 >= 190 → survives.
        int wallClockExpired = map.expireByWallClock();

        Assertions.assertEquals(1, wallClockExpired);
        Assertions.assertTrue(expired.contains("staleFromPriorRun"),
            "staleFromPriorRun should be expired by wall-clock watchdog");
        Assertions.assertFalse(expired.contains("headConn"),
            "headConn should NOT be expired — it's fresh");
    }

    @Test
    void doesNotExpireConnectionsWithUnsetTimestamp() {
        var expired = new ArrayList<String>();
        var map = new ExpiringTrafficStreamMap(TIMEOUT, GRANULARITY, new BehavioralPolicy() {
            @Override
            public void onExpireAccumulation(String partitionId, Accumulation accumulation) {
                expired.add(accumulation.trafficChannelKey.getConnectionId());
            }
        });

        // Establish queue head
        var tskHead = makeTsk("headConn");
        var accumHead = map.getOrCreateWithoutExpiration(tskHead, k -> new Accumulation(tskHead, 0));
        map.expireOldEntries(tskHead, accumHead, Instant.ofEpochSecond(200));

        // Insert a connection without setting its timestamp (newestPacketTs stays at 0)
        var tskNew = makeTsk("newConn");
        map.getOrCreateWithoutExpiration(tskNew, k -> new Accumulation(tskNew, 0));

        // expireByWallClock should skip connections with timestamp <= 0
        Assertions.assertEquals(0, map.expireByWallClock());
        Assertions.assertTrue(expired.isEmpty());
    }
}
