package org.opensearch.migrations.trafficcapture.kafkaoffloader;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PartitionRoutingPlanTest {
    @Test
    void topicMetadataAndTemporaryPlanIdentityAreDeterministic() {
        var first = PartitionRoutingPlan.forTopic(7);
        var second = PartitionRoutingPlan.forTopic(7);

        assertEquals(7, first.getTopicPartitionCount());
        assertEquals(first, second);
    }

    @Test
    void invalidPartitionCountFailsAtStartup() {
        assertThrows(IllegalArgumentException.class, () -> PartitionRoutingPlan.forTopic(0));
    }
}
