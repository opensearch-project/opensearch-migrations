package org.opensearch.migrations.replay.tracing;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.metrics.LongCounter;
import io.opentelemetry.api.metrics.DoubleHistogram;
import io.opentelemetry.api.metrics.LongUpDownCounter;
import io.opentelemetry.api.metrics.MeterProvider;
import org.opensearch.migrations.replay.traffic.source.ITrafficCaptureSource;
import org.opensearch.migrations.replay.traffic.source.InputStreamOfTraffic;
import org.opensearch.migrations.tracing.RootOtelContext;

import lombok.Getter;

@Getter
public class RootReplayerContext extends RootOtelContext<RootReplayerContext> implements IRootReplayerContext<RootReplayerContext> {
    public final KafkaConsumerContexts.AsyncListeningContext.MetricInstruments asyncListeningInstruments;
    public final KafkaConsumerContexts.TouchScopeContext.MetricInstruments touchInstruments;
    public final KafkaConsumerContexts.PollScopeContext.MetricInstruments pollInstruments;
    public final KafkaConsumerContexts.CommitScopeContext.MetricInstruments commitInstruments;
    public final KafkaConsumerContexts.KafkaCommitScopeContext.MetricInstruments kafkaCommitInstruments;

    public final TrafficSourceContexts.ReadChunkContext.MetricInstruments readChunkInstruments;
    public final TrafficSourceContexts.BackPressureBlockContext.MetricInstruments backPressureInstruments;
    public final TrafficSourceContexts.WaitForNextSignal.MetricInstruments waitForNextSignalInstruments;

    public final ReplayContexts.ChannelKeyContext.MetricInstruments channelKeyContext;
    public final ReplayContexts.KafkaRecordContext.MetricInstruments kafkaRecordContext;
    public final ReplayContexts.TrafficStreamsLifecycleContext.MetricInstruments trafficStreamLifecycleContext;
    public final ReplayContexts.HttpTransactionContext.MetricInstruments httpTransactionContext;
    public final ReplayContexts.RequestAccumulationContext.MetricInstruments requestAccumContext;
    public final ReplayContexts.ResponseAccumulationContext.MetricInstruments responseAccumContext;
    public final ReplayContexts.RequestTransformationContext.MetricInstruments transformationContext;
    public final ReplayContexts.ScheduledContext.MetricInstruments scheduledContext;
    public final ReplayContexts.TargetRequestContext.MetricInstruments targetRequestContext;
    public final ReplayContexts.RequestSendingContext.MetricInstruments requestSendingContext;
    public final ReplayContexts.WaitingForHttpResponseContext.MetricInstruments waitingForHttpResponseContext;
    public final ReplayContexts.ReceivingHttpResponseContext.MetricInstruments receivingHttpContext;
    public final ReplayContexts.TupleHandlingContext.MetricInstruments tupleHandlingContext;

    public final InputStreamOfTraffic.IOSTrafficStreamContext.MetricInstruments directInputStreamContext;

    public RootReplayerContext(OpenTelemetry sdk) {
        super(sdk);
        var meterProvider = this.getMeterProvider();

        asyncListeningInstruments = new KafkaConsumerContexts.AsyncListeningContext.MetricInstruments(meterProvider);
        touchInstruments = new KafkaConsumerContexts.TouchScopeContext.MetricInstruments(meterProvider);
        pollInstruments = new KafkaConsumerContexts.PollScopeContext.MetricInstruments(meterProvider);
        commitInstruments = new KafkaConsumerContexts.CommitScopeContext.MetricInstruments(meterProvider);
        kafkaCommitInstruments = new KafkaConsumerContexts.KafkaCommitScopeContext.MetricInstruments(meterProvider);

        directInputStreamContext = new InputStreamOfTraffic.IOSTrafficStreamContext.MetricInstruments(meterProvider);

        readChunkInstruments = new TrafficSourceContexts.ReadChunkContext.MetricInstruments(meterProvider);
        backPressureInstruments = new TrafficSourceContexts.BackPressureBlockContext.MetricInstruments(meterProvider);
        waitForNextSignalInstruments = new TrafficSourceContexts.WaitForNextSignal.MetricInstruments(meterProvider);


        channelKeyContext = new ReplayContexts.ChannelKeyContext.MetricInstruments(meterProvider);
        kafkaRecordContext = new ReplayContexts.KafkaRecordContext.MetricInstruments(meterProvider);
        trafficStreamLifecycleContext = new ReplayContexts.TrafficStreamsLifecycleContext.MetricInstruments(meterProvider);
        httpTransactionContext = new ReplayContexts.HttpTransactionContext.MetricInstruments(meterProvider);
        requestAccumContext = new ReplayContexts.RequestAccumulationContext.MetricInstruments(meterProvider);
        responseAccumContext = new ReplayContexts.ResponseAccumulationContext.MetricInstruments(meterProvider);
        transformationContext = new ReplayContexts.RequestTransformationContext.MetricInstruments(meterProvider);
        scheduledContext = new ReplayContexts.ScheduledContext.MetricInstruments(meterProvider);
        targetRequestContext = new ReplayContexts.TargetRequestContext.MetricInstruments(meterProvider);
        requestSendingContext = new ReplayContexts.RequestSendingContext.MetricInstruments(meterProvider);
        waitingForHttpResponseContext = new ReplayContexts.WaitingForHttpResponseContext.MetricInstruments(meterProvider);
        receivingHttpContext = new ReplayContexts.ReceivingHttpResponseContext.MetricInstruments(meterProvider);
        tupleHandlingContext = new ReplayContexts.TupleHandlingContext.MetricInstruments(meterProvider);
    }

    @Override
    public TrafficSourceContexts.ReadChunkContext createReadChunkContext() {
        return new TrafficSourceContexts.ReadChunkContext(this);
    }
}
