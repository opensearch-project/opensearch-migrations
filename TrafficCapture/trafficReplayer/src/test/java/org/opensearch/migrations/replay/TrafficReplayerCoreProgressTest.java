package org.opensearch.migrations.replay;

// REBUILD-LIMBO(G10) -- nothing in this file is live yet. Javadoc is left outside the marked
// regions so it needs no escaping and keeps its blame; it documents code that is not compiled.
// Resolve each region to dead, keep, or refactor deliberately. If a member is deleted, delete its
// javadoc with it. See AGENTS.md section 8a.
// Test carried byte-identical. Unresolved: BufferedFlowController ReplayProgressController ReplayReadGate . Per AGENTS.md section 4 an inherited test may stay broken while the architectures are partly connected; this one is restored by the milestone that rebuilds its subject, keeping its assertions conceptually stable while changing the mechanics.
// Un-mark a member by deleting the delimiter lines around it and splitting this region; the
// code between them is verbatim, so blame survives. Read this before writing anything new

// REBUILD-LIMBO-START(G10)
/*

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import org.opensearch.migrations.replay.lifecycle.ReplayIdentity.ConnectionSessionKey;
import org.opensearch.migrations.replay.lifecycle.ReplayIdentity.ReplayRequestId;
import org.opensearch.migrations.replay.lifecycle.ReplayIdentity.SourceConnectionKey;
import org.opensearch.migrations.replay.lifecycle.ReplayIdentity.SourcePartitionKey;
import org.opensearch.migrations.replay.lifecycle.ReplayProgressController;
import org.opensearch.migrations.replay.lifecycle.ReplayReadGate;
import org.opensearch.migrations.replay.lifecycle.TargetConnectionOwner.RequestProcessingOutcome;
import org.opensearch.migrations.replay.lifecycle.TargetConnectionOwner.RequestProcessingRegistration;
import org.opensearch.migrations.replay.traffic.source.BufferedFlowController;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class TrafficReplayerCoreProgressTest {
    @Test
    void processingCompletionDoesNotReleaseReadGateBeforeLifecycleHandling() {
        var readGate = new ReplayReadGate(Duration.ofSeconds(30), new NoopFlowController());
        var progress = new ReplayProgressController(Runnable::run, readGate);
        var partition = new SourcePartitionKey("topic", 0, 1);
        progress.onAssigned(List.of(partition));
        var progressToken = progress.admit(
            partition,
            new ReplayRequestId(
                new ConnectionSessionKey(new SourceConnectionKey("node", "connection"), 0, 1),
                0
            ),
            Instant.ofEpochSecond(10)
        ).toCompletableFuture().join();
        var processingCompletion = new CompletableFuture<RequestProcessingOutcome>();
        var lifecycleHandled = new CompletableFuture<Void>();
        var processingRegistration = new RequestProcessingRegistration(
            processingCompletion,
            cause -> CompletableFuture.failedFuture(
                new AssertionError("cancellation is not expected", cause)
            ),
            lifecycleHandled
        );

        TrafficReplayerCore.settleProgressAfterProcessingLifecycleIsHandled(
            processingRegistration,
            progressToken
        );
        processingCompletion.complete(new RequestProcessingOutcome.TupleDurable());

        Assertions.assertTrue(progress.isWorkOutstanding());
        Assertions.assertFalse(progressToken.settled().toCompletableFuture().isDone());

        lifecycleHandled.complete(null);
        progressToken.settled().toCompletableFuture().join();
        Assertions.assertFalse(progress.isWorkOutstanding());

        progress.advanceIdlePartitions(Instant.ofEpochSecond(100));
        Assertions.assertEquals(Instant.ofEpochSecond(130), readGate.frontier());
    }

    @Test
    void failedProcessingStillReleasesReadGate() {
        var readGate = new ReplayReadGate(Duration.ofSeconds(30), new NoopFlowController());
        var progress = new ReplayProgressController(Runnable::run, readGate);
        var partition = new SourcePartitionKey("topic", 0, 1);
        progress.onAssigned(List.of(partition));
        var progressToken = progress.admit(
            partition,
            new ReplayRequestId(
                new ConnectionSessionKey(new SourceConnectionKey("node", "connection"), 0, 1),
                0
            ),
            Instant.ofEpochSecond(10)
        ).toCompletableFuture().join();
        var processingCompletion = new CompletableFuture<RequestProcessingOutcome>();
        var processingRegistration = new RequestProcessingRegistration(
            processingCompletion,
            cause -> CompletableFuture.failedFuture(
                new AssertionError("cancellation is not expected", cause)
            ),
            new CompletableFuture<>()
        );

        TrafficReplayerCore.settleProgressAfterProcessingLifecycleIsHandled(
            processingRegistration,
            progressToken
        );
        processingCompletion.completeExceptionally(new IllegalStateException("processing failed"));

        progressToken.settled().toCompletableFuture().join();
        Assertions.assertFalse(progress.isWorkOutstanding());
    }

    @Test
    void replayQuiescenceBlocksWholeReplayDrain() {
        var replayQuiescence = new CompletableFuture<Void>();
        var drain = TrafficReplayerTopLevel.combineReplayDrainGates(
            CompletableFuture.completedFuture(null),
            replayQuiescence
        );

        Assertions.assertFalse(drain.isDone());
        replayQuiescence.complete(null);
        drain.join();
    }

    private static final class NoopFlowController implements BufferedFlowController {
        @Override
        public void stopReadsPast(Instant pointInTime) {}

        @Override
        public Duration getBufferTimeWindow() {
            return Duration.ZERO;
        }
    }
}

*/
// REBUILD-LIMBO-END(G10)