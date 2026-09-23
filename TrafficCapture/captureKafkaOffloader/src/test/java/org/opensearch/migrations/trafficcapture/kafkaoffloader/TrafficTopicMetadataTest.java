package org.opensearch.migrations.trafficcapture.kafkaoffloader;

import java.util.List;
import java.util.Set;

import org.apache.kafka.common.Node;
import org.apache.kafka.common.PartitionInfo;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
class TrafficTopicMetadataTest {
    @Test
    void topicMetadataContainsPartitionCount() {
        var first = TrafficTopicMetadata.forTopic(7);
        var second = TrafficTopicMetadata.forTopic(7);

        assertEquals(7, first.getTopicPartitionCount());
        assertEquals(List.of(0, 1, 2, 3, 4, 5, 6), first.getRepresentativePartitionsByLeader());
        assertEquals(Set.of(0, 1, 2, 3, 4, 5, 6), first.getLeaderIds());
        assertEquals(first, second);
    }

    @Test
    void discoveryChoosesOnePartitionPerCurrentLeader() {
        var leader0 = new Node(10, "broker-10", 9092);
        var leader1 = new Node(11, "broker-11", 9092);
        var partitionMetadata = List.of(
            partition("traffic", 0, leader0),
            partition("traffic", 1, leader1),
            partition("traffic", 2, leader0),
            partition("traffic", 3, leader1)
        );

        var metadata = TrafficTopicMetadata.fromPartitionMetadata("traffic", partitionMetadata);

        assertEquals(4, metadata.getTopicPartitionCount());
        assertEquals(List.of(0, 1), metadata.getRepresentativePartitionsByLeader());
        assertEquals(Set.of(10, 11), metadata.getLeaderIds());
    }

    @Test
    void missingPartitionLeaderRetriesAsUnavailableMetadata() {
        var partitionMetadata = List.of(
            new PartitionInfo("traffic", 0, null, new Node[0], new Node[0])
        );

        assertThrows(
            IllegalStateException.class,
            () -> TrafficTopicMetadata.fromPartitionMetadata("traffic", partitionMetadata)
        );
    }

    @Test
    void invalidPartitionCountFailsAtStartup() {
        assertThrows(IllegalArgumentException.class, () -> TrafficTopicMetadata.forTopic(0));
    }

    private static PartitionInfo partition(String topic, int partition, Node leader) {
        return new PartitionInfo(
            topic,
            partition,
            leader,
            new Node[] { leader },
            new Node[] { leader }
        );
    }
}
