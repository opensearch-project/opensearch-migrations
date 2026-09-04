package org.opensearch.migrations.trafficcapture.kafkaoffloader;

import java.util.HashSet;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PartitionRoutingPlanTest {
    @Test
    void fullWidthUsesEveryPartitionAndRoutesDeterministically() {
        var first = PartitionRoutingPlan.forTopic(7, 7, "node-a");
        var second = PartitionRoutingPlan.forTopic(7, 7, "node-a");

        assertEquals(new HashSet<>(java.util.stream.IntStream.range(0, 7).boxed().toList()),
            new HashSet<>(first.getSelectedPartitions()));
        assertEquals(first, second);
        assertEquals(first.partitionFor("connection-a"), second.partitionFor("connection-a"));
        assertTrue(first.getSelectedPartitions().contains(first.partitionFor("connection-b")));
    }

    @Test
    void reducedWidthFreezesNodeSpecificShardAndPlanIdentity() {
        var first = PartitionRoutingPlan.forTopic(16, 3, "node-a");
        var second = PartitionRoutingPlan.forTopic(16, 3, "node-b");

        assertEquals(3, first.getSelectedPartitions().size());
        assertNotEquals(first.getSelectedPartitions(), second.getSelectedPartitions());
        assertNotEquals(first.getRoutingPlanId(), second.getRoutingPlanId());
    }

    @Test
    void invalidWidthsFailAtStartup() {
        assertThrows(IllegalArgumentException.class, () -> PartitionRoutingPlan.forTopic(4, 0, "node"));
        assertThrows(IllegalArgumentException.class, () -> PartitionRoutingPlan.forTopic(4, 5, "node"));
    }
}
