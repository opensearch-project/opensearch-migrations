package org.opensearch.migrations.replay.lifecycle;

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

*/
// REBUILD-LIMBO-END(G11)