package org.opensearch.migrations.trafficcapture.kafkaoffloader;

import java.util.List;

import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.common.Node;
import org.apache.kafka.common.PartitionInfo;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TrafficTopicMetadataTest {
    @Test
    void topicMetadataContainsPartitionCount() {
        var first = TrafficTopicMetadata.forTopic(7);
        var second = TrafficTopicMetadata.forTopic(7);

        assertEquals(7, first.getTopicPartitionCount());
        assertEquals(List.of(0, 1, 2, 3, 4, 5, 6), first.getRepresentativePartitionsByLeader());
        assertEquals(first, second);
    }

    @Test
    void discoveryChoosesOnePartitionPerCurrentLeader() {
        @SuppressWarnings("unchecked")
        var producer = mock(Producer.class);
        var leader0 = new Node(10, "broker-10", 9092);
        var leader1 = new Node(11, "broker-11", 9092);
        when(producer.partitionsFor("traffic")).thenReturn(List.of(
            partition("traffic", 0, leader0),
            partition("traffic", 1, leader1),
            partition("traffic", 2, leader0),
            partition("traffic", 3, leader1)
        ));

        var metadata = TrafficTopicMetadata.discover(producer, "traffic");

        assertEquals(4, metadata.getTopicPartitionCount());
        assertEquals(List.of(0, 1), metadata.getRepresentativePartitionsByLeader());
    }

    @Test
    void missingPartitionLeaderRetriesAsUnavailableMetadata() {
        @SuppressWarnings("unchecked")
        var producer = mock(Producer.class);
        when(producer.partitionsFor("traffic")).thenReturn(List.of(
            new PartitionInfo("traffic", 0, null, new Node[0], new Node[0])
        ));

        assertThrows(
            IllegalStateException.class,
            () -> TrafficTopicMetadata.discover(producer, "traffic")
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
