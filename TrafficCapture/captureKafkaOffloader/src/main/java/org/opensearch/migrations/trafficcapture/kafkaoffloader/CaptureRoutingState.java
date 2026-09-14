package org.opensearch.migrations.trafficcapture.kafkaoffloader;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
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
import java.util.concurrent.CompletableFuture;

import org.apache.kafka.common.utils.Utils;

/**
 * Owns assignment-scoped writer identities, immutable connection routes, local connection
 * registries, and accepted heartbeat broker-time baselines.
 */
public final class CaptureRoutingState {
    enum WriterStatus {
        INITIALIZING,
        CURRENT,
        DRAINING,
        RETIRING,
        RETIRED
    }

    record WriterPartition(String writerNodeId, int partition) {}

    static final class ConnectionRoute {
        private final String writerNodeId;
        private final String connectionId;
        private final int partition;
        private boolean trafficSubmissionAccepted;
        private boolean terminalSubmissionAccepted;

        private ConnectionRoute(String writerNodeId, String connectionId, int partition) {
            this.writerNodeId = writerNodeId;
            this.connectionId = connectionId;
            this.partition = partition;
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

        WriterPartition writerPartition() {
            return new WriterPartition(writerNodeId, partition);
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

        List<WriterPartition> writerPartitions() {
            return partitions.stream()
                .map(partition -> new WriterPartition(writerNodeId, partition))
                .toList();
        }
    }

    private record ConnectionKey(String writerNodeId, String connectionId) {}

    private static final class WriterPartitionState {
        private final String writerNodeId;
        private final int partition;
        private final Set<String> connectionIds = new HashSet<>();
        private WriterStatus status = WriterStatus.INITIALIZING;
        private Long lastAcceptedHeartbeatLogAppendTime;

        private WriterPartitionState(String writerNodeId, int partition) {
            this.writerNodeId = writerNodeId;
            this.partition = partition;
        }

        private WriterPartition key() {
            return new WriterPartition(writerNodeId, partition);
        }
    }

    private record CurrentAssignment(String writerNodeId, List<Integer> partitions) {}

    private static final Comparator<WriterPartitionState> WRITER_PARTITION_ORDER =
        Comparator.comparing((WriterPartitionState state) -> state.writerNodeId)
            .thenComparingInt(state -> state.partition);

    private final String captureActivationId;
    private final int topicPartitionCount;
    private final Map<WriterPartition, WriterPartitionState> writerPartitions = new HashMap<>();
    private final Map<ConnectionKey, ConnectionRoute> connectionRoutes = new HashMap<>();
    private CompletableFuture<Void> noConnections = CompletableFuture.completedFuture(null);
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
            var key = new WriterPartition(writerNodeId, partition);
            var previous = writerPartitions.putIfAbsent(
                key,
                new WriterPartitionState(writerNodeId, partition)
            );
            if (previous != null) {
                throw new CorruptedCaptureStateException("Writer partition was already prepared: " + key);
            }
        }
        return new PendingAssignment(assignmentSequence, writerNodeId, validatedPartitions);
    }

    synchronized void activateAssignment(PendingAssignment assignment) {
        Objects.requireNonNull(assignment);
        if (shuttingDown) {
            throw new IllegalStateException("Kafka capture routing is shutting down");
        }
        for (var writerPartition : assignment.writerPartitions()) {
            var state = requireWriterPartition(writerPartition);
            if (state.status != WriterStatus.INITIALIZING
                || state.lastAcceptedHeartbeatLogAppendTime == null) {
                throw new CorruptedCaptureStateException(
                    "Writer partition is not ready for assignment activation: " + writerPartition
                );
            }
        }
        if (currentAssignment != null) {
            for (var partition : currentAssignment.partitions()) {
                var oldState = requireWriterPartition(
                    new WriterPartition(currentAssignment.writerNodeId(), partition)
                );
                if (oldState.status != WriterStatus.CURRENT) {
                    throw new CorruptedCaptureStateException(
                        "Current assignment contains a non-current writer partition: " + oldState.key()
                    );
                }
                oldState.status = WriterStatus.DRAINING;
            }
        }
        for (var writerPartition : assignment.writerPartitions()) {
            requireWriterPartition(writerPartition).status = WriterStatus.CURRENT;
        }
        currentAssignment = new CurrentAssignment(assignment.writerNodeId(), assignment.partitions());
    }

    public synchronized ConnectionRoute routeNewConnection(String connectionId) {
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
        var writerPartition = new WriterPartition(currentAssignment.writerNodeId(), partition);
        var state = requireWriterPartition(writerPartition);
        if (state.status != WriterStatus.CURRENT) {
            throw new CorruptedCaptureStateException(
                "New connection routed to a writer partition that is not current: " + writerPartition
            );
        }
        var key = new ConnectionKey(currentAssignment.writerNodeId(), connectionId);
        if (connectionRoutes.containsKey(key) || !state.connectionIds.add(connectionId)) {
            throw new CorruptedCaptureStateException(
                "Connection "
                    + connectionId
                    + " is already registered for writer "
                    + currentAssignment.writerNodeId()
            );
        }
        if (connectionRoutes.isEmpty()) {
            noConnections = new CompletableFuture<>();
        }
        var route = new ConnectionRoute(state.writerNodeId, connectionId, partition);
        connectionRoutes.put(key, route);
        return route;
    }

    synchronized void acceptTrafficSubmission(ConnectionRoute route, boolean terminal) {
        Objects.requireNonNull(route);
        var key = new ConnectionKey(route.writerNodeId(), route.connectionId());
        if (connectionRoutes.get(key) != route) {
            throw new CorruptedCaptureStateException("Connection route is not active: " + route);
        }
        var writerState = requireWriterPartition(route.writerPartition());
        if (writerState.status == WriterStatus.RETIRING || writerState.status == WriterStatus.RETIRED) {
            throw new CorruptedCaptureStateException(
                "Traffic submission reached a retired writer partition: " + route.writerPartition()
            );
        }
        if (route.terminalSubmissionAccepted) {
            throw new CorruptedCaptureStateException(
                "Traffic submission followed the terminal record for " + route
            );
        }
        route.trafficSubmissionAccepted = true;
        route.terminalSubmissionAccepted = terminal;
    }

    synchronized void removeAfterTerminalAcknowledgement(ConnectionRoute route) {
        Objects.requireNonNull(route);
        if (!route.terminalSubmissionAccepted) {
            throw new CorruptedCaptureStateException(
                "Connection route has no accepted terminal record: " + route
            );
        }
        removeRegisteredRoute(route);
    }

    synchronized void abandonUnpublishedConnection(ConnectionRoute route) {
        Objects.requireNonNull(route);
        if (route.trafficSubmissionAccepted) {
            throw new CorruptedCaptureStateException(
                "Cannot abandon a connection after accepting traffic publication: " + route
            );
        }
        removeRegisteredRoute(route);
    }

    private void removeRegisteredRoute(ConnectionRoute route) {
        var key = new ConnectionKey(route.writerNodeId(), route.connectionId());
        if (!connectionRoutes.remove(key, route)) {
            throw new CorruptedCaptureStateException("Connection route was not registered: " + route);
        }
        var state = requireWriterPartition(route.writerPartition());
        if (!state.connectionIds.remove(route.connectionId())) {
            throw new CorruptedCaptureStateException("Connection registry is inconsistent for " + route);
        }
        if (connectionRoutes.isEmpty()) {
            noConnections.complete(null);
        }
    }

    synchronized void acceptHeartbeatLogAppendTime(
        WriterPartition writerPartition,
        long heartbeatLogAppendTime,
        Duration expirationInterval
    ) {
        Objects.requireNonNull(writerPartition);
        var expirationMillis = requirePositive(expirationInterval, "expirationInterval").toMillis();
        if (heartbeatLogAppendTime <= 0) {
            throw new IllegalStateException(
                "Kafka did not assign a positive LogAppendTime to heartbeat " + writerPartition
            );
        }
        var state = requireWriterPartition(writerPartition);
        if (state.status == WriterStatus.RETIRED) {
            throw new CorruptedCaptureStateException(
                "Heartbeat acknowledgement followed writer-partition retirement: " + writerPartition
            );
        }
        var previous = state.lastAcceptedHeartbeatLogAppendTime;
        if (previous != null) {
            var elapsed = Math.subtractExact(heartbeatLogAppendTime, previous);
            if (elapsed >= expirationMillis) {
                throw new IllegalStateException(
                    "Heartbeat broker time exceeded the configured expiration interval for "
                        + writerPartition
                        + ": previous="
                        + previous
                        + ", candidate="
                        + heartbeatLogAppendTime
                        + ", expirationMillis="
                        + expirationMillis
                );
            }
        }
        state.lastAcceptedHeartbeatLogAppendTime = heartbeatLogAppendTime;
    }

    synchronized Long lastAcceptedHeartbeatLogAppendTime(WriterPartition writerPartition) {
        return requireWriterPartition(writerPartition).lastAcceptedHeartbeatLogAppendTime;
    }

    synchronized void validateCriticalMutationTrafficAcknowledgement(
        ConnectionRoute route,
        long observationLogAppendTime,
        Duration expirationInterval
    ) {
        Objects.requireNonNull(route);
        var expirationMillis = requirePositive(expirationInterval, "expirationInterval").toMillis();
        if (observationLogAppendTime <= 0) {
            throw new IllegalStateException(
                "Kafka did not assign a positive LogAppendTime to Critical Mutation Traffic for " + route
            );
        }
        var state = requireWriterPartition(route.writerPartition());
        var baseline = state.lastAcceptedHeartbeatLogAppendTime;
        if (baseline == null) {
            throw new CorruptedCaptureStateException(
                "Critical Mutation Traffic was acknowledged before the writer partition established "
                    + "its initial heartbeat broker-time baseline"
            );
        }
        var elapsed = Math.subtractExact(observationLogAppendTime, baseline);
        if (elapsed >= expirationMillis) {
            throw new IllegalStateException(
                "Critical Mutation Traffic acknowledgement exceeded the configured heartbeat expiration "
                    + "interval for "
                    + route
                    + ": heartbeat="
                    + baseline
                    + ", observation="
                    + observationLogAppendTime
                    + ", expirationMillis="
                    + expirationMillis
            );
        }
    }

    synchronized List<WriterPartition> prepareDrainedWriterRetirements() {
        var states = writerPartitions.values()
            .stream()
            .filter(state ->
                state.status == WriterStatus.DRAINING && state.connectionIds.isEmpty()
            )
            .sorted(WRITER_PARTITION_ORDER)
            .toList();
        var retiring = new ArrayList<WriterPartition>(states.size());
        for (var state : states) {
            state.status = WriterStatus.RETIRING;
            retiring.add(state.key());
        }
        return List.copyOf(retiring);
    }

    synchronized void completeWriterRetirement(WriterPartition writerPartition) {
        var state = requireWriterPartition(writerPartition);
        if (state.status != WriterStatus.RETIRING || !state.connectionIds.isEmpty()) {
            throw new CorruptedCaptureStateException(
                "Writer partition cannot complete retirement: " + writerPartition
            );
        }
        state.status = WriterStatus.RETIRED;
    }

    synchronized CompletableFuture<Void> whenNoConnections() {
        return noConnections;
    }

    synchronized void beginOrderlyRetirement() {
        if (!connectionRoutes.isEmpty()) {
            throw new CorruptedCaptureStateException(
                "Cannot retire proxy writers while captured connections remain"
            );
        }
        shuttingDown = true;
        if (currentAssignment != null) {
            for (var partition : currentAssignment.partitions()) {
                var state = requireWriterPartition(
                    new WriterPartition(currentAssignment.writerNodeId(), partition)
                );
                if (state.status != WriterStatus.CURRENT) {
                    throw new CorruptedCaptureStateException(
                        "Current writer partition is not current: " + state.key()
                    );
                }
                state.status = WriterStatus.DRAINING;
            }
            currentAssignment = null;
        }
    }

    synchronized boolean allWriterPartitionsRetired() {
        return writerPartitions.values()
            .stream()
            .allMatch(state -> state.status == WriterStatus.RETIRED);
    }

    synchronized void beginShutdown() {
        shuttingDown = true;
        currentAssignment = null;
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
            .map(WriterPartition::writerNodeId)
            .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    synchronized List<String> snapshot(String writerNodeId, int partition) {
        return sortedConnections(requireWriterPartition(new WriterPartition(writerNodeId, partition)));
    }

    synchronized WriterStatus writerStatus(String writerNodeId, int partition) {
        return requireWriterPartition(new WriterPartition(writerNodeId, partition)).status;
    }

    synchronized boolean hasConnections(WriterPartition writerPartition) {
        return !requireWriterPartition(writerPartition).connectionIds.isEmpty();
    }

    int topicPartitionCount() {
        return topicPartitionCount;
    }

    private List<String> sortedConnections(WriterPartitionState state) {
        var connections = new ArrayList<>(state.connectionIds);
        connections.sort(String::compareTo);
        return List.copyOf(connections);
    }

    private WriterPartitionState requireWriterPartition(WriterPartition writerPartition) {
        validatePartition(writerPartition.partition());
        var state = writerPartitions.get(writerPartition);
        if (state == null) {
            throw new CorruptedCaptureStateException("Unknown writer partition " + writerPartition);
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

    private static Duration requirePositive(Duration value, String name) {
        Objects.requireNonNull(value);
        if (value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value;
    }

    private static int positiveHash(String value) {
        return Utils.toPositive(Utils.murmur2(value.getBytes(StandardCharsets.UTF_8)));
    }
}
