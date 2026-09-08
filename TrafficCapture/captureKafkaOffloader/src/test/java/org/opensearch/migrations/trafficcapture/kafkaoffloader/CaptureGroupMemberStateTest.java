package org.opensearch.migrations.trafficcapture.kafkaoffloader;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.opensearch.migrations.trafficcapture.kafkaoffloader.CaptureGroupProtocol.AdmissionPhase;
import org.opensearch.migrations.trafficcapture.kafkaoffloader.CaptureGroupProtocol.AssignmentTable;
import org.opensearch.migrations.trafficcapture.kafkaoffloader.CaptureGroupProtocol.Footprint;
import org.opensearch.migrations.trafficcapture.kafkaoffloader.CaptureGroupProtocol.MemberRow;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CaptureGroupMemberStateTest {
    private static final Duration VISIBILITY_INTERVAL = Duration.ofSeconds(10);

    @Test
    void confirmationStartsOnlyAfterThePeerEchoesThisExactFootprint() {
        var nodeA = Footprint.known(1, List.of(0, 1));
        var nodeB = Footprint.known(1, List.of(0, 1));
        var state = new CaptureGroupMemberState(
            "node-a",
            nodeA,
            VISIBILITY_INTERVAL
        );

        var first = state.installAssignment(
            table(
                row(
                    "node-a",
                    AdmissionPhase.PROBATIONARY,
                    AdmissionPhase.PROBATIONARY,
                    nodeA,
                    Map.of()
                ),
                row(
                    "node-b",
                    AdmissionPhase.PROBATIONARY,
                    AdmissionPhase.PROBATIONARY,
                    nodeB,
                    Map.of()
                )
            ),
            0
        );
        assertEquals(AdmissionPhase.PROBATIONARY, first.subscription().advertisedPhase());
        assertEquals(Map.of("node-b", nodeB.identity("node-b")), first.subscription().observedFootprints());
        assertTrue(first.metadataChanged());

        var echoed = state.installAssignment(
            table(
                row(
                    "node-a",
                    AdmissionPhase.PROBATIONARY,
                    AdmissionPhase.PROBATIONARY,
                    nodeA,
                    Map.of()
                ),
                row(
                    "node-b",
                    AdmissionPhase.PROBATIONARY,
                    AdmissionPhase.PROBATIONARY,
                    nodeB,
                    Map.of("node-a", nodeA.identity("node-a"))
                )
            ),
            Duration.ofSeconds(1).toNanos()
        );
        assertEquals(Duration.ofSeconds(11).toNanos(), echoed.nextRebalanceAtNanos().orElseThrow());

        var confirmed = state.installAssignment(
            table(
                row(
                    "node-a",
                    AdmissionPhase.PROBATIONARY,
                    AdmissionPhase.PROBATIONARY,
                    nodeA,
                    Map.of()
                ),
                row(
                    "node-b",
                    AdmissionPhase.PROBATIONARY,
                    AdmissionPhase.PROBATIONARY,
                    nodeB,
                    Map.of("node-a", nodeA.identity("node-a"))
                )
            ),
            Duration.ofSeconds(11).toNanos()
        );
        assertEquals(AdmissionPhase.PROBATIONARY, confirmed.subscription().advertisedPhase());
        assertEquals(
            Map.of("node-b", nodeA.identity("node-a")),
            confirmed.subscription().confirmedPeerVisibility()
        );
        assertFalse(confirmed.nextRebalanceAtNanos().isPresent());
    }

    @Test
    void unrelatedJoinDoesNotResetAnExistingVisibilityInterval() {
        var nodeA = Footprint.known(1, List.of(0));
        var nodeB = Footprint.known(1, List.of(0));
        var nodeC = Footprint.known(1, List.of(0));
        var state = new CaptureGroupMemberState(
            "node-a",
            nodeA,
            VISIBILITY_INTERVAL
        );
        state.installAssignment(
            table(
                row(
                    "node-a",
                    AdmissionPhase.PROBATIONARY,
                    AdmissionPhase.PROBATIONARY,
                    nodeA,
                    Map.of()
                ),
                row(
                    "node-b",
                    AdmissionPhase.PROBATIONARY,
                    AdmissionPhase.PROBATIONARY,
                    nodeB,
                    Map.of("node-a", nodeA.identity("node-a"))
                )
            ),
            0
        );

        var withLaterJoin = state.installAssignment(
            table(
                row(
                    "node-a",
                    AdmissionPhase.PROBATIONARY,
                    AdmissionPhase.PROBATIONARY,
                    nodeA,
                    Map.of()
                ),
                row(
                    "node-b",
                    AdmissionPhase.PROBATIONARY,
                    AdmissionPhase.PROBATIONARY,
                    nodeB,
                    Map.of("node-a", nodeA.identity("node-a"))
                ),
                row(
                    "node-c",
                    AdmissionPhase.PROBATIONARY,
                    AdmissionPhase.PROBATIONARY,
                    nodeC,
                    Map.of()
                )
            ),
            Duration.ofSeconds(9).toNanos()
        );
        assertEquals(VISIBILITY_INTERVAL.toNanos(), withLaterJoin.nextRebalanceAtNanos().orElseThrow());

        var confirmed = state.installAssignment(
            table(
                row(
                    "node-a",
                    AdmissionPhase.PROBATIONARY,
                    AdmissionPhase.PROBATIONARY,
                    nodeA,
                    Map.of()
                ),
                row(
                    "node-b",
                    AdmissionPhase.PROBATIONARY,
                    AdmissionPhase.PROBATIONARY,
                    nodeB,
                    Map.of("node-a", nodeA.identity("node-a"))
                ),
                row(
                    "node-c",
                    AdmissionPhase.PROBATIONARY,
                    AdmissionPhase.PROBATIONARY,
                    nodeC,
                    Map.of()
                )
            ),
            VISIBILITY_INTERVAL.toNanos()
        );
        assertEquals(
            Map.of("node-b", nodeA.identity("node-a")),
            confirmed.subscription().confirmedPeerVisibility()
        );
    }

    @Test
    void losingOneObserverRemovesOnlyThatDirectionalConfirmation() {
        var nodeA = Footprint.known(1, List.of(0));
        var nodeB = Footprint.known(1, List.of(0));
        var nodeC = Footprint.known(1, List.of(0));
        var state = new CaptureGroupMemberState(
            "node-a",
            nodeA,
            Duration.ZERO
        );
        state.installAssignment(
            table(
                row(
                    "node-a",
                    AdmissionPhase.PROBATIONARY,
                    AdmissionPhase.PROBATIONARY,
                    nodeA,
                    Map.of()
                ),
                row(
                    "node-b",
                    AdmissionPhase.PROBATIONARY,
                    AdmissionPhase.PROBATIONARY,
                    nodeB,
                    Map.of("node-a", nodeA.identity("node-a"))
                ),
                row(
                    "node-c",
                    AdmissionPhase.PROBATIONARY,
                    AdmissionPhase.PROBATIONARY,
                    nodeC,
                    Map.of("node-a", nodeA.identity("node-a"))
                )
            ),
            0
        );

        var observerLeft = state.installAssignment(
            table(
                row(
                    "node-a",
                    AdmissionPhase.PROBATIONARY,
                    AdmissionPhase.PROBATIONARY,
                    nodeA,
                    Map.of()
                ),
                row(
                    "node-c",
                    AdmissionPhase.PROBATIONARY,
                    AdmissionPhase.PROBATIONARY,
                    nodeC,
                    Map.of("node-a", nodeA.identity("node-a"))
                )
            ),
            1
        );

        assertEquals(
            Map.of("node-c", nodeA.identity("node-a")),
            observerLeft.subscription().confirmedPeerVisibility()
        );
    }

    @Test
    void activeNeverDemotesAndContinuesTrackingReplacementPeers() {
        var nodeA = Footprint.known(1, List.of(0));
        var nodeB = Footprint.known(1, List.of(0));
        var state = new CaptureGroupMemberState(
            "node-a",
            nodeA,
            Duration.ZERO
        );

        state.installAssignment(
            table(row("node-a", AdmissionPhase.PROBATIONARY, AdmissionPhase.ACTIVE, nodeA, Map.of())),
            0
        );
        var replacementVisible = state.installAssignment(
            table(
                row("node-a", AdmissionPhase.ACTIVE, AdmissionPhase.ACTIVE, nodeA, Map.of()),
                row(
                    "node-b",
                    AdmissionPhase.PROBATIONARY,
                    AdmissionPhase.PROBATIONARY,
                    nodeB,
                    Map.of("node-a", nodeA.identity("node-a"))
                )
            ),
            1
        );

        assertEquals(AdmissionPhase.ACTIVE, replacementVisible.subscription().advertisedPhase());
        assertEquals(
            Map.of("node-b", nodeA.identity("node-a")),
            replacementVisible.subscription().confirmedPeerVisibility()
        );
    }

    @Test
    void assignmentCannotDemoteAnActiveMember() {
        var nodeA = Footprint.known(1, List.of(0));
        var state = new CaptureGroupMemberState("node-a", nodeA, Duration.ZERO);
        state.installAssignment(
            table(row("node-a", AdmissionPhase.PROBATIONARY, AdmissionPhase.ACTIVE, nodeA, Map.of())),
            0
        );

        assertThrows(
            IllegalStateException.class,
            () -> state.installAssignment(
                table(
                    row(
                        "node-a",
                        AdmissionPhase.ACTIVE,
                        AdmissionPhase.PROBATIONARY,
                        nodeA,
                        Map.of()
                    )
                ),
                1
            )
        );
    }

    @Test
    void assignmentCannotReplaceTheLocalFootprint() {
        var state = new CaptureGroupMemberState(
            "node-a",
            Footprint.known(1, List.of(0)),
            Duration.ZERO
        );

        assertThrows(
            IllegalStateException.class,
            () -> state.installAssignment(
                table(
                    row(
                        "node-a",
                        AdmissionPhase.PROBATIONARY,
                        AdmissionPhase.PROBATIONARY,
                        Footprint.known(2, List.of(0, 1)),
                        Map.of()
                    )
                ),
                0
            )
        );
    }

    private static AssignmentTable table(MemberRow... rows) {
        var members = new LinkedHashMap<String, MemberRow>();
        for (var row : rows) {
            members.put(row.nodeId(), row);
        }
        return new AssignmentTable(members);
    }

    private static MemberRow row(
        String nodeId,
        AdmissionPhase advertised,
        AdmissionPhase effective,
        Footprint footprint,
        Map<String, CaptureGroupProtocol.FootprintIdentity> observations
    ) {
        return new MemberRow(
            nodeId,
            advertised,
            effective,
            footprint,
            observations,
            Map.of()
        );
    }
}
