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

*/
// REBUILD-LIMBO-END(G11)
/**
 * Fails immediately if source lifecycle events arrive before the composition root installs their
 * required owner. This is intentionally not a no-op fallback.
 */
// REBUILD-LIMBO-START(G11)
/*
public final class UnconfiguredSourcePartitionLifecycleListener implements SourcePartitionLifecycleListener {
    @Override
    public void onAssigned(Collection<SourcePartitionKey> partitions) {
        throw missingListener("assignment", partitions);
    }

    @Override
    public void onRevoked(Collection<SourcePartitionKey> partitions) {
        throw missingListener("revocation", partitions);
    }

    @Override
    public void onRetired(Collection<SourcePartitionKey> partitions) {
        throw missingListener("retirement", partitions);
    }

    private static IllegalStateException missingListener(
        String event,
        Collection<SourcePartitionKey> partitions
    ) {
        return new IllegalStateException(
            "source partition lifecycle listener was not configured before " + event + ": " + partitions
        );
    }
}

*/
// REBUILD-LIMBO-END(G11)