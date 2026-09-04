package org.opensearch.migrations.trafficcapture.kafkaoffloader;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Objects;
import java.util.stream.IntStream;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.common.PartitionInfo;
import org.apache.kafka.common.utils.Utils;

/**
 * Immutable routing information shared by traffic records and liveness snapshots for one proxy process.
 */
@EqualsAndHashCode
@ToString
public final class PartitionRoutingPlan {
    private static final String HASH_POLICY = "kafka-murmur2-v1";

    @Getter
    private final int topicPartitionCount;
    @Getter
    private final int shardWidth;
    @Getter
    private final List<Integer> selectedPartitions;
    @Getter
    private final String routingPlanId;

    private PartitionRoutingPlan(int topicPartitionCount, int shardWidth, List<Integer> selectedPartitions) {
        if (topicPartitionCount <= 0) {
            throw new IllegalArgumentException("topicPartitionCount must be positive");
        }
        if (shardWidth <= 0 || shardWidth > topicPartitionCount) {
            throw new IllegalArgumentException(
                "shardWidth must be between 1 and " + topicPartitionCount + ", but was " + shardWidth
            );
        }
        if (selectedPartitions.size() != shardWidth
            || selectedPartitions.stream().distinct().count() != shardWidth
            || selectedPartitions.stream().anyMatch(p -> p < 0 || p >= topicPartitionCount)) {
            throw new IllegalArgumentException("selectedPartitions do not describe a valid shard");
        }
        this.topicPartitionCount = topicPartitionCount;
        this.shardWidth = shardWidth;
        this.selectedPartitions = List.copyOf(selectedPartitions);
        this.routingPlanId = makePlanId();
    }

    public static PartitionRoutingPlan discover(
        Producer<String, byte[]> producer,
        String topic,
        String nodeId,
        Integer requestedShardWidth
    ) {
        Objects.requireNonNull(producer);
        Objects.requireNonNull(topic);
        Objects.requireNonNull(nodeId);
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
        int width = requestedShardWidth == null ? partitions.size() : requestedShardWidth;
        return forTopic(partitions.size(), width, nodeId);
    }

    public static PartitionRoutingPlan forTopic(int topicPartitionCount, int shardWidth, String nodeId) {
        Objects.requireNonNull(nodeId);
        if (shardWidth <= 0 || shardWidth > topicPartitionCount) {
            throw new IllegalArgumentException(
                "traffic partition shard width must be between 1 and "
                    + topicPartitionCount
                    + ", but was "
                    + shardWidth
            );
        }
        int start = positiveHash(nodeId) % topicPartitionCount;
        var selected = IntStream.range(0, shardWidth)
            .map(i -> (start + i) % topicPartitionCount)
            .sorted()
            .boxed()
            .toList();
        return new PartitionRoutingPlan(topicPartitionCount, shardWidth, selected);
    }

    public int partitionFor(String connectionId) {
        Objects.requireNonNull(connectionId);
        return selectedPartitions.get(positiveHash(connectionId) % selectedPartitions.size());
    }

    private static int positiveHash(String value) {
        return Utils.toPositive(Utils.murmur2(value.getBytes(StandardCharsets.UTF_8)));
    }

    private String makePlanId() {
        var description = "v1;m="
            + topicPartitionCount
            + ";k="
            + shardWidth
            + ";p="
            + selectedPartitions
            + ";h="
            + HASH_POLICY;
        try {
            var digest = MessageDigest.getInstance("SHA-256")
                .digest(description.getBytes(StandardCharsets.UTF_8));
            return "routing-v1-" + java.util.HexFormat.of().formatHex(digest, 0, 12);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by the JVM", e);
        }
    }
}
