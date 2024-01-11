package org.opensearch.migrations.replay.tracing;

import org.opensearch.migrations.replay.datatypes.ITrafficStreamKey;
import org.opensearch.migrations.tracing.IInstrumentConstructor;
import org.opensearch.migrations.tracing.IRootOtelContext;

public interface IRootReplayerContext extends IRootOtelContext, IInstrumentConstructor {

    ITrafficSourceContexts.IReadChunkContext createReadChunkContext();
    IReplayContexts.IChannelKeyContext createChannelContext(ITrafficStreamKey tsk);
}
