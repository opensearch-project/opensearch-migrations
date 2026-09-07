package org.opensearch.migrations.trafficcapture.kafkaoffloader;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CapturePartitionAssignmentTest {
    @Test
    void assignmentChangesAffectOnlySubsequentAdmissionDecisions() {
        var assignment = new CapturePartitionAssignment(4, List.of(0, 1));
        var registry = new ProxyLivenessRegistry();
        int existingPartition = assignment.partitionForNewConnection("existing");
        registry.register("existing", existingPartition);

        assignment.replaceAssignedPartitions(List.of(2, 3));

        assertEquals(existingPartition, registry.partitionFor("existing"));
        assertTrue(List.of(2, 3).contains(assignment.partitionForNewConnection("new")));
    }

    @Test
    void revocationRemovesOnlyNewAdmissionChoices() {
        var assignment = new CapturePartitionAssignment(4, List.of(0, 1, 2));

        assignment.revokePartitions(List.of(1, 3));

        assertEquals(List.of(0, 2), assignment.assignedPartitions());
    }

    @Test
    void emptyAssignmentRejectsNewCaptureConnections() {
        var assignment = new CapturePartitionAssignment(4, List.of());

        assertThrows(
            IllegalStateException.class,
            () -> assignment.partitionForNewConnection("connection")
        );
    }

    @Test
    void configuredWidthCapsEachLiveKafkaAssignment() {
        var assignment = new CapturePartitionAssignment(8, 2, List.of(0, 1, 2, 3));

        assertEquals(List.of(0, 1), assignment.assignedPartitions());
        assignment.replaceAssignedPartitions(List.of(4, 5, 6));
        assertEquals(List.of(4, 5), assignment.assignedPartitions());
    }

    @Test
    void assignmentsMustBeUniqueAndInsideTheTopic() {
        assertThrows(
            IllegalArgumentException.class,
            () -> new CapturePartitionAssignment(2, List.of(0, 0))
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> new CapturePartitionAssignment(2, List.of(2))
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> new CapturePartitionAssignment(2, 3, List.of())
        );
    }
}
