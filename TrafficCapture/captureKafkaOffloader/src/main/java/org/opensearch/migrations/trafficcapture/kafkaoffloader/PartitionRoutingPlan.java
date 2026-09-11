package org.opensearch.migrations.trafficcapture.kafkaoffloader;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Objects;
import java.util.stream.IntStream;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.common.PartitionInfo;

/**
 * Immutable traffic-topic metadata shared by traffic records and liveness snapshots.
 *
 * routingPlanId remains temporarily because the current traffic schema and replayer still carry it.
 */
@EqualsAndHashCode
@ToString
public final class PartitionRoutingPlan {
    private static final String HASH_POLICY = "kafka-murmur2-v1";

    @Getter
    private final int topicPartitionCount;
    @Getter
    private final String routingPlanId;

    private PartitionRoutingPlan(int topicPartitionCount) {
        if (topicPartitionCount <= 0) {
            throw new IllegalArgumentException("topicPartitionCount must be positive");
        }
        this.topicPartitionCount = topicPartitionCount;
        this.routingPlanId = makePlanId();
    }

    public static PartitionRoutingPlan discover(
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

    public static PartitionRoutingPlan forTopic(int topicPartitionCount) {
        return new PartitionRoutingPlan(topicPartitionCount);
    }

    private String makePlanId() {
        var everyPartition = IntStream.range(0, topicPartitionCount).boxed().toList();
        var description = "v1;m="
            + topicPartitionCount
            + ";k="
            + topicPartitionCount
            + ";p="
            + everyPartition
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
