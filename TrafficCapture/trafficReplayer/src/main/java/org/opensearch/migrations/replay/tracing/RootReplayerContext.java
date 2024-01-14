package org.opensearch.migrations.replay.tracing;

import io.opentelemetry.api.OpenTelemetry;
import org.opensearch.migrations.replay.datatypes.ISourceTrafficChannelKey;
import org.opensearch.migrations.replay.datatypes.ITrafficStreamKey;
import org.opensearch.migrations.replay.traffic.source.InputStreamOfTraffic;
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

    public RootReplayerContext(OpenTelemetry sdk) {
        super(SCOPE_NAME, sdk);
        var meter = this.getMeterProvider().get(SCOPE_NAME);

        asyncListeningInstruments = new KafkaConsumerContexts.AsyncListeningContext.MetricInstruments(meter);
        touchInstruments = new KafkaConsumerContexts.TouchScopeContext.MetricInstruments(meter);
        pollInstruments = new KafkaConsumerContexts.PollScopeContext.MetricInstruments(meter);
        commitInstruments = new KafkaConsumerContexts.CommitScopeContext.MetricInstruments(meter);
        kafkaCommitInstruments = new KafkaConsumerContexts.KafkaCommitScopeContext.MetricInstruments(meter);

        readChunkInstruments = new TrafficSourceContexts.ReadChunkContext.MetricInstruments(meter);
        backPressureInstruments = new TrafficSourceContexts.BackPressureBlockContext.MetricInstruments(meter);
        waitForNextSignalInstruments = new TrafficSourceContexts.WaitForNextSignal.MetricInstruments(meter);

        channelKeyInstruments = new ReplayContexts.ChannelKeyContext.MetricInstruments(meter);
        kafkaRecordInstruments = new ReplayContexts.KafkaRecordContext.MetricInstruments(meter);
        trafficStreamLifecycleInstruments = new ReplayContexts.TrafficStreamLifecycleContext.MetricInstruments(meter);
        httpTransactionInstruments = new ReplayContexts.HttpTransactionContext.MetricInstruments(meter);
        requestAccumInstruments = new ReplayContexts.RequestAccumulationContext.MetricInstruments(meter);
        responseAccumInstruments = new ReplayContexts.ResponseAccumulationContext.MetricInstruments(meter);
        transformationInstruments = new ReplayContexts.RequestTransformationContext.MetricInstruments(meter);
        scheduledInstruments = new ReplayContexts.ScheduledContext.MetricInstruments(meter);
        targetRequestInstruments = new ReplayContexts.TargetRequestContext.MetricInstruments(meter);
        requestSendingInstruments = new ReplayContexts.RequestSendingContext.MetricInstruments(meter);
        waitingForHttpResponseInstruments = new ReplayContexts.WaitingForHttpResponseContext.MetricInstruments(meter);
        receivingHttpInstruments = new ReplayContexts.ReceivingHttpResponseContext.MetricInstruments(meter);
        tupleHandlingInstruments = new ReplayContexts.TupleHandlingContext.MetricInstruments(meter);
    }

    @Override
    public TrafficSourceContexts.ReadChunkContext createReadChunkContext() {
        return new TrafficSourceContexts.ReadChunkContext(this, this);
    }

    public IReplayContexts.IChannelKeyContext createChannelContext(ISourceTrafficChannelKey tsk) {
        return new ReplayContexts.ChannelKeyContext(this, this, tsk);
    }

    public IKafkaConsumerContexts.ICommitScopeContext createCommitContext() {
        return new KafkaConsumerContexts.CommitScopeContext(this, this);
    }

    public IReplayContexts.ITrafficStreamsLifecycleContext
    createTrafficStreamContextForStreamSource(IReplayContexts.IChannelKeyContext channelCtx,
                                              ITrafficStreamKey tsk) {
        return new InputStreamOfTraffic.IOSTrafficStreamContext(this, channelCtx, tsk);
    }

    public IReplayContexts.IKafkaRecordContext
    createTrafficStreamContextForKafkaSource(IReplayContexts.IChannelKeyContext channelCtx,
                                             String recordId,
                                             int kafkaRecordSize) {
        return new ReplayContexts.KafkaRecordContext(this, channelCtx, recordId, kafkaRecordSize);
    }
}
