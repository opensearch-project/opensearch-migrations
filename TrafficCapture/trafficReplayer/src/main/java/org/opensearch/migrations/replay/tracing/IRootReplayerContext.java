package org.opensearch.migrations.replay.tracing;

import org.opensearch.migrations.tracing.IInstrumentConstructor;
import org.opensearch.migrations.tracing.IRootOtelContext;

public interface IRootReplayerContext<S extends IRootOtelContext> extends IRootOtelContext<S>, IInstrumentConstructor {

    TrafficSourceContexts.ReadChunkContext createReadChunkContext();
}
