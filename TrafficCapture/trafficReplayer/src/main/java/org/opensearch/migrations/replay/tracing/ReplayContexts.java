package org.opensearch.migrations.replay.tracing;

import io.opentelemetry.api.metrics.DoubleHistogram;
import io.opentelemetry.api.metrics.LongCounter;
import io.opentelemetry.api.metrics.LongUpDownCounter;
import io.opentelemetry.api.metrics.Meter;
import io.opentelemetry.api.metrics.MeterProvider;
import lombok.Getter;
import lombok.NonNull;
import org.opensearch.migrations.replay.datatypes.ISourceTrafficChannelKey;
import org.opensearch.migrations.replay.datatypes.ITrafficStreamKey;
import org.opensearch.migrations.replay.datatypes.UniqueReplayerRequestKey;
import org.opensearch.migrations.tracing.BaseNestedSpanContext;
import org.opensearch.migrations.tracing.CommonScopedMetricInstruments;
import org.opensearch.migrations.tracing.DirectNestedSpanContext;
import org.opensearch.migrations.tracing.IInstrumentationAttributes;
import org.opensearch.migrations.tracing.IScopedInstrumentationAttributes;

import java.time.Duration;
import java.time.Instant;

public class ReplayContexts {

    public static final String COUNT_UNIT_STR = "count";
    public static final String BYTES_UNIT_STR = "bytes";

    private ReplayContexts() {}

    public static class ChannelKeyContext
            extends BaseNestedSpanContext<RootReplayerContext, IInstrumentationAttributes>
            implements IReplayContexts.IChannelKeyContext {
        @Getter
        final ISourceTrafficChannelKey channelKey;

        public ChannelKeyContext(RootReplayerContext rootScope,
                                 IInstrumentationAttributes enclosingScope,
                                 ISourceTrafficChannelKey channelKey) {
            super(rootScope, enclosingScope);
            this.channelKey = channelKey;
            initializeSpan();
        }

        public static class MetricInstruments extends CommonScopedMetricInstruments {
            final LongUpDownCounter activeChannelCounter;
            public MetricInstruments(Meter meter) {
                super(meter, ACTIVITY_NAME);
                activeChannelCounter = meter
                        .upDownCounterBuilder(IReplayContexts.MetricNames.ACTIVE_TARGET_CONNECTIONS).build();
            }
        }

        public @NonNull MetricInstruments getMetrics() {
            return getRootInstrumentationScope().channelKeyInstruments;
        }

        @Override
        public String toString() {
            return channelKey.toString();
        }

        @Override
        public void onConnectionCreated() {
            meterDeltaEvent(getMetrics().activeChannelCounter, 1);
        }
        @Override
        public void onConnectionClosed() {
            meterDeltaEvent(getMetrics().activeChannelCounter, -1);
        }
    }

    public static class KafkaRecordContext
            extends DirectNestedSpanContext<RootReplayerContext,ChannelKeyContext,IReplayContexts.IChannelKeyContext>
            implements IReplayContexts.IKafkaRecordContext {

        final String recordId;

        public KafkaRecordContext(ChannelKeyContext enclosingScope, String recordId, int recordSize) {
            super(enclosingScope);
            this.recordId = recordId;
            initializeSpan();
            meterIncrementEvent(getMetrics().recordCounter);
            meterIncrementEvent(getMetrics().bytesCounter, recordSize);
        }

        public static class MetricInstruments extends CommonScopedMetricInstruments {
            final LongCounter recordCounter;
            final LongCounter bytesCounter;
            public MetricInstruments(Meter meter) {
                super(meter, ACTIVITY_NAME);
                recordCounter = meter.counterBuilder(IReplayContexts.MetricNames.KAFKA_RECORD_READ)
                        .setUnit("records").build();
                bytesCounter = meter.counterBuilder(IReplayContexts.MetricNames.KAFKA_BYTES_READ)
                        .setUnit(BYTES_UNIT_STR).build();
            }
        }

        public @NonNull MetricInstruments getMetrics() {
            return getRootInstrumentationScope().kafkaRecordInstruments;
        }

        @Override
        public String getRecordId() {
            return recordId;
        }

        @Override
        public IReplayContexts.ITrafficStreamsLifecycleContext
        createTrafficLifecyleContext(ITrafficStreamKey tsk) {
            return new ReplayContexts.TrafficStreamsLifecycleContext(this, tsk);
        }
    }

    public static class TrafficStreamsLifecycleContext
            extends BaseNestedSpanContext<RootReplayerContext, IInstrumentationAttributes>
            implements IReplayContexts.ITrafficStreamsLifecycleContext {
        private final ITrafficStreamKey trafficStreamKey;

        protected TrafficStreamsLifecycleContext(IInstrumentationAttributes enclosingScope,
                                                 ITrafficStreamKey trafficStreamKey,
                                                 RootReplayerContext rootScope) {
            super(rootScope, enclosingScope);
            this.trafficStreamKey = trafficStreamKey;
            initializeSpan();
            meterIncrementEvent(getMetrics().streamsRead);
        }

        public TrafficStreamsLifecycleContext(KafkaRecordContext enclosingScope,
                                              ITrafficStreamKey trafficStreamKey) {
            this(enclosingScope, trafficStreamKey, enclosingScope.getRootInstrumentationScope());
        }

        protected TrafficStreamsLifecycleContext(ChannelKeyContext enclosingScope,
                                                 ITrafficStreamKey trafficStreamKey) {
            this(enclosingScope, trafficStreamKey, enclosingScope.getRootInstrumentationScope());
        }

        public static class MetricInstruments extends CommonScopedMetricInstruments {
            private final LongCounter streamsRead;

            public MetricInstruments(Meter meter) {
                super(meter, ACTIVITY_NAME);
                streamsRead = meter.counterBuilder(IReplayContexts.MetricNames.TRAFFIC_STREAMS_READ)
                        .setUnit("objects").build();
            }
        }

        public @NonNull MetricInstruments getMetrics() {
            return getRootInstrumentationScope().trafficStreamLifecycleInstruments;
        }

        @Override
        public IReplayContexts.IChannelKeyContext getChannelKeyContext() {
            return getLogicalEnclosingScope();
        }

        @Override
        public HttpTransactionContext createHttpTransactionContext(UniqueReplayerRequestKey requestKey,
                                                                   Instant sourceTimestamp) {
            return new ReplayContexts.HttpTransactionContext(getRootInstrumentationScope(),
                    this, requestKey, sourceTimestamp);
        }

        @Override
        public ITrafficStreamKey getTrafficStreamKey() {
            return trafficStreamKey;
        }

        @Override
        public IReplayContexts.IChannelKeyContext getLogicalEnclosingScope() {
            var parent = getEnclosingScope();
            while(!(parent instanceof IReplayContexts.IChannelKeyContext)) {
                parent = parent.getEnclosingScope();
            }
            return (IReplayContexts.IChannelKeyContext) parent;
        }
    }

    public static class HttpTransactionContext
            extends BaseNestedSpanContext<RootReplayerContext,IReplayContexts.ITrafficStreamsLifecycleContext>
            implements IReplayContexts.IReplayerHttpTransactionContext {
        final UniqueReplayerRequestKey replayerRequestKey;
        @Getter final Instant timeOfOriginalRequest;

        public HttpTransactionContext(RootReplayerContext rootScope,
                                      IReplayContexts.ITrafficStreamsLifecycleContext enclosingScope,
                                      UniqueReplayerRequestKey replayerRequestKey,
                                      Instant timeOfOriginalRequest) {
            super(rootScope, enclosingScope);
            this.replayerRequestKey = replayerRequestKey;
            this.timeOfOriginalRequest = timeOfOriginalRequest;
            initializeSpan();
        }

        @Override
        public IReplayContexts.ITupleHandlingContext createTupleContext() {
            return new ReplayContexts.TupleHandlingContext(this);
        }

        public static class MetricInstruments extends CommonScopedMetricInstruments {
            public MetricInstruments(Meter meter) {
                super(meter, ACTIVITY_NAME);
            }
        }

        public @NonNull MetricInstruments getMetrics() {
            return getRootInstrumentationScope().httpTransactionInstruments;
        }

        public IReplayContexts.IChannelKeyContext getChannelKeyContext() {
            return getLogicalEnclosingScope();
        }

        @Override
        public RequestTransformationContext createTransformationContext() {
            return new ReplayContexts.RequestTransformationContext(this);
        }

        @Override
        public IScopedInstrumentationAttributes createAccumulatorContext() {
            return new ReplayContexts.ResponseAccumulationContext(this);
        }

        @Override
        public TargetRequestContext createTargetRequestContext() {
            return new ReplayContexts.TargetRequestContext(this);
        }

        @Override
        public IReplayContexts.IScheduledContext createScheduledContext(Instant timestamp) {
            return new ReplayContexts.ScheduledContext(this, timestamp);
        }

        @Override
        public UniqueReplayerRequestKey getReplayerRequestKey() {
            return replayerRequestKey;
        }

        @Override
        public String toString() {
            return replayerRequestKey.toString();
        }

        @Override
        public IReplayContexts.IChannelKeyContext getLogicalEnclosingScope() {
            return getImmediateEnclosingScope().getLogicalEnclosingScope();
        }
    }

    public static class RequestAccumulationContext
            extends DirectNestedSpanContext<RootReplayerContext,HttpTransactionContext,IReplayContexts.IReplayerHttpTransactionContext>
            implements IReplayContexts.IRequestAccumulationContext {
        public RequestAccumulationContext(HttpTransactionContext enclosingScope) {
            super(enclosingScope);
            initializeSpan();
        }

        public static class MetricInstruments extends CommonScopedMetricInstruments {
            public MetricInstruments(Meter meter) {
                super(meter, ACTIVITY_NAME);
            }
        }

        public @NonNull MetricInstruments getMetrics() {
            return getRootInstrumentationScope().requestAccumInstruments;
        }
    }

    public static class ResponseAccumulationContext
            extends DirectNestedSpanContext<RootReplayerContext,HttpTransactionContext,IReplayContexts.IReplayerHttpTransactionContext>
            implements IReplayContexts.IResponseAccumulationContext {
        public ResponseAccumulationContext(HttpTransactionContext enclosingScope) {
            super(enclosingScope);
            initializeSpan();
        }

        public static class MetricInstruments extends CommonScopedMetricInstruments {
            public MetricInstruments(Meter meter) {
                super(meter, ACTIVITY_NAME);
            }
        }

        public @NonNull MetricInstruments getMetrics() {
            return getRootInstrumentationScope().responseAccumInstruments;
        }
    }

    public static class RequestTransformationContext
            extends DirectNestedSpanContext<RootReplayerContext,HttpTransactionContext,IReplayContexts.IReplayerHttpTransactionContext>
            implements IReplayContexts.IRequestTransformationContext {
        public RequestTransformationContext(HttpTransactionContext enclosingScope) {
            super(enclosingScope);
            initializeSpan();
        }

        public static class MetricInstruments extends CommonScopedMetricInstruments {
            private final LongCounter headerParses;
            private final LongCounter payloadParses;
            private final LongCounter payloadSuccessParses;
            private final LongCounter jsonPayloadParses;
            private final LongCounter jsonTransformSuccess;
            private final LongCounter payloadBytesIn;
            private final LongCounter uncompressedBytesIn;
            private final LongCounter uncompressedBytesOut;
            private final LongCounter finalPayloadBytesOut;
            private final LongCounter transformSuccess;
            private final LongCounter transformSkipped;
            private final LongCounter transformError;
            private final LongCounter transformBytesIn;
            private final LongCounter transformChunksIn;
            private final LongCounter transformBytesOut;
            private final LongCounter transformChunksOut;

            public MetricInstruments(Meter meter) {
                super(meter, ACTIVITY_NAME);
                headerParses = meter.counterBuilder(IReplayContexts.MetricNames.TRANSFORM_HEADER_PARSE)
                        .setUnit(COUNT_UNIT_STR).build();
                payloadParses = meter.counterBuilder(IReplayContexts.MetricNames.TRANSFORM_PAYLOAD_PARSE_REQUIRED)
                        .setUnit(COUNT_UNIT_STR).build();
                payloadSuccessParses = meter.counterBuilder(IReplayContexts.MetricNames.TRANSFORM_PAYLOAD_PARSE_SUCCESS)
                        .setUnit(COUNT_UNIT_STR).build();
                jsonPayloadParses = meter.counterBuilder(IReplayContexts.MetricNames.TRANSFORM_JSON_REQUIRED)
                        .setUnit(COUNT_UNIT_STR).build();
                jsonTransformSuccess = meter.counterBuilder(IReplayContexts.MetricNames.TRANSFORM_JSON_SUCCEEDED)
                        .setUnit(COUNT_UNIT_STR).build();
                payloadBytesIn = meter.counterBuilder(IReplayContexts.MetricNames.TRANSFORM_PAYLOAD_BYTES_IN)
                        .setUnit(BYTES_UNIT_STR).build();
                uncompressedBytesIn = meter.counterBuilder(IReplayContexts.MetricNames.TRANSFORM_UNCOMPRESSED_BYTES_IN)
                        .setUnit(BYTES_UNIT_STR).build();
                uncompressedBytesOut = meter.counterBuilder(IReplayContexts.MetricNames.TRANSFORM_UNCOMPRESSED_BYTES_OUT)
                        .setUnit(BYTES_UNIT_STR).build();
                finalPayloadBytesOut = meter.counterBuilder(IReplayContexts.MetricNames.TRANSFORM_FINAL_PAYLOAD_BYTES_OUT)
                        .setUnit(BYTES_UNIT_STR).build();
                transformSuccess = meter.counterBuilder(IReplayContexts.MetricNames.TRANSFORM_SUCCESS)
                        .setUnit(COUNT_UNIT_STR).build();
                transformSkipped = meter.counterBuilder(IReplayContexts.MetricNames.TRANSFORM_SKIPPED)
                        .setUnit(COUNT_UNIT_STR).build();
                transformError = meter.counterBuilder(IReplayContexts.MetricNames.TRANSFORM_ERROR)
                        .setUnit(COUNT_UNIT_STR).build();
                transformBytesIn = meter.counterBuilder(IReplayContexts.MetricNames.TRANSFORM_BYTES_IN)
                        .setUnit(BYTES_UNIT_STR).build();
                transformChunksIn = meter.counterBuilder(IReplayContexts.MetricNames.TRANSFORM_CHUNKS_IN)
                        .setUnit(COUNT_UNIT_STR).build();
                transformBytesOut = meter.counterBuilder(IReplayContexts.MetricNames.TRANSFORM_BYTES_OUT)
                        .setUnit(BYTES_UNIT_STR).build();
                transformChunksOut = meter.counterBuilder(IReplayContexts.MetricNames.TRANSFORM_CHUNKS_OUT)
                        .setUnit(COUNT_UNIT_STR).build();

            }
        }

        public @NonNull MetricInstruments getMetrics() {
            return getRootInstrumentationScope().transformationInstruments;
        }

        @Override public void onHeaderParse() {
            meterIncrementEvent(getMetrics().headerParses);
        }
        @Override  public void onPayloadParse() {
            meterIncrementEvent(getMetrics().payloadParses);
        }
        @Override  public void onPayloadParseSuccess() {
            meterIncrementEvent(getMetrics().payloadSuccessParses);
        }
        @Override public void onJsonPayloadParseRequired() {
            meterIncrementEvent(getMetrics().jsonPayloadParses);
        }
        @Override  public void onJsonPayloadParseSucceeded() {
            meterIncrementEvent(getMetrics().jsonTransformSuccess);
        }
        @Override public void onPayloadBytesIn(int inputSize) {
            meterIncrementEvent(getMetrics().payloadBytesIn, inputSize);
        }
        @Override public void onUncompressedBytesIn(int inputSize) {
            meterIncrementEvent(getMetrics().uncompressedBytesIn, inputSize);
        }
        @Override public void onUncompressedBytesOut(int inputSize) {
            meterIncrementEvent(getMetrics().uncompressedBytesOut, inputSize);
        }
        @Override public void onFinalBytesOut(int inputSize) {
            meterIncrementEvent(getMetrics().finalPayloadBytesOut, inputSize);
        }
        @Override public void onTransformSuccess() {
            meterIncrementEvent(getMetrics().transformSuccess);
        }
        @Override public void onTransformSkip() {
            meterIncrementEvent(getMetrics().transformSkipped);
        }
        @Override public void onTransformFailure() {
            meterIncrementEvent(getMetrics().transformError);
        }
        @Override public void aggregateInputChunk(int sizeInBytes) {
            meterIncrementEvent(getMetrics().transformBytesIn, sizeInBytes);
            meterIncrementEvent(getMetrics().transformChunksIn);
        }
        @Override public void aggregateOutputChunk(int sizeInBytes) {
            meterIncrementEvent(getMetrics().transformBytesOut, sizeInBytes);
            meterIncrementEvent(getMetrics().transformChunksOut);
        }
    }

    public static class ScheduledContext
            extends DirectNestedSpanContext<RootReplayerContext,HttpTransactionContext,IReplayContexts.IReplayerHttpTransactionContext>
            implements IReplayContexts.IScheduledContext {
        private final Instant scheduledFor;

        public ScheduledContext(HttpTransactionContext enclosingScope, Instant scheduledFor) {
            super(enclosingScope);
            this.scheduledFor = scheduledFor;
            initializeSpan();
        }

        public static class MetricInstruments extends CommonScopedMetricInstruments {
            DoubleHistogram lag;
            public MetricInstruments(Meter meter) {
                super(meter, ACTIVITY_NAME);
                lag = meter.histogramBuilder(IReplayContexts.MetricNames.NETTY_SCHEDULE_LAG).setUnit("ms").build();
            }
        }

        public @NonNull MetricInstruments getMetrics() {
            return getRootInstrumentationScope().scheduledInstruments;
        }

        @Override
        public void sendMeterEventsForEnd() {
            super.sendMeterEventsForEnd();
            meterHistogramMillis(getMetrics().lag, Duration.between(scheduledFor, Instant.now()));
        }
    }

    public static class TargetRequestContext
            extends DirectNestedSpanContext<RootReplayerContext,HttpTransactionContext,IReplayContexts.IReplayerHttpTransactionContext>
            implements IReplayContexts.ITargetRequestContext {
        public TargetRequestContext(HttpTransactionContext enclosingScope) {
            super(enclosingScope);
            initializeSpan();
            meterHistogramMillis(getMetrics().sourceTargetGap,
                    Duration.between(enclosingScope.getTimeOfOriginalRequest(), Instant.now()));
        }

        public static class MetricInstruments extends CommonScopedMetricInstruments {

            private final DoubleHistogram sourceTargetGap;
            private final LongCounter bytesWritten;
            private final LongCounter bytesRead;

            public MetricInstruments(Meter meter) {
                super(meter, ACTIVITY_NAME);
                sourceTargetGap = meter.histogramBuilder(IReplayContexts.MetricNames.SOURCE_TO_TARGET_REQUEST_LAG)
                        .setUnit("ms").build();
                bytesWritten = meter.counterBuilder(IReplayContexts.MetricNames.BYTES_WRITTEN_TO_TARGET)
                        .setUnit(BYTES_UNIT_STR).build();
                bytesRead = meter.counterBuilder(IReplayContexts.MetricNames.BYTES_READ_FROM_TARGET)
                        .setUnit(BYTES_UNIT_STR).build();
            }
        }

        public @NonNull MetricInstruments getMetrics() {
            return getRootInstrumentationScope().targetRequestInstruments;
        }

        @Override
        public void onBytesSent(int size) {
            meterIncrementEvent(getMetrics().bytesWritten, size);
        }

        @Override
        public void onBytesReceived(int size) {
            meterIncrementEvent(getMetrics().bytesRead, size);
        }

        @Override
        public IReplayContexts.IReceivingHttpResponseContext createHttpReceivingContext() {
            return new ReplayContexts.ReceivingHttpResponseContext(this);
        }

        @Override
        public IReplayContexts.IWaitingForHttpResponseContext createWaitingForResponseContext() {
            return new ReplayContexts.WaitingForHttpResponseContext(this);
        }
    }

    public static class RequestSendingContext
            extends DirectNestedSpanContext<RootReplayerContext,TargetRequestContext,IReplayContexts.ITargetRequestContext>
            implements IReplayContexts.IRequestSendingContext {
        public RequestSendingContext(TargetRequestContext enclosingScope) {
            super(enclosingScope);
            initializeSpan();
        }

        public static class MetricInstruments extends CommonScopedMetricInstruments {
            public MetricInstruments(Meter meter) {
                super(meter, ACTIVITY_NAME);
            }
        }

        public @NonNull MetricInstruments getMetrics() {
            return getRootInstrumentationScope().requestSendingInstruments;
        }
    }

    public static class WaitingForHttpResponseContext
            extends DirectNestedSpanContext<RootReplayerContext,TargetRequestContext,IReplayContexts.ITargetRequestContext>
            implements IReplayContexts.IWaitingForHttpResponseContext {
        public WaitingForHttpResponseContext(TargetRequestContext enclosingScope) {
            super(enclosingScope);
            initializeSpan();
        }

        public static class MetricInstruments extends CommonScopedMetricInstruments {
            public MetricInstruments(Meter meter) {
                super(meter, ACTIVITY_NAME);
            }
        }

        public @NonNull MetricInstruments getMetrics() {
            return getRootInstrumentationScope().waitingForHttpResponseInstruments;
        }

    }

    public static class ReceivingHttpResponseContext
            extends DirectNestedSpanContext<RootReplayerContext,TargetRequestContext,IReplayContexts.ITargetRequestContext>
            implements IReplayContexts.IReceivingHttpResponseContext {
        public ReceivingHttpResponseContext(TargetRequestContext enclosingScope) {
            super(enclosingScope);
            initializeSpan();
        }

        public static class MetricInstruments extends CommonScopedMetricInstruments {
            public MetricInstruments(Meter meter) {
                super(meter, ACTIVITY_NAME);
            }
        }

        public @NonNull MetricInstruments getMetrics() {
            return getRootInstrumentationScope().receivingHttpInstruments;
        }

    }

    public static class TupleHandlingContext
            extends DirectNestedSpanContext<RootReplayerContext,HttpTransactionContext,IReplayContexts.IReplayerHttpTransactionContext>
            implements IReplayContexts.ITupleHandlingContext {
        public TupleHandlingContext(HttpTransactionContext enclosingScope) {
            super(enclosingScope);
            initializeSpan();
        }

        public static class MetricInstruments extends CommonScopedMetricInstruments {
            public MetricInstruments(Meter meter) {
                super(meter, ACTIVITY_NAME);
            }
        }

        public @NonNull MetricInstruments getMetrics() {
            return getRootInstrumentationScope().tupleHandlingInstruments;
        }

    }
}
