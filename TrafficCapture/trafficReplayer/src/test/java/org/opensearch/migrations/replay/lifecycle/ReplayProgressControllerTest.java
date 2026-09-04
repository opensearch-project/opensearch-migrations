package org.opensearch.migrations.replay.lifecycle;

import java.time.Duration;
import java.time.Instant;

import org.opensearch.migrations.replay.lifecycle.ReplayIdentity.ConnectionSessionKey;
import org.opensearch.migrations.replay.lifecycle.ReplayIdentity.ReplayRequestId;
import org.opensearch.migrations.replay.lifecycle.ReplayIdentity.SourceConnectionKey;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class ReplayProgressControllerTest {
    @Test
    void watermarkOnlyAdvancesAcrossContiguousSettledWork() {
        var controller = new ReplayProgressController(Duration.ofSeconds(30), Runnable::run);
        var first = controller.admit(request(0), Instant.ofEpochSecond(10)).toCompletableFuture().join();
        var second = controller.admit(request(1), Instant.ofEpochSecond(20)).toCompletableFuture().join();

        second.close();
        Assertions.assertEquals(Instant.MIN, controller.settledWatermark().toCompletableFuture().join());

        first.close();
        Assertions.assertEquals(
            Instant.ofEpochSecond(20),
            controller.settledWatermark().toCompletableFuture().join()
        );
        Assertions.assertEquals(
            Instant.ofEpochSecond(50),
            controller.readFrontier().toCompletableFuture().join()
        );
    }

    @Test
    void callerCannotCompleteOrCancelTheSettlementGate() {
        var controller = new ReplayProgressController(Duration.ZERO, Runnable::run);
        var token = controller.admit(request(0), Instant.EPOCH).toCompletableFuture().join();
        var callerFuture = token.settled().toCompletableFuture();

        callerFuture.cancel(false);
        Assertions.assertFalse(token.settled().toCompletableFuture().isDone());

        token.close();
        token.settled().toCompletableFuture().join();
    }

    private static ReplayRequestId request(int index) {
        return new ReplayRequestId(
            new ConnectionSessionKey(new SourceConnectionKey("node", "connection"), 0, 1),
            index
        );
    }
}
