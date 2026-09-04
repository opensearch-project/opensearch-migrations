package org.opensearch.migrations.replay.lifecycle;

import java.util.Collection;

import org.opensearch.migrations.replay.lifecycle.ReplayIdentity.SourcePartitionKey;

public interface SourcePartitionLifecycleListener {
    SourcePartitionLifecycleListener NO_OP = new SourcePartitionLifecycleListener() {
        @Override
        public void onAssigned(Collection<SourcePartitionKey> partitions) {}

        @Override
        public void onRevoked(Collection<SourcePartitionKey> partitions) {}
    };

    void onAssigned(Collection<SourcePartitionKey> partitions);

    void onRevoked(Collection<SourcePartitionKey> partitions);
}
