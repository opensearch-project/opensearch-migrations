package org.opensearch.migrations.replay.datatypes;

import org.opensearch.migrations.replay.tracing.IReplayContexts;

import lombok.NonNull;

public interface ITrafficStreamKey extends ISourceTrafficChannelKey {
    int getTrafficStreamIndex();

    @NonNull
    IReplayContexts.ITrafficStreamsLifecycleContext getTrafficStreamsContext();

    /**
     * Returns a monotonically increasing generation counter that is incremented each time
     * the underlying source reassigns ownership of this key's partition (e.g. a Kafka
     * consumer-group rebalance).  Non-Kafka sources always return 0.
     */
    default int getSourceGeneration() {
        return 0;
    }
}
