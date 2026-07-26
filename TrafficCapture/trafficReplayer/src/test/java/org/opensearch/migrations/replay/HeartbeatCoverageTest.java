package org.opensearch.migrations.replay;

import java.time.Duration;
import java.time.Instant;

import org.opensearch.migrations.replay.traffic.source.BufferedFlowController;
import org.opensearch.migrations.replay.traffic.source.TrafficStreamLimiter;
import org.opensearch.migrations.utils.TextTrackedFuture;

import io.netty.util.concurrent.ScheduledFuture;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class HeartbeatCoverageTest {

    @Test
    void trafficStreamLimiter_logHeartbeat_doesNotThrow() throws Exception {
        try (var limiter = new TrafficStreamLimiter(100)) {
            Assertions.assertDoesNotThrow(limiter::logHeartbeat);
        }
    }

    @Test
    void trafficStreamLimiter_logHeartbeat_withExhaustedPermits() throws Exception {
        try (var limiter = new TrafficStreamLimiter(1)) {
            limiter.queueWork(1, null, wi -> {});
            Assertions.assertDoesNotThrow(limiter::logHeartbeat);
        }
    }

    @Test
    void replayEngine_logHeartbeat_doesNotThrow() {
        var orchestrator = mock(RequestSenderOrchestrator.class);
        var flowController = mock(BufferedFlowController.class);
        when(flowController.getBufferTimeWindow()).thenReturn(Duration.ofSeconds(10));
        when(orchestrator.scheduleAtFixedRate(any(Runnable.class), anyLong(), anyLong(), any()))
            .thenReturn(mock(ScheduledFuture.class));

        var timeShifter = new TimeShifter();
        timeShifter.setFirstTimestamp(Instant.now().minusSeconds(60));

        var engine = new ReplayEngine(orchestrator, flowController, timeShifter);
        Assertions.assertDoesNotThrow(engine::logHeartbeat);
    }

    @Test
    void replayEngine_logHeartbeat_withWorkOutstanding() {
        var orchestrator = mock(RequestSenderOrchestrator.class);
        var flowController = mock(BufferedFlowController.class);
        when(flowController.getBufferTimeWindow()).thenReturn(Duration.ofSeconds(10));
        when(orchestrator.scheduleAtFixedRate(any(Runnable.class), anyLong(), anyLong(), any()))
            .thenReturn(mock(ScheduledFuture.class));
        when(orchestrator.scheduleWork(any(), any(), any()))
            .thenReturn(new TextTrackedFuture<>(new java.util.concurrent.CompletableFuture<>(), () -> "pending"));

        var timeShifter = new TimeShifter();
        timeShifter.setFirstTimestamp(Instant.now().minusSeconds(60));

        var engine = new ReplayEngine(orchestrator, flowController, timeShifter);

        var ctx = mock(org.opensearch.migrations.replay.tracing.IReplayContexts.IReplayerHttpTransactionContext.class);
        var channelKeyCtx = mock(org.opensearch.migrations.replay.tracing.IReplayContexts.IChannelKeyContext.class);
        when(ctx.getLogicalEnclosingScope()).thenReturn(channelKeyCtx);
        when(ctx.getConnectionId()).thenReturn("test-conn");
        var tsk = mock(org.opensearch.migrations.replay.datatypes.ITrafficStreamKey.class);
        when(tsk.getSourceGeneration()).thenReturn(0);
        var requestKey = new org.opensearch.migrations.replay.datatypes.UniqueReplayerRequestKey(tsk, 0, 0);
        when(ctx.getReplayerRequestKey()).thenReturn(requestKey);

        engine.scheduleTransformationWork(ctx, Instant.now().minusSeconds(30), () ->
            new TextTrackedFuture<>(new java.util.concurrent.CompletableFuture<>(), () -> "task"));

        Assertions.assertTrue(engine.isWorkOutstanding());
        Assertions.assertDoesNotThrow(engine::logHeartbeat);
    }

    @Test
    void replayEngine_logHeartbeat_withResponseCodes() {
        var orchestrator = mock(RequestSenderOrchestrator.class);
        var flowController = mock(BufferedFlowController.class);
        when(flowController.getBufferTimeWindow()).thenReturn(Duration.ofSeconds(10));
        when(orchestrator.scheduleAtFixedRate(any(Runnable.class), anyLong(), anyLong(), any()))
            .thenReturn(mock(ScheduledFuture.class));

        var timeShifter = new TimeShifter();
        timeShifter.setFirstTimestamp(Instant.now().minusSeconds(60));

        var engine = new ReplayEngine(orchestrator, flowController, timeShifter);
        engine.recordTargetResponseCode(200);
        engine.recordTargetResponseCode(200);
        engine.recordTargetResponseCode(503);

        Assertions.assertDoesNotThrow(engine::logHeartbeat);
    }

    @Test
    void replayEngine_logHeartbeat_withStaleDuration() throws Exception {
        var orchestrator = mock(RequestSenderOrchestrator.class);
        var flowController = mock(BufferedFlowController.class);
        when(flowController.getBufferTimeWindow()).thenReturn(Duration.ofSeconds(10));

        var scheduledRunnableCaptor = ArgumentCaptor.forClass(Runnable.class);
        when(orchestrator.scheduleAtFixedRate(scheduledRunnableCaptor.capture(), anyLong(), anyLong(), any()))
            .thenReturn(mock(ScheduledFuture.class));

        var completableFuture = new java.util.concurrent.CompletableFuture<>();
        when(orchestrator.scheduleWork(any(), any(), any()))
            .thenReturn(new TextTrackedFuture<>(completableFuture, () -> "pending"));

        var timeShifter = new TimeShifter();
        timeShifter.setFirstTimestamp(Instant.now().minusSeconds(120));

        var engine = new ReplayEngine(orchestrator, flowController, timeShifter);

        var ctx = mock(org.opensearch.migrations.replay.tracing.IReplayContexts.IReplayerHttpTransactionContext.class);
        var channelKeyCtx = mock(org.opensearch.migrations.replay.tracing.IReplayContexts.IChannelKeyContext.class);
        when(ctx.getLogicalEnclosingScope()).thenReturn(channelKeyCtx);
        when(ctx.getConnectionId()).thenReturn("test-conn");
        var tsk = mock(org.opensearch.migrations.replay.datatypes.ITrafficStreamKey.class);
        when(tsk.getSourceGeneration()).thenReturn(0);
        var requestKey = new org.opensearch.migrations.replay.datatypes.UniqueReplayerRequestKey(tsk, 0, 0);
        when(ctx.getReplayerRequestKey()).thenReturn(requestKey);

        engine.scheduleTransformationWork(ctx, Instant.now().minusSeconds(90), () ->
            new TextTrackedFuture<>(completableFuture, () -> "task"));

        // Complete the work so lastCompletedWallClockMs is set (simulating stale state)
        completableFuture.complete(null);
        Thread.sleep(10);

        // Schedule a NEW piece of work that never completes (to make isWorkOutstanding true)
        var neverCompletes = new java.util.concurrent.CompletableFuture<>();
        when(orchestrator.scheduleWork(any(), any(), any()))
            .thenReturn(new TextTrackedFuture<>(neverCompletes, () -> "stuck"));

        var ctx2 = mock(org.opensearch.migrations.replay.tracing.IReplayContexts.IReplayerHttpTransactionContext.class);
        when(ctx2.getLogicalEnclosingScope()).thenReturn(channelKeyCtx);
        when(ctx2.getConnectionId()).thenReturn("test-conn-2");
        var requestKey2 = new org.opensearch.migrations.replay.datatypes.UniqueReplayerRequestKey(tsk, 1, 0);
        when(ctx2.getReplayerRequestKey()).thenReturn(requestKey2);

        engine.scheduleTransformationWork(ctx2, Instant.now().minusSeconds(30), () ->
            new TextTrackedFuture<>(new java.util.concurrent.CompletableFuture<>(), () -> "task2"));

        Assertions.assertTrue(engine.isWorkOutstanding());
        Assertions.assertDoesNotThrow(engine::logHeartbeat);
    }
}
