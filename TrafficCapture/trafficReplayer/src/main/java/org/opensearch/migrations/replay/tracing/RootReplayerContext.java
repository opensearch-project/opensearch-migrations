package org.opensearch.migrations.replay.tracing;

import io.opentelemetry.api.OpenTelemetry;

import org.opensearch.migrations.replay.datatypes.ISourceTrafficChannelKey;
import org.opensearch.migrations.replay.datatypes.ITrafficStreamKey;
import org.opensearch.migrations.replay.traffic.source.InputStreamOfTraffic;
import org.opensearch.migrations.tracing.IContextTracker;
import org.opensearch.migrations.tracing.RootOtelContext;

import lombok.Getter;

@Getter
public class RootReplayerContext extends RootOtelContext implements IRootReplayerContext {
    public static final String SCOPE_NAME = "replayer";

    public final KafkaConsumerContexts.AsyncListeningContext.MetricInstruments asyncListeningInstruments;
    public final KafkaConsumerContexts.TouchScopeContext.MetricInstruments touchInstruments;
    public final KafkaConsumerContexts.PollScopeContext.MetricInstruments pollInstruments;
    public final KafkaConsumerContexts.CommitScopeContext.MetricInstruments commitInstruments;
    public final KafkaConsumerContexts.KafkaCommitScopeContext.MetricInstruments kafkaCommitInstruments;

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
    public final ReplayContexts.RequestSendingContext.MetricInstruments requestSendingInstruments;
    public final ReplayContexts.WaitingForHttpResponseContext.MetricInstruments waitingForHttpResponseInstruments;
    public final ReplayContexts.ReceivingHttpResponseContext.MetricInstruments receivingHttpInstruments;
    public final ReplayContexts.TupleHandlingContext.MetricInstruments tupleHandlingInstruments;
    public final ReplayContexts.SocketContext.MetricInstruments socketInstruments;

    public RootReplayerContext(OpenTelemetry sdk, IContextTracker contextTracker) {
        super(SCOPE_NAME, contextTracker, sdk);
        var meter = this.getMeterProvider().get(SCOPE_NAME);

        asyncListeningInstruments = KafkaConsumerContexts.AsyncListeningContext.makeMetrics(meter);
        touchInstruments = KafkaConsumerContexts.TouchScopeContext.makeMetrics(meter);
        pollInstruments = KafkaConsumerContexts.PollScopeContext.makeMetrics(meter);
        commitInstruments = KafkaConsumerContexts.CommitScopeContext.makeMetrics(meter);
        kafkaCommitInstruments = KafkaConsumerContexts.KafkaCommitScopeContext.makeMetrics(meter);

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
        requestSendingInstruments = ReplayContexts.RequestSendingContext.makeMetrics(meter);
        waitingForHttpResponseInstruments = ReplayContexts.WaitingForHttpResponseContext.makeMetrics(meter);
        receivingHttpInstruments = ReplayContexts.ReceivingHttpResponseContext.makeMetrics(meter);
        tupleHandlingInstruments = ReplayContexts.TupleHandlingContext.makeMetrics(meter);
    }

    @Override
    public TrafficSourceContexts.ReadChunkContext createReadChunkContext() {
        return new TrafficSourceContexts.ReadChunkContext(this, null);
    }

    public IReplayContexts.IChannelKeyContext createChannelContext(ISourceTrafficChannelKey tsk) {
        return new ReplayContexts.ChannelKeyContext(this, null, tsk);
    }

    public IKafkaConsumerContexts.ICommitScopeContext createCommitContext() {
        return new KafkaConsumerContexts.CommitScopeContext(this, null);
    }

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
}
