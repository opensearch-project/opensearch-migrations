package org.opensearch.migrations.replay.lifecycle;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.opensearch.migrations.replay.lifecycle.ReplayIdentity.ConnectionSessionKey;
import org.opensearch.migrations.replay.lifecycle.ReplayIdentity.ReplayRequestId;
import org.opensearch.migrations.replay.lifecycle.ReplayIdentity.SourceConnectionKey;
import org.opensearch.migrations.replay.lifecycle.ReplayIdentity.SourcePartitionKey;
import org.opensearch.migrations.replay.traffic.source.BufferedFlowController;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class ReplayProgressControllerTest {
    @Test
    void watermarkOnlyAdvancesAcrossContiguousSettledWork() {
        var fixture = new Fixture(Duration.ofSeconds(30));
        var partition = partition(0, 1);
        fixture.controller.onAssigned(List.of(partition));
        var first = fixture.controller.admit(
            partition,
            request(0),
            Instant.ofEpochSecond(10)
        ).toCompletableFuture().join();
        var second = fixture.controller.admit(
            partition,
            request(1),
            Instant.ofEpochSecond(20)
        ).toCompletableFuture().join();

        second.close();
        Assertions.assertEquals(Instant.ofEpochSecond(10), fixture.controller.currentSnapshot().settledWatermark());
        Assertions.assertEquals(List.of(Instant.ofEpochSecond(40)), fixture.flowController.frontiers);

        first.close();
        Assertions.assertEquals(
            Instant.ofEpochSecond(20),
            fixture.controller.currentSnapshot().settledWatermark()
        );
        Assertions.assertEquals(
            List.of(Instant.ofEpochSecond(40), Instant.ofEpochSecond(50)),
            fixture.flowController.frontiers
        );
    }

    @Test
    void callerCannotCompleteOrCancelTheSettlementGate() {
        var fixture = new Fixture(Duration.ZERO);
        var partition = partition(0, 1);
        var token = fixture.controller.admit(
            partition,
            request(0),
            Instant.EPOCH
        ).toCompletableFuture().join();
        var callerFuture = token.settled().toCompletableFuture();

        callerFuture.cancel(false);
        Assertions.assertFalse(token.settled().toCompletableFuture().isDone());

        token.close();
        token.settled().toCompletableFuture().join();
    }

    @Test
    void minimumAssignedPartitionControlsTheGlobalFrontier() {
        var fixture = new Fixture(Duration.ofSeconds(5));
        var firstPartition = partition(0, 1);
        var secondPartition = partition(1, 1);
        fixture.controller.onAssigned(List.of(firstPartition, secondPartition));
        var first = fixture.controller.admit(
            firstPartition,
            request(0),
            Instant.ofEpochSecond(10)
        ).toCompletableFuture().join();
        var second = fixture.controller.admit(
            secondPartition,
            request(1),
            Instant.ofEpochSecond(30)
        ).toCompletableFuture().join();

        second.close();
        Assertions.assertEquals(Instant.ofEpochSecond(10), fixture.controller.currentSnapshot().settledWatermark());

        first.close();
        Assertions.assertEquals(Instant.ofEpochSecond(10), fixture.controller.currentSnapshot().settledWatermark());

        fixture.controller.advanceIdlePartitions(Instant.ofEpochSecond(40));
        Assertions.assertEquals(Instant.ofEpochSecond(40), fixture.controller.currentSnapshot().settledWatermark());
        Assertions.assertEquals(Instant.ofEpochSecond(45), fixture.readGate.frontier());
    }

    @Test
    void idleClockCannotMovePastAnActivePartition() {
        var fixture = new Fixture(Duration.ZERO);
        var activePartition = partition(0, 1);
        var idlePartition = partition(1, 1);
        fixture.controller.onAssigned(List.of(activePartition, idlePartition));
        var active = fixture.controller.admit(
            activePartition,
            request(0),
            Instant.ofEpochSecond(10)
        ).toCompletableFuture().join();

        fixture.controller.advanceIdlePartitions(Instant.ofEpochSecond(100));

        Assertions.assertEquals(Instant.ofEpochSecond(10), fixture.controller.currentSnapshot().settledWatermark());
        Assertions.assertEquals(Instant.ofEpochSecond(10), fixture.readGate.frontier());

        active.close();
        fixture.controller.advanceIdlePartitions(Instant.ofEpochSecond(100));
        Assertions.assertEquals(Instant.ofEpochSecond(100), fixture.controller.currentSnapshot().settledWatermark());
    }

    @Test
    void assignmentUsesTheLatestReplayClockEvenIfNoPartitionWasPreviouslyAssigned() {
        var fixture = new Fixture(Duration.ofSeconds(5));
        fixture.controller.advanceIdlePartitions(Instant.ofEpochSecond(100));

        fixture.controller.onAssigned(List.of(partition(0, 1)));

        Assertions.assertEquals(Instant.ofEpochSecond(100), fixture.controller.currentSnapshot().settledWatermark());
        Assertions.assertEquals(Instant.ofEpochSecond(105), fixture.readGate.frontier());
    }

    @Test
    void revocationDrainsExistingWorkAndRejectsNewAdmission() {
        var fixture = new Fixture(Duration.ZERO);
        var partition = partition(0, 3);
        var token = fixture.controller.admit(
            partition,
            request(0),
            Instant.ofEpochSecond(10)
        ).toCompletableFuture().join();

        fixture.controller.onRevoked(List.of(partition));
        Assertions.assertEquals(1, fixture.controller.currentSnapshot().assignedPartitions());
        var failure = Assertions.assertThrows(
            java.util.concurrent.CompletionException.class,
            () -> fixture.controller.admit(
                partition,
                request(1),
                Instant.ofEpochSecond(20)
            ).toCompletableFuture().join()
        );
        Assertions.assertTrue(failure.getCause().getMessage().contains("revoking"));

        token.close();
        Assertions.assertEquals(0, fixture.controller.currentSnapshot().assignedPartitions());
    }

    @Test
    void aNewGenerationDoesNotInheritTheRetiredGenerationWatermark() {
        var fixture = new Fixture(Duration.ZERO);
        var oldGeneration = partition(0, 1);
        fixture.controller.onAssigned(List.of(oldGeneration));
        var oldToken = fixture.controller.admit(
            oldGeneration,
            request(0),
            Instant.ofEpochSecond(100)
        ).toCompletableFuture().join();
        oldToken.close();
        fixture.controller.onRevoked(List.of(oldGeneration));

        var newGeneration = partition(0, 2);
        fixture.controller.onAssigned(List.of(newGeneration));

        Assertions.assertEquals(Instant.MIN, fixture.controller.currentSnapshot().settledWatermark());
        var newToken = fixture.controller.admit(
            newGeneration,
            request(1),
            Instant.ofEpochSecond(10)
        ).toCompletableFuture().join();
        Assertions.assertEquals(Instant.ofEpochSecond(10), fixture.controller.currentSnapshot().settledWatermark());
        Assertions.assertEquals(Instant.ofEpochSecond(10), fixture.readGate.frontier());
        newToken.close();
    }

    private static ReplayRequestId request(int index) {
        return new ReplayRequestId(
            new ConnectionSessionKey(new SourceConnectionKey("node", "connection"), 0, 1),
            index
        );
    }

    private static SourcePartitionKey partition(int partition, int generation) {
        return new SourcePartitionKey("topic", partition, generation);
    }

    private static final class Fixture {
        private final RecordingFlowController flowController = new RecordingFlowController();
        private final ReplayReadGate readGate;
        private final ReplayProgressController controller;

        private Fixture(Duration epsilon) {
            readGate = new ReplayReadGate(epsilon, flowController);
            controller = new ReplayProgressController(Runnable::run, readGate);
        }
    }

    private static final class RecordingFlowController implements BufferedFlowController {
        private final List<Instant> frontiers = new ArrayList<>();

        @Override
        public void stopReadsPast(Instant pointInTime) {
            frontiers.add(pointInTime);
        }

        @Override
        public Duration getBufferTimeWindow() {
            return Duration.ZERO;
        }
    }
}
