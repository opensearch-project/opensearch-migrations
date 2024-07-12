package org.opensearch.migrations.replay.tracing;

import java.time.Instant;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.AttributesBuilder;

import org.opensearch.migrations.replay.datatypes.ISourceTrafficChannelKey;
import org.opensearch.migrations.replay.datatypes.ITrafficStreamKey;
import org.opensearch.migrations.replay.datatypes.UniqueReplayerRequestKey;
import org.opensearch.migrations.tracing.IScopedInstrumentationAttributes;
import org.opensearch.migrations.tracing.IWithTypedEnclosingScope;

public abstract class IReplayContexts {

    public static class ActivityNames {
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
        public static final String REQUEST_SENDING = "requestSending";
        public static final String WAITING_FOR_RESPONSE = "waitingForResponse";
        public static final String RECEIVING_RESPONSE = "receivingResponse";
        public static final String TUPLE_COMPARISON = "comparingResults";
    }

    public static class MetricNames {
        private MetricNames() {}

        public static final String KAFKA_RECORD_READ = "kafkaRecordsRead";
        public static final String KAFKA_BYTES_READ = "kafkaBytesRead";
        public static final String TRAFFIC_STREAMS_READ = "trafficStreamsRead";
        public static final String TRANSFORM_HEADER_PARSE = "parsedHeader";
        public static final String TRANSFORM_PAYLOAD_PARSE_REQUIRED = "parsedPayload";
        public static final String TRANSFORM_PAYLOAD_PARSE_SUCCESS = "parsedPayloadSuccess";
        public static final String TRANSFORM_JSON_REQUIRED = "transformedJsonRequired";
        public static final String TRANSFORM_JSON_SUCCEEDED = "transformedJsonSucceeded";
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
        public static final String SOURCE_TO_TARGET_REQUEST_LAG = "lagBetweenSourceAndTargetRequests";
        public static final String ACTIVE_CHANNELS_YET_TO_BE_FULLY_DISCARDED = "activeReplayerChannels";
        public static final String FAILED_CONNECTION_ATTEMPTS = "failedConnectionAttempts";
        public static final String ACTIVE_TARGET_CONNECTIONS = "activeTargetConnections";
        public static final String CONNECTIONS_OPENED = "connectionsOpened";
        public static final String CONNECTIONS_CLOSED = "connectionsClosedCount";
        public static final String BYTES_WRITTEN_TO_TARGET = "bytesWrittenToTarget";
        public static final String BYTES_READ_FROM_TARGET = "bytesReadFromTarget";
        public static final String TUPLE_COMPARISON = "tupleComparison";
    }

    public interface IAccumulationScope extends IScopedInstrumentationAttributes {}

    public interface IChannelKeyContext
        extends
            IAccumulationScope,
            org.opensearch.migrations.tracing.commoncontexts.IConnectionContext {
        String ACTIVITY_NAME = ActivityNames.CHANNEL;

        @Override
        default String getActivityName() {
            return ACTIVITY_NAME;
        }

        // do not add this as a property
        // because its components are already being added in the IConnectionContext implementation
        ISourceTrafficChannelKey getChannelKey();

        default String getConnectionId() {
            return getChannelKey().getConnectionId();
        }

        default String getNodeId() {
            return getChannelKey().getNodeId();
        }

        ISocketContext createSocketContext();

        void addFailedChannelCreation();
    }

    public interface ISocketContext extends IAccumulationScope, IWithTypedEnclosingScope<IChannelKeyContext> {
        public static final String ACTIVITY_NAME = ActivityNames.TCP_CONNECTION;

        @Override
        default String getActivityName() {
            return ACTIVITY_NAME;
        }
    }

    public interface IKafkaRecordContext extends IAccumulationScope, IWithTypedEnclosingScope<IChannelKeyContext> {
        String ACTIVITY_NAME = ActivityNames.RECORD_LIFETIME;

        @Override
        default String getActivityName() {
            return ACTIVITY_NAME;
        }

        static final AttributeKey<String> RECORD_ID_KEY = AttributeKey.stringKey("recordId");

        String getRecordId();

        @Override
        default AttributesBuilder fillAttributesForSpansBelow(AttributesBuilder builder) {
            return IAccumulationScope.super.fillAttributesForSpansBelow(builder.put(RECORD_ID_KEY, getRecordId()));
        }

        ITrafficStreamsLifecycleContext createTrafficLifecyleContext(ITrafficStreamKey tsk);
    }

    public interface ITrafficStreamsLifecycleContext
        extends
            IAccumulationScope,
            IWithTypedEnclosingScope<IChannelKeyContext> {
        String ACTIVITY_NAME = ActivityNames.TRAFFIC_STREAM_LIFETIME;

        @Override
        default String getActivityName() {
            return ACTIVITY_NAME;
        }

        ITrafficStreamKey getTrafficStreamKey();

        IChannelKeyContext getChannelKeyContext();

        default String getConnectionId() {
            return getChannelKey().getConnectionId();
        }

        default ISourceTrafficChannelKey getChannelKey() {
            return getChannelKeyContext().getChannelKey();
        }

        IReplayerHttpTransactionContext createHttpTransactionContext(
            UniqueReplayerRequestKey requestKey,
            Instant sourceTimestamp
        );
    }

    public interface IReplayerHttpTransactionContext
        extends
            org.opensearch.migrations.tracing.commoncontexts.IHttpTransactionContext,
            IAccumulationScope,
            IWithTypedEnclosingScope<IChannelKeyContext> {
        AttributeKey<Long> REPLAYER_REQUEST_INDEX_KEY = AttributeKey.longKey("replayerRequestIndex");

        String ACTIVITY_NAME = ActivityNames.HTTP_TRANSACTION;

        @Override
        default String getActivityName() {
            return ACTIVITY_NAME;
        }

        UniqueReplayerRequestKey getReplayerRequestKey();

        IChannelKeyContext getChannelKeyContext();

        Instant getTimeOfOriginalRequest();

        default String getConnectionId() {
            return getChannelKey().getConnectionId();
        }

        default ISourceTrafficChannelKey getChannelKey() {
            return getChannelKeyContext().getChannelKey();
        }

        default long getSourceRequestIndex() {
            return getReplayerRequestKey().getSourceRequestIndex();
        }

        default long replayerRequestIndex() {
            return getReplayerRequestKey().getReplayerRequestIndex();
        }

        @Override
        default AttributesBuilder fillAttributesForSpansBelow(AttributesBuilder builder) {
            return org.opensearch.migrations.tracing.commoncontexts.IHttpTransactionContext.super.fillAttributesForSpansBelow(
                builder
            ).put(REPLAYER_REQUEST_INDEX_KEY, replayerRequestIndex());
        }

        IRequestAccumulationContext createRequestAccumulationContext();

        IResponseAccumulationContext createResponseAccumulationContext();

        IRequestTransformationContext createTransformationContext();

        IScheduledContext createScheduledContext(Instant timestamp);

        ITargetRequestContext createTargetRequestContext();

        ITupleHandlingContext createTupleContext();
    }

    public interface IRequestAccumulationContext
        extends
            IAccumulationScope,
            IWithTypedEnclosingScope<IReplayerHttpTransactionContext> {
        String ACTIVITY_NAME = ActivityNames.ACCUMULATING_REQUEST;

        @Override
        default String getActivityName() {
            return ACTIVITY_NAME;
        }
    }

    public interface IResponseAccumulationContext
        extends
            IAccumulationScope,
            IWithTypedEnclosingScope<IReplayerHttpTransactionContext> {
        String ACTIVITY_NAME = ActivityNames.ACCUMULATING_RESPONSE;

        @Override
        default String getActivityName() {
            return ACTIVITY_NAME;
        }
    }

    public interface IRequestTransformationContext
        extends
            IAccumulationScope,
            IWithTypedEnclosingScope<IReplayerHttpTransactionContext> {
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

    public interface IScheduledContext
        extends
            IAccumulationScope,
            IWithTypedEnclosingScope<IReplayerHttpTransactionContext> {
        String ACTIVITY_NAME = ActivityNames.SCHEDULED;

        @Override
        default String getActivityName() {
            return ACTIVITY_NAME;
        }
    }

    public interface ITargetRequestContext
        extends
            IAccumulationScope,
            IWithTypedEnclosingScope<IReplayerHttpTransactionContext> {
        String ACTIVITY_NAME = ActivityNames.TARGET_TRANSACTION;

        @Override
        default String getActivityName() {
            return ACTIVITY_NAME;
        }

        void onBytesSent(int size);

        void onBytesReceived(int size);

        IRequestSendingContext createHttpSendingContext();

        IWaitingForHttpResponseContext createWaitingForResponseContext();

        IReceivingHttpResponseContext createHttpReceivingContext();
    }

    public interface IRequestSendingContext
        extends
            IAccumulationScope,
            IWithTypedEnclosingScope<ITargetRequestContext> {
        String ACTIVITY_NAME = ActivityNames.REQUEST_SENDING;

        @Override
        default String getActivityName() {
            return ACTIVITY_NAME;
        }
    }

    public interface IWaitingForHttpResponseContext
        extends
            IAccumulationScope,
            IWithTypedEnclosingScope<ITargetRequestContext> {
        String ACTIVITY_NAME = ActivityNames.WAITING_FOR_RESPONSE;

        @Override
        default String getActivityName() {
            return ACTIVITY_NAME;
        }
    }

    public interface IReceivingHttpResponseContext
        extends
            IAccumulationScope,
            IWithTypedEnclosingScope<ITargetRequestContext> {
        String ACTIVITY_NAME = ActivityNames.RECEIVING_RESPONSE;

        @Override
        default String getActivityName() {
            return ACTIVITY_NAME;
        }
    }

    public interface ITupleHandlingContext
        extends
            IAccumulationScope,
            IWithTypedEnclosingScope<IReplayerHttpTransactionContext> {
        String ACTIVITY_NAME = ActivityNames.TUPLE_COMPARISON;
        AttributeKey<Long> SOURCE_STATUS_CODE_KEY = AttributeKey.longKey("sourceStatusCode");
        AttributeKey<Long> TARGET_STATUS_CODE_KEY = AttributeKey.longKey("targetStatusCode");
        AttributeKey<Boolean> STATUS_CODE_MATCH_KEY = AttributeKey.booleanKey("statusCodesMatch");
        AttributeKey<String> METHOD_KEY = AttributeKey.stringKey("method");
        AttributeKey<String> HTTP_VERSION_KEY = AttributeKey.stringKey("version"); // for the span, not metric
        AttributeKey<String> ENDPOINT_KEY = AttributeKey.stringKey("endpoint"); // for the span, not metric

        @Override
        default String getActivityName() {
            return ACTIVITY_NAME;
        }

        void setSourceStatus(Integer sourceStatus);

        void setTargetStatus(Integer targetStatus);

        void setMethod(String method);

        void setEndpoint(String endpointUrl);

        void setHttpVersion(String string);

        default UniqueReplayerRequestKey getReplayerRequestKey() {
            return getLogicalEnclosingScope().getReplayerRequestKey();
        }
    }
}
