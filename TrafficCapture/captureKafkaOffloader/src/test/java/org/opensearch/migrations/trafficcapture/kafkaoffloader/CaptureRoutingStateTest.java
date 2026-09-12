package org.opensearch.migrations.trafficcapture.kafkaoffloader;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

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
    void initialManifestEstablishesBrokerTimeAndLaterManifestsReplaceItWhenFresh() {
        var state = new CaptureRoutingState(ACTIVATION_ID, 1);
        var assignment = state.prepareAssignment(List.of(0));
        var initial = only(state.prepareInitialManifests(assignment));

        state.acceptManifestLogAppendTime(initial, 1_000L, Duration.ofSeconds(60));
        assertEquals(1_000L, state.lastAcceptedManifestLogAppendTime("activation:1", 0));

        state.activateAssignment(assignment);
        var later = only(state.preparePeriodicManifests());
        state.acceptManifestLogAppendTime(later, 60_999L, Duration.ofSeconds(60));
        assertEquals(60_999L, state.lastAcceptedManifestLogAppendTime("activation:1", 0));
    }

    @Test
    void manifestAtExpirationBoundaryIsRejectedWithoutChangingBaseline() {
        var state = new CaptureRoutingState(ACTIVATION_ID, 1);
        var assignment = state.prepareAssignment(List.of(0));
        var initial = only(state.prepareInitialManifests(assignment));
        state.acceptManifestLogAppendTime(initial, 1_000L, Duration.ofSeconds(60));
        state.activateAssignment(assignment);

        var late = only(state.preparePeriodicManifests());
        assertThrows(
            IllegalStateException.class,
            () -> state.acceptManifestLogAppendTime(late, 61_000L, Duration.ofSeconds(60))
        );
        assertEquals(1_000L, state.lastAcceptedManifestLogAppendTime("activation:1", 0));
    }

    @Test
    void backwardBrokerTimeMovementBecomesTheNewAcceptedBaseline() {
        var state = new CaptureRoutingState(ACTIVATION_ID, 1);
        var assignment = state.prepareAssignment(List.of(0));
        var initial = only(state.prepareInitialManifests(assignment));
        state.acceptManifestLogAppendTime(initial, 10_000L, Duration.ofSeconds(60));
        state.activateAssignment(assignment);

        var later = only(state.preparePeriodicManifests());
        state.acceptManifestLogAppendTime(later, 9_000L, Duration.ofSeconds(60));
        assertEquals(9_000L, state.lastAcceptedManifestLogAppendTime("activation:1", 0));
    }

    @Test
    void writerPartitionsMaintainIndependentBrokerTimeBaselines() {
        var state = new CaptureRoutingState(ACTIVATION_ID, 2);
        var assignment = state.prepareAssignment(List.of(0, 1));
        var manifests = state.prepareInitialManifests(assignment);

        state.acceptManifestLogAppendTime(manifests.get(0), 1_000L, Duration.ofSeconds(60));
        state.acceptManifestLogAppendTime(manifests.get(1), 2_000L, Duration.ofSeconds(60));

        assertEquals(1_000L, state.lastAcceptedManifestLogAppendTime("activation:1", 0));
        assertEquals(2_000L, state.lastAcceptedManifestLogAppendTime("activation:1", 1));
    }

    @Test
    void criticalMutationTrafficMustBeAcknowledgedWithinTheManifestExpirationInterval() {
        var state = new CaptureRoutingState(ACTIVATION_ID, 1);
        var assignment = state.prepareAssignment(List.of(0));
        var initial = only(state.prepareInitialManifests(assignment));
        state.acceptManifestLogAppendTime(initial, 1_000L, Duration.ofSeconds(60));
        state.activateAssignment(assignment);
        var route = state.admitConnection("connection");

        state.validateCriticalMutationTrafficAcknowledgement(
            route,
            60_999L,
            Duration.ofSeconds(60)
        );
        state.validateCriticalMutationTrafficAcknowledgement(
            route,
            900L,
            Duration.ofSeconds(60)
        );
        assertThrows(
            IllegalStateException.class,
            () -> state.validateCriticalMutationTrafficAcknowledgement(
                route,
                61_000L,
                Duration.ofSeconds(60)
            )
        );
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

        state.acceptTrafficSubmission(oldRoute, true);
        state.removeAfterTerminalAcknowledgement(oldRoute);
        assertEquals(List.of(), state.snapshot(oldRoute.writerNodeId(), 0));
        assertEquals(List.of("connection"), state.snapshot(newRoute.writerNodeId(), 0));
    }

    @Test
    void aDrainedOldWriterPreparesOneFinalEmptyManifestAndThenRetires() {
        var state = activeState(1, List.of(0));
        var oldWriter = state.currentWriterNodeId();

        var replacement = state.prepareAssignment(List.of(0));
        state.prepareInitialManifests(replacement);
        state.activateAssignment(replacement);

        var retirement = only(state.prepareDrainedWriterRetirements());
        assertEquals(oldWriter, retirement.writerNodeId());
        assertEquals(0, retirement.partition());
        assertEquals(List.of(), retirement.connectionIds());
        assertEquals(CaptureRoutingState.WriterStatus.RETIRING, state.writerStatus(oldWriter, 0));
        assertEquals(List.of(), state.prepareDrainedWriterRetirements());

        state.completeWriterRetirement(retirement);
        assertEquals(CaptureRoutingState.WriterStatus.RETIRED, state.writerStatus(oldWriter, 0));
        assertEquals(false, state.allWriterPartitionsRetired());
        assertEquals(List.of(), state.preparePeriodicManifests()
            .stream()
            .filter(manifest -> manifest.writerNodeId().equals(oldWriter))
            .toList());
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
    void connectionRegistrationAndRetirementRemainLinearizableWhileManifestsRace()
        throws Exception {
        var state = activeState(1, List.of(0));
        var executor = Executors.newFixedThreadPool(2);
        try {
            for (int iteration = 0; iteration < 500; ++iteration) {
                var connectionId = "connection-" + iteration;
                var admissionRace = race(
                    executor,
                    () -> state.admitConnection(connectionId),
                    () -> only(state.preparePeriodicManifests())
                );
                var route = admissionRace.first();
                var admissionManifest = admissionRace.second();

                assertTrue(
                    admissionManifest.connectionIds().equals(List.of())
                        || admissionManifest.connectionIds().equals(List.of(connectionId))
                );
                assertEquals(
                    admissionManifest.manifestCycle() + 1,
                    route.manifestCycle()
                );

                state.acceptTrafficSubmission(route, true);
                var retirementRace = race(
                    executor,
                    () -> {
                        state.removeAfterTerminalAcknowledgement(route);
                        return null;
                    },
                    () -> only(state.preparePeriodicManifests())
                );
                var retirementManifest = retirementRace.second();

                assertTrue(
                    retirementManifest.connectionIds().equals(List.of())
                        || retirementManifest.connectionIds().equals(List.of(connectionId))
                );
                assertEquals(List.of(), state.snapshot(route.writerNodeId(), route.partition()));
                assertEquals(
                    retirementManifest.manifestCycle() + 1,
                    route.manifestCycle()
                );
            }
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void removalRequiresTheExactImmutableConnectionRoute() {
        var state = activeState(1, List.of(0));
        var route = state.admitConnection("connection");

        state.acceptTrafficSubmission(route, true);
        state.removeAfterTerminalAcknowledgement(route);

        assertEquals(0, state.size());
        assertThrows(
            IllegalStateException.class,
            () -> state.removeAfterTerminalAcknowledgement(route)
        );
    }

    @Test
    void terminalSubmissionRejectsEveryLaterTrafficSubmission() {
        var state = activeState(1, List.of(0));
        var route = state.admitConnection("connection");

        state.acceptTrafficSubmission(route, false);
        state.acceptTrafficSubmission(route, true);

        assertThrows(
            IllegalStateException.class,
            () -> state.acceptTrafficSubmission(route, false)
        );
        assertEquals(List.of("connection"), state.snapshot(route.writerNodeId(), route.partition()));
        state.removeAfterTerminalAcknowledgement(route);
    }

    @Test
    void onlyAnUnpublishedConnectionMayBeAbandoned() {
        var state = activeState(1, List.of(0));
        var unpublished = state.admitConnection("unpublished");
        state.abandonUnpublishedConnection(unpublished);

        var published = state.admitConnection("published");
        state.acceptTrafficSubmission(published, false);

        assertThrows(
            IllegalStateException.class,
            () -> state.abandonUnpublishedConnection(published)
        );
    }

    @Test
    void shutdownRemovesNewConnectionEligibility() {
        var state = activeState(1, List.of(0));
        state.beginShutdown();

        assertEquals(List.of(), state.assignedPartitions());
        assertThrows(IllegalStateException.class, () -> state.admitConnection("new"));
    }

    @Test
    void orderlyRetirementWaitsForConnectionsAndMakesCurrentWritersDraining() {
        var state = activeState(1, List.of(0));
        var route = state.admitConnection("connection");
        var noConnections = state.whenNoConnections();

        assertThrows(IllegalStateException.class, state::beginOrderlyRetirement);
        assertEquals(false, noConnections.isDone());

        state.acceptTrafficSubmission(route, true);
        state.removeAfterTerminalAcknowledgement(route);
        assertTrue(noConnections.isDone());

        state.beginOrderlyRetirement();
        assertEquals(List.of(), state.assignedPartitions());
        assertEquals(
            CaptureRoutingState.WriterStatus.DRAINING,
            state.writerStatus(route.writerNodeId(), route.partition())
        );
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

    private record RaceResult<T, U>(T first, U second) {}

    private static <T, U> RaceResult<T, U> race(
        ExecutorService executor,
        Callable<T> first,
        Callable<U> second
    ) throws Exception {
        var start = new CyclicBarrier(3);
        var firstResult = executor.submit(() -> {
            start.await();
            return first.call();
        });
        var secondResult = executor.submit(() -> {
            start.await();
            return second.call();
        });
        start.await();
        return new RaceResult<>(
            firstResult.get(5, TimeUnit.SECONDS),
            secondResult.get(5, TimeUnit.SECONDS)
        );
    }
}
