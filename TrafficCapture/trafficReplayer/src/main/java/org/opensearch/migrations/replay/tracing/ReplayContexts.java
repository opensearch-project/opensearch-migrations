package org.opensearch.migrations.replay.tracing;

import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Optional;

import org.opensearch.migrations.replay.identity.ConnectionProcessingId;
import org.opensearch.migrations.replay.identity.KafkaRecordId;
import org.opensearch.migrations.replay.identity.ReplayRequestId;
import org.opensearch.migrations.tracing.BaseNestedSpanContext;
import org.opensearch.migrations.tracing.CommonScopedMetricInstruments;
import org.opensearch.migrations.tracing.DirectNestedSpanContext;
import org.opensearch.migrations.tracing.IScopedInstrumentationAttributes;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.common.AttributesBuilder;
import io.opentelemetry.api.metrics.DoubleHistogram;
import io.opentelemetry.api.metrics.LongCounter;
import io.opentelemetry.api.metrics.LongUpDownCounter;
import io.opentelemetry.api.metrics.Meter;
import lombok.Getter;
import lombok.NonNull;
import lombok.Setter;

// REBUILD-TRACE-START(G5,source): retain through the rebuild; remove in final pre-merge cleanup.
// ChannelKeyContext constructor/getLogicalEnclosingScope/createSocketContext/
//     addFailedChannelCreation -> ConnectionContext constructor/getConnectionProcessingId/
//     createSocketContext/addFailedChannelCreation
// KafkaRecordContext constructor/getRecordId/createTrafficLifecyleContext ->
//     KafkaRecordContext typed constructor/getRecordId/createTrafficStreamContext/complete
// TrafficStreamLifecycleContext constructor/getTrafficStreamKey/createHttpTransactionContext ->
//     TrafficStreamsLifecycleContext constructor/getTrafficStreamNumber/createRequestContext
// HttpTransactionContext constructor/getReplayerRequestKey/getChannelKeyContext ->
//     typed ReplayRequestId constructor/getRequestId/getConnectionProcessingId
// requestReconstituted manual metric call -> HttpTransactionContext.onRequestReconstituted
// request/response accumulation, transformation, scheduling, target, and tuple child factories ->
//     same-named live context factories with typed parent identities
// every RequestTransformationContext metric method -> same-named live method and preserved metric
// TargetRequestContext onBytesSent/onBytesReceived and four target child factories ->
//     same-named live methods used by NettyPacketToHttpConsumer's deployed factory
// TupleHandlingContext comparison setters/attributes -> same-named live tuple context behavior
// REBUILD-TRACE-END(G5,source)
// REBUILD-TRACE-START(G5,target): retain through the rebuild; remove in final pre-merge cleanup.
// ConnectionContext constructor/getConnectionProcessingId/createSocketContext/
//     addFailedChannelCreation -> predecessor ChannelKeyContext constructor/logical scope/
//     createSocketContext/addFailedChannelCreation.
// KafkaRecordContext typed constructor/getRecordId/createTrafficStreamContext/complete ->
//     predecessor constructor/String getRecordId/createTrafficLifecyleContext and close.
// TrafficStreamsLifecycleContext constructor/getTrafficStreamNumber/createRequestContext ->
//     predecessor TrafficStreamLifecycleContext constructor/getTrafficStreamKey/
//     createHttpTransactionContext.
// HttpTransactionContext typed constructor/getRequestId/getConnectionProcessingId/
//     getCapturedRequestOrdinal/onRequestReconstituted ->
//     predecessor constructor/getReplayerRequestKey/getChannelKeyContext and manual metric call.
// HttpTransactionContext child factories -> predecessor child factories with the same span hierarchy.
// RequestTransformationContext metric methods -> same predecessor methods and metric instruments.
// TargetRequestContext identity/onBytesSent/onBytesReceived/target child factories ->
//     predecessor target context behavior, now consumed by the deployed Netty transport.
// TupleHandlingContext comparison methods -> same predecessor tuple attributes and metrics.
// REBUILD-TRACE-END(G5,target)

public interface ReplayContexts extends IReplayContexts {

    String COUNT_UNIT_STR = "count";
    String BYTES_UNIT_STR = "bytes";

    class SocketContext extends DirectNestedSpanContext<
        RootReplayerContext,
        ConnectionContext,
        IConnectionContext> implements ISocketContext {

        protected SocketContext(ConnectionContext enclosingScope) {
            super(enclosingScope);
            initializeSpan();
            meterIncrementEvent(getMetrics().channelCreatedCounter);
            meterDeltaEvent(getMetrics().activeSocketConnectionsCounter, 1);
        }

        public static class MetricInstruments extends CommonScopedMetricInstruments {
            final LongUpDownCounter activeSocketConnectionsCounter;
            final LongCounter channelCreatedCounter;
            final LongCounter channelClosedCounter;

            private MetricInstruments(Meter meter, String activityName) {
                super(meter, activityName);
                activeSocketConnectionsCounter = meter.upDownCounterBuilder(MetricNames.ACTIVE_TARGET_CONNECTIONS)
                    .build();
                channelCreatedCounter = meter.counterBuilder(MetricNames.CONNECTIONS_OPENED).build();
                channelClosedCounter = meter.counterBuilder(MetricNames.CONNECTIONS_CLOSED).build();
            }
        }

        @Override
        public void sendMeterEventsForEnd() {
            super.sendMeterEventsForEnd();
            meterIncrementEvent(getMetrics().channelClosedCounter);
            meterDeltaEvent(getMetrics().activeSocketConnectionsCounter, -1);
        }

        public static MetricInstruments makeMetrics(Meter meter) {
            return new MetricInstruments(meter, ACTIVITY_NAME);
        }

        @Override
        public MetricInstruments getMetrics() {
            return getRootInstrumentationScope().socketInstruments;
        }
    }

    class ConnectionContext extends BaseNestedSpanContext<
        RootReplayerContext,
        IScopedInstrumentationAttributes>
        implements IConnectionContext {
        private final ConnectionProcessingId connectionProcessingId;

        ConnectionContext(
            @NonNull RootReplayerContext rootScope,
            @NonNull ConnectionProcessingId connectionProcessingId
        ) {
            super(rootScope, null);
            this.connectionProcessingId = connectionProcessingId;
            initializeSpan();
            meterDeltaEvent(getMetrics().activeChannelCounter, 1);
        }

        public static class MetricInstruments extends CommonScopedMetricInstruments {
            final LongUpDownCounter activeChannelCounter;
            final LongCounter unretryableConnectionFailures;

            private MetricInstruments(Meter meter, String activityName) {
                super(meter, activityName);
                activeChannelCounter = meter.upDownCounterBuilder(MetricNames.ACTIVE_CHANNELS_YET_TO_BE_FULLY_DISCARDED)
                    .build();
                unretryableConnectionFailures = meter.counterBuilder(MetricNames.NONRETRYABLE_CONNECTION_FAILURES).build();
            }
        }

        public static @NonNull MetricInstruments makeMetrics(Meter meter) {
            return new MetricInstruments(meter, ACTIVITY_NAME);
        }

        @Override
        public ConnectionProcessingId getConnectionProcessingId() {
            return connectionProcessingId;
        }

        @Override
        public ISocketContext createSocketContext() {
            return new SocketContext(this);
        }

        @Override
        public void addFailedChannelCreation() {
            meterIncrementEvent(getMetrics().unretryableConnectionFailures);
        }

        @Override
        public @NonNull MetricInstruments getMetrics() {
            return getRootInstrumentationScope().channelKeyInstruments;
        }

        @Override
        public AttributesBuilder fillAttributesForSpansBelow(AttributesBuilder builder) {
            return IConnectionContext.super.fillAttributesForSpansBelow(builder);
        }

        @Override
        public void sendMeterEventsForEnd() {
            super.sendMeterEventsForEnd();
            meterDeltaEvent(getMetrics().activeChannelCounter, -1);
        }

        @Override
        public String toString() {
            return connectionProcessingId.toString();
        }
    }

    class KafkaRecordContext extends BaseNestedSpanContext<
        RootReplayerContext,
        IScopedInstrumentationAttributes>
        implements IKafkaRecordContext {
        final KafkaRecordId recordId;
        private boolean completed;

        KafkaRecordContext(
            @NonNull RootReplayerContext rootScope,
            @NonNull KafkaRecordId recordId,
            int recordSize
        ) {
            super(rootScope, null);
            this.recordId = recordId;
            initializeSpan();
            meterIncrementEvent(getMetrics().recordCounter);
            meterIncrementEvent(getMetrics().bytesCounter, recordSize);
        }

        public static class MetricInstruments extends CommonScopedMetricInstruments {
            final LongCounter recordCounter;
            final LongCounter bytesCounter;

            private MetricInstruments(Meter meter, String activityName) {
                super(meter, activityName);
                recordCounter = meter.counterBuilder(MetricNames.KAFKA_RECORD_READ).setUnit("records").build();
                bytesCounter = meter.counterBuilder(MetricNames.KAFKA_BYTES_READ).setUnit(BYTES_UNIT_STR).build();
            }
        }

        public static @NonNull MetricInstruments makeMetrics(Meter meter) {
            return new MetricInstruments(meter, ACTIVITY_NAME);
        }

        @Override
        public KafkaRecordId getRecordId() {
            return recordId;
        }

        @Override
        public ITrafficStreamsLifecycleContext createTrafficStreamContext(
            long trafficStreamNumber
        ) {
            return new TrafficStreamLifecycleContext(this, trafficStreamNumber);
        }

        @Override
        public void complete(@NonNull RecordDisposition disposition) {
            if (completed) {
                throw new IllegalStateException("record context already completed for " + recordId);
            }
            completed = true;
            setAttribute(
                TraceAttributes.RECORD_DISPOSITION,
                disposition.name().toLowerCase(Locale.ROOT)
            );
            close();
        }

        @Override
        public @NonNull MetricInstruments getMetrics() {
            return getRootInstrumentationScope().kafkaRecordInstruments;
        }

        @Override
        public AttributesBuilder fillAttributesForSpansBelow(AttributesBuilder builder) {
            return IKafkaRecordContext.super.fillAttributesForSpansBelow(builder);
        }
    }

    class TrafficStreamLifecycleContext extends DirectNestedSpanContext<
        RootReplayerContext,
        KafkaRecordContext,
        IKafkaRecordContext>
        implements ITrafficStreamsLifecycleContext {
        private final long trafficStreamNumber;

        TrafficStreamLifecycleContext(
            KafkaRecordContext enclosingScope,
            long trafficStreamNumber
        ) {
            super(enclosingScope);
            this.trafficStreamNumber = trafficStreamNumber;
            initializeSpan();
            meterIncrementEvent(getMetrics().streamsRead);
        }

        public static class MetricInstruments extends CommonScopedMetricInstruments {
            private final LongCounter streamsRead;

            private MetricInstruments(Meter meter, String activityName) {
                super(meter, activityName);
                streamsRead = meter.counterBuilder(MetricNames.TRAFFIC_STREAMS_READ).setUnit("objects").build();
            }
        }

        public static @NonNull MetricInstruments makeMetrics(Meter meter) {
            return new MetricInstruments(meter, ACTIVITY_NAME);
        }

        @Override
        public long getTrafficStreamNumber() {
            return trafficStreamNumber;
        }

        @Override
        public IRequestContext createRequestContext(
            @NonNull ReplayRequestId requestId,
            @NonNull Instant sourceTimestamp
        ) {
            return new HttpTransactionContext(
                this,
                requestId,
                sourceTimestamp
            );
        }

        @Override
        public @NonNull MetricInstruments getMetrics() {
            return getRootInstrumentationScope().trafficStreamLifecycleInstruments;
        }

        @Override
        public AttributesBuilder fillAttributesForSpansBelow(AttributesBuilder builder) {
            return ITrafficStreamsLifecycleContext.super
                .fillAttributesForSpansBelow(builder);
        }
    }

    class HttpTransactionContext extends DirectNestedSpanContext<
        RootReplayerContext,
        TrafficStreamLifecycleContext,
        ITrafficStreamsLifecycleContext>
        implements IRequestContext {
        private final ReplayRequestId requestId;
        @Getter
        final Instant timeOfOriginalRequest;
        private boolean reconstituted;
        int numTransactionContextsCreated;

        HttpTransactionContext(
            TrafficStreamLifecycleContext enclosingScope,
            ReplayRequestId requestId,
            Instant timeOfOriginalRequest
        ) {
            super(enclosingScope);
            this.requestId = requestId;
            this.timeOfOriginalRequest = timeOfOriginalRequest;
            initializeSpan();
        }

        public static class MetricInstruments extends CommonScopedMetricInstruments {
            final LongCounter numRetries;

            private MetricInstruments(Meter meter, String activityName) {
                super(meter, activityName);
                numRetries = meter.counterBuilder(MetricNames.NUM_REQUEST_RETRIES).setUnit(COUNT_UNIT_STR).build();
            }

        }

        public static @NonNull MetricInstruments makeMetrics(Meter meter) {
            return new MetricInstruments(meter, ACTIVITY_NAME);
        }

        @Override
        public ReplayRequestId getRequestId() {
            return requestId;
        }

        @Override
        public ConnectionProcessingId getConnectionProcessingId() {
            return requestId.connectionProcessingId();
        }

        @Override
        public long getCapturedRequestOrdinal() {
            return requestId.capturedRequestOrdinal();
        }

        @Override
        public void onRequestReconstituted() {
            if (reconstituted) {
                throw new IllegalStateException(
                    "request context already reconstituted for " + requestId
                );
            }
            reconstituted = true;
            getRootInstrumentationScope().replayIntakeMetrics.requestReconstituted();
        }

        @Override
        public IRequestAccumulationContext createRequestAccumulationContext() {
            return new RequestAccumulationContext(this);
        }

        @Override
        public IResponseAccumulationContext createResponseAccumulationContext() {
            return new ResponseAccumulationContext(this);
        }

        @Override
        public IRequestTransformationContext createTransformationContext() {
            return new RequestTransformationContext(this);
        }

        @Override
        public IScheduledContext createScheduledContext(Instant timestamp) {
            return new ScheduledContext(
                this,
                System.nanoTime() + Math.max(0, Duration.between(Instant.now(), timestamp).toNanos())
            );
        }

        @Override
        public ITargetRequestContext createTargetRequestContext() {
            if (numTransactionContextsCreated > 0) {
                meterIncrementEvent(getMetrics().numRetries);
            }
            ++numTransactionContextsCreated;
            return new TargetRequestContext(this);
        }

        @Override
        public ITupleHandlingContext createTupleContext() {
            return new TupleHandlingContext(this);
        }

        @Override
        public @NonNull MetricInstruments getMetrics() {
            return getRootInstrumentationScope().httpTransactionInstruments;
        }

        @Override
        public AttributesBuilder fillAttributesForSpansBelow(AttributesBuilder builder) {
            var connection = getConnectionProcessingId();
            var generation = connection.generation();
            var captured = connection.capturedConnectionId();
            return IRequestContext.super.fillAttributesForSpansBelow(builder)
                .put(TraceAttributes.TOPIC, generation.topicPartition().topic())
                .put(TraceAttributes.PARTITION, generation.topicPartition().partition())
                .put(TraceAttributes.GENERATION, generation.localSequence())
                .put(TraceAttributes.WRITER_NODE, captured.writerNodeId())
                .put(TraceAttributes.CONNECTION, captured.connectionId())
                .put(TraceAttributes.CONNECTION_LIFETIME, connection.localSequence());
        }

        @Override
        public String toString() {
            return requestId.toString();
        }
    }

    class RequestAccumulationContext extends DirectNestedSpanContext<
        RootReplayerContext,
        HttpTransactionContext,
        IRequestContext> implements IRequestAccumulationContext {
        public RequestAccumulationContext(HttpTransactionContext enclosingScope) {
            super(enclosingScope);
            initializeSpan();
        }

        public static class MetricInstruments extends CommonScopedMetricInstruments {
            private MetricInstruments(Meter meter, String activityName) {
                super(meter, activityName);
            }
        }

        public static @NonNull MetricInstruments makeMetrics(Meter meter) {
            return new MetricInstruments(meter, ACTIVITY_NAME);
        }

        @Override
        public @NonNull MetricInstruments getMetrics() {
            return getRootInstrumentationScope().requestAccumInstruments;
        }
    }

    class ResponseAccumulationContext extends DirectNestedSpanContext<
        RootReplayerContext,
        HttpTransactionContext,
        IRequestContext> implements IResponseAccumulationContext {
        public ResponseAccumulationContext(HttpTransactionContext enclosingScope) {
            super(enclosingScope);
            initializeSpan();
        }

        public static class MetricInstruments extends CommonScopedMetricInstruments {
            private MetricInstruments(Meter meter, String activityName) {
                super(meter, activityName);
            }
        }

        public static @NonNull MetricInstruments makeMetrics(Meter meter) {
            return new MetricInstruments(meter, ACTIVITY_NAME);
        }

        @Override
        public @NonNull MetricInstruments getMetrics() {
            return getRootInstrumentationScope().responseAccumInstruments;
        }
    }

    class RequestTransformationContext extends DirectNestedSpanContext<
        RootReplayerContext,
        HttpTransactionContext,
        IRequestContext> implements IRequestTransformationContext {
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
            private final LongCounter textParseSuccess;
            private final LongCounter textParseFailed;
            private final LongCounter payloadTransformBinary;
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

            private MetricInstruments(Meter meter, String activityName) {
                super(meter, activityName);
                headerParses = meter.counterBuilder(MetricNames.TRANSFORM_HEADER_PARSE).setUnit(COUNT_UNIT_STR).build();
                payloadParses = meter.counterBuilder(MetricNames.TRANSFORM_PAYLOAD_PARSE_REQUIRED)
                    .setUnit(COUNT_UNIT_STR)
                    .build();
                payloadSuccessParses = meter.counterBuilder(MetricNames.TRANSFORM_PAYLOAD_PARSE_SUCCESS)
                    .setUnit(COUNT_UNIT_STR)
                    .build();
                jsonPayloadParses = meter.counterBuilder(MetricNames.TRANSFORM_JSON_REQUIRED)
                    .setUnit(COUNT_UNIT_STR)
                    .build();
                jsonTransformSuccess = meter.counterBuilder(MetricNames.TRANSFORM_JSON_SUCCEEDED)
                    .setUnit(COUNT_UNIT_STR)
                    .build();
                textParseSuccess = meter.counterBuilder(MetricNames.TRANSFORM_TEXT_SUCCEEDED)
                    .setUnit(COUNT_UNIT_STR)
                    .build();
                textParseFailed = meter.counterBuilder(MetricNames.TRANSFORM_TEXT_FAILED)
                    .setUnit(COUNT_UNIT_STR)
                    .build();
                payloadTransformBinary = meter.counterBuilder(MetricNames.TRANSFORM_PAYLOAD_BINARY)
                    .setUnit(COUNT_UNIT_STR)
                    .build();
                payloadBytesIn = meter.counterBuilder(MetricNames.TRANSFORM_PAYLOAD_BYTES_IN)
                    .setUnit(BYTES_UNIT_STR)
                    .build();
                uncompressedBytesIn = meter.counterBuilder(MetricNames.TRANSFORM_UNCOMPRESSED_BYTES_IN)
                    .setUnit(BYTES_UNIT_STR)
                    .build();
                uncompressedBytesOut = meter.counterBuilder(MetricNames.TRANSFORM_UNCOMPRESSED_BYTES_OUT)
                    .setUnit(BYTES_UNIT_STR)
                    .build();
                finalPayloadBytesOut = meter.counterBuilder(MetricNames.TRANSFORM_FINAL_PAYLOAD_BYTES_OUT)
                    .setUnit(BYTES_UNIT_STR)
                    .build();
                transformSuccess = meter.counterBuilder(MetricNames.TRANSFORM_SUCCESS).setUnit(COUNT_UNIT_STR).build();
                transformSkipped = meter.counterBuilder(MetricNames.TRANSFORM_SKIPPED).setUnit(COUNT_UNIT_STR).build();
                transformError = meter.counterBuilder(MetricNames.TRANSFORM_ERROR).setUnit(COUNT_UNIT_STR).build();
                transformBytesIn = meter.counterBuilder(MetricNames.TRANSFORM_BYTES_IN).setUnit(BYTES_UNIT_STR).build();
                transformChunksIn = meter.counterBuilder(MetricNames.TRANSFORM_CHUNKS_IN)
                    .setUnit(COUNT_UNIT_STR)
                    .build();
                transformBytesOut = meter.counterBuilder(MetricNames.TRANSFORM_BYTES_OUT)
                    .setUnit(BYTES_UNIT_STR)
                    .build();
                transformChunksOut = meter.counterBuilder(MetricNames.TRANSFORM_CHUNKS_OUT)
                    .setUnit(COUNT_UNIT_STR)
                    .build();

            }
        }

        public static @NonNull MetricInstruments makeMetrics(Meter meter) {
            return new MetricInstruments(meter, ACTIVITY_NAME);
        }

        @Override
        public @NonNull MetricInstruments getMetrics() {
            return getRootInstrumentationScope().transformationInstruments;
        }

        @Override
        public void onHeaderParse() {
            meterIncrementEvent(getMetrics().headerParses);
        }

        @Override
        public void onPayloadParse() {
            meterIncrementEvent(getMetrics().payloadParses);
        }

        @Override
        public void onPayloadParseSuccess() {
            meterIncrementEvent(getMetrics().payloadSuccessParses);
        }

        @Override
        public void onJsonPayloadParseRequired() {
            meterIncrementEvent(getMetrics().jsonPayloadParses);
        }

        @Override
        public void onJsonPayloadParseSucceeded() {
            meterIncrementEvent(getMetrics().jsonTransformSuccess);
        }

        @Override
        public void onTextPayloadParseSucceeded() {
            meterIncrementEvent(getMetrics().textParseSuccess);
        }

        @Override
        public void onTextPayloadParseFailed() {
            meterIncrementEvent(getMetrics().textParseFailed);
        }

        @Override
        public void onPayloadSetBinary() {
            meterIncrementEvent(getMetrics().payloadTransformBinary);
        }

        @Override
        public void onPayloadBytesIn(int inputSize) {
            meterIncrementEvent(getMetrics().payloadBytesIn, inputSize);
        }

        @Override
        public void onUncompressedBytesIn(int inputSize) {
            meterIncrementEvent(getMetrics().uncompressedBytesIn, inputSize);
        }

        @Override
        public void onUncompressedBytesOut(int inputSize) {
            meterIncrementEvent(getMetrics().uncompressedBytesOut, inputSize);
        }

        @Override
        public void onFinalBytesOut(int outputSize) {
            meterIncrementEvent(getMetrics().finalPayloadBytesOut, outputSize);
        }

        @Override
        public void onTransformSuccess() {
            meterIncrementEvent(getMetrics().transformSuccess);
        }

        @Override
        public void onTransformSkip() {
            meterIncrementEvent(getMetrics().transformSkipped);
        }

        @Override
        public void onTransformFailure() {
            meterIncrementEvent(getMetrics().transformError);
        }

        @Override
        public void aggregateInputChunk(int sizeInBytes) {
            meterIncrementEvent(getMetrics().transformBytesIn, sizeInBytes);
            meterIncrementEvent(getMetrics().transformChunksIn);
        }

        @Override
        public void aggregateOutputChunk(int sizeInBytes) {
            meterIncrementEvent(getMetrics().transformBytesOut, sizeInBytes);
            meterIncrementEvent(getMetrics().transformChunksOut);
        }
    }

    class ScheduledContext extends DirectNestedSpanContext<
        RootReplayerContext,
        HttpTransactionContext,
        IRequestContext> implements IScheduledContext {
        private final long scheduledForNanoTime;

        public ScheduledContext(HttpTransactionContext enclosingScope, long scheduledForNanoTime) {
            super(enclosingScope);
            this.scheduledForNanoTime = scheduledForNanoTime;
            initializeSpan();
        }

        public static class MetricInstruments extends CommonScopedMetricInstruments {
            DoubleHistogram lag;

            private MetricInstruments(Meter meter, String activityName) {
                super(meter, activityName);
                lag = meter.histogramBuilder(MetricNames.NETTY_SCHEDULE_LAG).setUnit("ms").build();
            }
        }

        public static @NonNull MetricInstruments makeMetrics(Meter meter) {
            return new MetricInstruments(meter, ACTIVITY_NAME);
        }

        @Override
        public @NonNull MetricInstruments getMetrics() {
            return getRootInstrumentationScope().scheduledInstruments;
        }

        @Override
        public void sendMeterEventsForEnd() {
            super.sendMeterEventsForEnd();
            meterHistogramMillis(
                getMetrics().lag,
                Duration.ofNanos(Math.max(0, System.nanoTime() - scheduledForNanoTime))
            );
        }
    }

    class TargetRequestContext extends DirectNestedSpanContext<
        RootReplayerContext,
        HttpTransactionContext,
        IRequestContext> implements ITargetRequestContext {
        public TargetRequestContext(HttpTransactionContext enclosingScope) {
            super(enclosingScope);
            initializeSpan();
            meterHistogramMillis(
                getMetrics().sourceTargetGap,
                Duration.between(enclosingScope.getTimeOfOriginalRequest(), Instant.now())
            );
        }

        public static class MetricInstruments extends CommonScopedMetricInstruments {

            private final DoubleHistogram sourceTargetGap;
            private final LongCounter bytesWritten;
            private final LongCounter bytesRead;

            private MetricInstruments(Meter meter, String activityName) {
                super(meter, activityName);
                sourceTargetGap = meter.histogramBuilder(MetricNames.SOURCE_TO_TARGET_REQUEST_LAG)
                    .setUnit("ms")
                    .build();
                bytesWritten = meter.counterBuilder(MetricNames.BYTES_WRITTEN_TO_TARGET)
                    .setUnit(BYTES_UNIT_STR)
                    .build();
                bytesRead = meter.counterBuilder(MetricNames.BYTES_READ_FROM_TARGET).setUnit(BYTES_UNIT_STR).build();
            }
        }

        public static @NonNull MetricInstruments makeMetrics(Meter meter) {
            return new MetricInstruments(meter, ACTIVITY_NAME);
        }

        @Override
        public @NonNull MetricInstruments getMetrics() {
            return getRootInstrumentationScope().targetRequestInstruments;
        }

        @Override
        public ReplayRequestId getRequestId() {
            return getLogicalEnclosingScope().getRequestId();
        }

        @Override
        public ConnectionProcessingId getConnectionProcessingId() {
            return getLogicalEnclosingScope().getConnectionProcessingId();
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
        public IRequestConnectingContext createHttpConnectingContext() {
            return new RequestConnectingContext(this);
        }

        @Override
        public IRequestSendingContext createHttpSendingContext() {
            return new RequestSendingContext(this);
        }

        @Override
        public IWaitingForHttpResponseContext createWaitingForResponseContext() {
            return new WaitingForHttpResponseContext(this);
        }

        @Override
        public IReceivingHttpResponseContext createHttpReceivingContext() {
            return new ReceivingHttpResponseContext(this);
        }
    }

    class RequestConnectingContext extends DirectNestedSpanContext<
        RootReplayerContext,
        TargetRequestContext,
        ITargetRequestContext> implements IRequestConnectingContext {
        public RequestConnectingContext(TargetRequestContext enclosingScope) {
            super(enclosingScope);
            initializeSpan();
        }

        public static class MetricInstruments extends CommonScopedMetricInstruments {
            private MetricInstruments(Meter meter, String activityName) {
                super(meter, activityName);
            }
        }

        public static @NonNull MetricInstruments makeMetrics(Meter meter) {
            return new MetricInstruments(meter, ACTIVITY_NAME);
        }

        @Override
        public @NonNull MetricInstruments getMetrics() {
            return getRootInstrumentationScope().requestConnectingInstruments;
        }
    }

    class RequestSendingContext extends DirectNestedSpanContext<
        RootReplayerContext,
        TargetRequestContext,
        ITargetRequestContext> implements IRequestSendingContext {
        public RequestSendingContext(TargetRequestContext enclosingScope) {
            super(enclosingScope);
            initializeSpan();
        }

        public static class MetricInstruments extends CommonScopedMetricInstruments {
            private MetricInstruments(Meter meter, String activityName) {
                super(meter, activityName);
            }
        }

        public static @NonNull MetricInstruments makeMetrics(Meter meter) {
            return new MetricInstruments(meter, ACTIVITY_NAME);
        }

        @Override
        public @NonNull MetricInstruments getMetrics() {
            return getRootInstrumentationScope().requestSendingInstruments;
        }
    }

    class WaitingForHttpResponseContext extends DirectNestedSpanContext<
        RootReplayerContext,
        TargetRequestContext,
        ITargetRequestContext> implements IWaitingForHttpResponseContext {
        public WaitingForHttpResponseContext(TargetRequestContext enclosingScope) {
            super(enclosingScope);
            initializeSpan();
        }

        public static class MetricInstruments extends CommonScopedMetricInstruments {
            private MetricInstruments(Meter meter, String activityName) {
                super(meter, activityName);
            }
        }

        public static @NonNull MetricInstruments makeMetrics(Meter meter) {
            return new MetricInstruments(meter, ACTIVITY_NAME);
        }

        @Override
        public @NonNull MetricInstruments getMetrics() {
            return getRootInstrumentationScope().waitingForHttpResponseInstruments;
        }
    }

    class ReceivingHttpResponseContext extends DirectNestedSpanContext<
        RootReplayerContext,
        TargetRequestContext,
        ITargetRequestContext> implements IReceivingHttpResponseContext {
        public ReceivingHttpResponseContext(TargetRequestContext enclosingScope) {
            super(enclosingScope);
            initializeSpan();
        }

        public static class MetricInstruments extends CommonScopedMetricInstruments {
            private MetricInstruments(Meter meter, String activityName) {
                super(meter, activityName);
            }
        }

        public static @NonNull MetricInstruments makeMetrics(Meter meter) {
            return new MetricInstruments(meter, ACTIVITY_NAME);
        }

        @Override
        public @NonNull MetricInstruments getMetrics() {
            return getRootInstrumentationScope().receivingHttpInstruments;
        }
    }

    @Getter
    @Setter
    class TupleHandlingContext extends DirectNestedSpanContext<
        RootReplayerContext,
        HttpTransactionContext,
        IRequestContext> implements ITupleHandlingContext {
        Integer sourceStatus;
        Integer targetStatus;
        String method;

        public TupleHandlingContext(HttpTransactionContext enclosingScope) {
            super(enclosingScope);
            initializeSpan();
        }

        public static class MetricInstruments extends CommonScopedMetricInstruments {
            private final LongCounter resultCounter;

            private MetricInstruments(Meter meter, String activityName) {
                super(meter, activityName);
                resultCounter = meter.counterBuilder(MetricNames.TUPLE_COMPARISON).build();
            }
        }

        public static @NonNull MetricInstruments makeMetrics(Meter meter) {
            return new MetricInstruments(meter, ACTIVITY_NAME);
        }

        @Override
        public @NonNull MetricInstruments getMetrics() {
            return getRootInstrumentationScope().tupleHandlingInstruments;
        }

        static final AttributeKey<Long> TARGET_STATUS_CODE_ATTR = AttributeKey.longKey("targetStatusCode");

        public AttributesBuilder getSharedAttributes(AttributesBuilder attributesBuilder) {
            final var sourceOp = Optional.ofNullable(sourceStatus);
            final var targetOp = Optional.ofNullable(targetStatus);
            final boolean didMatch = sourceOp.flatMap(ss -> targetOp.map(ss::equals)).orElse(false);
            return addAttributeIfPresent(
                addAttributeIfPresent(
                    addAttributeIfPresent(attributesBuilder, METHOD_KEY, Optional.ofNullable(method)),
                    SOURCE_STATUS_CODE_KEY,
                    sourceOp.map(TupleHandlingContext::categorizeStatus)
                ),
                TARGET_STATUS_CODE_KEY,
                targetOp.map(TupleHandlingContext::categorizeStatus)
            ).put(STATUS_CODE_MATCH_KEY, didMatch);
        }

        @Override
        public AttributesBuilder fillExtraAttributesForThisSpan(AttributesBuilder builder) {
            return getSharedAttributes(super.fillExtraAttributesForThisSpan(builder));
        }

        @Override
        public void sendMeterEventsForEnd() {
            super.sendMeterEventsForEnd();
            AttributesBuilder attributesBuilderForAggregate = getSharedAttributes(Attributes.builder());
            setAllAttributes(attributesBuilderForAggregate.build());
            meterIncrementEvent(getMetrics().resultCounter, 1, attributesBuilderForAggregate);
        }

        /**
         * Convert everything in the 2xx range to 200; 300-399 to 300
         *
         * @param status
         * @return
         */
        public static long categorizeStatus(int status) {
            return (status / 100L) * 100L;
        }

        /**
         * Like httpVersion, Endpoint doesn't have a field because it isn't used as an attribute for metrics
         * (it would create too much cardinality pressure).  So just drop an attribute into a span instead of
         * stashing it for both the span and final metric.
         */
        @Override
        public void setEndpoint(String endpointUrl) {
            setAttribute(ENDPOINT_KEY, endpointUrl);
        }

        /**
         * Like Endpoint, httpVersion doesn't have a field because it isn't used as an attribute for metrics
         * (it just isn't expected to be super-useful and could create too much cardinality pressure).
         * So just drop an attribute into a span instead of stashing it for both the span and final metric.
         */
        @Override
        public void setHttpVersion(String httpVersion) {
            setAttribute(HTTP_VERSION_KEY, httpVersion);
        }

        @Override
        public String toString() {
            return getRequestId().toString();
        }
    }
}
