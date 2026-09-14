package org.opensearch.migrations.trafficcapture.kafkaoffloader;

import java.time.Duration;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CaptureRoutingStateTest {
    private static final String ACTIVATION_ID = "activation";
    private static final Duration EXPIRATION = Duration.ofSeconds(30);

    @Test
    void assignmentIsNotUsableUntilEveryInitialHeartbeatIsAcknowledged() {
        var state = new CaptureRoutingState(ACTIVATION_ID, 3);
        var assignment = state.prepareAssignment(List.of(0, 2));

        assertEquals(1, assignment.assignmentSequence());
        assertEquals("activation:1", assignment.writerNodeId());
        assertEquals(List.of(0, 2), assignment.partitions());
        assertThrows(IllegalStateException.class, () -> state.routeNewConnection("too-early"));

        state.acceptHeartbeatLogAppendTime(assignment.writerPartitions().get(0), 1_000L, EXPIRATION);
        assertThrows(
            CorruptedCaptureStateException.class,
            () -> state.activateAssignment(assignment)
        );

        state.acceptHeartbeatLogAppendTime(assignment.writerPartitions().get(1), 2_000L, EXPIRATION);
        state.activateAssignment(assignment);

        var route = state.routeNewConnection("accepted");
        assertEquals("activation:1", route.writerNodeId());
        assertTrue(List.of(0, 2).contains(route.partition()));
    }

    @Test
    void everyAssignmentGetsANewWriterIdentity() {
        var state = new CaptureRoutingState(ACTIVATION_ID, 2);

        var first = state.prepareAssignment(List.of(0));
        acceptInitialHeartbeats(state, first);
        state.activateAssignment(first);

        var second = state.prepareAssignment(List.of(1));
        acceptInitialHeartbeats(state, second);
        state.activateAssignment(second);

        assertEquals("activation:1", first.writerNodeId());
        assertEquals("activation:2", second.writerNodeId());
        assertNotEquals(first.writerNodeId(), second.writerNodeId());
        assertEquals(Set.of(first.writerNodeId(), second.writerNodeId()), state.writerNodeIds());
    }

    @Test
    void writerPartitionsMaintainIndependentContinuousHeartbeatBaselines() {
        var state = new CaptureRoutingState(ACTIVATION_ID, 2);
        var assignment = state.prepareAssignment(List.of(0, 1));
        var first = assignment.writerPartitions().get(0);
        var second = assignment.writerPartitions().get(1);

        state.acceptHeartbeatLogAppendTime(first, 1_000L, EXPIRATION);
        state.acceptHeartbeatLogAppendTime(second, 2_000L, EXPIRATION);
        state.activateAssignment(assignment);

        state.acceptHeartbeatLogAppendTime(first, 30_999L, EXPIRATION);
        state.acceptHeartbeatLogAppendTime(second, 1_500L, EXPIRATION);

        assertEquals(30_999L, state.lastAcceptedHeartbeatLogAppendTime(first));
        assertEquals(1_500L, state.lastAcceptedHeartbeatLogAppendTime(second));
    }

    @Test
    void heartbeatAtExpirationBoundaryIsRejectedWithoutChangingBaseline() {
        var state = activeState(1, List.of(0), 1_000L);
        var writerPartition = currentWriterPartition(state, 0);

        assertThrows(
            IllegalStateException.class,
            () -> state.acceptHeartbeatLogAppendTime(writerPartition, 31_000L, EXPIRATION)
        );
        assertEquals(1_000L, state.lastAcceptedHeartbeatLogAppendTime(writerPartition));
    }

    @Test
    void nonPositiveBrokerTimestampsAreRejected() {
        var state = new CaptureRoutingState(ACTIVATION_ID, 1);
        var assignment = state.prepareAssignment(List.of(0));
        var writerPartition = only(assignment.writerPartitions());

        assertThrows(
            IllegalStateException.class,
            () -> state.acceptHeartbeatLogAppendTime(writerPartition, 0, EXPIRATION)
        );
    }

    @Test
    void criticalMutationTrafficMustBeFreshRelativeToItsWriterPartitionHeartbeat() {
        var state = activeState(1, List.of(0), 1_000L);
        var route = state.routeNewConnection("connection");

        state.validateCriticalMutationTrafficAcknowledgement(route, 30_999L, EXPIRATION);
        state.validateCriticalMutationTrafficAcknowledgement(route, 900L, EXPIRATION);

        assertThrows(
            IllegalStateException.class,
            () -> state.validateCriticalMutationTrafficAcknowledgement(route, 31_000L, EXPIRATION)
        );
        assertThrows(
            IllegalStateException.class,
            () -> state.validateCriticalMutationTrafficAcknowledgement(route, 0, EXPIRATION)
        );
    }

    @Test
    void replacementAssignmentChangesOnlyNewConnectionRoutes() {
        var state = activeState(4, List.of(0, 1), 1_000L);
        var existing = state.routeNewConnection("same-local-id");

        var replacement = state.prepareAssignment(List.of(2, 3));
        acceptInitialHeartbeats(state, replacement);
        state.activateAssignment(replacement);
        var later = state.routeNewConnection("same-local-id");

        assertEquals("activation:1", existing.writerNodeId());
        assertTrue(List.of(0, 1).contains(existing.partition()));
        assertEquals("activation:2", later.writerNodeId());
        assertTrue(List.of(2, 3).contains(later.partition()));
    }

    @Test
    void connectionIdentityIsLocalToWriterIdentity() {
        var state = activeState(1, List.of(0), 1_000L);
        var oldRoute = state.routeNewConnection("connection");
        assertThrows(
            CorruptedCaptureStateException.class,
            () -> state.routeNewConnection("connection")
        );

        var replacement = state.prepareAssignment(List.of(0));
        acceptInitialHeartbeats(state, replacement);
        state.activateAssignment(replacement);
        var newRoute = state.routeNewConnection("connection");

        assertNotEquals(oldRoute.writerNodeId(), newRoute.writerNodeId());
        assertEquals(2, state.size());

        state.acceptTrafficSubmission(oldRoute, true);
        state.removeAfterTerminalAcknowledgement(oldRoute);
        assertEquals(1, state.size());
        state.acceptTrafficSubmission(newRoute, false);
    }

    @Test
    void connectionRemainsRegisteredUntilTerminalRecordAcknowledgement() {
        var state = activeState(1, List.of(0), 1_000L);
        var route = state.routeNewConnection("connection");
        var noConnections = state.whenNoConnections();

        state.acceptTrafficSubmission(route, false);
        assertFalse(noConnections.isDone());
        assertThrows(
            CorruptedCaptureStateException.class,
            () -> state.removeAfterTerminalAcknowledgement(route)
        );

        state.acceptTrafficSubmission(route, true);
        assertFalse(noConnections.isDone());
        assertThrows(
            CorruptedCaptureStateException.class,
            () -> state.acceptTrafficSubmission(route, false)
        );

        state.removeAfterTerminalAcknowledgement(route);
        assertTrue(noConnections.isDone());
        assertEquals(0, state.size());
    }

    @Test
    void onlyAConnectionWithNoAcceptedPublicationMayBeAbandoned() {
        var state = activeState(1, List.of(0), 1_000L);
        var unpublished = state.routeNewConnection("unpublished");
        state.abandonUnpublishedConnection(unpublished);

        var published = state.routeNewConnection("published");
        state.acceptTrafficSubmission(published, false);

        assertThrows(
            CorruptedCaptureStateException.class,
            () -> state.abandonUnpublishedConnection(published)
        );
    }

    @Test
    void drainingWriterRetiresLocallyOnlyAfterItsConnectionsAreGone() {
        var state = activeState(1, List.of(0), 1_000L);
        var oldRoute = state.routeNewConnection("connection");
        var oldWriterPartition = oldRoute.writerPartition();

        var replacement = state.prepareAssignment(List.of(0));
        acceptInitialHeartbeats(state, replacement);
        state.activateAssignment(replacement);

        assertEquals(
            CaptureRoutingState.WriterStatus.DRAINING,
            state.writerStatus(oldRoute.writerNodeId(), oldRoute.partition())
        );
        assertEquals(List.of(), state.prepareDrainedWriterRetirements());

        state.acceptTrafficSubmission(oldRoute, true);
        state.removeAfterTerminalAcknowledgement(oldRoute);
        assertEquals(List.of(oldWriterPartition), state.prepareDrainedWriterRetirements());
        assertEquals(
            CaptureRoutingState.WriterStatus.RETIRING,
            state.writerStatus(oldRoute.writerNodeId(), oldRoute.partition())
        );

        state.completeWriterRetirement(oldWriterPartition);
        assertEquals(
            CaptureRoutingState.WriterStatus.RETIRED,
            state.writerStatus(oldRoute.writerNodeId(), oldRoute.partition())
        );
        assertFalse(state.allWriterPartitionsRetired());
    }

    @Test
    void orderlyRetirementRequiresAnEmptyConnectionRegistry() {
        var state = activeState(1, List.of(0), 1_000L);
        var route = state.routeNewConnection("connection");

        assertThrows(CorruptedCaptureStateException.class, state::beginOrderlyRetirement);

        state.acceptTrafficSubmission(route, true);
        state.removeAfterTerminalAcknowledgement(route);
        state.beginOrderlyRetirement();

        assertEquals(List.of(), state.assignedPartitions());
        assertEquals(
            CaptureRoutingState.WriterStatus.DRAINING,
            state.writerStatus(route.writerNodeId(), route.partition())
        );
        assertThrows(IllegalStateException.class, () -> state.routeNewConnection("new"));

        var retirement = only(state.prepareDrainedWriterRetirements());
        state.completeWriterRetirement(retirement);
        assertTrue(state.allWriterPartitionsRetired());
    }

    @Test
    void immediateShutdownRemovesNewConnectionEligibility() {
        var state = activeState(1, List.of(0), 1_000L);
        state.beginShutdown();

        assertEquals(List.of(), state.assignedPartitions());
        assertThrows(IllegalStateException.class, () -> state.routeNewConnection("new"));
    }

    @Test
    void invalidConstructionAssignmentsAndDurationsFailLoudly() {
        assertThrows(IllegalArgumentException.class, () -> new CaptureRoutingState("", 1));
        assertThrows(IllegalArgumentException.class, () -> new CaptureRoutingState(ACTIVATION_ID, 0));

        var state = new CaptureRoutingState(ACTIVATION_ID, 2);
        assertThrows(IllegalArgumentException.class, () -> state.prepareAssignment(List.of()));
        assertThrows(IllegalArgumentException.class, () -> state.prepareAssignment(List.of(0, 0)));
        assertThrows(IllegalArgumentException.class, () -> state.prepareAssignment(List.of(2)));

        var assignment = state.prepareAssignment(List.of(0));
        var writerPartition = only(assignment.writerPartitions());
        assertThrows(
            IllegalArgumentException.class,
            () -> state.acceptHeartbeatLogAppendTime(writerPartition, 1, Duration.ZERO)
        );
    }

    private static CaptureRoutingState activeState(
        int partitionCount,
        List<Integer> partitions,
        long initialLogAppendTime
    ) {
        var state = new CaptureRoutingState(ACTIVATION_ID, partitionCount);
        var assignment = state.prepareAssignment(partitions);
        for (var writerPartition : assignment.writerPartitions()) {
            state.acceptHeartbeatLogAppendTime(writerPartition, initialLogAppendTime, EXPIRATION);
        }
        state.activateAssignment(assignment);
        return state;
    }

    private static void acceptInitialHeartbeats(
        CaptureRoutingState state,
        CaptureRoutingState.PendingAssignment assignment
    ) {
        long logAppendTime = 1_000L;
        for (var writerPartition : assignment.writerPartitions()) {
            state.acceptHeartbeatLogAppendTime(writerPartition, logAppendTime++, EXPIRATION);
        }
    }

    private static CaptureRoutingState.WriterPartition currentWriterPartition(
        CaptureRoutingState state,
        int partition
    ) {
        return new CaptureRoutingState.WriterPartition(state.currentWriterNodeId(), partition);
    }

    private static <T> T only(List<T> values) {
        assertEquals(1, values.size());
        return values.get(0);
    }
}
