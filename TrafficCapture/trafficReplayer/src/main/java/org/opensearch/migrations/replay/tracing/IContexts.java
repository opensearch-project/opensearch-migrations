package org.opensearch.migrations.replay.tracing;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.AttributesBuilder;
import org.opensearch.migrations.replay.datatypes.ISourceTrafficChannelKey;
import org.opensearch.migrations.replay.datatypes.ITrafficStreamKey;
import org.opensearch.migrations.replay.datatypes.UniqueReplayerRequestKey;
import org.opensearch.migrations.tracing.IScopedInstrumentationAttributes;
import org.opensearch.migrations.tracing.IWithTypedEnclosingScope;
import org.opensearch.migrations.tracing.commoncontexts.IHttpTransactionContext;

public class IContexts {
    public interface IKafkaRecordContext
            extends IScopedInstrumentationAttributes, IWithTypedEnclosingScope<IChannelKeyContext> {
        static final AttributeKey<String> RECORD_ID_KEY = AttributeKey.stringKey("recordId");

        String getRecordId();

        default AttributesBuilder fillAttributes(AttributesBuilder builder) {
            return IScopedInstrumentationAttributes.super.fillAttributes(builder.put(RECORD_ID_KEY, getRecordId()));
        }
    }

    public interface ITrafficStreamsLifecycleContext
            extends IChannelKeyContext, IWithTypedEnclosingScope<IChannelKeyContext> {
        ITrafficStreamKey getTrafficStreamKey();
        IChannelKeyContext getChannelKeyContext();
        default ISourceTrafficChannelKey getChannelKey() {
            return getChannelKeyContext().getChannelKey();
        }
    }

    public interface IReplayerHttpTransactionContext
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

    public interface IRequestAccumulationContext
            extends IScopedInstrumentationAttributes, IWithTypedEnclosingScope<IReplayerHttpTransactionContext> { }

    public interface IResponseAccumulationContext
            extends IScopedInstrumentationAttributes, IWithTypedEnclosingScope<IReplayerHttpTransactionContext> { }

    public interface IRequestTransformationContext
            extends IScopedInstrumentationAttributes, IWithTypedEnclosingScope<IReplayerHttpTransactionContext> { }

    public interface IWaitingForHttpResponseContext
            extends IScopedInstrumentationAttributes, IWithTypedEnclosingScope<IReplayerHttpTransactionContext> { }

    public interface IReceivingHttpResponseContext
            extends IScopedInstrumentationAttributes, IWithTypedEnclosingScope<IReplayerHttpTransactionContext> { }

    public interface IRequestSendingContext
            extends IScopedInstrumentationAttributes, IWithTypedEnclosingScope<IReplayerHttpTransactionContext> { }

    public interface ITupleHandlingContext
            extends IScopedInstrumentationAttributes, IWithTypedEnclosingScope<IReplayerHttpTransactionContext> { }
}
