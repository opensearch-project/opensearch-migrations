package org.opensearch.migrations.replay.kafka;

// REBUILD-LIMBO(G11) -- nothing in this file is live yet. Javadoc is left outside the marked
// regions so it needs no escaping and keeps its blame; it documents code that is not compiled.
// Resolve each region to dead, keep, or refactor deliberately. If a member is deleted, delete its
// javadoc with it. See AGENTS.md section 8a.
// Carried verbatim. This was the pre-rebuild implementation of a responsibility the design
// reassigns, so it is the input to that refactor rather than something to re-derive. Resolve it to
// dead, keep, or refactor deliberately -- see AGENTS.md section 8a, and read this before writing

// REBUILD-LIMBO-START(G11)
/*

import java.time.Duration;
import java.time.Instant;

import org.opensearch.migrations.replay.ActorRequestTestUtils.RequestProcessingFixture;
import org.opensearch.migrations.replay.ReplayEngine;
import org.opensearch.migrations.replay.RequestSenderOrchestrator;
import org.opensearch.migrations.replay.TimeShifter;
import org.opensearch.migrations.replay.datatypes.ByteBufList;
import org.opensearch.migrations.replay.datatypes.ByteBufListProducer;
import org.opensearch.migrations.replay.datatypes.HttpRequestTransformationStatus;
import org.opensearch.migrations.replay.datatypes.ITrafficStreamKey;
import org.opensearch.migrations.replay.datatypes.TransformedOutputAndResult;
import org.opensearch.migrations.replay.datatypes.UniqueReplayerRequestKey;
import org.opensearch.migrations.replay.lifecycle.AsyncPermitPool;
import org.opensearch.migrations.replay.lifecycle.ReplayIdentity.PartitionGenerationId;
import org.opensearch.migrations.replay.lifecycle.ReplayProgressController;
import org.opensearch.migrations.replay.lifecycle.ReplayReadGate;
import org.opensearch.migrations.replay.tracing.IReplayContexts;
import org.opensearch.migrations.replay.traffic.source.BufferedFlowController;
import org.opensearch.migrations.utils.TextTrackedFuture;

import org.apache.kafka.common.TopicPartition;

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

*/
// REBUILD-LIMBO-END(G11)
/**
 * Tests #13, #14: Quiescent delay enforcement in ReplayEngine.
 */
// REBUILD-LIMBO-START(G11)
/*
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
        var progressController = new ReplayProgressController(
            Runnable::run,
            new ReplayReadGate(Duration.ZERO, flowController)
        );
        return new ReplayEngine(orchestrator, flowController, timeShifter, progressController);
    }

    private PartitionGenerationId testGeneration() {
        return new PartitionGenerationId(
            new TopicPartition("replay-engine-quiescent-test", 3),
            7
        );
    }

*/
// REBUILD-LIMBO-END(G11)
    /**
     * Test #13: Schedule a request with quiescentUntil 200ms in the future.
     * Assert the effective start time passed to networkSendOrchestrator.scheduleRequest
     * is at least quiescentUntil (not the original time-shifted start).
     */
// REBUILD-LIMBO-START(G11)
/*
    @Test
    void replayEngineHonorsQuiescentDelay() {
        var orchestrator = mock(RequestSenderOrchestrator.class);
        var engine = buildEngine(orchestrator);

        when(orchestrator.scheduleRequestLifecycle(
            any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()
        ))
            .thenReturn(TextTrackedFuture.completedFuture(null, () -> "mock"));

        var sourceRequestTime = Instant.parse("2025-01-01T00:00:00.100Z");
        // quiescentDuration is 200ms — applied relative to the time-shifted start
        var quiescentDuration = Duration.ofMillis(200);

        var ctx = buildMockCtx();
        var packets = new ByteBufList(Unpooled.wrappedBuffer("test".getBytes()));
        var producer = ByteBufListProducer.of(packets);
        var processing = new RequestProcessingFixture();
        engine.scheduleRequestLifecycle(
            testGeneration(),
            ctx,
            sourceRequestTime,
            sourceRequestTime.plusMillis(50),
            new AsyncPermitPool(1, Runnable::run),
            () -> TextTrackedFuture.completedFuture(
                new TransformedOutputAndResult<>(
                    producer,
                    HttpRequestTransformationStatus.completed()
                ),
                () -> "prepared"
            ),
            ignored -> (reqBytes, outcome) -> null,
            ignored -> null,
            quiescentDuration,
            processing.registration()
        );

        var startCaptor = ArgumentCaptor.forClass(Instant.class);
        verify(orchestrator).scheduleRequestLifecycle(
            any(), any(), any(), any(), startCaptor.capture(), any(), any(), any(), any(), any(), any()
        );

        var effectiveStart = startCaptor.getValue();
        // The time-shifted start is sourceRequestTime (TimeShifter is identity in tests)
        // quiescentUntil = timeShiftedStart + 200ms
        var expectedMinStart = sourceRequestTime.plus(quiescentDuration);
        Assertions.assertFalse(effectiveStart.isBefore(expectedMinStart),
            "Effective start time (" + effectiveStart + ") must be >= timeShiftedStart + quiescentDuration (" + expectedMinStart + ")");
        producer.release();
    }

*/
// REBUILD-LIMBO-END(G11)
    /**
     * Test #14: Without quiescentUntil, the start time should be the normal time-shifted value.
     * Verifies quiescent delay only applies when explicitly set.
     */
// REBUILD-LIMBO-START(G11)
/*
    @Test
    void quiescentOnlyAppliesToFirstRequest() {
        var orchestrator = mock(RequestSenderOrchestrator.class);
        var engine = buildEngine(orchestrator);

        when(orchestrator.scheduleRequestLifecycle(
            any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()
        ))
            .thenReturn(TextTrackedFuture.completedFuture(null, () -> "mock"));

        var sourceRequestTime = Instant.parse("2025-01-01T00:00:01Z");

        var ctx = buildMockCtx();
        var packets = new ByteBufList(Unpooled.wrappedBuffer("test".getBytes()));
        var producer = ByteBufListProducer.of(packets);
        var processing = new RequestProcessingFixture();

        // No quiescentUntil — normal timing
        engine.scheduleRequestLifecycle(
            testGeneration(),
            ctx,
            sourceRequestTime,
            sourceRequestTime.plusMillis(50),
            new AsyncPermitPool(1, Runnable::run),
            () -> TextTrackedFuture.completedFuture(
                new TransformedOutputAndResult<>(
                    producer,
                    HttpRequestTransformationStatus.completed()
                ),
                () -> "prepared"
            ),
            ignored -> (reqBytes, outcome) -> null,
            ignored -> null,
            null,
            processing.registration()
        );

        var startCaptor = ArgumentCaptor.forClass(Instant.class);
        verify(orchestrator).scheduleRequestLifecycle(
            any(), any(), any(), any(), startCaptor.capture(), any(), any(), any(), any(), any(), any()
        );

        var effectiveStart = startCaptor.getValue();
        // Without quiescentUntil, the start should be the normal time-shifted value (close to now + 1s)
        // It should NOT be delayed by any quiescent period
        Assertions.assertTrue(effectiveStart.isBefore(Instant.now().plusSeconds(5)),
            "Without quiescentUntil, start time should be the normal time-shifted value, not delayed. " +
            "Got " + effectiveStart);
        producer.release();
    }
}

*/
// REBUILD-LIMBO-END(G11)