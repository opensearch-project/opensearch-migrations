package org.opensearch.migrations.replay.lifecycle;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.opensearch.migrations.replay.traffic.source.BufferedFlowController;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class ReplayReadGateTest {
    @Test
    void addsEpsilonOnceAndReconcilesAssignmentDrivenRegressions() {
        var flowController = new RecordingFlowController();
        var gate = new ReplayReadGate(Duration.ofSeconds(30), flowController);

        gate.advanceTo(Instant.ofEpochSecond(100));
        gate.advanceTo(Instant.ofEpochSecond(90));
        gate.advanceTo(Instant.ofEpochSecond(110));

        Assertions.assertEquals(
            List.of(
                Instant.ofEpochSecond(130),
                Instant.ofEpochSecond(120),
                Instant.ofEpochSecond(140)
            ),
            flowController.frontiers
        );
        Assertions.assertEquals(Instant.ofEpochSecond(140), gate.frontier());
    }

    @Test
    void doesNotTurnAnUninitializedWatermarkIntoAnUnboundedTimestamp() {
        var flowController = new RecordingFlowController();
        var gate = new ReplayReadGate(Duration.ofSeconds(30), flowController);

        gate.advanceTo(Instant.MIN);

        Assertions.assertTrue(flowController.frontiers.isEmpty());
        Assertions.assertEquals(Instant.MIN, gate.frontier());
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
