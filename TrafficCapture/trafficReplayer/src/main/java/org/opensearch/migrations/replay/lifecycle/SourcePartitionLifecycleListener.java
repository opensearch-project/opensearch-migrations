package org.opensearch.migrations.replay.lifecycle;

// REBUILD-LIMBO(G11) -- nothing in this file is live yet. Javadoc is left outside the marked
// regions so it needs no escaping and keeps its blame; it documents code that is not compiled.
// Resolve each region to dead, keep, or refactor deliberately. If a member is deleted, delete its
// javadoc with it. See AGENTS.md section 8a.
// Carried verbatim. This was the pre-rebuild implementation of a responsibility the design
// reassigns, so it is the input to that refactor rather than something to re-derive. Resolve it to
// dead, keep, or refactor deliberately -- see AGENTS.md section 8a, and read this before writing

// REBUILD-LIMBO-START(G11)
/*

import java.util.Collection;

import org.opensearch.migrations.replay.lifecycle.ReplayIdentity.SourcePartitionKey;

public interface SourcePartitionLifecycleListener {
    void onAssigned(Collection<SourcePartitionKey> partitions);

    void onRevoked(Collection<SourcePartitionKey> partitions);

*/
// REBUILD-LIMBO-END(G11)
    /**
     * Signals that the source cannot deliver more records from these generations and all
     * source-created termination work has settled.
     */
// REBUILD-LIMBO-START(G11)
/*
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

*/
// REBUILD-LIMBO-END(G11)