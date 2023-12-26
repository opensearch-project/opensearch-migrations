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
        public static final String RECEIVING_REQUEST = "receivingRequest";
        public static final String WAITING_FOR_RESPONSE = "waitingForResponse";
        public static final String TUPLE_HANDLING = "tupleHandling";
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
            extends IChannelKeyContext, IWithTypedEnclosingScope<IChannelKeyContext> {
        default String getActivityName() { return ActivityNames.TRAFFIC_STREAM_LIFETIME; }
        ITrafficStreamKey getTrafficStreamKey();
        IChannelKeyContext getChannelKeyContext();
        default String getScopeName() { return ScopeNames.TRAFFIC_STREAM_LIFETIME_SCOPE; }
        default ISourceTrafficChannelKey getChannelKey() {
            return getChannelKeyContext().getChannelKey();
        }
    }

    public interface IReplayerHttpTransactionContext
            extends IHttpTransactionContext, IChannelKeyContext, IWithTypedEnclosingScope<IChannelKeyContext> {
        static final AttributeKey<Long> REPLAYER_REQUEST_INDEX_KEY = AttributeKey.longKey("replayerRequestIndex");

        default String getActivityName() { return ActivityNames.HTTP_TRANSACTION; }

        UniqueReplayerRequestKey getReplayerRequestKey();
        IChannelKeyContext getChannelKeyContext();

        @Override default String getScopeName() { return ScopeNames.ACCUMULATOR_SCOPE; }
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
        default String getActivityName() { return ActivityNames.RECEIVING_REQUEST; }
        default String getScopeName() { return ScopeNames.REQUEST_SENDER_SCOPE; }
    }

    public interface ITupleHandlingContext
            extends IScopedInstrumentationAttributes, IWithTypedEnclosingScope<IReplayerHttpTransactionContext> {
        default String getActivityName() { return ActivityNames.TUPLE_HANDLING; }
        default String getScopeName() { return ScopeNames.TRAFFIC_REPLAYER_SCOPE; }
    }
}
