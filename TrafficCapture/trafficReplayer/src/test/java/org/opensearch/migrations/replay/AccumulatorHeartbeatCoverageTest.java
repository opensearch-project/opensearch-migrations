package org.opensearch.migrations.replay;

import java.lang.reflect.Field;
import java.time.Duration;

import org.opensearch.migrations.replay.datatypes.ITrafficStreamKey;
import org.opensearch.migrations.replay.traffic.expiration.ExpiringTrafficStreamMap;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AccumulatorHeartbeatCoverageTest {

    @Test
    void heartbeatAndExpireStaleConnections_emptyLiveStreams() {
        var callbacks = mock(AccumulationCallbacks.class);
        var accumulator = new CapturedTrafficToHttpTransactionAccumulator(
            Duration.ofMinutes(6), "--timeout 6m", callbacks);
        Assertions.assertDoesNotThrow(accumulator::heartbeatAndExpireStaleConnections);
    }

    @Test
    void heartbeatAndExpireStaleConnections_withActiveConnections() throws Exception {
        var callbacks = mock(AccumulationCallbacks.class);
        var accumulator = new CapturedTrafficToHttpTransactionAccumulator(
            Duration.ofMinutes(6), "--timeout 6m", callbacks);

        var liveStreams = getLiveStreams(accumulator);
        var tsk = mockTrafficStreamKey("node1", "conn-active", 0);

        liveStreams.getOrCreateWithoutExpiration(tsk, k -> {
            var accum = new Accumulation(k, 0);
            accum.getNewestPacketTimestampInMillisReference().set(System.currentTimeMillis() - 1000);
            return accum;
        });

        Assertions.assertDoesNotThrow(accumulator::heartbeatAndExpireStaleConnections);
    }

    @Test
    void heartbeatAndExpireStaleConnections_withWriteState() throws Exception {
        var callbacks = mock(AccumulationCallbacks.class);
        doNothing().when(callbacks).onTrafficStreamsExpired(any(), any(), any());
        var accumulator = new CapturedTrafficToHttpTransactionAccumulator(
            Duration.ofMinutes(6), "--timeout 6m", callbacks);

        var liveStreams = getLiveStreams(accumulator);
        var tsk = mockTrafficStreamKey("node1", "conn-write", 0);

        liveStreams.getOrCreateWithoutExpiration(tsk, k -> {
            var accum = new Accumulation(k, 0);
            accum.state = Accumulation.State.ACCUMULATING_WRITES;
            accum.getNewestPacketTimestampInMillisReference().set(System.currentTimeMillis() - 60_000);
            return accum;
        });

        Assertions.assertDoesNotThrow(accumulator::heartbeatAndExpireStaleConnections);
    }

    @Test
    void heartbeatAndExpireStaleConnections_wallClockExpiry() throws Exception {
        var callbacks = mock(AccumulationCallbacks.class);
        doNothing().when(callbacks).onTrafficStreamsExpired(any(), any(), any());
        var accumulator = new CapturedTrafficToHttpTransactionAccumulator(
            Duration.ofMinutes(6), "--timeout 6m", callbacks);

        var liveStreams = getLiveStreams(accumulator);
        var tsk = mockTrafficStreamKey("node1", "conn-expired", 0);

        liveStreams.getOrCreateWithoutExpiration(tsk, k -> {
            var accum = new Accumulation(k, 0);
            // Use ACCUMULATING_READS state — expiry triggers the warning-only path
            accum.state = Accumulation.State.ACCUMULATING_READS;
            // Set packet timestamp to 10 minutes ago — exceeds 1.5x of 6min = 9min threshold
            accum.getNewestPacketTimestampInMillisReference().set(System.currentTimeMillis() - 600_000);
            return accum;
        });

        Assertions.assertDoesNotThrow(accumulator::heartbeatAndExpireStaleConnections);
    }

    @Test
    void heartbeatAndExpireStaleConnections_mixedStates() throws Exception {
        var callbacks = mock(AccumulationCallbacks.class);
        doNothing().when(callbacks).onTrafficStreamsExpired(any(), any(), any());
        var accumulator = new CapturedTrafficToHttpTransactionAccumulator(
            Duration.ofMinutes(6), "--timeout 6m", callbacks);

        var liveStreams = getLiveStreams(accumulator);

        // Connection in WAITING state (recent)
        var tsk1 = mockTrafficStreamKey("node1", "conn-wait", 0);
        liveStreams.getOrCreateWithoutExpiration(tsk1, k -> {
            var accum = new Accumulation(k, 0);
            accum.getNewestPacketTimestampInMillisReference().set(System.currentTimeMillis() - 500);
            return accum;
        });

        // Connection in READS state (recent)
        var tsk2 = mockTrafficStreamKey("node1", "conn-read", 0);
        liveStreams.getOrCreateWithoutExpiration(tsk2, k -> {
            var accum = new Accumulation(k, 0);
            accum.state = Accumulation.State.ACCUMULATING_READS;
            accum.getNewestPacketTimestampInMillisReference().set(System.currentTimeMillis() - 2000);
            return accum;
        });

        // Connection in READS state (stale — will trigger wall-clock expiry)
        var tsk3 = mockTrafficStreamKey("node1", "conn-stale-write", 0);
        liveStreams.getOrCreateWithoutExpiration(tsk3, k -> {
            var accum = new Accumulation(k, 0);
            accum.state = Accumulation.State.ACCUMULATING_READS;
            accum.getNewestPacketTimestampInMillisReference().set(System.currentTimeMillis() - 700_000);
            return accum;
        });

        // Connection in IGNORING state (recent)
        var tsk4 = mockTrafficStreamKey("node1", "conn-ignore", 0);
        liveStreams.getOrCreateWithoutExpiration(tsk4, k -> {
            var accum = new Accumulation(k, 0);
            accum.state = Accumulation.State.IGNORING_LAST_REQUEST;
            accum.getNewestPacketTimestampInMillisReference().set(System.currentTimeMillis() - 1000);
            return accum;
        });

        Assertions.assertDoesNotThrow(accumulator::heartbeatAndExpireStaleConnections);
    }

    private static ExpiringTrafficStreamMap getLiveStreams(
        CapturedTrafficToHttpTransactionAccumulator accumulator
    ) throws Exception {
        Field f = CapturedTrafficToHttpTransactionAccumulator.class.getDeclaredField("liveStreams");
        f.setAccessible(true);
        return (ExpiringTrafficStreamMap) f.get(accumulator);
    }

    private static ITrafficStreamKey mockTrafficStreamKey(String nodeId, String connectionId, int index) {
        var tsk = mock(ITrafficStreamKey.class);
        when(tsk.getNodeId()).thenReturn(nodeId);
        when(tsk.getConnectionId()).thenReturn(connectionId);
        when(tsk.getTrafficStreamIndex()).thenReturn(index);
        var ctx = mock(org.opensearch.migrations.replay.tracing.IReplayContexts.ITrafficStreamsLifecycleContext.class);
        var channelCtx = mock(org.opensearch.migrations.replay.tracing.IReplayContexts.IChannelKeyContext.class);
        when(ctx.getLogicalEnclosingScope()).thenReturn(channelCtx);
        when(tsk.getTrafficStreamsContext()).thenReturn(ctx);
        when(tsk.toString()).thenReturn(nodeId + ":" + connectionId + "#" + index);
        return tsk;
    }
}
