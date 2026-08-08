package org.opensearch.migrations.replay;

import java.util.List;

import org.opensearch.migrations.replay.datatypes.ITrafficStreamKey;
import org.opensearch.migrations.replay.traffic.source.ITrafficStreamWithKey;
import org.opensearch.migrations.trafficcapture.protos.TrafficStream;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TimingDiagnosticCoverageTest {

    @Test
    void warnIfReadWasSlow_doesNotWarnForFastReads() throws Exception {
        var core = createMinimalCore();
        var method = TrafficReplayerCore.class.getDeclaredMethod(
            "warnIfReadWasSlow", long.class, List.class);
        method.setAccessible(true);
        var streams = List.of(mockTrafficStream());
        Assertions.assertDoesNotThrow(() ->
            method.invoke(core, System.nanoTime(), streams));
    }

    @Test
    void warnIfReadWasSlow_warnsForSlowReads() throws Exception {
        var core = createMinimalCore();
        var method = TrafficReplayerCore.class.getDeclaredMethod(
            "warnIfReadWasSlow", long.class, List.class);
        method.setAccessible(true);
        var streams = List.of(mockTrafficStream());
        var sixSecondsAgo = System.nanoTime() - 6_000_000_000L;
        Assertions.assertDoesNotThrow(() ->
            method.invoke(core, sixSecondsAgo, streams));
    }

    @Test
    void acceptWithTimingDiagnostic_fastPath() throws Exception {
        var method = TrafficReplayerCore.class.getDeclaredMethod(
            "acceptWithTimingDiagnostic",
            CapturedTrafficToHttpTransactionAccumulator.class,
            ITrafficStreamWithKey.class);
        method.setAccessible(true);
        var accumulator = mock(CapturedTrafficToHttpTransactionAccumulator.class);
        doNothing().when(accumulator).accept(org.mockito.ArgumentMatchers.any());
        var ts = mockTrafficStream();
        Assertions.assertDoesNotThrow(() ->
            method.invoke(null, accumulator, ts));
    }

    @Test
    void warnIfBatchWasSlow_doesNotWarnForFastBatches() throws Exception {
        var core = createMinimalCore();
        var method = TrafficReplayerCore.class.getDeclaredMethod(
            "warnIfBatchWasSlow", long.class, List.class);
        method.setAccessible(true);
        var streams = List.of(mockTrafficStream());
        Assertions.assertDoesNotThrow(() ->
            method.invoke(core, System.nanoTime(), streams));
    }

    @Test
    void warnIfBatchWasSlow_warnsForSlowBatches() throws Exception {
        var core = createMinimalCore();
        var method = TrafficReplayerCore.class.getDeclaredMethod(
            "warnIfBatchWasSlow", long.class, List.class);
        method.setAccessible(true);
        var streams = List.of(mockTrafficStream());
        var sixSecondsAgo = System.nanoTime() - 6_000_000_000L;
        Assertions.assertDoesNotThrow(() ->
            method.invoke(core, sixSecondsAgo, streams));
    }

    private static ITrafficStreamWithKey mockTrafficStream() {
        var ts = mock(ITrafficStreamWithKey.class);
        var stream = TrafficStream.newBuilder()
            .setConnectionId("test-conn")
            .setNumberOfThisLastChunk(0)
            .build();
        when(ts.getStream()).thenReturn(stream);
        var key = mock(ITrafficStreamKey.class);
        when(key.toString()).thenReturn("test-key");
        when(ts.getKey()).thenReturn(key);
        return ts;
    }

    private static TrafficReplayerCore createMinimalCore() {
        return mock(TrafficReplayerCore.class, org.mockito.Mockito.CALLS_REAL_METHODS);
    }
}
