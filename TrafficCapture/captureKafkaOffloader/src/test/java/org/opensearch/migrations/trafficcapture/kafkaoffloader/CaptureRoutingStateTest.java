package org.opensearch.migrations.trafficcapture.kafkaoffloader;

import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CaptureRoutingStateTest {
    @Test
    void assignmentChangesAffectOnlySubsequentAdmissionDecisions() {
        var state = new CaptureRoutingState(4, List.of(0, 1));
        int existingPartition = state.admitConnection("existing");

        state.replaceAssignedPartitions(List.of(2, 3));

        assertEquals(existingPartition, state.partitionFor("existing"));
        assertTrue(List.of(2, 3).contains(state.admitConnection("new")));
    }

    @Test
    void revokedPartitionReleasesOnlyAfterItsLastConnectionCloses() {
        var state = new CaptureRoutingState(4, List.of(0, 1));
        state.register("first", 1);
        state.register("last", 1);

        assertEquals(List.of(), state.revokePartitions(List.of(1)));
        assertEquals(List.of(), state.remove("first", 1).stream().toList());

        var release = state.remove("last", 1).orElseThrow();
        assertEquals(1, release.partition());
        assertEquals(List.of(0), state.assignedPartitions());
        assertThrows(IllegalStateException.class, () -> state.remove("missing", 1));
    }

    @Test
    void reassignmentInvalidatesAnUnsubmittedSelfRelease() {
        var state = new CaptureRoutingState(2, List.of(0));
        var release = state.revokePartitions(List.of(0)).get(0);
        state.replaceAssignedPartitions(List.of(0));
        var submitted = new AtomicBoolean();

        assertFalse(state.submitSelfReleaseIfCurrent(release, () -> submitted.set(true)));
        assertFalse(submitted.get());
        assertEquals(0, state.admitConnection("new"));
    }

    @Test
    void releaseSubmissionLinearizesBeforeReassignmentAndNewAdmission() throws Exception {
        var state = new CaptureRoutingState(1, List.of(0));
        var release = state.revokePartitions(List.of(0)).get(0);
        var submissionEntered = new CountDownLatch(1);
        var allowSubmission = new CountDownLatch(1);
        var reassignmentStarted = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var submitted = executor.submit(() -> state.submitSelfReleaseIfCurrent(release, () -> {
                submissionEntered.countDown();
                try {
                    assertTrue(allowSubmission.await(1, TimeUnit.SECONDS));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(e);
                }
            }));
            assertTrue(submissionEntered.await(1, TimeUnit.SECONDS));

            var reassigned = executor.submit(() -> {
                reassignmentStarted.countDown();
                return state.replaceAssignedPartitions(List.of(0));
            });
            assertTrue(reassignmentStarted.await(1, TimeUnit.SECONDS));
            assertFalse(reassigned.isDone());

            allowSubmission.countDown();
            assertTrue(submitted.get(1, TimeUnit.SECONDS));
            reassigned.get(1, TimeUnit.SECONDS);
            assertEquals(0, state.admitConnection("new"));
        }
    }

    @Test
    void snapshotsAreExactSortedCopiesAtOneLinearizationPoint() {
        var state = new CaptureRoutingState(3, List.of(0, 1, 2));
        state.register("connection-b", 1);
        state.register("connection-a", 1);
        state.register("connection-c", 2);

        var snapshot = state.snapshot(1);
        state.remove("connection-a", 1);

        assertEquals(List.of("connection-a", "connection-b"), snapshot);
        assertEquals(List.of("connection-b"), state.snapshot(1));
        assertEquals(List.of("connection-c"), state.snapshot(2));
        assertEquals(Set.of(1, 2), state.partitionsWithConnections());
        assertEquals(List.of(0, 1, 2), state.partitionsForSnapshot());
    }

    @Test
    void gracefulShutdownRequiresACompleteDrainAndBlocksFutureAdmission() {
        var state = new CaptureRoutingState(3, List.of(0, 1));
        state.register("connection", 0);
        assertThrows(IllegalStateException.class, state::beginGracefulShutdown);
        state.remove("connection", 0);

        assertEquals(List.of(0, 1, 2), state.beginGracefulShutdown());
        assertEquals(List.of(), state.assignedPartitions());
        assertThrows(IllegalStateException.class, () -> state.admitConnection("new"));
    }

    @Test
    void configuredWidthCapsEachLiveKafkaAssignment() {
        var state = new CaptureRoutingState(8, 2, List.of(0, 1, 2, 3));

        assertEquals(List.of(0, 1), state.assignedPartitions());
        state.replaceAssignedPartitions(List.of(4, 5, 6));
        assertEquals(List.of(4, 5), state.assignedPartitions());
    }

    @Test
    void duplicateOrInvalidTransitionsFailLoudly() {
        assertThrows(
            IllegalArgumentException.class,
            () -> new CaptureRoutingState(2, List.of(0, 0))
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> new CaptureRoutingState(2, List.of(2))
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> new CaptureRoutingState(2, 3, List.of())
        );

        var state = new CaptureRoutingState(2, List.of(0, 1));
        state.register("connection", 1);
        assertThrows(IllegalStateException.class, () -> state.register("connection", 1));
        assertThrows(IllegalStateException.class, () -> state.remove("connection", 0));
        state.remove("connection", 1);
        assertThrows(IllegalStateException.class, () -> state.remove("connection", 1));
        assertThrows(IllegalStateException.class, () -> state.partitionFor("connection"));
    }
}
