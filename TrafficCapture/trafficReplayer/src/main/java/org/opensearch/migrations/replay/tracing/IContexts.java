package org.opensearch.migrations.replay.tracing;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.AttributesBuilder;
import org.opensearch.migrations.replay.datatypes.ISourceTrafficChannelKey;
import org.opensearch.migrations.replay.datatypes.ITrafficStreamKey;
import org.opensearch.migrations.replay.datatypes.UniqueReplayerRequestKey;
import org.opensearch.migrations.tracing.IWithAttributes;
import org.opensearch.migrations.tracing.IWithTypedEnclosingScope;
import org.opensearch.migrations.tracing.commoncontexts.IHttpTransactionContext;

public class IContexts {
    public static interface IKafkaRecordContext
            extends IWithAttributes, IWithTypedEnclosingScope<IChannelKeyContext> {
        static final AttributeKey<String> RECORD_ID_KEY = AttributeKey.stringKey("recordId");

        String getRecordId();

        default AttributesBuilder fillAttributes(AttributesBuilder builder) {
            return IWithAttributes.super.fillAttributes(builder.put(RECORD_ID_KEY, getRecordId()));
        }
    }

    public static interface ITrafficStreamsLifecycleContext
            extends IChannelKeyContext, IWithTypedEnclosingScope<IChannelKeyContext> {
        ITrafficStreamKey getTrafficStreamKey();
        IChannelKeyContext getChannelKeyContext();
        default ISourceTrafficChannelKey getChannelKey() {
            return getChannelKeyContext().getChannelKey();
        }
    }

    public static interface IReplayerHttpTransactionContext
            extends IHttpTransactionContext, IChannelKeyContext, IWithTypedEnclosingScope<IChannelKeyContext> {
        static final AttributeKey<Long> REPLAYER_REQUEST_INDEX_KEY = AttributeKey.longKey("replayerRequestIndex");

        UniqueReplayerRequestKey getReplayerRequestKey();
        IChannelKeyContext getChannelKeyContext();

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

    public static interface IRequestAccumulationContext
            extends IWithAttributes, IWithTypedEnclosingScope<IReplayerHttpTransactionContext> { }

    public static interface IResponseAccumulationContext
            extends IWithAttributes, IWithTypedEnclosingScope<IReplayerHttpTransactionContext> { }

    public static interface IRequestTransformationContext
            extends IWithAttributes, IWithTypedEnclosingScope<IReplayerHttpTransactionContext> { }

    public static interface IWaitingForHttpResponseContext
            extends IWithAttributes, IWithTypedEnclosingScope<IReplayerHttpTransactionContext> { }

    public static interface IReceivingHttpResponseContext
            extends IWithAttributes, IWithTypedEnclosingScope<IReplayerHttpTransactionContext> { }

    public static interface IRequestSendingContext
            extends IWithAttributes, IWithTypedEnclosingScope<IReplayerHttpTransactionContext> { }

    public static interface ITupleHandlingContext
            extends IWithAttributes, IWithTypedEnclosingScope<IReplayerHttpTransactionContext> { }
}
