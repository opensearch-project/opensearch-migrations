package org.opensearch.migrations.trafficcapture.kafkaoffloader;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CaptureRoutingStateTest {
    private static final String ACTIVATION_ID = "activation";

    @Test
    void assignmentIsNotUsableUntilItsInitialManifestBoundaryIsPreparedAndActivated() {
        var state = new CaptureRoutingState(ACTIVATION_ID, 3);
        var assignment = state.prepareAssignment(List.of(0, 2));

        assertEquals("activation:1", assignment.writerNodeId());
        assertThrows(IllegalStateException.class, () -> state.admitConnection("too-early"));

        var initialManifests = state.prepareInitialManifests(assignment);
        assertEquals(List.of(0, 2), initialManifests.stream()
            .map(CaptureRoutingState.PreparedManifest::partition)
            .toList());
        assertTrue(initialManifests.stream().allMatch(manifest -> manifest.manifestCycle() == 0));
        assertTrue(initialManifests.stream().allMatch(manifest -> manifest.connectionIds().isEmpty()));

        state.activateAssignment(assignment);
        var route = state.admitConnection("accepted");
        assertEquals("activation:1", route.writerNodeId());
        assertTrue(List.of(0, 2).contains(route.partition()));
        assertEquals(1, route.manifestCycle());
    }

    @Test
    void replacementAssignmentChangesOnlyNewConnectionRoutes() {
        var state = activeState(4, List.of(0, 1));
        var existing = state.admitConnection("same-local-id");

        var replacement = state.prepareAssignment(List.of(2, 3));
        state.prepareInitialManifests(replacement);
        state.activateAssignment(replacement);
        var later = state.admitConnection("same-local-id");

        assertEquals("activation:1", existing.writerNodeId());
        assertTrue(List.of(0, 1).contains(existing.partition()));
        assertEquals("activation:2", later.writerNodeId());
        assertTrue(List.of(2, 3).contains(later.partition()));
        assertNotEquals(existing.writerNodeId(), later.writerNodeId());
        assertEquals(Set.of("activation:1", "activation:2"), state.writerNodeIds());
    }

    @Test
    void oldAndNewWriterRegistriesRemainIndependentOnTheSamePartition() {
        var state = activeState(1, List.of(0));
        var oldRoute = state.admitConnection("connection");

        var replacement = state.prepareAssignment(List.of(0));
        state.prepareInitialManifests(replacement);
        state.activateAssignment(replacement);
        var newRoute = state.admitConnection("connection");

        assertEquals(List.of("connection"), state.snapshot(oldRoute.writerNodeId(), 0));
        assertEquals(List.of("connection"), state.snapshot(newRoute.writerNodeId(), 0));

        state.remove(oldRoute);
        assertEquals(List.of(), state.snapshot(oldRoute.writerNodeId(), 0));
        assertEquals(List.of("connection"), state.snapshot(newRoute.writerNodeId(), 0));
    }

    @Test
    void manifestCopyAndCycleAdvanceShareOneBoundary() {
        var state = activeState(1, List.of(0));
        var first = state.admitConnection("first");
        assertEquals(1, first.manifestCycle());

        var manifest = only(state.preparePeriodicManifests());
        assertEquals(1, manifest.manifestCycle());
        assertEquals(List.of("first"), manifest.connectionIds());
        assertEquals(2, first.manifestCycle());

        var second = state.admitConnection("second");
        assertEquals(2, second.manifestCycle());
        var nextManifest = only(state.preparePeriodicManifests());
        assertEquals(2, nextManifest.manifestCycle());
        assertEquals(List.of("first", "second"), nextManifest.connectionIds());
    }

    @Test
    void removalRequiresTheExactImmutableConnectionRoute() {
        var state = activeState(1, List.of(0));
        var route = state.admitConnection("connection");

        state.remove(route);

        assertEquals(0, state.size());
        assertThrows(IllegalStateException.class, () -> state.remove(route));
    }

    @Test
    void shutdownRemovesNewConnectionEligibility() {
        var state = activeState(1, List.of(0));
        state.beginShutdown();

        assertEquals(List.of(), state.assignedPartitions());
        assertThrows(IllegalStateException.class, () -> state.admitConnection("new"));
    }

    @Test
    void invalidAssignmentsFailLoudly() {
        var state = new CaptureRoutingState(ACTIVATION_ID, 2);

        assertThrows(IllegalArgumentException.class, () -> state.prepareAssignment(List.of()));
        assertThrows(IllegalArgumentException.class, () -> state.prepareAssignment(List.of(0, 0)));
        assertThrows(IllegalArgumentException.class, () -> state.prepareAssignment(List.of(2)));
    }

    private static CaptureRoutingState activeState(int partitionCount, List<Integer> partitions) {
        var state = new CaptureRoutingState(ACTIVATION_ID, partitionCount);
        var assignment = state.prepareAssignment(partitions);
        state.prepareInitialManifests(assignment);
        state.activateAssignment(assignment);
        return state;
    }

    private static <T> T only(List<T> values) {
        assertEquals(1, values.size());
        return values.get(0);
    }
}
