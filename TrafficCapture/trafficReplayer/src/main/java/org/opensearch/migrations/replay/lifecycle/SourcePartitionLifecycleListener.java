package org.opensearch.migrations.replay.lifecycle;

import java.util.Collection;

import org.opensearch.migrations.replay.lifecycle.ReplayIdentity.SourcePartitionKey;

public interface SourcePartitionLifecycleListener {
    SourcePartitionLifecycleListener NO_OP = new SourcePartitionLifecycleListener() {
        @Override
        public void onAssigned(Collection<SourcePartitionKey> partitions) {
            // This listener intentionally ignores assignment changes.
        }

        @Override
        public void onRevoked(Collection<SourcePartitionKey> partitions) {
            // This listener intentionally ignores revocation changes.
        }

        @Override
        public void onRetired(Collection<SourcePartitionKey> partitions) {
            // This listener intentionally ignores retirement changes.
        }
    };

    void onAssigned(Collection<SourcePartitionKey> partitions);

    void onRevoked(Collection<SourcePartitionKey> partitions);

    /**
     * Signals that the source cannot deliver more records from these generations and all
     * source-created termination work has settled.
     */
    default void onRetired(Collection<SourcePartitionKey> partitions) {}

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
