package org.opensearch.migrations.replay.tracing;

import org.opensearch.migrations.tracing.ActiveContextTracker;
import org.opensearch.migrations.tracing.ActiveContextTrackerByActivityType;
import org.opensearch.migrations.tracing.CompositeContextTracker;

// REBUILD-LIMBO-START(G5)
// Legacy context factories below still use these identities.
/*
import org.opensearch.migrations.replay.datatypes.ISourceTrafficChannelKey;
import org.opensearch.migrations.replay.datatypes.ITrafficStreamKey;
import org.opensearch.migrations.replay.traffic.source.InputStreamOfTraffic;
*/
// REBUILD-LIMBO-END(G5)
import org.opensearch.migrations.tracing.IContextTracker;
import org.opensearch.migrations.tracing.RootOtelContext;

import io.opentelemetry.api.OpenTelemetry;
import lombok.Getter;
import lombok.NonNull;

@Getter
// REBUILD-LIMBO-NOTE(G5): implements IRootReplayerContext after that legacy interface is reduced to the
// final owner-facing context API.
public class RootReplayerContext extends RootOtelContext {
    public static final String SCOPE_NAME = "replayer";

// REBUILD-LIMBO-START(G5)
// These contexts return with the source and connection tracing chain.
/*
    public final KafkaConsumerContexts.LivenessScanContext.MetricInstruments livenessScanInstruments;
    public final KafkaConsumerContexts.AsyncListeningContext.MetricInstruments asyncListeningInstruments;
    public final KafkaConsumerContexts.TouchScopeContext.MetricInstruments touchInstruments;
*/
// REBUILD-LIMBO-END(G5)
    public final KafkaConsumerContexts.PollScopeContext.MetricInstruments pollInstruments;
    public final KafkaConsumerContexts.CommitScopeContext.MetricInstruments commitInstruments;
    public final KafkaConsumerContexts.KafkaCommitScopeContext.MetricInstruments kafkaCommitInstruments;
    public final KafkaConsumerContexts.RebalanceCallbackScopeContext.MetricInstruments
        rebalanceCallbackInstruments;
    public final ReplayIntakeMetrics replayIntakeMetrics;
    public final TargetAttemptPermitMetrics targetAttemptPermitMetrics;

// REBUILD-LIMBO-START(G5)
// Instruments for owners and operations built in G5 and later.
/*
    public final ConnectionActorMetrics connectionActorMetrics;
    public final TargetExchangeStateMetrics targetExchangeStateMetrics;
    public final ReplayTransactionMetrics replayTransactionMetrics;
*/
// REBUILD-LIMBO-END(G5)
    public final KafkaCommitStateMetrics kafkaCommitStateMetrics;
// REBUILD-LIMBO-START(G5)
/*
    public final ResourceOwnershipMetrics resourceOwnershipMetrics;
    public final ReplayProcessFatalMetrics replayProcessFatalMetrics;

    public final TrafficSourceContexts.ReadChunkContext.MetricInstruments readChunkInstruments;
    public final TrafficSourceContexts.BackPressureBlockContext.MetricInstruments backPressureInstruments;
    public final TrafficSourceContexts.WaitForNextSignal.MetricInstruments waitForNextSignalInstruments;

    public final ReplayContexts.ChannelKeyContext.MetricInstruments channelKeyInstruments;
    public final ReplayContexts.KafkaRecordContext.MetricInstruments kafkaRecordInstruments;
    public final ReplayContexts.TrafficStreamLifecycleContext.MetricInstruments trafficStreamLifecycleInstruments;
    public final ReplayContexts.HttpTransactionContext.MetricInstruments httpTransactionInstruments;
    public final ReplayContexts.RequestAccumulationContext.MetricInstruments requestAccumInstruments;
    public final ReplayContexts.ResponseAccumulationContext.MetricInstruments responseAccumInstruments;
    public final ReplayContexts.RequestTransformationContext.MetricInstruments transformationInstruments;
    public final ReplayContexts.ScheduledContext.MetricInstruments scheduledInstruments;
    public final ReplayContexts.TargetRequestContext.MetricInstruments targetRequestInstruments;
    public final ReplayContexts.RequestConnectingContext.MetricInstruments requestConnectingInstruments;
    public final ReplayContexts.RequestSendingContext.MetricInstruments requestSendingInstruments;
    public final ReplayContexts.WaitingForHttpResponseContext.MetricInstruments waitingForHttpResponseInstruments;
    public final ReplayContexts.ReceivingHttpResponseContext.MetricInstruments receivingHttpInstruments;
    public final ReplayContexts.TupleHandlingContext.MetricInstruments tupleHandlingInstruments;
    public final ReplayContexts.SocketContext.MetricInstruments socketInstruments;
*/
// REBUILD-LIMBO-END(G5)

    public RootReplayerContext(@NonNull OpenTelemetry sdk) {
        this(
            sdk,
            new CompositeContextTracker(new ActiveContextTracker(), new ActiveContextTrackerByActivityType())
        );
    }

    public RootReplayerContext(@NonNull OpenTelemetry sdk, @NonNull IContextTracker contextTracker) {
        super(SCOPE_NAME, contextTracker, sdk);
        var meter = this.getMeterProvider().get(SCOPE_NAME);

// REBUILD-LIMBO-START(G5)
// Construction returns with the contexts above.
/*
        livenessScanInstruments = KafkaConsumerContexts.LivenessScanContext.makeMetrics(meter);
        asyncListeningInstruments = KafkaConsumerContexts.AsyncListeningContext.makeMetrics(meter);
        touchInstruments = KafkaConsumerContexts.TouchScopeContext.makeMetrics(meter);
*/
// REBUILD-LIMBO-END(G5)
        pollInstruments = KafkaConsumerContexts.PollScopeContext.makeMetrics(meter);
        commitInstruments = KafkaConsumerContexts.CommitScopeContext.makeMetrics(meter);
        kafkaCommitInstruments = KafkaConsumerContexts.KafkaCommitScopeContext.makeMetrics(meter);
        rebalanceCallbackInstruments =
            KafkaConsumerContexts.RebalanceCallbackScopeContext.makeMetrics(meter);
        replayIntakeMetrics = new ReplayIntakeMetrics(meter);
        targetAttemptPermitMetrics = new TargetAttemptPermitMetrics(meter);

// REBUILD-LIMBO-START(G5)
// Construction returns with the owner and operation instruments above.
/*
        connectionActorMetrics = new ConnectionActorMetrics(meter);
        targetExchangeStateMetrics = new TargetExchangeStateMetrics(meter);
        replayTransactionMetrics = new ReplayTransactionMetrics(meter);
*/
// REBUILD-LIMBO-END(G5)
        kafkaCommitStateMetrics = new KafkaCommitStateMetrics(meter);
// REBUILD-LIMBO-START(G5)
/*
        resourceOwnershipMetrics = new ResourceOwnershipMetrics(meter);
        replayProcessFatalMetrics = new ReplayProcessFatalMetrics(meter);

        readChunkInstruments = TrafficSourceContexts.ReadChunkContext.makeMetrics(meter);
        backPressureInstruments = TrafficSourceContexts.BackPressureBlockContext.makeMetrics(meter);
        waitForNextSignalInstruments = TrafficSourceContexts.WaitForNextSignal.makeMetrics(meter);

        channelKeyInstruments = ReplayContexts.ChannelKeyContext.makeMetrics(meter);
        socketInstruments = ReplayContexts.SocketContext.makeMetrics(meter);
        kafkaRecordInstruments = ReplayContexts.KafkaRecordContext.makeMetrics(meter);
        trafficStreamLifecycleInstruments = ReplayContexts.TrafficStreamLifecycleContext.makeMetrics(meter);
        httpTransactionInstruments = ReplayContexts.HttpTransactionContext.makeMetrics(meter);
        requestAccumInstruments = ReplayContexts.RequestAccumulationContext.makeMetrics(meter);
        responseAccumInstruments = ReplayContexts.ResponseAccumulationContext.makeMetrics(meter);
        transformationInstruments = ReplayContexts.RequestTransformationContext.makeMetrics(meter);
        scheduledInstruments = ReplayContexts.ScheduledContext.makeMetrics(meter);
        targetRequestInstruments = ReplayContexts.TargetRequestContext.makeMetrics(meter);
        requestConnectingInstruments = ReplayContexts.RequestConnectingContext.makeMetrics(meter);
        requestSendingInstruments = ReplayContexts.RequestSendingContext.makeMetrics(meter);
        waitingForHttpResponseInstruments = ReplayContexts.WaitingForHttpResponseContext.makeMetrics(meter);
        receivingHttpInstruments = ReplayContexts.ReceivingHttpResponseContext.makeMetrics(meter);
        tupleHandlingInstruments = ReplayContexts.TupleHandlingContext.makeMetrics(meter);
*/
// REBUILD-LIMBO-END(G5)
    }

// REBUILD-LIMBO-START(G5)
// Legacy source and channel factories are refactored with their consumers.
/*
    @Override
    public TrafficSourceContexts.ReadChunkContext createReadChunkContext() {
        return new TrafficSourceContexts.ReadChunkContext(this, null);
    }

    public IReplayContexts.IChannelKeyContext createChannelContext(ISourceTrafficChannelKey tsk) {
        return new ReplayContexts.ChannelKeyContext(this, null, tsk);
    }
*/
// REBUILD-LIMBO-END(G5)

    public IKafkaConsumerContexts.IPollScopeContext createPollContext() {
        return new KafkaConsumerContexts.PollScopeContext(this, null);
    }

    public IKafkaConsumerContexts.ICommitScopeContext createCommitContext() {
        return new KafkaConsumerContexts.CommitScopeContext(this, null);
    }

    public IKafkaConsumerContexts.IRebalanceCallbackScopeContext createRebalanceCallbackContext() {
        return new KafkaConsumerContexts.RebalanceCallbackScopeContext(this);
    }

// REBUILD-LIMBO-START(G5)
// Legacy traffic-stream factories are refactored with their consumers.
/*
    public IReplayContexts.ITrafficStreamsLifecycleContext createTrafficStreamContextForStreamSource(
        IReplayContexts.IChannelKeyContext channelCtx,
        ITrafficStreamKey tsk
    ) {
        return new InputStreamOfTraffic.IOSTrafficStreamContext(this, channelCtx, tsk);
    }

    public IReplayContexts.IKafkaRecordContext createTrafficStreamContextForKafkaSource(
        IReplayContexts.IChannelKeyContext channelCtx,
        String recordId,
        int kafkaRecordSize
    ) {
        return new ReplayContexts.KafkaRecordContext(this, channelCtx, recordId, kafkaRecordSize);
    }
*/
// REBUILD-LIMBO-END(G5)
}
