package org.opensearch.migrations.trafficcapture.kafkaoffloader;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Exact registry whose synchronized operations define registration, removal, and snapshot linearization points.
 */
public class ProxyLivenessRegistry {
    private final Map<String, Integer> connectionPartitions = new HashMap<>();

    public synchronized void register(String connectionId, int partition) {
        var previous = connectionPartitions.putIfAbsent(connectionId, partition);
        if (previous != null) {
            throw new IllegalStateException(
                "Connection " + connectionId + " is already registered for partition " + previous
            );
        }
    }

    public synchronized void remove(String connectionId, int expectedPartition) {
        if (!connectionPartitions.remove(connectionId, expectedPartition)) {
            throw new IllegalStateException(
                "Connection "
                    + connectionId
                    + " was not registered for expected partition "
                    + expectedPartition
            );
        }
    }

    public synchronized int partitionFor(String connectionId) {
        var partition = connectionPartitions.get(connectionId);
        if (partition == null) {
            throw new IllegalStateException("Connection " + connectionId + " is not registered");
        }
        return partition;
    }

    public synchronized List<String> snapshot(int partition) {
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
        return Set.copyOf(connectionPartitions.values());
    }

    public synchronized int size() {
        return connectionPartitions.size();
    }
}
