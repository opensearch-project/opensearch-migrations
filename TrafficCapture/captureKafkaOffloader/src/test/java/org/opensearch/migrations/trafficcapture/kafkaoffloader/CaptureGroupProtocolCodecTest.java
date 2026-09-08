package org.opensearch.migrations.trafficcapture.kafkaoffloader;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.Map;

import org.opensearch.migrations.trafficcapture.kafkaoffloader.CaptureGroupProtocol.AdmissionPhase;
import org.opensearch.migrations.trafficcapture.kafkaoffloader.CaptureGroupProtocol.AssignmentTable;
import org.opensearch.migrations.trafficcapture.kafkaoffloader.CaptureGroupProtocol.Footprint;
import org.opensearch.migrations.trafficcapture.kafkaoffloader.CaptureGroupProtocol.MemberRow;
import org.opensearch.migrations.trafficcapture.kafkaoffloader.CaptureGroupProtocol.Subscription;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CaptureGroupProtocolCodecTest {
    @Test
    void subscriptionRoundTripIsCanonical() {
        var nodeA = Footprint.known(4, List.of(2, 0));
        var nodeB = Footprint.known(7, List.of(3));
        var subscription = new Subscription(
            "node-a",
            AdmissionPhase.PROBATIONARY,
            nodeA,
            Map.of("node-b", nodeB.identity("node-b")),
            Map.of("node-b", nodeA.identity("node-a"))
        );

        var decoded = CaptureGroupProtocolCodec.decodeSubscription(
            CaptureGroupProtocolCodec.encodeSubscription(subscription)
        );

        assertEquals(subscription, decoded);
        assertEquals(List.of(0, 2), decoded.footprint().partitions());
    }

    @Test
    void assignmentRoundTripCarriesTheFullMemberTable() {
        var nodeA = Footprint.known(4, List.of(0, 1));
        var nodeB = Footprint.unknown(1);
        var rowA = new MemberRow(
            "node-a",
            AdmissionPhase.ACTIVE,
            AdmissionPhase.ACTIVE,
            nodeA,
            Map.of("node-b", nodeB.identity("node-b")),
            Map.of("node-b", nodeA.identity("node-a"))
        );
        var rowB = new MemberRow(
            "node-b",
            AdmissionPhase.PROBATIONARY,
            AdmissionPhase.PROBATIONARY,
            nodeB,
            Map.of("node-a", nodeA.identity("node-a")),
            Map.of()
        );
        var table = new AssignmentTable(Map.of("node-b", rowB, "node-a", rowA));

        var decoded = CaptureGroupProtocolCodec.decodeAssignment(
            CaptureGroupProtocolCodec.encodeAssignment(table)
        );

        assertEquals(table, decoded);
        assertEquals(List.of("node-a", "node-b"), decoded.members().keySet().stream().toList());
    }

    @Test
    void footprintDigestBindsTheExactPartitionSet() {
        var footprint = Footprint.known(11, List.of(0, 1));

        assertThrows(
            IllegalArgumentException.class,
            () -> new Footprint(11, false, List.of(0), footprint.digest())
        );
    }

    @Test
    void malformedOrWrongTypeMetadataFailsClosed() {
        var subscription = new Subscription(
            "node-a",
            AdmissionPhase.PROBATIONARY,
            Footprint.unknown(0),
            Map.of(),
            Map.of()
        );
        var encoded = CaptureGroupProtocolCodec.encodeSubscription(subscription);
        var truncated = encoded.duplicate();
        truncated.limit(truncated.limit() - 1);

        assertThrows(
            IllegalStateException.class,
            () -> CaptureGroupProtocolCodec.decodeSubscription(truncated)
        );
        assertThrows(
            IllegalStateException.class,
            () -> CaptureGroupProtocolCodec.decodeAssignment(encoded)
        );
        assertThrows(
            IllegalStateException.class,
            () -> CaptureGroupProtocolCodec.decodeSubscription(ByteBuffer.allocate(16))
        );
    }

    @Test
    void peerMapsMustUseTheIdentityNodeAsTheirKey() {
        var nodeB = Footprint.known(1, List.of(0));

        assertThrows(
            IllegalArgumentException.class,
            () -> new Subscription(
                "node-a",
                AdmissionPhase.PROBATIONARY,
                Footprint.unknown(0),
                Map.of("wrong", nodeB.identity("node-b")),
                Map.of()
            )
        );
    }

    @Test
    void confirmedVisibilityNamesTheObserverButBindsTheAdvertisingCandidateFootprint() {
        var nodeA = Footprint.known(3, List.of(0));
        var nodeB = Footprint.known(1, List.of(0));

        assertThrows(
            IllegalArgumentException.class,
            () -> new Subscription(
                "node-a",
                AdmissionPhase.PROBATIONARY,
                nodeA,
                Map.of("node-b", nodeB.identity("node-b")),
                Map.of("node-b", nodeB.identity("node-b"))
            )
        );
    }
}
