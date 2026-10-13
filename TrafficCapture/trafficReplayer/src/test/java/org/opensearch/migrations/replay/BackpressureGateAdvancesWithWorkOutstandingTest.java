package org.opensearch.migrations.replay;

import java.time.Duration;
import java.time.Instant;

import org.opensearch.migrations.replay.traffic.source.BufferedFlowController;
import org.opensearch.migrations.utils.TextTrackedFuture;

import io.netty.util.concurrent.ScheduledFuture;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Verifies that the backpressure gate advances unconditionally (even with work outstanding).
 * Before the fix, updateContentTimeControllerWhenIdling() returned immediately when
 * isWorkOutstanding()==true, freezing the gate and starving source-time-based expiry.
 */
class BackpressureGateAdvancesWithWorkOutstandingTest {

    @Test
    void gateAdvancesEvenWithOutstandingWork() throws Exception {
        var orchestrator = mock(RequestSenderOrchestrator.class);
        var flowController = mock(BufferedFlowController.class);
        when(flowController.getBufferTimeWindow()).thenReturn(Duration.ofSeconds(10));

        var scheduledRunnableCaptor = ArgumentCaptor.forClass(Runnable.class);
        when(orchestrator.scheduleAtFixedRate(scheduledRunnableCaptor.capture(), anyLong(), anyLong(), any()))
            .thenReturn(mock(ScheduledFuture.class));

        var timeShifter = new TimeShifter();
        timeShifter.setFirstTimestamp(Instant.now().minusSeconds(60));

        var engine = new ReplayEngine(orchestrator, flowController, timeShifter);

        // Simulate work outstanding by scheduling a request that never completes
        var ctx = mock(org.opensearch.migrations.replay.tracing.IReplayContexts.IReplayerHttpTransactionContext.class);
        var channelKeyCtx = mock(org.opensearch.migrations.replay.tracing.IReplayContexts.IChannelKeyContext.class);
        when(ctx.getLogicalEnclosingScope()).thenReturn(channelKeyCtx);
        when(ctx.getConnectionId()).thenReturn("test-conn");
        var tsk = mock(org.opensearch.migrations.replay.datatypes.ITrafficStreamKey.class);
        when(tsk.getSourceGeneration()).thenReturn(0);
        var requestKey = new org.opensearch.migrations.replay.datatypes.UniqueReplayerRequestKey(tsk, 0, 0);
        when(ctx.getReplayerRequestKey()).thenReturn(requestKey);

        // Return a never-completing future so work stays outstanding
        when(orchestrator.scheduleWork(any(), any(), any()))
            .thenReturn(new TextTrackedFuture<>(new java.util.concurrent.CompletableFuture<>(), () -> "pending"));

        engine.scheduleTransformationWork(ctx, Instant.now().minusSeconds(30), () ->
            new TextTrackedFuture<>(new java.util.concurrent.CompletableFuture<>(), () -> "task"));

        Assertions.assertTrue(engine.isWorkOutstanding(), "Work should be outstanding");

        // Trigger the scheduled idle update runnable
        var idleUpdateRunnable = scheduledRunnableCaptor.getValue();
        idleUpdateRunnable.run();

        // Verify the flow controller was called — gate advanced despite work being outstanding
        verify(flowController, atLeastOnce()).stopReadsPast(any(Instant.class));
    }
}
