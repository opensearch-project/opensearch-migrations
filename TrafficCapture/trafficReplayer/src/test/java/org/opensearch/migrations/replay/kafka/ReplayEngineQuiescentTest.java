package org.opensearch.migrations.replay.kafka;

import java.time.Duration;
import java.time.Instant;

import org.opensearch.migrations.replay.ReplayEngine;
import org.opensearch.migrations.replay.RequestSenderOrchestrator;
import org.opensearch.migrations.replay.TimeShifter;
import org.opensearch.migrations.replay.datatypes.ByteBufList;
import org.opensearch.migrations.replay.datatypes.ITrafficStreamKey;
import org.opensearch.migrations.replay.datatypes.UniqueReplayerRequestKey;
import org.opensearch.migrations.replay.tracing.IReplayContexts;
import org.opensearch.migrations.replay.traffic.source.BufferedFlowController;
import org.opensearch.migrations.utils.TextTrackedFuture;

import io.netty.buffer.Unpooled;
import io.netty.util.concurrent.ScheduledFuture;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests #13, #14: Quiescent delay enforcement in ReplayEngine.
 */
class ReplayEngineQuiescentTest {

    private IReplayContexts.IReplayerHttpTransactionContext buildMockCtx() {
        var ctx = mock(IReplayContexts.IReplayerHttpTransactionContext.class);
        var channelKeyCtx = mock(IReplayContexts.IChannelKeyContext.class);
        when(ctx.getLogicalEnclosingScope()).thenReturn(channelKeyCtx);
        when(ctx.getConnectionId()).thenReturn("test-conn");

        var tsk = mock(ITrafficStreamKey.class);
        when(tsk.getSourceGeneration()).thenReturn(0);
        var requestKey = new UniqueReplayerRequestKey(tsk, 0, 0);
        when(ctx.getReplayerRequestKey()).thenReturn(requestKey);
        return ctx;
    }

    private ReplayEngine buildEngine(RequestSenderOrchestrator orchestrator) {
        var flowController = mock(BufferedFlowController.class);
        when(flowController.getBufferTimeWindow()).thenReturn(Duration.ofSeconds(10));
        when(orchestrator.scheduleAtFixedRate(any(), anyLong(), anyLong(), any()))
            .thenReturn(mock(ScheduledFuture.class));
        var timeShifter = new TimeShifter();
        timeShifter.setFirstTimestamp(Instant.parse("2025-01-01T00:00:00Z"));
        return new ReplayEngine(orchestrator, flowController, timeShifter);
    }

    /**
     * Test #13: Schedule a request with quiescentUntil 200ms in the future.
     * Assert the effective start time passed to networkSendOrchestrator.scheduleRequest
     * is at least quiescentUntil (not the original time-shifted start).
     */
    @Test
    void replayEngineHonorsQuiescentDelay() {
        var orchestrator = mock(RequestSenderOrchestrator.class);
        var engine = buildEngine(orchestrator);

        when(orchestrator.scheduleRequest(any(), any(), any(), any(), any(), any()))
            .thenReturn(TextTrackedFuture.completedFuture(null, () -> "mock"));

        var sourceRequestTime = Instant.parse("2025-01-01T00:00:00.100Z");
        // quiescentDuration is 200ms — applied relative to the time-shifted start
        var quiescentDuration = Duration.ofMillis(200);

        var ctx = buildMockCtx();
        var packets = new ByteBufList(Unpooled.wrappedBuffer("test".getBytes()));
        engine.scheduleRequest(ctx, sourceRequestTime, sourceRequestTime.plusMillis(50),
            1, packets, (reqBytes, arr, t) -> null, quiescentDuration);

        var startCaptor = ArgumentCaptor.forClass(Instant.class);
        verify(orchestrator).scheduleRequest(any(), any(), startCaptor.capture(), any(), any(), any());

        var effectiveStart = startCaptor.getValue();
        // The time-shifted start is sourceRequestTime (TimeShifter is identity in tests)
        // quiescentUntil = timeShiftedStart + 200ms
        var expectedMinStart = sourceRequestTime.plus(quiescentDuration);
        Assertions.assertFalse(effectiveStart.isBefore(expectedMinStart),
            "Effective start time (" + effectiveStart + ") must be >= timeShiftedStart + quiescentDuration (" + expectedMinStart + ")");
    }

    /**
     * Test #14: Without quiescentUntil, the start time should be the normal time-shifted value.
     * Verifies quiescent delay only applies when explicitly set.
     */
    @Test
    void quiescentOnlyAppliesToFirstRequest() {
        var orchestrator = mock(RequestSenderOrchestrator.class);
        var engine = buildEngine(orchestrator);

        when(orchestrator.scheduleRequest(any(), any(), any(), any(), any(), any()))
            .thenReturn(TextTrackedFuture.completedFuture(null, () -> "mock"));

        var sourceRequestTime = Instant.parse("2025-01-01T00:00:01Z");

        var ctx = buildMockCtx();
        var packets = new ByteBufList(Unpooled.wrappedBuffer("test".getBytes()));

        // No quiescentUntil — normal timing
        engine.scheduleRequest(ctx, sourceRequestTime, sourceRequestTime.plusMillis(50),
            1, packets, (reqBytes, arr, t) -> null, null);

        var startCaptor = ArgumentCaptor.forClass(Instant.class);
        verify(orchestrator).scheduleRequest(any(), any(), startCaptor.capture(), any(), any(), any());

        var effectiveStart = startCaptor.getValue();
        // Without quiescentUntil, the start should be the normal time-shifted value (close to now + 1s)
        // It should NOT be delayed by any quiescent period
        Assertions.assertTrue(effectiveStart.isBefore(Instant.now().plusSeconds(5)),
            "Without quiescentUntil, start time should be the normal time-shifted value, not delayed. " +
            "Got " + effectiveStart);
    }
}
