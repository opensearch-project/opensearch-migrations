package org.opensearch.migrations.replay;

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
import org.opensearch.migrations.replay.traffic.source.BufferedFlowController;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class TrafficReplayerCoreProgressTest {
    @Test
    void targetCompletionReleasesReadGateWithoutWaitingForSourceDisposition() {
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
        var targetCompletion = new CompletableFuture<Void>();
        var sourceDisposition = new CompletableFuture<Void>();

        TrafficReplayerCore.settleProgressWhenTargetCompletes(targetCompletion, progressToken);
        targetCompletion.complete(null);

        progressToken.settled().toCompletableFuture().join();
        Assertions.assertFalse(progress.isWorkOutstanding());
        Assertions.assertFalse(sourceDisposition.isDone());

        progress.advanceIdlePartitions(Instant.ofEpochSecond(100));
        Assertions.assertEquals(Instant.ofEpochSecond(130), readGate.frontier());
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
