package org.opensearch.migrations.trafficcapture.kafkaoffloader;

import java.util.Objects;

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

    private TrafficTopicMetadata(int topicPartitionCount) {
        if (topicPartitionCount <= 0) {
            throw new IllegalArgumentException("topicPartitionCount must be positive");
        }
        this.topicPartitionCount = topicPartitionCount;
    }

    public static TrafficTopicMetadata discover(
        Producer<String, byte[]> producer,
        String topic
    ) {
        Objects.requireNonNull(producer);
        Objects.requireNonNull(topic);
        var partitions = producer.partitionsFor(topic)
            .stream()
            .map(PartitionInfo::partition)
            .sorted()
            .toList();
        if (partitions.isEmpty()) {
            throw new IllegalStateException("Kafka returned no partitions for topic " + topic);
        }
        for (int i = 0; i < partitions.size(); ++i) {
            if (partitions.get(i) != i) {
                throw new IllegalStateException(
                    "Expected contiguous Kafka partitions 0.." + (partitions.size() - 1) + " for " + topic
                );
            }
        }
        return forTopic(partitions.size());
    }

    public static TrafficTopicMetadata forTopic(int topicPartitionCount) {
        return new TrafficTopicMetadata(topicPartitionCount);
    }
}
