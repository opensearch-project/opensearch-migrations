package org.opensearch.migrations.trafficcapture.kafkaoffloader;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicLong;

import org.apache.kafka.common.utils.Utils;

/**
 * Owns assignment-scoped writer identities, immutable connection routes, and the exact
 * connection registry for every writer and partition.
 */
public final class CaptureRoutingState {
    enum WriterStatus {
        INITIALIZING,
        CURRENT,
        DRAINING
    }

    static final class ConnectionRoute {
        private final String writerNodeId;
        private final String connectionId;
        private final int partition;
        private final AtomicLong manifestCycle;

        private ConnectionRoute(
            String writerNodeId,
            String connectionId,
            int partition,
            AtomicLong manifestCycle
        ) {
            this.writerNodeId = writerNodeId;
            this.connectionId = connectionId;
            this.partition = partition;
            this.manifestCycle = manifestCycle;
        }

        String writerNodeId() {
            return writerNodeId;
        }

        String connectionId() {
            return connectionId;
        }

        int partition() {
            return partition;
        }

        long manifestCycle() {
            return manifestCycle.get();
        }

        @Override
        public String toString() {
            return "ConnectionRoute[writerNodeId="
                + writerNodeId
                + ", connectionId="
                + connectionId
                + ", partition="
                + partition
                + "]";
        }
    }

    static final class PendingAssignment {
        private final long assignmentSequence;
        private final String writerNodeId;
        private final List<Integer> partitions;

        private PendingAssignment(
            long assignmentSequence,
            String writerNodeId,
            List<Integer> partitions
        ) {
            this.assignmentSequence = assignmentSequence;
            this.writerNodeId = writerNodeId;
            this.partitions = partitions;
        }

        long assignmentSequence() {
            return assignmentSequence;
        }

        String writerNodeId() {
            return writerNodeId;
        }

        List<Integer> partitions() {
            return partitions;
        }
    }

    static final class PreparedManifest {
        private final String writerNodeId;
        private final int partition;
        private final long manifestCycle;
        private final List<String> connectionIds;

        private PreparedManifest(
            String writerNodeId,
            int partition,
            long manifestCycle,
            List<String> connectionIds
        ) {
            this.writerNodeId = writerNodeId;
            this.partition = partition;
            this.manifestCycle = manifestCycle;
            this.connectionIds = connectionIds;
        }

        String writerNodeId() {
            return writerNodeId;
        }

        int partition() {
            return partition;
        }

        long manifestCycle() {
            return manifestCycle;
        }

        List<String> connectionIds() {
            return connectionIds;
        }
    }

    private record WriterPartitionKey(String writerNodeId, int partition) {}

    private record ConnectionKey(String writerNodeId, String connectionId) {}

    private static final class WriterPartitionState {
        private final String writerNodeId;
        private final int partition;
        private final AtomicLong manifestCycle = new AtomicLong();
        private final Set<String> connectionIds = new HashSet<>();
        private WriterStatus status = WriterStatus.INITIALIZING;

        private WriterPartitionState(String writerNodeId, int partition) {
            this.writerNodeId = writerNodeId;
            this.partition = partition;
        }
    }

    private record CurrentAssignment(String writerNodeId, List<Integer> partitions) {}

    private static final Comparator<WriterPartitionState> WRITER_PARTITION_ORDER =
        Comparator.comparing((WriterPartitionState state) -> state.writerNodeId)
            .thenComparingInt(state -> state.partition);

    private final String captureActivationId;
    private final int topicPartitionCount;
    private final Map<WriterPartitionKey, WriterPartitionState> writerPartitions = new HashMap<>();
    private final Map<ConnectionKey, ConnectionRoute> connectionRoutes = new HashMap<>();
    private CurrentAssignment currentAssignment;
    private long assignmentSequence;
    private boolean shuttingDown;

    public CaptureRoutingState(String captureActivationId, int topicPartitionCount) {
        this.captureActivationId = requireNonBlank(captureActivationId, "captureActivationId");
        if (topicPartitionCount <= 0) {
            throw new IllegalArgumentException("topicPartitionCount must be positive");
        }
        this.topicPartitionCount = topicPartitionCount;
    }

    synchronized PendingAssignment prepareAssignment(Collection<Integer> partitions) {
        if (shuttingDown) {
            throw new IllegalStateException("Kafka capture routing is shutting down");
        }
        var validatedPartitions = validateAssignment(partitions);
        if (validatedPartitions.isEmpty()) {
            throw new IllegalArgumentException("A usable Kafka assignment must contain at least one partition");
        }
        assignmentSequence = Math.incrementExact(assignmentSequence);
        var writerNodeId = captureActivationId + ":" + assignmentSequence;
        for (var partition : validatedPartitions) {
            var key = new WriterPartitionKey(writerNodeId, partition);
            var previous = writerPartitions.putIfAbsent(
                key,
                new WriterPartitionState(writerNodeId, partition)
            );
            if (previous != null) {
                throw new IllegalStateException("Writer partition was already prepared: " + key);
            }
        }
        return new PendingAssignment(assignmentSequence, writerNodeId, validatedPartitions);
    }

    synchronized void activateAssignment(PendingAssignment assignment) {
        Objects.requireNonNull(assignment);
        if (shuttingDown) {
            throw new IllegalStateException("Kafka capture routing is shutting down");
        }
        for (var partition : assignment.partitions()) {
            var state = requireWriterPartition(assignment.writerNodeId(), partition);
            if (state.status != WriterStatus.INITIALIZING) {
                throw new IllegalStateException(
                    "Writer partition is not initializing: "
                        + assignment.writerNodeId()
                        + "/"
                        + partition
                );
            }
        }
        if (currentAssignment != null) {
            for (var partition : currentAssignment.partitions()) {
                requireWriterPartition(currentAssignment.writerNodeId(), partition).status =
                    WriterStatus.DRAINING;
            }
        }
        for (var partition : assignment.partitions()) {
            requireWriterPartition(assignment.writerNodeId(), partition).status = WriterStatus.CURRENT;
        }
        currentAssignment = new CurrentAssignment(assignment.writerNodeId(), assignment.partitions());
    }

    public synchronized ConnectionRoute admitConnection(String connectionId) {
        Objects.requireNonNull(connectionId);
        if (shuttingDown) {
            throw new IllegalStateException("Kafka capture routing is shutting down");
        }
        if (currentAssignment == null) {
            throw new IllegalStateException("Kafka capture has no usable assignment for new connections");
        }
        int partition = currentAssignment.partitions().get(
            positiveHash(connectionId) % currentAssignment.partitions().size()
        );
        var state = requireWriterPartition(currentAssignment.writerNodeId(), partition);
        var key = new ConnectionKey(currentAssignment.writerNodeId(), connectionId);
        if (connectionRoutes.containsKey(key)) {
            throw new IllegalStateException(
                "Connection "
                    + connectionId
                    + " is already registered for writer "
                    + currentAssignment.writerNodeId()
            );
        }
        if (!state.connectionIds.add(connectionId)) {
            throw new IllegalStateException(
                "Connection "
                    + connectionId
                    + " is already present for writer "
                    + state.writerNodeId
                    + " and partition "
                    + partition
            );
        }
        var route = new ConnectionRoute(
            state.writerNodeId,
            connectionId,
            partition,
            state.manifestCycle
        );
        connectionRoutes.put(key, route);
        return route;
    }

    synchronized void remove(ConnectionRoute route) {
        Objects.requireNonNull(route);
        var key = new ConnectionKey(route.writerNodeId(), route.connectionId());
        if (connectionRoutes.remove(key, route)) {
            var state = requireWriterPartition(route.writerNodeId(), route.partition());
            if (!state.connectionIds.remove(route.connectionId())) {
                throw new IllegalStateException("Connection registry is inconsistent for " + route);
            }
            return;
        }
        throw new IllegalStateException("Connection route was not registered: " + route);
    }

    synchronized List<PreparedManifest> prepareInitialManifests(PendingAssignment assignment) {
        Objects.requireNonNull(assignment);
        var manifests = new ArrayList<PreparedManifest>(assignment.partitions().size());
        for (var partition : assignment.partitions()) {
            var state = requireWriterPartition(assignment.writerNodeId(), partition);
            if (state.status != WriterStatus.INITIALIZING) {
                throw new IllegalStateException(
                    "Initial manifest requested for a writer partition that is not initializing"
                );
            }
            manifests.add(prepareManifest(state));
        }
        return List.copyOf(manifests);
    }

    synchronized List<PreparedManifest> preparePeriodicManifests() {
        var states = writerPartitions.values()
            .stream()
            .filter(state -> state.status != WriterStatus.INITIALIZING)
            .sorted(WRITER_PARTITION_ORDER)
            .toList();
        var manifests = new ArrayList<PreparedManifest>(states.size());
        states.forEach(state -> manifests.add(prepareManifest(state)));
        return List.copyOf(manifests);
    }

    synchronized int size() {
        return connectionRoutes.size();
    }

    synchronized List<Integer> assignedPartitions() {
        return currentAssignment == null ? List.of() : currentAssignment.partitions();
    }

    synchronized String currentWriterNodeId() {
        return currentAssignment == null ? null : currentAssignment.writerNodeId();
    }

    synchronized Set<String> writerNodeIds() {
        return writerPartitions.keySet()
            .stream()
            .map(WriterPartitionKey::writerNodeId)
            .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    synchronized List<String> snapshot(String writerNodeId, int partition) {
        return sortedConnections(requireWriterPartition(writerNodeId, partition));
    }

    synchronized void beginShutdown() {
        shuttingDown = true;
        currentAssignment = null;
    }

    int topicPartitionCount() {
        return topicPartitionCount;
    }

    private PreparedManifest prepareManifest(WriterPartitionState state) {
        var cycle = state.manifestCycle.getAndIncrement();
        return new PreparedManifest(
            state.writerNodeId,
            state.partition,
            cycle,
            sortedConnections(state)
        );
    }

    private List<String> sortedConnections(WriterPartitionState state) {
        var connections = new ArrayList<>(state.connectionIds);
        connections.sort(String::compareTo);
        return List.copyOf(connections);
    }

    private WriterPartitionState requireWriterPartition(String writerNodeId, int partition) {
        validatePartition(partition);
        var state = writerPartitions.get(new WriterPartitionKey(writerNodeId, partition));
        if (state == null) {
            throw new IllegalStateException(
                "Unknown writer partition " + writerNodeId + "/" + partition
            );
        }
        return state;
    }

    private List<Integer> validateAssignment(Collection<Integer> assignment) {
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

    private static String requireNonBlank(String value, String name) {
        Objects.requireNonNull(value);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    private static int positiveHash(String value) {
        return Utils.toPositive(Utils.murmur2(value.getBytes(StandardCharsets.UTF_8)));
    }
}
