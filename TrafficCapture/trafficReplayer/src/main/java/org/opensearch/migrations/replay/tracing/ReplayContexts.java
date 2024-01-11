package org.opensearch.migrations.replay.tracing;

import io.opentelemetry.api.metrics.DoubleHistogram;
import io.opentelemetry.api.metrics.LongCounter;
import io.opentelemetry.api.metrics.LongUpDownCounter;
import io.opentelemetry.api.metrics.MeterProvider;
import lombok.Getter;
import lombok.NonNull;
import org.opensearch.migrations.replay.datatypes.ISourceTrafficChannelKey;
import org.opensearch.migrations.replay.datatypes.ITrafficStreamKey;
import org.opensearch.migrations.replay.datatypes.UniqueReplayerRequestKey;
import org.opensearch.migrations.tracing.AbstractNestedSpanContext;
import org.opensearch.migrations.tracing.CommonScopedMetricInstruments;
import org.opensearch.migrations.tracing.DirectNestedSpanContext;
import org.opensearch.migrations.tracing.IInstrumentationAttributes;
import org.opensearch.migrations.tracing.IndirectNestedSpanContext;

import java.time.Duration;
import java.time.Instant;

public class ReplayContexts {

    public static final String COUNT_UNIT_STR = "count";
    public static final String BYTES_UNIT_STR = "bytes";

    private ReplayContexts() {}

    public static class ChannelKeyContext
            extends AbstractNestedSpanContext<RootReplayerContext, IInstrumentationAttributes<RootReplayerContext>>
            implements IReplayContexts.IChannelKeyContext<RootReplayerContext> {
        @Getter
        final ISourceTrafficChannelKey channelKey;

        public ChannelKeyContext(IInstrumentationAttributes<RootReplayerContext> enclosingScope, ISourceTrafficChannelKey channelKey) {
            super(enclosingScope);
            this.channelKey = channelKey;
            initializeSpan();
        }

        public static class MetricInstruments extends CommonScopedMetricInstruments {
            final LongUpDownCounter activeChannelCounter;
            public MetricInstruments(MeterProvider meterProvider) {
                super(meterProvider, SCOPE_NAME2, ACTIVITY_NAME);
                var meter = meterProvider.get(SCOPE_NAME2);
                activeChannelCounter = meter
                        .upDownCounterBuilder(IReplayContexts.MetricNames.ACTIVE_TARGET_CONNECTIONS).build();
            }
        }

        public @NonNull MetricInstruments getMetrics() {
            return getRootInstrumentationScope().channelKeyContext;
        }

        @Override
        public String toString() {
            return channelKey.toString();
        }

        @Override
        public void onTargetConnectionCreated() {
            meterDeltaEvent(getMetrics().activeChannelCounter, 1);
        }
        @Override
        public void onTargetConnectionClosed() {
            meterDeltaEvent(getMetrics().activeChannelCounter, -1);
        }
    }

    public static class KafkaRecordContext
            extends DirectNestedSpanContext<RootReplayerContext,IReplayContexts.IChannelKeyContext<RootReplayerContext>>
            implements IReplayContexts.IKafkaRecordContext<RootReplayerContext> {

        final String recordId;

        public KafkaRecordContext(IReplayContexts.IChannelKeyContext<RootReplayerContext> enclosingScope,
                                  String recordId, int recordSize) {
            super(enclosingScope);
            this.recordId = recordId;
            initializeSpan();
            meterIncrementEvent(getMetrics().recordCounter);
            meterIncrementEvent(getMetrics().bytesCounter, recordSize);
        }

        public static class MetricInstruments extends CommonScopedMetricInstruments {
            final LongCounter recordCounter;
            final LongCounter bytesCounter;
            public MetricInstruments(MeterProvider meterProvider) {
                super(meterProvider, SCOPE_NAME2, ACTIVITY_NAME);
                var meter = meterProvider.get(SCOPE_NAME2);
                recordCounter = meter.counterBuilder(IReplayContexts.MetricNames.KAFKA_RECORD_READ)
                        .setUnit("records").build();
                bytesCounter = meter.counterBuilder(IReplayContexts.MetricNames.KAFKA_BYTES_READ)
                        .setUnit(BYTES_UNIT_STR).build();
            }
        }

        public @NonNull MetricInstruments getMetrics() {
            return getRootInstrumentationScope().kafkaRecordContext;
        }

        @Override
        public String getRecordId() {
            return recordId;
        }
    }

    public static class TrafficStreamsLifecycleContext
            extends IndirectNestedSpanContext<RootReplayerContext,IReplayContexts.IKafkaRecordContext<RootReplayerContext>, IReplayContexts.IChannelKeyContext<RootReplayerContext>>
            implements IReplayContexts.ITrafficStreamsLifecycleContext<RootReplayerContext> {
        private final ITrafficStreamKey trafficStreamKey;

        public TrafficStreamsLifecycleContext(IReplayContexts.IKafkaRecordContext<RootReplayerContext> enclosingScope,
                                              ITrafficStreamKey trafficStreamKey) {
            super(enclosingScope);
            this.trafficStreamKey = trafficStreamKey;
            initializeSpan();
            meterIncrementEvent(getMetrics().streamsRead);
        }

        public static class MetricInstruments extends CommonScopedMetricInstruments {
            private final LongCounter streamsRead;

            public MetricInstruments(MeterProvider meterProvider) {
                super(meterProvider, SCOPE_NAME2, ACTIVITY_NAME);
                var meter = meterProvider.get(SCOPE_NAME2);
                streamsRead = meter.counterBuilder(IReplayContexts.MetricNames.TRAFFIC_STREAMS_READ)
                        .setUnit("objects").build();
            }
        }

        public @NonNull MetricInstruments getMetrics() {
            return getRootInstrumentationScope().trafficStreamLifecycleContext;
        }

        @Override
        public IReplayContexts.IChannelKeyContext<RootReplayerContext> getChannelKeyContext() {
            return getLogicalEnclosingScope();
        }

        @Override
        public ITrafficStreamKey getTrafficStreamKey() {
            return trafficStreamKey;
        }

        @Override
        public IReplayContexts.IChannelKeyContext<RootReplayerContext> getLogicalEnclosingScope() {
            return getImmediateEnclosingScope().getLogicalEnclosingScope();
        }
    }

    public static class HttpTransactionContext
            extends IndirectNestedSpanContext<RootReplayerContext,IReplayContexts.ITrafficStreamsLifecycleContext<RootReplayerContext>, IReplayContexts.IChannelKeyContext<RootReplayerContext>>
            implements IReplayContexts.IReplayerHttpTransactionContext<RootReplayerContext> {
        final UniqueReplayerRequestKey replayerRequestKey;
        @Getter final Instant timeOfOriginalRequest;

        public HttpTransactionContext(IReplayContexts.ITrafficStreamsLifecycleContext<RootReplayerContext> enclosingScope,
                                      UniqueReplayerRequestKey replayerRequestKey,
                                      Instant timeOfOriginalRequest) {
            super(enclosingScope);
            this.replayerRequestKey = replayerRequestKey;
            this.timeOfOriginalRequest = timeOfOriginalRequest;
            initializeSpan();
        }

        @Override
        public IReplayContexts.ITupleHandlingContext createTupleContext() {
            return new ReplayContexts.TupleHandlingContext(this);
        }

        public static class MetricInstruments extends CommonScopedMetricInstruments {
            public MetricInstruments(MeterProvider meterProvider) {
                super(meterProvider, SCOPE_NAME2, ACTIVITY_NAME);
            }
        }

        public @NonNull MetricInstruments getMetrics() {
            return getRootInstrumentationScope().httpTransactionContext;
        }

        public IReplayContexts.IChannelKeyContext<RootReplayerContext> getChannelKeyContext() {
            return getLogicalEnclosingScope();
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
        public IReplayContexts.IChannelKeyContext<RootReplayerContext> getLogicalEnclosingScope() {
            return getImmediateEnclosingScope().getLogicalEnclosingScope();
        }
    }

    public static class RequestAccumulationContext
            extends DirectNestedSpanContext<RootReplayerContext,IReplayContexts.IReplayerHttpTransactionContext<RootReplayerContext>>
            implements IReplayContexts.IRequestAccumulationContext<RootReplayerContext> {
        public RequestAccumulationContext(IReplayContexts.IReplayerHttpTransactionContext<RootReplayerContext> enclosingScope) {
            super(enclosingScope);
            initializeSpan();
        }

        public static class MetricInstruments extends CommonScopedMetricInstruments {
            public MetricInstruments(MeterProvider meterProvider) {
                super(meterProvider, SCOPE_NAME2, ACTIVITY_NAME);
            }
        }

        public @NonNull MetricInstruments getMetrics() {
            return getRootInstrumentationScope().requestAccumContext;
        }
    }

    public static class ResponseAccumulationContext
            extends DirectNestedSpanContext<RootReplayerContext,IReplayContexts.IReplayerHttpTransactionContext<RootReplayerContext>>
            implements IReplayContexts.IResponseAccumulationContext<RootReplayerContext> {
        public ResponseAccumulationContext(IReplayContexts.IReplayerHttpTransactionContext<RootReplayerContext> enclosingScope) {
            super(enclosingScope);
            initializeSpan();
        }

        public static class MetricInstruments extends CommonScopedMetricInstruments {
            public MetricInstruments(MeterProvider meterProvider) {
                super(meterProvider, SCOPE_NAME2, ACTIVITY_NAME);
            }
        }

        public @NonNull MetricInstruments getMetrics() {
            return getRootInstrumentationScope().responseAccumContext;
        }
    }

    public static class RequestTransformationContext
            extends DirectNestedSpanContext<RootReplayerContext,IReplayContexts.IReplayerHttpTransactionContext<RootReplayerContext>>
            implements IReplayContexts.IRequestTransformationContext<RootReplayerContext> {
        public RequestTransformationContext(IReplayContexts.IReplayerHttpTransactionContext<RootReplayerContext> enclosingScope) {
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

            public MetricInstruments(MeterProvider meterProvider) {
                super(meterProvider, SCOPE_NAME2, ACTIVITY_NAME);
                var meter = meterProvider.get(SCOPE_NAME2);
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
            return getRootInstrumentationScope().transformationContext;
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
            extends DirectNestedSpanContext<RootReplayerContext,IReplayContexts.IReplayerHttpTransactionContext<RootReplayerContext>>
            implements IReplayContexts.IScheduledContext<RootReplayerContext> {
        private final Instant scheduledFor;

        public ScheduledContext(IReplayContexts.IReplayerHttpTransactionContext<RootReplayerContext> enclosingScope,
                                Instant scheduledFor) {
            super(enclosingScope);
            this.scheduledFor = scheduledFor;
            initializeSpan();
        }

        public static class MetricInstruments extends CommonScopedMetricInstruments {
            DoubleHistogram lag;
            public MetricInstruments(MeterProvider meterProvider) {
                super(meterProvider, SCOPE_NAME2, ACTIVITY_NAME);
                var meter = meterProvider.get(SCOPE_NAME2);
                lag = meter.histogramBuilder(IReplayContexts.MetricNames.NETTY_SCHEDULE_LAG).setUnit("ms").build();
            }
        }

        public @NonNull MetricInstruments getMetrics() {
            return getRootInstrumentationScope().scheduledContext;
        }

        @Override
        public void sendMeterEventsForEnd() {
            super.sendMeterEventsForEnd();
            meterHistogramMillis(getMetrics().lag, Duration.between(scheduledFor, Instant.now()));
        }
    }

    public static class TargetRequestContext
            extends DirectNestedSpanContext<RootReplayerContext,IReplayContexts.IReplayerHttpTransactionContext<RootReplayerContext>>
            implements IReplayContexts.ITargetRequestContext<RootReplayerContext> {
        public TargetRequestContext(IReplayContexts.IReplayerHttpTransactionContext<RootReplayerContext> enclosingScope) {
            super(enclosingScope);
            initializeSpan();
            meterHistogramMillis(getMetrics().sourceTargetGap,
                    Duration.between(enclosingScope.getTimeOfOriginalRequest(), Instant.now()));
        }

        public static class MetricInstruments extends CommonScopedMetricInstruments {

            private final DoubleHistogram sourceTargetGap;
            private final LongCounter bytesWritten;
            private final LongCounter bytesRead;

            public MetricInstruments(MeterProvider meterProvider) {
                super(meterProvider, SCOPE_NAME2, ACTIVITY_NAME);
                var meter = meterProvider.get(SCOPE_NAME2);
                sourceTargetGap = meter.histogramBuilder(IReplayContexts.MetricNames.SOURCE_TO_TARGET_REQUEST_LAG)
                        .setUnit("ms").build();
                bytesWritten = meter.counterBuilder(IReplayContexts.MetricNames.BYTES_WRITTEN_TO_TARGET)
                        .setUnit(BYTES_UNIT_STR).build();
                bytesRead = meter.counterBuilder(IReplayContexts.MetricNames.BYTES_READ_FROM_TARGET)
                        .setUnit(BYTES_UNIT_STR).build();
            }
        }

        public @NonNull MetricInstruments getMetrics() {
            return getRootInstrumentationScope().targetRequestContext;
        }

        @Override
        public void onBytesSent(int size) {
            meterIncrementEvent(getMetrics().bytesWritten, size);
        }

        @Override
        public void onBytesReceived(int size) {
            meterIncrementEvent(getMetrics().bytesRead, size);
        }
    }

    public static class RequestSendingContext
            extends DirectNestedSpanContext<RootReplayerContext,IReplayContexts.ITargetRequestContext<RootReplayerContext>>
            implements IReplayContexts.IRequestSendingContext<RootReplayerContext> {
        public RequestSendingContext(IReplayContexts.ITargetRequestContext<RootReplayerContext> enclosingScope) {
            super(enclosingScope);
            initializeSpan();
        }

        public static class MetricInstruments extends CommonScopedMetricInstruments {
            public MetricInstruments(MeterProvider meterProvider) {
                super(meterProvider, SCOPE_NAME2, ACTIVITY_NAME);
            }
        }

        public @NonNull MetricInstruments getMetrics() {
            return getRootInstrumentationScope().requestSendingContext;
        }
    }

    public static class WaitingForHttpResponseContext
            extends DirectNestedSpanContext<RootReplayerContext,IReplayContexts.ITargetRequestContext<RootReplayerContext>>
            implements IReplayContexts.IWaitingForHttpResponseContext<RootReplayerContext> {
        public WaitingForHttpResponseContext(IReplayContexts.ITargetRequestContext<RootReplayerContext> enclosingScope) {
            super(enclosingScope);
            initializeSpan();
        }

        public static class MetricInstruments extends CommonScopedMetricInstruments {
            public MetricInstruments(MeterProvider meterProvider) {
                super(meterProvider, SCOPE_NAME2, ACTIVITY_NAME);
            }
        }

        public @NonNull MetricInstruments getMetrics() {
            return getRootInstrumentationScope().waitingForHttpResponseContext;
        }

    }

    public static class ReceivingHttpResponseContext
            extends DirectNestedSpanContext<RootReplayerContext,IReplayContexts.ITargetRequestContext<RootReplayerContext>>
            implements IReplayContexts.IReceivingHttpResponseContext<RootReplayerContext> {
        public ReceivingHttpResponseContext(IReplayContexts.ITargetRequestContext<RootReplayerContext> enclosingScope) {
            super(enclosingScope);
            initializeSpan();
        }

        public static class MetricInstruments extends CommonScopedMetricInstruments {
            public MetricInstruments(MeterProvider meterProvider) {
                super(meterProvider, SCOPE_NAME2, ACTIVITY_NAME);
            }
        }

        public @NonNull MetricInstruments getMetrics() {
            return getRootInstrumentationScope().receivingHttpContext;
        }

    }

    public static class TupleHandlingContext
            extends DirectNestedSpanContext<RootReplayerContext,IReplayContexts.IReplayerHttpTransactionContext<RootReplayerContext>>
            implements IReplayContexts.ITupleHandlingContext<RootReplayerContext> {
        public TupleHandlingContext(IReplayContexts.IReplayerHttpTransactionContext<RootReplayerContext> enclosingScope) {
            super(enclosingScope);
            initializeSpan();
        }

        public static class MetricInstruments extends CommonScopedMetricInstruments {
            public MetricInstruments(MeterProvider meterProvider) {
                super(meterProvider, SCOPE_NAME2, ACTIVITY_NAME);
            }
        }

        public @NonNull MetricInstruments getMetrics() {
            return getRootInstrumentationScope().tupleHandlingContext;
        }

    }
}
