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
    };

    void onAssigned(Collection<SourcePartitionKey> partitions);

    void onRevoked(Collection<SourcePartitionKey> partitions);
}
