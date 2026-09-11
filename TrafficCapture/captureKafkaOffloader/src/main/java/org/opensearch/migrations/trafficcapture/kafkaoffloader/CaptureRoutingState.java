package org.opensearch.migrations.trafficcapture.kafkaoffloader;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

import org.apache.kafka.common.utils.Utils;

/**
 * Single synchronization owner for Kafka assignment, immutable connection routing, liveness snapshots, and
 * partition self-release.
 */
public final class CaptureRoutingState {
    private enum ReleaseStatus {
        NONE,
        PENDING,
        SUBMITTED,
        COMPLETE
    }

    static final class SelfRelease {
        private final int partition;
        private final long assignmentRevision;

        SelfRelease(int partition, long assignmentRevision) {
            this.partition = partition;
            this.assignmentRevision = assignmentRevision;
        }

        int partition() {
            return partition;
        }

        long assignmentRevision() {
            return assignmentRevision;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            return other instanceof SelfRelease release
                && partition == release.partition
                && assignmentRevision == release.assignmentRevision;
        }

        @Override
        public int hashCode() {
            return Objects.hash(partition, assignmentRevision);
        }

        @Override
        public String toString() {
            return "SelfRelease[partition=" + partition + ", assignmentRevision=" + assignmentRevision + "]";
        }
    }

    private static final class PartitionState {
        private boolean assigned;
        private long assignmentRevision;
        private ReleaseStatus releaseStatus = ReleaseStatus.NONE;

        private void transitionTo(boolean nextAssigned) {
            if (assigned == nextAssigned) {
                return;
            }
            assigned = nextAssigned;
            assignmentRevision++;
            releaseStatus = ReleaseStatus.NONE;
        }
    }

    private final int topicPartitionCount;
    private final Map<String, Integer> connectionPartitions = new HashMap<>();
    private final Map<Integer, Integer> connectionCounts = new HashMap<>();
    private final List<PartitionState> partitions;
    private List<Integer> assignedPartitions;
    private boolean newConnectionsAllowed;
    private boolean shuttingDown;

    public CaptureRoutingState(int topicPartitionCount, Collection<Integer> assignedPartitions) {
        if (topicPartitionCount <= 0) {
            throw new IllegalArgumentException("topicPartitionCount must be positive");
        }
        this.topicPartitionCount = topicPartitionCount;
        partitions = new ArrayList<>(topicPartitionCount);
        for (int partition = 0; partition < topicPartitionCount; ++partition) {
            partitions.add(new PartitionState());
        }
        var initial = validateAndCapAssignment(assignedPartitions);
        initial.forEach(partition -> partitions.get(partition).assigned = true);
        this.assignedPartitions = initial;
        this.newConnectionsAllowed = !initial.isEmpty();
    }

    public synchronized int admitConnection(String connectionId) {
        Objects.requireNonNull(connectionId);
        if (shuttingDown) {
            throw new IllegalStateException("Kafka capture routing is shutting down");
        }
        if (!newConnectionsAllowed) {
            throw new IllegalStateException("Kafka capture is not accepting new connections");
        }
        if (assignedPartitions.isEmpty()) {
            throw new IllegalStateException("No Kafka partitions are assigned for new capture connections");
        }
        int partition = assignedPartitions.get(positiveHash(connectionId) % assignedPartitions.size());
        register(connectionId, partition);
        return partition;
    }

    synchronized void register(String connectionId, int partition) {
        Objects.requireNonNull(connectionId);
        validatePartition(partition);
        if (shuttingDown) {
            throw new IllegalStateException("Kafka capture routing is shutting down");
        }
        var previous = connectionPartitions.putIfAbsent(connectionId, partition);
        if (previous != null) {
            throw new IllegalStateException(
                "Connection " + connectionId + " is already registered for partition " + previous
            );
        }
        connectionCounts.merge(partition, 1, Integer::sum);
    }

    synchronized Optional<SelfRelease> remove(String connectionId, int expectedPartition) {
        if (!connectionPartitions.remove(connectionId, expectedPartition)) {
            throw new IllegalStateException(
                "Connection "
                    + connectionId
                    + " was not registered for expected partition "
                    + expectedPartition
            );
        }
        connectionCounts.compute(expectedPartition, (ignored, count) -> {
            if (count == null || count <= 0) {
                throw new IllegalStateException("Connection count is missing for partition " + expectedPartition);
            }
            return count == 1 ? null : count - 1;
        });
        return selfReleaseIfDrained(expectedPartition);
    }

    public synchronized int partitionFor(String connectionId) {
        var partition = connectionPartitions.get(connectionId);
        if (partition == null) {
            throw new IllegalStateException("Connection " + connectionId + " is not registered");
        }
        return partition;
    }

    public synchronized List<String> snapshot(int partition) {
        validatePartition(partition);
        var result = new ArrayList<String>();
        connectionPartitions.forEach((connectionId, registeredPartition) -> {
            if (registeredPartition == partition) {
                result.add(connectionId);
            }
        });
        result.sort(String::compareTo);
        return List.copyOf(result);
    }

    public synchronized Set<Integer> partitionsWithConnections() {
        return Set.copyOf(connectionCounts.keySet());
    }

    public synchronized List<Integer> partitionsForSnapshot() {
        var result = new TreeSet<>(assignedPartitions);
        result.addAll(connectionCounts.keySet());
        return List.copyOf(result);
    }

    public synchronized int size() {
        return connectionPartitions.size();
    }

    public synchronized List<SelfRelease> replaceAssignedPartitions(Collection<Integer> replacement) {
        if (shuttingDown) {
            return List.of();
        }
        var releases = applyAssignment(validateAndCapAssignment(replacement));
        newConnectionsAllowed = !assignedPartitions.isEmpty();
        return releases;
    }

    public synchronized List<SelfRelease> revokePartitions(Collection<Integer> revokedPartitions) {
        Objects.requireNonNull(revokedPartitions);
        if (shuttingDown) {
            return List.of();
        }
        var revoked = Set.copyOf(revokedPartitions);
        revoked.forEach(this::validatePartition);
        var replacement = assignedPartitions.stream()
            .filter(partition -> !revoked.contains(partition))
            .toList();
        return applyAssignment(replacement);
    }

    /**
     * Linearizes a self-release submission against reassignment and new connection admission.
     */
    synchronized boolean submitSelfReleaseIfCurrent(SelfRelease release, Runnable submission) {
        Objects.requireNonNull(release);
        Objects.requireNonNull(submission);
        validatePartition(release.partition());
        var state = partitions.get(release.partition());
        if (shuttingDown
            || state.assignmentRevision != release.assignmentRevision()
            || state.assigned
            || state.releaseStatus != ReleaseStatus.PENDING
            || hasConnections(release.partition())) {
            return false;
        }
        state.releaseStatus = ReleaseStatus.SUBMITTED;
        submission.run();
        return true;
    }

    synchronized void completeSelfRelease(SelfRelease release) {
        var state = partitions.get(release.partition());
        if (state.assignmentRevision == release.assignmentRevision()
            && state.releaseStatus == ReleaseStatus.SUBMITTED) {
            state.releaseStatus = ReleaseStatus.COMPLETE;
        }
    }

    synchronized List<Integer> beginGracefulShutdown() {
        if (!connectionPartitions.isEmpty()) {
            throw new IllegalStateException(
                "Cannot gracefully release Kafka membership with "
                    + connectionPartitions.size()
                    + " capture connection(s) still open"
            );
        }
        shuttingDown = true;
        newConnectionsAllowed = false;
        assignedPartitions = List.of();
        return java.util.stream.IntStream.range(0, topicPartitionCount).boxed().toList();
    }

    public synchronized List<Integer> assignedPartitions() {
        return assignedPartitions;
    }

    public int topicPartitionCount() {
        return topicPartitionCount;
    }

    private List<SelfRelease> applyAssignment(List<Integer> replacement) {
        var next = new HashSet<>(replacement);
        var releases = new ArrayList<SelfRelease>();
        for (int partition = 0; partition < topicPartitionCount; ++partition) {
            var state = partitions.get(partition);
            boolean wasAssigned = state.assigned;
            boolean willBeAssigned = next.contains(partition);
            state.transitionTo(willBeAssigned);
            if (wasAssigned && !willBeAssigned) {
                selfReleaseIfDrained(partition).ifPresent(releases::add);
            }
        }
        assignedPartitions = replacement;
        return List.copyOf(releases);
    }

    private Optional<SelfRelease> selfReleaseIfDrained(int partition) {
        var state = partitions.get(partition);
        if (shuttingDown
            || state.assigned
            || state.releaseStatus != ReleaseStatus.NONE
            || hasConnections(partition)) {
            return Optional.empty();
        }
        state.releaseStatus = ReleaseStatus.PENDING;
        return Optional.of(new SelfRelease(partition, state.assignmentRevision));
    }

    private boolean hasConnections(int partition) {
        return connectionCounts.containsKey(partition);
    }

    private List<Integer> validateAndCapAssignment(Collection<Integer> assignment) {
        Objects.requireNonNull(assignment);
        var unique = new TreeSet<Integer>();
        for (var partition : assignment) {
            validatePartition(partition);
            if (!unique.add(partition)) {
                throw new IllegalArgumentException("assignedPartitions must be unique");
            }
        }
        return List.copyOf(unique);
    }

    private void validatePartition(int partition) {
        if (partition < 0 || partition >= topicPartitionCount) {
            throw new IllegalArgumentException("partition is outside the traffic topic");
        }
    }

    private static int positiveHash(String value) {
        return Utils.toPositive(Utils.murmur2(value.getBytes(StandardCharsets.UTF_8)));
    }
}
