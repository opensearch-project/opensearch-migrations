package org.opensearch.migrations.replay.datatypes;

import lombok.NonNull;
import org.opensearch.migrations.replay.tracing.IReplayContexts;
import org.opensearch.migrations.replay.tracing.IRootReplayerContext;
import org.opensearch.migrations.tracing.IInstrumentConstructor;

public interface ISourceTrafficChannelKey {
    String getNodeId();
    String getConnectionId();
    @NonNull IReplayContexts.ITrafficStreamsLifecycleContext<IInstrumentConstructor> getTrafficStreamsContext();
}
