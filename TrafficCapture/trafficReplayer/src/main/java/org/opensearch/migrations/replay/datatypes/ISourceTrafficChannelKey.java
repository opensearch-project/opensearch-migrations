package org.opensearch.migrations.replay.datatypes;

import lombok.NonNull;
import org.opensearch.migrations.replay.tracing.IChannelKeyContext;
import org.opensearch.migrations.replay.tracing.IContexts;

public interface ISourceTrafficChannelKey {
    String getNodeId();
    String getConnectionId();
    @NonNull IContexts.ITrafficStreamsLifecycleContext getTrafficStreamsContext();
}
