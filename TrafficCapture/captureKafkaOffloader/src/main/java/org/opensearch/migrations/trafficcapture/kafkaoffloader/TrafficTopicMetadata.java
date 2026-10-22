package org.opensearch.migrations.trafficcapture.kafkaoffloader;

import java.util.List;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.IntStream;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NonNull;
import lombok.ToString;
import org.apache.kafka.clients.admin.TopicDescription;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.common.PartitionInfo;

/**
 * Reduces Kafka topic metadata to the facts capture startup needs: a validated partition count and
 * one representative partition for every current leader broker. Probing one partition per leader
 * exercises every broker that can acknowledge traffic without publishing redundant probes to all
 * partitions.
 *
 * <p>Discovery also verifies that partition numbers are contiguous and every partition currently
 * has a valid leader. The resulting immutable snapshot is therefore suitable both for sizing
 * routing state and for deciding whether the topic is sufficiently available to begin capture.
 */
@EqualsAndHashCode
@ToString
public final class TrafficTopicMetadata {
    @Getter
    private final int topicPartitionCount;
    @Getter
    private final List<Integer> representativePartitionsByLeader;
    @Getter
    private final Set<Integer> leaderIds;

    private TrafficTopicMetadata(
        int topicPartitionCount,
        List<Integer> representativePartitionsByLeader,
        Set<Integer> leaderIds
    ) {
        if (topicPartitionCount <= 0) {
            throw new IllegalArgumentException("topicPartitionCount must be positive");
        }
        this.topicPartitionCount = topicPartitionCount;
        this.representativePartitionsByLeader = List.copyOf(representativePartitionsByLeader);
        this.leaderIds = Set.copyOf(leaderIds);
        if (this.representativePartitionsByLeader.isEmpty()) {
            throw new IllegalArgumentException("At least one leader-representative partition is required");
        }
        if (this.representativePartitionsByLeader.size() != this.leaderIds.size()) {
            throw new IllegalArgumentException("Every leader must have exactly one representative partition");
        }
    }

    public static TrafficTopicMetadata discover(
        @NonNull Producer<String, byte[]> producer,
        @NonNull String topic
    ) {
        return fromPartitionMetadata(topic, producer.partitionsFor(topic));
    }

    static TrafficTopicMetadata fromPartitionMetadata(
        @NonNull String topic,
        @NonNull List<PartitionInfo> discoveredPartitions
    ) {
        var partitionMetadata = discoveredPartitions
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
            List.copyOf(representativeByLeader.values()),
            Set.copyOf(representativeByLeader.keySet())
        );
    }

    static TrafficTopicMetadata fromTopicDescription(
        @NonNull String topic,
        @NonNull TopicDescription topicDescription
    ) {
        var partitionMetadata = topicDescription
            .partitions()
            .stream()
            .sorted(java.util.Comparator.comparingInt(partition -> partition.partition()))
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
            List.copyOf(representativeByLeader.values()),
            Set.copyOf(representativeByLeader.keySet())
        );
    }

    static TrafficTopicMetadata forTopic(int topicPartitionCount) {
        return new TrafficTopicMetadata(
            topicPartitionCount,
            IntStream.range(0, topicPartitionCount).boxed().toList(),
            IntStream.range(0, topicPartitionCount).boxed().collect(java.util.stream.Collectors.toSet())
        );
    }
}
