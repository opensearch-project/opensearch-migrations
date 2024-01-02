package org.opensearch.migrations.replay.tracing;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.AttributesBuilder;
import org.opensearch.migrations.replay.datatypes.ISourceTrafficChannelKey;
import org.opensearch.migrations.replay.datatypes.ITrafficStreamKey;
import org.opensearch.migrations.replay.datatypes.UniqueReplayerRequestKey;
import org.opensearch.migrations.tracing.IScopedInstrumentationAttributes;
import org.opensearch.migrations.tracing.IWithTypedEnclosingScope;
import org.opensearch.migrations.tracing.commoncontexts.IConnectionContext;
import org.opensearch.migrations.tracing.commoncontexts.IHttpTransactionContext;

import java.time.Instant;

public class IReplayContexts {

    public static class ScopeNames {
        private ScopeNames() {}

        public static final String KAFKA_RECORD_SCOPE = "KafkaRecord";
        public static final String TRAFFIC_STREAM_LIFETIME_SCOPE = "TrafficStreamLifetime";
        public static final String ACCUMULATOR_SCOPE = "Accumulator";
        public static final String HTTP_TRANSFORMER_SCOPE = "HttpTransformer";
        public static final String REQUEST_SENDER_SCOPE = "RequestSender";
        public static final String TRAFFIC_REPLAYER_SCOPE = "TrafficReplayer";
    }

    public static class ActivityNames {
        private ActivityNames() {}

        public static final String CHANNEL = "channel";
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
        public static final String TUPLE_HANDLING = "tupleHandling";
    }

    public static class MetricNames {
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
        public static final String ACTIVE_TARGET_CONNECTIONS = "activeTargetConnections";
        public static final String BYTES_WRITTEN_TO_TARGET = "bytesWrittenToTarget";
        public static final String BYTES_READ_FROM_TARGET = "bytesReadFromTarget";
    }

    public interface IChannelKeyContext extends IConnectionContext {
        @Override
        default String getActivityName() { return ActivityNames.CHANNEL; }

        // do not add this as a property
        // because its components are already being added in the IConnectionContext implementation
        ISourceTrafficChannelKey getChannelKey();

        default String getConnectionId() {
            return getChannelKey().getConnectionId();
        }

        default String getNodeId() {
            return getChannelKey().getNodeId();
        }

        void onTargetConnectionCreated();

        void onTargetConnectionClosed();
    }

    public interface IKafkaRecordContext
            extends IScopedInstrumentationAttributes, IWithTypedEnclosingScope<IChannelKeyContext> {        @Override
        default String getActivityName() { return ActivityNames.RECORD_LIFETIME; }

        static final AttributeKey<String> RECORD_ID_KEY = AttributeKey.stringKey("recordId");

        String getRecordId();

        default String getScopeName() { return ScopeNames.KAFKA_RECORD_SCOPE; }
        default AttributesBuilder fillAttributes(AttributesBuilder builder) {
            return IScopedInstrumentationAttributes.super.fillAttributes(builder.put(RECORD_ID_KEY, getRecordId()));
        }
    }

    public interface ITrafficStreamsLifecycleContext
            extends IScopedInstrumentationAttributes, IWithTypedEnclosingScope<IChannelKeyContext> {
        default String getActivityName() { return ActivityNames.TRAFFIC_STREAM_LIFETIME; }
        ITrafficStreamKey getTrafficStreamKey();
        IChannelKeyContext getChannelKeyContext();
        default String getConnectionId() {
            return getChannelKey().getConnectionId();
        }
        default String getScopeName() { return ScopeNames.TRAFFIC_STREAM_LIFETIME_SCOPE; }
        default ISourceTrafficChannelKey getChannelKey() {
            return getChannelKeyContext().getChannelKey();
        }
    }

    public interface IReplayerHttpTransactionContext
            extends IHttpTransactionContext, IWithTypedEnclosingScope<IChannelKeyContext> {
        static final AttributeKey<Long> REPLAYER_REQUEST_INDEX_KEY = AttributeKey.longKey("replayerRequestIndex");

        default String getActivityName() { return ActivityNames.HTTP_TRANSACTION; }

        UniqueReplayerRequestKey getReplayerRequestKey();
        IChannelKeyContext getChannelKeyContext();
        Instant getTimeOfOriginalRequest();

        @Override default String getScopeName() { return ScopeNames.ACCUMULATOR_SCOPE; }
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
        default AttributesBuilder fillAttributes(AttributesBuilder builder) {
            return IHttpTransactionContext.super.fillAttributes(
                    builder.put(REPLAYER_REQUEST_INDEX_KEY, replayerRequestIndex()));
        }
    }

    public interface IRequestAccumulationContext
            extends IScopedInstrumentationAttributes, IWithTypedEnclosingScope<IReplayerHttpTransactionContext> {
        default String getActivityName() { return ActivityNames.ACCUMULATING_REQUEST; }

        default String getScopeName() { return ScopeNames.ACCUMULATOR_SCOPE; }
    }

    public interface IResponseAccumulationContext
            extends IScopedInstrumentationAttributes, IWithTypedEnclosingScope<IReplayerHttpTransactionContext> {
        default String getActivityName() { return ActivityNames.ACCUMULATING_RESPONSE; }
        default String getScopeName() { return ScopeNames.ACCUMULATOR_SCOPE; }
    }

    public interface IRequestTransformationContext
            extends IScopedInstrumentationAttributes, IWithTypedEnclosingScope<IReplayerHttpTransactionContext> {
        default String getActivityName() { return ActivityNames.TRANSFORMATION; }
        default String getScopeName() { return ScopeNames.HTTP_TRANSFORMER_SCOPE; }


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
            extends IScopedInstrumentationAttributes, IWithTypedEnclosingScope<IReplayerHttpTransactionContext> {
        default String getActivityName() { return ActivityNames.SCHEDULED; }
        default String getScopeName() { return ScopeNames.REQUEST_SENDER_SCOPE; }
    }

    public interface ITargetRequestContext
            extends IScopedInstrumentationAttributes, IWithTypedEnclosingScope<IReplayerHttpTransactionContext> {
        default String getActivityName() { return ActivityNames.TARGET_TRANSACTION; }
        default String getScopeName() { return ScopeNames.REQUEST_SENDER_SCOPE; }

        void onBytesSent(int size);
        void onBytesReceived(int size);
    }

    public interface IRequestSendingContext
            extends IScopedInstrumentationAttributes, IWithTypedEnclosingScope<ITargetRequestContext> {
        default String getActivityName() { return ActivityNames.REQUEST_SENDING; }
        default String getScopeName() { return ScopeNames.REQUEST_SENDER_SCOPE; }
    }

    public interface IWaitingForHttpResponseContext
            extends IScopedInstrumentationAttributes, IWithTypedEnclosingScope<ITargetRequestContext> {
        default String getActivityName() { return ActivityNames.WAITING_FOR_RESPONSE; }
        default String getScopeName() { return ScopeNames.REQUEST_SENDER_SCOPE; }
    }

    public interface IReceivingHttpResponseContext
            extends IScopedInstrumentationAttributes, IWithTypedEnclosingScope<ITargetRequestContext> {
        default String getActivityName() { return ActivityNames.RECEIVING_RESPONSE; }
        default String getScopeName() { return ScopeNames.REQUEST_SENDER_SCOPE; }
    }

    public interface ITupleHandlingContext
            extends IScopedInstrumentationAttributes, IWithTypedEnclosingScope<IReplayerHttpTransactionContext> {
        default String getActivityName() { return ActivityNames.TUPLE_HANDLING; }
        default String getScopeName() { return ScopeNames.TRAFFIC_REPLAYER_SCOPE; }
    }
}
