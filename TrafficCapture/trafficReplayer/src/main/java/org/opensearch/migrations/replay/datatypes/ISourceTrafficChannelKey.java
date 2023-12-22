package org.opensearch.migrations.replay.datatypes;

import lombok.NonNull;
import org.opensearch.migrations.replay.tracing.IReplayContexts;

public interface ISourceTrafficChannelKey {
    String getNodeId();
    String getConnectionId();
    @NonNull IReplayContexts.ITrafficStreamsLifecycleContext getTrafficStreamsContext();
}
