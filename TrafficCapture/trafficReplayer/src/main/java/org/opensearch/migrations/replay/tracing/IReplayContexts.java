package org.opensearch.migrations.replay.tracing;

import java.time.Instant;

import org.opensearch.migrations.replay.identity.ConnectionProcessingId;
import org.opensearch.migrations.replay.identity.KafkaRecordId;
import org.opensearch.migrations.replay.identity.ReplayRequestId;
import org.opensearch.migrations.tracing.IScopedInstrumentationAttributes;
import org.opensearch.migrations.tracing.IWithTypedEnclosingScope;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.AttributesBuilder;

// REBUILD-TRACE-START(G5,source): retain through the rebuild; remove in final pre-merge cleanup.
// IChannelKeyContext identity access -> IConnectionContext.getConnectionProcessingId
// IKafkaRecordContext String record id/createTrafficLifecyleContext ->
//     typed KafkaRecordId/createTrafficStreamContext/complete
// ITrafficStreamsLifecycleContext ITrafficStreamKey/createHttpTransactionContext ->
//     traffic-stream ordinal/createRequestContext(ReplayRequestId, source time)
// IReplayerHttpTransactionContext legacy key/channel access ->
//     IRequestContext getRequestId/getConnectionProcessingId/getCapturedRequestOrdinal
// request reconstitution metric callback -> IRequestContext.onRequestReconstituted
// request/response accumulation, transformation, scheduling, target, and tuple context factories ->
//     same lifecycle positions with typed parents
// ITargetRequestContext legacy enclosing lookups -> direct request/connection identity accessors
// all 33 legacy MetricNames -> same literal names, units, and fixed-cardinality attributes
// REBUILD-TRACE-END(G5,source)
// REBUILD-TRACE-START(G5,target): retain through the rebuild; remove in final pre-merge cleanup.
// IConnectionContext.getConnectionProcessingId -> predecessor IChannelKeyContext identity access.
// IKafkaRecordContext.getRecordId/createTrafficStreamContext/complete ->
//     predecessor String record id/createTrafficLifecyleContext and terminal span closure.
// ITrafficStreamsLifecycleContext.getTrafficStreamNumber/createRequestContext ->
//     predecessor ITrafficStreamKey/createHttpTransactionContext.
// IRequestContext getRequestId/getConnectionProcessingId/getCapturedRequestOrdinal/
//     onRequestReconstituted -> predecessor legacy request-key/channel access plus metric callback.
// IRequestContext child factories -> predecessor request/response accumulation, transformation,
//     scheduling, target, and tuple factories at the same hierarchy positions.
// ITargetRequestContext direct identity and target child factories ->
//     predecessor enclosing-scope lookup and same target sub-span creators.
// MetricNames constants -> predecessor constants with unchanged literal names.
// REBUILD-TRACE-END(G5,target)

public interface IReplayContexts {

    class ActivityNames {
        private ActivityNames() {}

        public static final String CHANNEL = "channel";
        public static final String TCP_CONNECTION = "tcpConnection";
        public static final String RECORD_LIFETIME = "recordLifetime";
        public static final String TRAFFIC_STREAM_LIFETIME = "trafficStreamLifetime";
        public static final String HTTP_TRANSACTION = "httpTransaction";
        public static final String ACCUMULATING_REQUEST = "accumulatingRequest";
        public static final String ACCUMULATING_RESPONSE = "accumulatingResponse";
        public static final String TRANSFORMATION = "transformation";
        public static final String SCHEDULED = "scheduled";
        public static final String TARGET_TRANSACTION = "targetTransaction";
        public static final String REQUEST_CONNECTING = "requestConnecting";
        public static final String REQUEST_SENDING = "requestSending";
        public static final String WAITING_FOR_RESPONSE = "waitingForResponse";
        public static final String RECEIVING_RESPONSE = "receivingResponse";
        public static final String TUPLE_COMPARISON = "comparingResults";
    }

    class MetricNames {
        private MetricNames() {}

        public static final String KAFKA_RECORD_READ = "kafkaRecordsRead";
        public static final String KAFKA_BYTES_READ = "kafkaBytesRead";
        public static final String TRAFFIC_STREAMS_READ = "trafficStreamsRead";
        public static final String TRANSFORM_HEADER_PARSE = "parsedHeader";
        public static final String TRANSFORM_PAYLOAD_PARSE_REQUIRED = "parsedPayload";
        public static final String TRANSFORM_PAYLOAD_PARSE_SUCCESS = "parsedPayloadSuccess";
        public static final String TRANSFORM_JSON_REQUIRED = "transformedJsonRequired";
        public static final String TRANSFORM_JSON_SUCCEEDED = "transformedJsonSucceeded";
        public static final String TRANSFORM_TEXT_SUCCEEDED = "transformedTextSucceeded";
        public static final String TRANSFORM_TEXT_FAILED = "transformedTextFailed";
        public static final String TRANSFORM_PAYLOAD_BINARY = "transformedPayloadBinary";
        public static final String TRANSFORM_PAYLOAD_BYTES_IN = "originalPayloadBytesIn";
        public static final String TRANSFORM_UNCOMPRESSED_BYTES_IN = "uncompressedBytesIn";
        public static final String TRANSFORM_UNCOMPRESSED_BYTES_OUT = "uncompressedBytesOut";
        public static final String TRANSFORM_FINAL_PAYLOAD_BYTES_OUT = "finalPayloadBytesOut";
        public static final String TRANSFORM_SUCCESS = "transformSuccess";
        public static final String TRANSFORM_SKIPPED = "transformSkipped";
        public static final String TRANSFORM_ERROR = "transformError";
        public static final String TRANSFORM_BYTES_IN = "transformBytesIn";
        public static final String TRANSFORM_BYTES_OUT = "transformBytesOut";
        public static final String TRANSFORM_CHUNKS_IN = "transformChunksIn";
        public static final String TRANSFORM_CHUNKS_OUT = "transformChunksOut";
        public static final String NETTY_SCHEDULE_LAG = "scheduleLag";
        public static final String NUM_REQUEST_RETRIES = "numRetriedRequests";
        public static final String SOURCE_TO_TARGET_REQUEST_LAG = "lagBetweenSourceAndTargetRequests";
        public static final String ACTIVE_CHANNELS_YET_TO_BE_FULLY_DISCARDED = "activeReplayerChannels";
        public static final String NONRETRYABLE_CONNECTION_FAILURES = "nonRetryableConnectionFailures";
        public static final String ACTIVE_TARGET_CONNECTIONS = "activeTargetConnections";
        public static final String CONNECTIONS_OPENED = "connectionsOpened";
        public static final String CONNECTIONS_CLOSED = "connectionsClosedCount";
        public static final String BYTES_WRITTEN_TO_TARGET = "bytesWrittenToTarget";
        public static final String BYTES_READ_FROM_TARGET = "bytesReadFromTarget";
        public static final String TUPLE_COMPARISON = "tupleComparison";
    }

    final class TraceAttributes {
        private TraceAttributes() {}

        public static final AttributeKey<String> TOPIC = AttributeKey.stringKey("replay.kafka.topic");
        public static final AttributeKey<Long> PARTITION = AttributeKey.longKey("replay.kafka.partition");
        public static final AttributeKey<Long> GENERATION =
            AttributeKey.longKey("replay.partition.generation");
        public static final AttributeKey<Long> KAFKA_OFFSET =
            AttributeKey.longKey("replay.kafka.offset");
        public static final AttributeKey<String> RECORD_DISPOSITION =
            AttributeKey.stringKey("replay.kafka.record.disposition");
        public static final AttributeKey<String> WRITER_NODE =
            AttributeKey.stringKey("replay.capture.writerNode");
        public static final AttributeKey<String> CONNECTION =
            AttributeKey.stringKey("replay.capture.connection");
        public static final AttributeKey<Long> CONNECTION_LIFETIME =
            AttributeKey.longKey("replay.connection.lifetime");
        public static final AttributeKey<Long> TRAFFIC_STREAM_NUMBER =
            AttributeKey.longKey("replay.capture.trafficStream");
        public static final AttributeKey<Long> REQUEST_ORDINAL =
            AttributeKey.longKey("replay.request.ordinal");
    }

    enum RecordDisposition {
        COMMITTED,
        COMMIT_INELIGIBLE,
        GENERATION_ENDED_UNCOMMITTED
    }

    interface IAccumulationScope extends IScopedInstrumentationAttributes {}

    interface IConnectionContext extends IAccumulationScope {
        String ACTIVITY_NAME = ActivityNames.CHANNEL;

        @Override
        default String getActivityName() {
            return ACTIVITY_NAME;
        }

        ConnectionProcessingId getConnectionProcessingId();

        ISocketContext createSocketContext();

        void addFailedChannelCreation();

        @Override
        default AttributesBuilder fillAttributesForSpansBelow(AttributesBuilder builder) {
            var connectionId = getConnectionProcessingId();
            var generation = connectionId.generation();
            var captured = connectionId.capturedConnectionId();
            return builder
                .put(TraceAttributes.TOPIC, generation.topicPartition().topic())
                .put(TraceAttributes.PARTITION, generation.topicPartition().partition())
                .put(TraceAttributes.GENERATION, generation.localSequence())
                .put(TraceAttributes.WRITER_NODE, captured.writerNodeId())
                .put(TraceAttributes.CONNECTION, captured.connectionId())
                .put(TraceAttributes.CONNECTION_LIFETIME, connectionId.localSequence());
        }
    }

    interface ISocketContext
        extends IAccumulationScope, IWithTypedEnclosingScope<IConnectionContext> {
        String ACTIVITY_NAME = ActivityNames.TCP_CONNECTION;

        @Override
        default String getActivityName() {
            return ACTIVITY_NAME;
        }
    }

    interface IKafkaRecordContext extends IAccumulationScope {
        String ACTIVITY_NAME = ActivityNames.RECORD_LIFETIME;

        @Override
        default String getActivityName() {
            return ACTIVITY_NAME;
        }

        KafkaRecordId getRecordId();

        ITrafficStreamsLifecycleContext createTrafficStreamContext(long trafficStreamNumber);

        void complete(RecordDisposition disposition);

        @Override
        default AttributesBuilder fillAttributesForSpansBelow(AttributesBuilder builder) {
            var recordId = getRecordId();
            var generation = recordId.generation();
            return builder
                .put(TraceAttributes.TOPIC, generation.topicPartition().topic())
                .put(TraceAttributes.PARTITION, generation.topicPartition().partition())
                .put(TraceAttributes.GENERATION, generation.localSequence())
                .put(TraceAttributes.KAFKA_OFFSET, recordId.offset());
        }
    }

    interface ITrafficStreamsLifecycleContext
        extends IAccumulationScope, IWithTypedEnclosingScope<IKafkaRecordContext> {
        String ACTIVITY_NAME = ActivityNames.TRAFFIC_STREAM_LIFETIME;

        @Override
        default String getActivityName() {
            return ACTIVITY_NAME;
        }

        long getTrafficStreamNumber();

        IRequestContext createRequestContext(
            ReplayRequestId requestId,
            Instant sourceTimestamp
        );

        @Override
        default AttributesBuilder fillAttributesForSpansBelow(AttributesBuilder builder) {
            return builder.put(TraceAttributes.TRAFFIC_STREAM_NUMBER, getTrafficStreamNumber());
        }
    }

    interface IRequestContext
        extends
            org.opensearch.migrations.tracing.commoncontexts.IHttpTransactionContext,
            IAccumulationScope,
            IWithTypedEnclosingScope<ITrafficStreamsLifecycleContext> {
        String ACTIVITY_NAME = ActivityNames.HTTP_TRANSACTION;

        @Override
        default String getActivityName() {
            return ACTIVITY_NAME;
        }

        ReplayRequestId getRequestId();

        ConnectionProcessingId getConnectionProcessingId();

        long getCapturedRequestOrdinal();

        void onRequestReconstituted();

        Instant getTimeOfOriginalRequest();

        @Override
        default long getSourceRequestIndex() {
            return getCapturedRequestOrdinal();
        }

        IRequestAccumulationContext createRequestAccumulationContext();

        IResponseAccumulationContext createResponseAccumulationContext();

        IRequestTransformationContext createTransformationContext();

        IScheduledContext createScheduledContext(Instant timestamp);

        ITargetRequestContext createTargetRequestContext();

        ITupleHandlingContext createTupleContext();

        @Override
        default AttributesBuilder fillAttributesForSpansBelow(AttributesBuilder builder) {
            return org.opensearch.migrations.tracing.commoncontexts.IHttpTransactionContext.super
                .fillAttributesForSpansBelow(builder)
                .put(TraceAttributes.REQUEST_ORDINAL, getCapturedRequestOrdinal());
        }
    }

    interface IRequestAccumulationContext
        extends IAccumulationScope, IWithTypedEnclosingScope<IRequestContext> {
        String ACTIVITY_NAME = ActivityNames.ACCUMULATING_REQUEST;

        @Override
        default String getActivityName() {
            return ACTIVITY_NAME;
        }
    }

    interface IResponseAccumulationContext
        extends IAccumulationScope, IWithTypedEnclosingScope<IRequestContext> {
        String ACTIVITY_NAME = ActivityNames.ACCUMULATING_RESPONSE;

        @Override
        default String getActivityName() {
            return ACTIVITY_NAME;
        }
    }

    interface IRequestTransformationContext
        extends IAccumulationScope, IWithTypedEnclosingScope<IRequestContext> {
        String ACTIVITY_NAME = ActivityNames.TRANSFORMATION;

        @Override
        default String getActivityName() {
            return ACTIVITY_NAME;
        }

        void onHeaderParse();

        void onPayloadParse();

        void onPayloadParseSuccess();

        void onJsonPayloadParseRequired();

        void onJsonPayloadParseSucceeded();

        void onTextPayloadParseSucceeded();

        void onTextPayloadParseFailed();

        void onPayloadSetBinary();

        void onPayloadBytesIn(int inputSize);

        void onUncompressedBytesIn(int inputSize);

        void onUncompressedBytesOut(int inputSize);

        void onFinalBytesOut(int outputSize);

        void onTransformSuccess();

        void onTransformSkip();

        void onTransformFailure();

        void aggregateInputChunk(int sizeInBytes);

        void aggregateOutputChunk(int sizeInBytes);
    }

    interface IScheduledContext
        extends IAccumulationScope, IWithTypedEnclosingScope<IRequestContext> {
        String ACTIVITY_NAME = ActivityNames.SCHEDULED;

        @Override
        default String getActivityName() {
            return ACTIVITY_NAME;
        }
    }

    interface ITargetRequestContext
        extends IAccumulationScope, IWithTypedEnclosingScope<IRequestContext> {
        String ACTIVITY_NAME = ActivityNames.TARGET_TRANSACTION;

        @Override
        default String getActivityName() {
            return ACTIVITY_NAME;
        }

        ReplayRequestId getRequestId();

        ConnectionProcessingId getConnectionProcessingId();

        void onBytesSent(int size);

        void onBytesReceived(int size);

        IRequestConnectingContext createHttpConnectingContext();

        IRequestSendingContext createHttpSendingContext();

        IWaitingForHttpResponseContext createWaitingForResponseContext();

        IReceivingHttpResponseContext createHttpReceivingContext();
    }

    interface IRequestConnectingContext
        extends IAccumulationScope, IWithTypedEnclosingScope<ITargetRequestContext> {
        String ACTIVITY_NAME = ActivityNames.REQUEST_CONNECTING;

        @Override
        default String getActivityName() {
            return ACTIVITY_NAME;
        }
    }

    interface IRequestSendingContext
        extends IAccumulationScope, IWithTypedEnclosingScope<ITargetRequestContext> {
        String ACTIVITY_NAME = ActivityNames.REQUEST_SENDING;

        @Override
        default String getActivityName() {
            return ACTIVITY_NAME;
        }
    }

    interface IWaitingForHttpResponseContext
        extends IAccumulationScope, IWithTypedEnclosingScope<ITargetRequestContext> {
        String ACTIVITY_NAME = ActivityNames.WAITING_FOR_RESPONSE;

        @Override
        default String getActivityName() {
            return ACTIVITY_NAME;
        }
    }

    interface IReceivingHttpResponseContext
        extends IAccumulationScope, IWithTypedEnclosingScope<ITargetRequestContext> {
        String ACTIVITY_NAME = ActivityNames.RECEIVING_RESPONSE;

        @Override
        default String getActivityName() {
            return ACTIVITY_NAME;
        }
    }

    interface ITupleHandlingContext
        extends IAccumulationScope, IWithTypedEnclosingScope<IRequestContext> {
        String ACTIVITY_NAME = ActivityNames.TUPLE_COMPARISON;
        AttributeKey<Long> SOURCE_STATUS_CODE_KEY = AttributeKey.longKey("sourceStatusCode");
        AttributeKey<Long> TARGET_STATUS_CODE_KEY = AttributeKey.longKey("targetStatusCode");
        AttributeKey<Boolean> STATUS_CODE_MATCH_KEY = AttributeKey.booleanKey("statusCodesMatch");
        AttributeKey<String> METHOD_KEY = AttributeKey.stringKey("method");
        AttributeKey<String> HTTP_VERSION_KEY = AttributeKey.stringKey("version");
        AttributeKey<String> ENDPOINT_KEY = AttributeKey.stringKey("endpoint");

        @Override
        default String getActivityName() {
            return ACTIVITY_NAME;
        }

        void setSourceStatus(Integer sourceStatus);

        void setTargetStatus(Integer targetStatus);

        void setMethod(String method);

        void setEndpoint(String endpointUrl);

        void setHttpVersion(String httpVersion);

        default ReplayRequestId getRequestId() {
            return getLogicalEnclosingScope().getRequestId();
        }
    }
}
