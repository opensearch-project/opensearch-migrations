package org.opensearch.migrations.trafficcapture.kafkaoffloader;

import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import org.apache.kafka.common.utils.Utils;

/**
 * Thread-safe assignment used only when admitting new connections. Existing connections retain the partition stored
 * in {@link ProxyLivenessRegistry}.
 */
public final class CapturePartitionAssignment {
    private final int topicPartitionCount;
    private final int maximumAssignedPartitions;
    private volatile List<Integer> assignedPartitions;

    public CapturePartitionAssignment(int topicPartitionCount, Collection<Integer> assignedPartitions) {
        this(topicPartitionCount, topicPartitionCount, assignedPartitions);
    }

    public CapturePartitionAssignment(
        int topicPartitionCount,
        int maximumAssignedPartitions,
        Collection<Integer> assignedPartitions
    ) {
        if (topicPartitionCount <= 0) {
            throw new IllegalArgumentException("topicPartitionCount must be positive");
        }
        if (maximumAssignedPartitions <= 0 || maximumAssignedPartitions > topicPartitionCount) {
            throw new IllegalArgumentException(
                "maximumAssignedPartitions must be between 1 and " + topicPartitionCount
            );
        }
        this.topicPartitionCount = topicPartitionCount;
        this.maximumAssignedPartitions = maximumAssignedPartitions;
        replaceAssignedPartitions(assignedPartitions);
    }

    public int partitionForNewConnection(String connectionId) {
        Objects.requireNonNull(connectionId);
        var current = assignedPartitions;
        if (current.isEmpty()) {
            throw new IllegalStateException("No Kafka partitions are assigned for new capture connections");
        }
        return current.get(positiveHash(connectionId) % current.size());
    }

    public synchronized void replaceAssignedPartitions(Collection<Integer> partitions) {
        Objects.requireNonNull(partitions);
        var replacement = partitions.stream().distinct().sorted().toList();
        if (replacement.size() != partitions.size()
            || replacement.stream().anyMatch(partition -> partition < 0 || partition >= topicPartitionCount)) {
            throw new IllegalArgumentException("assignedPartitions do not describe valid unique topic partitions");
        }
        assignedPartitions = replacement.stream().limit(maximumAssignedPartitions).toList();
    }

    public synchronized void revokePartitions(Collection<Integer> partitions) {
        Objects.requireNonNull(partitions);
        var revoked = Set.copyOf(partitions);
        assignedPartitions = assignedPartitions.stream()
            .filter(partition -> !revoked.contains(partition))
            .toList();
    }

    public List<Integer> assignedPartitions() {
        return assignedPartitions;
    }

    public int topicPartitionCount() {
        return topicPartitionCount;
    }

    private static int positiveHash(String value) {
        return Utils.toPositive(Utils.murmur2(value.getBytes(StandardCharsets.UTF_8)));
    }
}
