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

    public static final String KAFKA_RECORD_SCOPE = "KafkaRecord";
    public static final String TRAFFIC_STREAM_LIFETIME_SCOPE = "TrafficStreamLifetime";
    public static final String ACCUMULATOR_SCOPE = "Accumulator";
    public static final String KAFKA_CONSUMER_SCOPE = "TrackingKafkaConsumer";
    public static final String HTTP_TRANSFORMER_SCOPE = "HttpTransformer";
    public static final String REQUEST_SENDER_SCOPE = "RequestSender";
    public static final String TRAFFIC_REPLAYER_SCOPE = "TrafficReplayer";

    public interface IChannelKeyContext extends IConnectionContext {
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
            extends IScopedInstrumentationAttributes, IWithTypedEnclosingScope<IChannelKeyContext> {
        static final AttributeKey<String> RECORD_ID_KEY = AttributeKey.stringKey("recordId");

        String getRecordId();

        default String getScopeName() { return KAFKA_RECORD_SCOPE; }
        default AttributesBuilder fillAttributes(AttributesBuilder builder) {
            return IScopedInstrumentationAttributes.super.fillAttributes(builder.put(RECORD_ID_KEY, getRecordId()));
        }
    }

    public interface ITrafficStreamsLifecycleContext
            extends IChannelKeyContext, IWithTypedEnclosingScope<IChannelKeyContext> {
        ITrafficStreamKey getTrafficStreamKey();
        IChannelKeyContext getChannelKeyContext();
        default String getScopeName() { return TRAFFIC_STREAM_LIFETIME_SCOPE; }
        default ISourceTrafficChannelKey getChannelKey() {
            return getChannelKeyContext().getChannelKey();
        }
    }

    public interface IReplayerHttpTransactionContext
            extends IHttpTransactionContext, IChannelKeyContext, IWithTypedEnclosingScope<IChannelKeyContext> {
        static final AttributeKey<Long> REPLAYER_REQUEST_INDEX_KEY = AttributeKey.longKey("replayerRequestIndex");

        UniqueReplayerRequestKey getReplayerRequestKey();
        IChannelKeyContext getChannelKeyContext();

        @Override default String getScopeName() { return ACCUMULATOR_SCOPE; }
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
        default String getScopeName() { return ACCUMULATOR_SCOPE; }
    }

    public interface IResponseAccumulationContext
            extends IScopedInstrumentationAttributes, IWithTypedEnclosingScope<IReplayerHttpTransactionContext> {
        default String getScopeName() { return ACCUMULATOR_SCOPE; }
    }

    public interface IRequestTransformationContext
            extends IScopedInstrumentationAttributes, IWithTypedEnclosingScope<IReplayerHttpTransactionContext> {
        default String getScopeName() { return HTTP_TRANSFORMER_SCOPE; }
    }

    public interface IScheduledContext
            extends IScopedInstrumentationAttributes, IWithTypedEnclosingScope<IReplayerHttpTransactionContext> {
        default String getScopeName() { return REQUEST_SENDER_SCOPE; }
    }

    public interface ITargetRequestContext
            extends IScopedInstrumentationAttributes, IWithTypedEnclosingScope<IReplayerHttpTransactionContext> {
        default String getScopeName() { return REQUEST_SENDER_SCOPE; }
    }

    public interface IRequestSendingContext
            extends IScopedInstrumentationAttributes, IWithTypedEnclosingScope<ITargetRequestContext> {
        default String getScopeName() { return REQUEST_SENDER_SCOPE; }
    }

    public interface IWaitingForHttpResponseContext
            extends IScopedInstrumentationAttributes, IWithTypedEnclosingScope<ITargetRequestContext> {
        default String getScopeName() { return REQUEST_SENDER_SCOPE; }
    }

    public interface IReceivingHttpResponseContext
            extends IScopedInstrumentationAttributes, IWithTypedEnclosingScope<ITargetRequestContext> {
        default String getScopeName() { return REQUEST_SENDER_SCOPE; }
    }

    public interface ITupleHandlingContext
            extends IScopedInstrumentationAttributes, IWithTypedEnclosingScope<IReplayerHttpTransactionContext> {
        default String getScopeName() { return TRAFFIC_REPLAYER_SCOPE; }
    }
}
