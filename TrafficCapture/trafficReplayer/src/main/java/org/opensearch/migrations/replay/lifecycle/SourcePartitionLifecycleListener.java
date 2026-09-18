package org.opensearch.migrations.replay.lifecycle;

import java.util.Collection;

import org.opensearch.migrations.replay.lifecycle.ReplayIdentity.SourcePartitionKey;

public interface SourcePartitionLifecycleListener {
    void onAssigned(Collection<SourcePartitionKey> partitions);

    void onRevoked(Collection<SourcePartitionKey> partitions);

    /**
     * Signals that the source cannot deliver more records from these generations and all
     * source-created termination work has settled.
     */
    void onRetired(Collection<SourcePartitionKey> partitions);

    static SourcePartitionLifecycleListener combine(SourcePartitionLifecycleListener... listeners) {
        var immutableListeners = java.util.List.of(listeners);
        return new SourcePartitionLifecycleListener() {
            @Override
            public void onAssigned(Collection<SourcePartitionKey> partitions) {
                immutableListeners.forEach(listener -> listener.onAssigned(partitions));
            }

            @Override
            public void onRevoked(Collection<SourcePartitionKey> partitions) {
                immutableListeners.forEach(listener -> listener.onRevoked(partitions));
            }

            @Override
            public void onRetired(Collection<SourcePartitionKey> partitions) {
                immutableListeners.forEach(listener -> listener.onRetired(partitions));
            }
        };
    }
}
