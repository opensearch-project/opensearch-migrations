package org.opensearch.migrations.replay.lifecycle;

import java.util.Collection;

import org.opensearch.migrations.replay.lifecycle.ReplayIdentity.SourcePartitionKey;

/**
 * Explicit test listener for cases that exercise a source without asserting lifecycle events.
 */
public final class IgnoringSourcePartitionLifecycleListener
    implements SourcePartitionLifecycleListener {

    @Override
    public void onAssigned(Collection<SourcePartitionKey> partitions) {}

    @Override
    public void onRevoked(Collection<SourcePartitionKey> partitions) {}

    @Override
    public void onRetired(Collection<SourcePartitionKey> partitions) {}
}
