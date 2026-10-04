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

import lombok.NonNull;
import org.apache.kafka.common.utils.Utils;

/**
 * Models the process-local routing and retirement state independently of Kafka I/O. A routing
 * generation creates a fresh writer identity and one writer-partition state per eligible
 * partition; it becomes active only after those partitions have established broker-time
 * heartbeats.
 *
 * <p>New connections receive an immutable writer identity and partition from the active
 * generation. When another generation becomes active, the old writer partitions move through
 * {@code DRAINING}, {@code RETIRING}, and {@code RETIRED} while their original connections finish.
 * The registries and heartbeat baselines are checked on every transition so duplicate connection
 * ownership, stale acknowledgements, or premature retirement is treated as corrupted process
 * state rather than recoverable Kafka failure.
 */
public final class CaptureRoutingState {
    enum WriterStatus {
        INITIALIZING,
        CURRENT,
        DRAINING,
        RETIRING,
        RETIRED
    }

    static final class WriterPartition {
        private final String writerNodeId;
        private final int partition;

        WriterPartition(String writerNodeId, int partition) {
            this.writerNodeId = writerNodeId;
            this.partition = partition;
        }

        String writerNodeId() {
            return writerNodeId;
        }

        int partition() {
            return partition;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof WriterPartition that)) {
                return false;
            }
            return partition == that.partition && Objects.equals(writerNodeId, that.writerNodeId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(writerNodeId, partition);
        }

        @Override
        public String toString() {
            return "WriterPartition[writerNodeId=" + writerNodeId + ", partition=" + partition + "]";
        }
    }

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

    static final class PendingRoutingGeneration {
        private final long generationSequence;
        private final String writerNodeId;
        private final List<Integer> partitions;

        private PendingRoutingGeneration(
            long generationSequence,
            String writerNodeId,
            List<Integer> partitions
        ) {
            this.generationSequence = generationSequence;
            this.writerNodeId = writerNodeId;
            this.partitions = partitions;
        }

        long generationSequence() {
            return generationSequence;
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

    private static final class ConnectionKey {
        private final String writerNodeId;
        private final String connectionId;

        private ConnectionKey(String writerNodeId, String connectionId) {
            this.writerNodeId = writerNodeId;
            this.connectionId = connectionId;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof ConnectionKey that)) {
                return false;
            }
            return Objects.equals(writerNodeId, that.writerNodeId)
                && Objects.equals(connectionId, that.connectionId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(writerNodeId, connectionId);
        }

        @Override
        public String toString() {
            return "ConnectionKey[writerNodeId="
                + writerNodeId
                + ", connectionId="
                + connectionId
                + "]";
        }
    }

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

    private static final class ActiveRoutingGeneration {
        private final String writerNodeId;
        private final List<Integer> partitions;

        private ActiveRoutingGeneration(String writerNodeId, List<Integer> partitions) {
            this.writerNodeId = writerNodeId;
            this.partitions = partitions;
        }

        private String writerNodeId() {
            return writerNodeId;
        }

        private List<Integer> partitions() {
            return partitions;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof ActiveRoutingGeneration that)) {
                return false;
            }
            return Objects.equals(writerNodeId, that.writerNodeId)
                && Objects.equals(partitions, that.partitions);
        }

        @Override
        public int hashCode() {
            return Objects.hash(writerNodeId, partitions);
        }

        @Override
        public String toString() {
            return "ActiveRoutingGeneration[writerNodeId="
                + writerNodeId
                + ", partitions="
                + partitions
                + "]";
        }
    }

    private static final Comparator<WriterPartitionState> WRITER_PARTITION_ORDER =
        Comparator.comparing((WriterPartitionState state) -> state.writerNodeId)
            .thenComparingInt(state -> state.partition);

    private final String captureActivationId;
    private final int topicPartitionCount;
    private final Map<WriterPartition, WriterPartitionState> writerPartitions = new HashMap<>();
    private final Map<ConnectionKey, ConnectionRoute> connectionRoutes = new HashMap<>();
    private CompletableFuture<Void> noConnections = CompletableFuture.completedFuture(null);
    private ActiveRoutingGeneration activeRoutingGeneration;
    private long routingGenerationSequence;
    private boolean shuttingDown;

    public CaptureRoutingState(String captureActivationId, int topicPartitionCount) {
        this.captureActivationId = requireNonBlank(captureActivationId, "captureActivationId");
        if (topicPartitionCount <= 0) {
            throw new IllegalArgumentException("topicPartitionCount must be positive");
        }
        this.topicPartitionCount = topicPartitionCount;
    }

    synchronized PendingRoutingGeneration prepareRoutingGeneration(
        Collection<Integer> partitions
    ) {
        if (shuttingDown) {
            throw new IllegalStateException("Kafka capture routing is shutting down");
        }
        var validatedPartitions = validateRoutingPartitions(partitions);
        if (validatedPartitions.isEmpty()) {
            throw new IllegalArgumentException(
                "A routing generation must contain at least one partition"
            );
        }
        routingGenerationSequence = Math.incrementExact(routingGenerationSequence);
        var writerNodeId = captureActivationId + ":" + routingGenerationSequence;
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
        return new PendingRoutingGeneration(
            routingGenerationSequence,
            writerNodeId,
            validatedPartitions
        );
    }

    synchronized void activateRoutingGeneration(
        @NonNull PendingRoutingGeneration generation
    ) {
        if (shuttingDown) {
            throw new IllegalStateException("Kafka capture routing is shutting down");
        }
        for (var writerPartition : generation.writerPartitions()) {
            var state = requireWriterPartition(writerPartition);
            if (state.status != WriterStatus.INITIALIZING
                || state.lastAcceptedHeartbeatLogAppendTime == null) {
                throw new CorruptedCaptureStateException(
                    "Writer partition is not ready for routing activation: " + writerPartition
                );
            }
        }
        if (activeRoutingGeneration != null) {
            for (var partition : activeRoutingGeneration.partitions()) {
                var oldState = requireWriterPartition(
                    new WriterPartition(activeRoutingGeneration.writerNodeId(), partition)
                );
                if (oldState.status != WriterStatus.CURRENT) {
                    throw new CorruptedCaptureStateException(
                        "Active routing generation contains a non-current writer partition: "
                            + oldState.key()
                    );
                }
                oldState.status = WriterStatus.DRAINING;
            }
        }
        for (var writerPartition : generation.writerPartitions()) {
            requireWriterPartition(writerPartition).status = WriterStatus.CURRENT;
        }
        activeRoutingGeneration = new ActiveRoutingGeneration(
            generation.writerNodeId(),
            generation.partitions()
        );
    }

    public synchronized ConnectionRoute routeNewConnection(@NonNull String connectionId) {
        if (shuttingDown) {
            throw new IllegalStateException("Kafka capture routing is shutting down");
        }
        if (activeRoutingGeneration == null) {
            throw new IllegalStateException(
                "Kafka capture has no active routing generation for new connections"
            );
        }
        int partition = activeRoutingGeneration.partitions().get(
            positiveHash(connectionId) % activeRoutingGeneration.partitions().size()
        );
        var writerPartition = new WriterPartition(
            activeRoutingGeneration.writerNodeId(),
            partition
        );
        var state = requireWriterPartition(writerPartition);
        if (state.status != WriterStatus.CURRENT) {
            throw new CorruptedCaptureStateException(
                "New connection routed to a writer partition that is not current: " + writerPartition
            );
        }
        var key = new ConnectionKey(activeRoutingGeneration.writerNodeId(), connectionId);
        if (connectionRoutes.containsKey(key) || !state.connectionIds.add(connectionId)) {
            throw new CorruptedCaptureStateException(
                "Connection "
                    + connectionId
                    + " is already registered for writer "
                    + activeRoutingGeneration.writerNodeId()
            );
        }
        if (connectionRoutes.isEmpty()) {
            noConnections = new CompletableFuture<>();
        }
        var route = new ConnectionRoute(state.writerNodeId, connectionId, partition);
        connectionRoutes.put(key, route);
        return route;
    }

    synchronized void acceptTrafficSubmission(@NonNull ConnectionRoute route, boolean terminal) {
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

    synchronized void removeAfterTerminalAcknowledgement(@NonNull ConnectionRoute route) {
        if (!route.terminalSubmissionAccepted) {
            throw new CorruptedCaptureStateException(
                "Connection route has no accepted terminal record: " + route
            );
        }
        removeRegisteredRoute(route);
    }

    synchronized void abandonUnpublishedConnection(@NonNull ConnectionRoute route) {
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
        @NonNull WriterPartition writerPartition,
        long heartbeatLogAppendTime,
        Duration expirationInterval
    ) {
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
        @NonNull ConnectionRoute route,
        long observationLogAppendTime,
        Duration expirationInterval
    ) {
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
        if (activeRoutingGeneration != null) {
            for (var partition : activeRoutingGeneration.partitions()) {
                var state = requireWriterPartition(
                    new WriterPartition(activeRoutingGeneration.writerNodeId(), partition)
                );
                if (state.status != WriterStatus.CURRENT) {
                    throw new CorruptedCaptureStateException(
                        "Current writer partition is not current: " + state.key()
                    );
                }
                state.status = WriterStatus.DRAINING;
            }
            activeRoutingGeneration = null;
        }
    }

    synchronized boolean allWriterPartitionsRetired() {
        return writerPartitions.values()
            .stream()
            .allMatch(state -> state.status == WriterStatus.RETIRED);
    }

    synchronized void beginShutdown() {
        shuttingDown = true;
        activeRoutingGeneration = null;
    }

    synchronized int size() {
        return connectionRoutes.size();
    }

    synchronized List<Integer> activeRoutingPartitions() {
        return activeRoutingGeneration == null
            ? List.of()
            : activeRoutingGeneration.partitions();
    }

    synchronized String currentWriterNodeId() {
        return activeRoutingGeneration == null
            ? null
            : activeRoutingGeneration.writerNodeId();
    }

    synchronized Set<String> writerNodeIds() {
        return writerPartitions.keySet()
            .stream()
            .map(WriterPartition::writerNodeId)
            .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    synchronized WriterStatus writerStatus(String writerNodeId, int partition) {
        return requireWriterPartition(new WriterPartition(writerNodeId, partition)).status;
    }

    synchronized boolean hasConnections(WriterPartition writerPartition) {
        return !requireWriterPartition(writerPartition).connectionIds.isEmpty();
    }

    private WriterPartitionState requireWriterPartition(WriterPartition writerPartition) {
        validatePartition(writerPartition.partition());
        var state = writerPartitions.get(writerPartition);
        if (state == null) {
            throw new CorruptedCaptureStateException("Unknown writer partition " + writerPartition);
        }
        return state;
    }

    private List<Integer> validateRoutingPartitions(@NonNull Collection<Integer> partitions) {
        var unique = new TreeSet<Integer>();
        for (var partition : partitions) {
            validatePartition(partition);
            if (!unique.add(partition)) {
                throw new IllegalArgumentException("routing partitions must be unique");
            }
        }
        return List.copyOf(unique);
    }

    private void validatePartition(int partition) {
        if (partition < 0 || partition >= topicPartitionCount) {
            throw new IllegalArgumentException("partition is outside the traffic topic");
        }
    }

    private static String requireNonBlank(@NonNull String value, String name) {
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    private static Duration requirePositive(@NonNull Duration value, String name) {
        if (value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value;
    }

    private static int positiveHash(String value) {
        return Utils.toPositive(Utils.murmur2(value.getBytes(StandardCharsets.UTF_8)));
    }
}
