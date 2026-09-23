package org.opensearch.migrations.replay.tracing;

// REBUILD-LIMBO(G2) -- nothing in this file is live yet. Javadoc is left outside the marked
// regions so it needs no escaping and keeps its blame; it documents code that is not compiled.
// Resolve each region to dead, keep, or refactor deliberately. If a member is deleted, delete its
// javadoc with it. See AGENTS.md section 8a.
// Cascade from the left-behind legacy set. Unresolved: ISourceTrafficChannelKey ReplayTransaction TargetExchangeState . Carried byte-identical so the behaviour stays enumerable; its milestone strips the legacy references and un-marks it.
// Un-mark a member by deleting the delimiter lines around it and splitting this region; the
// code between them is verbatim, so blame survives. Read this before writing anything new

// REBUILD-LIMBO-START(G2)
/*

import org.opensearch.migrations.replay.ReplayProcessFatalHandler;
import org.opensearch.migrations.replay.datatypes.ISourceTrafficChannelKey;
import org.opensearch.migrations.replay.kafka.TrackingKafkaConsumer;
import org.opensearch.migrations.replay.lifecycle.TargetAttemptPermitProvider;
import org.opensearch.migrations.replay.lifecycle.ReplayTransaction;
import org.opensearch.migrations.replay.lifecycle.ResourceOwnership;
import org.opensearch.migrations.replay.lifecycle.TargetConnectionOwner;
import org.opensearch.migrations.replay.lifecycle.TargetExchangeState;
import org.opensearch.migrations.tracing.IInstrumentConstructor;
import org.opensearch.migrations.tracing.IRootOtelContext;

public interface IRootReplayerContext extends IRootOtelContext, IInstrumentConstructor {

    ITrafficSourceContexts.IReadChunkContext createReadChunkContext();

    IReplayContexts.IChannelKeyContext createChannelContext(ISourceTrafficChannelKey tsk);

    TargetAttemptPermitProvider.Metrics getPermitPoolMetrics();

    TargetConnectionOwner.Metrics getConnectionActorMetrics();

    TargetExchangeState.Metrics getTargetExchangeStateMetrics();

    ReplayTransaction.Metrics getReplayTransactionMetrics();

    TrackingKafkaConsumer.Metrics getKafkaCommitStateMetrics();

    ResourceOwnership.Metrics getResourceOwnershipMetrics();

    ReplayProcessFatalHandler.Metrics getReplayProcessFatalMetrics();
}

*/
// REBUILD-LIMBO-END(G2)