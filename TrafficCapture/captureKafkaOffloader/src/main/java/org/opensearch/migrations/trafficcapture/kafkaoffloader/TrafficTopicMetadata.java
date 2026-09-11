package org.opensearch.migrations.trafficcapture.kafkaoffloader;

import java.util.List;
import java.util.Objects;
import java.util.TreeMap;
import java.util.stream.IntStream;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.common.PartitionInfo;

/**
 * Immutable metadata discovered from the Kafka traffic topic.
 */
@EqualsAndHashCode
@ToString
public final class TrafficTopicMetadata {
    @Getter
    private final int topicPartitionCount;
    @Getter
    private final List<Integer> representativePartitionsByLeader;

    private TrafficTopicMetadata(
        int topicPartitionCount,
        List<Integer> representativePartitionsByLeader
    ) {
        if (topicPartitionCount <= 0) {
            throw new IllegalArgumentException("topicPartitionCount must be positive");
        }
        this.topicPartitionCount = topicPartitionCount;
        this.representativePartitionsByLeader = List.copyOf(representativePartitionsByLeader);
        if (this.representativePartitionsByLeader.isEmpty()) {
            throw new IllegalArgumentException("At least one leader-representative partition is required");
        }
    }

    public static TrafficTopicMetadata discover(
        Producer<String, byte[]> producer,
        String topic
    ) {
        Objects.requireNonNull(producer);
        Objects.requireNonNull(topic);
        var partitionMetadata = producer.partitionsFor(topic)
            .stream()
            .sorted(java.util.Comparator.comparingInt(PartitionInfo::partition))
            .toList();
        if (partitionMetadata.isEmpty()) {
            throw new IllegalStateException("Kafka returned no partitions for topic " + topic);
        }
        var representativeByLeader = new TreeMap<Integer, Integer>();
        for (int i = 0; i < partitionMetadata.size(); ++i) {
            var partitionInfo = partitionMetadata.get(i);
            if (partitionInfo.partition() != i) {
                throw new IllegalStateException(
                    "Expected contiguous Kafka partitions 0.."
                        + (partitionMetadata.size() - 1)
                        + " for "
                        + topic
                );
            }
            var leader = partitionInfo.leader();
            if (leader == null || leader.id() < 0) {
                throw new IllegalStateException(
                    "Kafka returned no current leader for " + topic + " partition " + partitionInfo.partition()
                );
            }
            representativeByLeader.putIfAbsent(leader.id(), partitionInfo.partition());
        }
        return new TrafficTopicMetadata(
            partitionMetadata.size(),
            List.copyOf(representativeByLeader.values())
        );
    }

    public static TrafficTopicMetadata forTopic(int topicPartitionCount) {
        return new TrafficTopicMetadata(
            topicPartitionCount,
            IntStream.range(0, topicPartitionCount).boxed().toList()
        );
    }
}
