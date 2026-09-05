package org.opensearch.migrations.replay.tracing;

import org.opensearch.migrations.replay.datatypes.ISourceTrafficChannelKey;
import org.opensearch.migrations.replay.lifecycle.AsyncPermitPool;
import org.opensearch.migrations.replay.lifecycle.ConnectionActor;
import org.opensearch.migrations.replay.lifecycle.ReplayTransaction;
import org.opensearch.migrations.replay.lifecycle.TargetExchangeState;
import org.opensearch.migrations.tracing.IInstrumentConstructor;
import org.opensearch.migrations.tracing.IRootOtelContext;

public interface IRootReplayerContext extends IRootOtelContext, IInstrumentConstructor {

    ITrafficSourceContexts.IReadChunkContext createReadChunkContext();

    IReplayContexts.IChannelKeyContext createChannelContext(ISourceTrafficChannelKey tsk);

    AsyncPermitPool.Metrics getPermitPoolMetrics();

    ConnectionActor.Metrics getConnectionActorMetrics();

    TargetExchangeState.Metrics getTargetExchangeStateMetrics();

    ReplayTransaction.Metrics getReplayTransactionMetrics();
}
