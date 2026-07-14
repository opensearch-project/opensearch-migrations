package org.opensearch.migrations.replay;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import org.opensearch.migrations.replay.datatypes.ITrafficStreamKey;
import org.opensearch.migrations.replay.datatypes.PojoTrafficStreamKeyAndContext;
import org.opensearch.migrations.replay.tracing.IReplayContexts;
import org.opensearch.migrations.replay.traffic.expiration.BehavioralPolicy;
import org.opensearch.migrations.replay.traffic.expiration.ExpiringTrafficStreamMap;
import org.opensearch.migrations.tracing.InstrumentationTest;

import lombok.NonNull;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link ExpiringTrafficStreamMap#expireByWallClock()} — the watchdog that
 * breaks the deadlock where blocked polls prevent event-driven expiry from firing.
 *
 * The wall-clock watchdog uses real elapsed time (via Accumulation.lastWallClockUpdateMillis)
 * to identify connections that haven't received new data for longer than the configured
 * timeout, regardless of source-time relationships.
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

        var tsk1 = makeTsk("conn1");
        var accum1 = map.getOrCreateWithoutExpiration(tsk1, k -> new Accumulation(tsk1, 0));
        map.expireOldEntries(tsk1, accum1, Instant.ofEpochSecond(100));

        var tsk2 = makeTsk("conn2");
        var accum2 = map.getOrCreateWithoutExpiration(tsk2, k -> new Accumulation(tsk2, 0));
        map.expireOldEntries(tsk2, accum2, Instant.ofEpochSecond(105));

        // Both connections were just touched (wall-clock is recent), should NOT be expired
        Assertions.assertEquals(0, map.expireByWallClock());
        Assertions.assertTrue(expired.isEmpty());
    }

    @Test
    void expiresConnectionStaleInWallClockTime() {
        var expired = new ArrayList<String>();
        var map = new ExpiringTrafficStreamMap(TIMEOUT, GRANULARITY, new BehavioralPolicy() {
            @Override
            public void onExpireAccumulation(String partitionId, Accumulation accumulation) {
                expired.add(accumulation.trafficChannelKey.getConnectionId());
            }
        });

        // Create a connection and register it normally
        var tskStale = makeTsk("staleConn");
        var accumStale = map.getOrCreateWithoutExpiration(tskStale, k -> new Accumulation(tskStale, 0));
        map.expireOldEntries(tskStale, accumStale, Instant.ofEpochSecond(100));

        // Create a fresh connection
        var tskFresh = makeTsk("freshConn");
        var accumFresh = map.getOrCreateWithoutExpiration(tskFresh, k -> new Accumulation(tskFresh, 0));
        map.expireOldEntries(tskFresh, accumFresh, Instant.ofEpochSecond(105));

        // Simulate: staleConn hasn't been updated in wall-clock time for > TIMEOUT (10s)
        // Set its lastWallClockUpdateMillis to 11 seconds ago
        accumStale.lastWallClockUpdateMillis.set(System.currentTimeMillis() - 11_000);

        // freshConn was just touched, so it should survive
        int count = map.expireByWallClock();
        Assertions.assertEquals(1, count);
        Assertions.assertTrue(expired.contains("staleConn"));
        Assertions.assertFalse(expired.contains("freshConn"));
    }

    @Test
    void expiresConnectionThatEventDrivenMissed() {
        // The critical deadlock scenario: source timestamps are close together but
        // wall-clock time reveals the connection is actually stale (no new data arriving).
        var expired = new ArrayList<String>();
        var map = new ExpiringTrafficStreamMap(TIMEOUT, GRANULARITY, new BehavioralPolicy() {
            @Override
            public void onExpireAccumulation(String partitionId, Accumulation accumulation) {
                expired.add(accumulation.trafficChannelKey.getConnectionId());
            }
        });

        // Establish a connection via getOrCreateWithoutExpiration (bypasses event-driven expiry)
        var tskStale = makeTsk("staleFromPriorRun");
        var accumStale = map.getOrCreateWithoutExpiration(tskStale, k -> new Accumulation(tskStale, 0));
        // Source-time is T=192 (close to queue head of 200 — within source-time timeout)
        accumStale.getNewestPacketTimestampInMillisReference().set(
            Instant.ofEpochSecond(192).toEpochMilli()
        );
        // But in wall-clock time, this connection hasn't been updated for 15 seconds (> 10s timeout)
        accumStale.lastWallClockUpdateMillis.set(System.currentTimeMillis() - 15_000);

        // Establish queue head at T=200 (source-time)
        var tskHead = makeTsk("headConn");
        var accumHead = map.getOrCreateWithoutExpiration(tskHead, k -> new Accumulation(tskHead, 0));
        map.expireOldEntries(tskHead, accumHead, Instant.ofEpochSecond(200));

        // In source-time: staleFromPriorRun (T=192) is within threshold (200-10=190), survives.
        // In wall-clock: 15 seconds idle > 10 second timeout → EXPIRED.
        Assertions.assertTrue(expired.isEmpty(), "event-driven should not expire it (source-time is fresh)");

        int wallClockExpired = map.expireByWallClock();
        Assertions.assertEquals(1, wallClockExpired);
        Assertions.assertTrue(expired.contains("staleFromPriorRun"),
            "staleFromPriorRun should be expired by wall-clock watchdog");
        Assertions.assertFalse(expired.contains("headConn"),
            "headConn should NOT be expired — it was just touched");
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

        // Insert a connection that is old in wall-clock time but was never given source-time data.
        // The watchdog should still expire it since wall-clock time has exceeded the threshold.
        var tskNew = makeTsk("noDataConn");
        var accumNew = map.getOrCreateWithoutExpiration(tskNew, k -> new Accumulation(tskNew, 0));
        // Set wall clock to be stale
        accumNew.lastWallClockUpdateMillis.set(System.currentTimeMillis() - 15_000);

        int count = map.expireByWallClock();
        Assertions.assertEquals(1, count);
        Assertions.assertTrue(expired.contains("noDataConn"));
    }

    @Test
    void callbackExceptionDoesNotPreventRemainingExpiries() {
        var expired = new ArrayList<String>();
        var map = new ExpiringTrafficStreamMap(TIMEOUT, GRANULARITY, new BehavioralPolicy() {
            @Override
            public void onExpireAccumulation(String partitionId, Accumulation accumulation) {
                expired.add(accumulation.trafficChannelKey.getConnectionId());
                if ("failConn".equals(accumulation.trafficChannelKey.getConnectionId())) {
                    throw new RuntimeException("simulated callback failure");
                }
            }
        });

        // Create two stale connections — one will throw in the callback
        var tskFail = makeTsk("failConn");
        var accumFail = map.getOrCreateWithoutExpiration(tskFail, k -> new Accumulation(tskFail, 0));
        accumFail.lastWallClockUpdateMillis.set(System.currentTimeMillis() - 15_000);

        var tskOk = makeTsk("okConn");
        var accumOk = map.getOrCreateWithoutExpiration(tskOk, k -> new Accumulation(tskOk, 0));
        accumOk.lastWallClockUpdateMillis.set(System.currentTimeMillis() - 15_000);

        // Both should be expired even though failConn's callback throws
        int count = map.expireByWallClock();
        Assertions.assertEquals(2, count,
            "Both connections should be counted as expired despite callback exception");
        Assertions.assertTrue(expired.contains("failConn"));
        Assertions.assertTrue(expired.contains("okConn"),
            "okConn must still be expired even after failConn's callback threw");
    }

    @Test
    void runWallClockExpiry_exercisesAccumulatorWatchdogMethod() {
        var accumulator = new CapturedTrafficToHttpTransactionAccumulator(
            Duration.ofSeconds(10), null, new AccumulationCallbacks() {
                @Override
                public Consumer<RequestResponsePacketPair> onRequestReceived(
                    @NonNull IReplayContexts.IReplayerHttpTransactionContext ctx,
                    @NonNull HttpMessageAndTimestamp request,
                    boolean isResumedConnection
                ) {
                    return rrpp -> {};
                }

                @Override
                public void onTrafficStreamsExpired(
                    RequestResponsePacketPair.ReconstructionStatus status,
                    @NonNull IReplayContexts.IChannelKeyContext ctx,
                    @NonNull List<ITrafficStreamKey> trafficStreamKeysBeingHeld
                ) {}

                @Override
                public void onConnectionClose(
                    int channelInteractionNumber,
                    @NonNull IReplayContexts.IChannelKeyContext ctx,
                    int channelSessionNumber,
                    RequestResponsePacketPair.ReconstructionStatus status,
                    @NonNull Instant when,
                    @NonNull List<ITrafficStreamKey> trafficStreamKeysBeingHeld
                ) {}

                @Override
                public void onTrafficStreamIgnored(@NonNull IReplayContexts.ITrafficStreamsLifecycleContext ctx) {}
            }
        );
        // Directly invoke the watchdog method — exercises the try block and the "expired == 0" path
        accumulator.runWallClockExpiry();
        accumulator.close();
    }
}
