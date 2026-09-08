package org.opensearch.migrations.trafficcapture.kafkaoffloader;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.opensearch.migrations.trafficcapture.kafkaoffloader.CaptureGroupProtocol.AdmissionPhase;
import org.opensearch.migrations.trafficcapture.kafkaoffloader.CaptureGroupProtocol.Footprint;
import org.opensearch.migrations.trafficcapture.kafkaoffloader.CaptureGroupProtocol.Subscription;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class CaptureGroupAssignmentPlannerTest {
    private static final Duration DEBOUNCE = Duration.ofSeconds(2);

    @Test
    void activationRequiresAnExactCurrentConfirmedPeerVisibilityEcho() {
        var planner = new CaptureGroupAssignmentPlanner(1, 1, DEBOUNCE);
        var candidateFootprint = Footprint.known(2, List.of(0, 1));
        var staleFootprint = Footprint.known(1, List.of(0));
        var observerFootprint = Footprint.known(3, List.of(0, 1));

        var plan = planner.plan(
            Map.of(
                "member-a",
                subscription(
                    "node-a",
                    AdmissionPhase.PROBATIONARY,
                    candidateFootprint,
                    Map.of(),
                    Map.of("node-b", candidateFootprint.identity("node-a"))
                ),
                "member-b",
                subscription(
                    "node-b",
                    AdmissionPhase.ACTIVE,
                    observerFootprint,
                    Map.of("node-a", staleFootprint.identity("node-a")),
                    Map.of()
                )
            ),
            0
        );

        assertEquals(AdmissionPhase.PROBATIONARY, plan.table().members().get("node-a").effectivePhase());
        assertEquals(
            Map.of("node-a", java.util.Set.of(), "node-b", java.util.Set.of()),
            plan.confirmedPeerVisibilityByCandidate()
        );
        assertNull(plan.activationDeadlineNanos());
    }

    @Test
    void confirmedPeerVisibilityIsDirectional() {
        var planner = new CaptureGroupAssignmentPlanner(1, 1, Duration.ZERO);
        var nodeA = Footprint.known(1, List.of(0));
        var nodeB = Footprint.known(1, List.of(0));

        var plan = planner.plan(
            subscriptions(
                subscription(
                    "node-a",
                    AdmissionPhase.PROBATIONARY,
                    nodeA,
                    Map.of("node-b", nodeB.identity("node-b")),
                    Map.of()
                ),
                subscription(
                    "node-b",
                    AdmissionPhase.PROBATIONARY,
                    nodeB,
                    Map.of(),
                    Map.of("node-a", nodeB.identity("node-b"))
                )
            ),
            0
        );

        assertEquals(
            java.util.Set.of("node-a"),
            plan.confirmedPeerVisibilityByCandidate().get("node-b")
        );
        assertEquals(
            java.util.Set.of(),
            plan.confirmedPeerVisibilityByCandidate().get("node-a")
        );
        assertEquals(java.util.Set.of("node-b"), plan.promotedNodeIds());
        assertEquals(AdmissionPhase.PROBATIONARY, plan.table().members().get("node-a").effectivePhase());
        assertEquals(AdmissionPhase.ACTIVE, plan.table().members().get("node-b").effectivePhase());
    }

    @Test
    void laterEligibleMembersJoinTheCohortWithoutExtendingItsDeadline() {
        var planner = new CaptureGroupAssignmentPlanner(2, 1, DEBOUNCE);
        var nodeA = Footprint.known(1, List.of(0, 1));
        var nodeB = Footprint.known(1, List.of(0, 1));
        var first = subscriptions(
            probationaryWithConfirmedObserver("node-a", nodeA, "node-b"),
            probationaryObserver("node-b", nodeB, "node-a", nodeA)
        );

        var initial = planner.plan(first, 0);
        assertEquals(DEBOUNCE.toNanos(), initial.activationDeadlineNanos());

        var bothEligible = subscriptions(
            probationaryWithVisibility(
                "node-a",
                nodeA,
                "node-b",
                nodeB,
                Map.of()
            ),
            probationaryWithVisibility(
                "node-b",
                nodeB,
                "node-a",
                nodeA,
                Map.of("node-a", nodeA.identity("node-a"))
            )
        );
        var joined = planner.plan(bothEligible, Duration.ofSeconds(1).toNanos());
        assertEquals(DEBOUNCE.toNanos(), joined.activationDeadlineNanos());

        var promoted = planner.plan(bothEligible, DEBOUNCE.toNanos());
        assertEquals(
            java.util.Set.of("node-a", "node-b"),
            promoted.promotedNodeIds()
        );
        assertEquals(AdmissionPhase.ACTIVE, promoted.table().members().get("node-a").effectivePhase());
        assertEquals(AdmissionPhase.ACTIVE, promoted.table().members().get("node-b").effectivePhase());
        assertNull(promoted.activationDeadlineNanos());
    }

    @Test
    void losingRequiredPeerVisibilityRevokesEligibilityBeforePromotion() {
        var planner = new CaptureGroupAssignmentPlanner(1, 1, DEBOUNCE);
        var nodeA = Footprint.known(1, List.of(0));
        var nodeB = Footprint.known(1, List.of(0));

        var visible = subscriptions(
            probationaryWithConfirmedObserver("node-a", nodeA, "node-b"),
            probationaryObserver("node-b", nodeB, "node-a", nodeA)
        );
        planner.plan(visible, 0);

        var lostObserver = planner.plan(
            Map.of("member-a", visible.get("member-node-a")),
            DEBOUNCE.toNanos()
        );

        assertEquals(
            AdmissionPhase.PROBATIONARY,
            lostObserver.table().members().get("node-a").effectivePhase()
        );
        assertEquals(java.util.Set.of(), lostObserver.promotedNodeIds());
        assertNull(lostObserver.activationDeadlineNanos());
    }

    @Test
    void activeIsMonotonicEvenAfterPeerVisibilityFalls() {
        var planner = new CaptureGroupAssignmentPlanner(2, 2, DEBOUNCE);
        var active = subscription(
            "node-a",
            AdmissionPhase.ACTIVE,
            Footprint.known(1, List.of(0)),
            Map.of(),
            Map.of()
        );

        var plan = planner.plan(Map.of("member-a", active), 0);

        assertEquals(AdmissionPhase.ACTIVE, plan.table().members().get("node-a").effectivePhase());
        assertEquals(
            java.util.Set.of(),
            plan.confirmedPeerVisibilityByCandidate().get("node-a")
        );
        assertNull(plan.activationDeadlineNanos());
    }

    @Test
    void minimumActiveProxyCountIncludesThePromotedCandidate() {
        var planner = new CaptureGroupAssignmentPlanner(1, 0, Duration.ZERO);
        var candidate = subscription(
            "node-a",
            AdmissionPhase.PROBATIONARY,
            Footprint.known(1, List.of(0)),
            Map.of(),
            Map.of()
        );

        var plan = planner.plan(Map.of("member-a", candidate), 0);

        assertEquals(java.util.Set.of("node-a"), plan.promotedNodeIds());
        assertEquals(AdmissionPhase.ACTIVE, plan.table().members().get("node-a").effectivePhase());
    }

    private static Map<String, Subscription> subscriptions(Subscription... subscriptions) {
        var result = new LinkedHashMap<String, Subscription>();
        for (var subscription : subscriptions) {
            result.put("member-" + subscription.nodeId(), subscription);
        }
        return Map.copyOf(result);
    }

    private static Subscription probationaryWithConfirmedObserver(
        String nodeId,
        Footprint footprint,
        String observerNodeId
    ) {
        return subscription(
            nodeId,
            AdmissionPhase.PROBATIONARY,
            footprint,
            Map.of(),
            Map.of(observerNodeId, footprint.identity(nodeId))
        );
    }

    private static Subscription probationaryObserver(
        String nodeId,
        Footprint footprint,
        String observedNodeId,
        Footprint observedFootprint
    ) {
        return subscription(
            nodeId,
            AdmissionPhase.PROBATIONARY,
            footprint,
            Map.of(observedNodeId, observedFootprint.identity(observedNodeId)),
            Map.of()
        );
    }

    private static Subscription probationaryWithVisibility(
        String nodeId,
        Footprint footprint,
        String observerNodeId,
        Footprint observerFootprint,
        Map<String, CaptureGroupProtocol.FootprintIdentity> additionalObservations
    ) {
        var observations = new LinkedHashMap<>(additionalObservations);
        observations.put(observerNodeId, observerFootprint.identity(observerNodeId));
        return subscription(
            nodeId,
            AdmissionPhase.PROBATIONARY,
            footprint,
            observations,
            Map.of(observerNodeId, footprint.identity(nodeId))
        );
    }

    private static Subscription subscription(
        String nodeId,
        AdmissionPhase phase,
        Footprint footprint,
        Map<String, CaptureGroupProtocol.FootprintIdentity> observations,
        Map<String, CaptureGroupProtocol.FootprintIdentity> confirmedPeerVisibility
    ) {
        return new Subscription(nodeId, phase, footprint, observations, confirmedPeerVisibility);
    }
}
