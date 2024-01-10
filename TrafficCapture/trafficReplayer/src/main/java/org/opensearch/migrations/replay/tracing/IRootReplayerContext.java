package org.opensearch.migrations.replay.tracing;

import org.opensearch.migrations.tracing.IInstrumentConstructor;
import org.opensearch.migrations.tracing.IRootOtelContext;

public interface IRootReplayerContext extends IRootOtelContext<IRootReplayerContext>, IInstrumentConstructor<IRootReplayerContext> {
}
