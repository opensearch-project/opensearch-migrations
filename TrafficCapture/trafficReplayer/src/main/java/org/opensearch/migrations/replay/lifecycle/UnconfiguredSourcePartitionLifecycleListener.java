package org.opensearch.migrations.replay.lifecycle;

import java.util.Collection;

import org.opensearch.migrations.replay.lifecycle.ReplayIdentity.SourcePartitionKey;

/**
 * Fails immediately if source lifecycle events arrive before the composition root installs their
 * required owner. This is intentionally not a no-op fallback.
 */
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
