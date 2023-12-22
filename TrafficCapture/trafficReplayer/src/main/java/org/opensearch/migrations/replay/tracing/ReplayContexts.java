package org.opensearch.migrations.replay.tracing;

import lombok.Getter;
import org.opensearch.migrations.replay.datatypes.ISourceTrafficChannelKey;
import org.opensearch.migrations.replay.datatypes.ITrafficStreamKey;
import org.opensearch.migrations.replay.datatypes.UniqueReplayerRequestKey;
import org.opensearch.migrations.tracing.AbstractNestedSpanContext;
import org.opensearch.migrations.tracing.DirectNestedSpanContext;
import org.opensearch.migrations.tracing.IInstrumentationAttributes;
import org.opensearch.migrations.tracing.IWithStartTime;
import org.opensearch.migrations.tracing.IndirectNestedSpanContext;

public class ReplayContexts {

    private ReplayContexts() {}

    public static class ChannelKeyContext extends AbstractNestedSpanContext<IInstrumentationAttributes>
            implements IReplayContexts.IChannelKeyContext, IWithStartTime {
        @Getter
        final ISourceTrafficChannelKey channelKey;

        public ChannelKeyContext(IInstrumentationAttributes enclosingScope, ISourceTrafficChannelKey channelKey) {
            super(enclosingScope);
            this.channelKey = channelKey;
            setCurrentSpan("channel");
        }

        @Override
        public String toString() {
            return channelKey.toString();
        }

        @Override public String getScopeName() { return "Connection"; }
    }
    
    public static class KafkaRecordContext extends DirectNestedSpanContext<IReplayContexts.IChannelKeyContext>
            implements IReplayContexts.IKafkaRecordContext {
        final String recordId;

        public KafkaRecordContext(IReplayContexts.IChannelKeyContext enclosingScope, String recordId) {
            super(enclosingScope);
            this.recordId = recordId;
            setCurrentSpan("recordLifetime");
        }

        @Override
        public String getRecordId() {
            return recordId;
        }
    }

    public static class TrafficStreamsLifecycleContext
            extends IndirectNestedSpanContext<IReplayContexts.IKafkaRecordContext, IReplayContexts.IChannelKeyContext>
            implements IReplayContexts.ITrafficStreamsLifecycleContext {
        private final ITrafficStreamKey trafficStreamKey;

        public TrafficStreamsLifecycleContext(IReplayContexts.IKafkaRecordContext enclosingScope,
                                              ITrafficStreamKey trafficStreamKey) {
            super(enclosingScope);
            this.trafficStreamKey = trafficStreamKey;
            setCurrentSpan("trafficStreamLifetime");
        }

        @Override
        public IReplayContexts.IChannelKeyContext getChannelKeyContext() {
            return getLogicalEnclosingScope();
        }

        @Override
        public ITrafficStreamKey getTrafficStreamKey() {
            return trafficStreamKey;
        }

        @Override
        public IReplayContexts.IChannelKeyContext getLogicalEnclosingScope() {
            return getImmediateEnclosingScope().getLogicalEnclosingScope();
        }
    }

    public static class HttpTransactionContext
            extends IndirectNestedSpanContext<IReplayContexts.ITrafficStreamsLifecycleContext, IReplayContexts.IChannelKeyContext>
            implements IReplayContexts.IReplayerHttpTransactionContext {
        final UniqueReplayerRequestKey replayerRequestKey;

        public HttpTransactionContext(IReplayContexts.ITrafficStreamsLifecycleContext enclosingScope,
                                      UniqueReplayerRequestKey replayerRequestKey) {
            super(enclosingScope);
            this.replayerRequestKey = replayerRequestKey;
            setCurrentSpan("httpTransaction");
        }

        public IReplayContexts.IChannelKeyContext getChannelKeyContext() {
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
        public IReplayContexts.IChannelKeyContext getLogicalEnclosingScope() {
            return getImmediateEnclosingScope().getLogicalEnclosingScope();
        }
    }

    public static class RequestAccumulationContext
            extends DirectNestedSpanContext<IReplayContexts.IReplayerHttpTransactionContext>
            implements IReplayContexts.IRequestAccumulationContext {
        public RequestAccumulationContext(IReplayContexts.IReplayerHttpTransactionContext enclosingScope) {
            super(enclosingScope);
            setCurrentSpan("accumulatingRequest");
        }
    }

    public static class ResponseAccumulationContext
            extends DirectNestedSpanContext<IReplayContexts.IReplayerHttpTransactionContext>
            implements IReplayContexts.IResponseAccumulationContext {
        public ResponseAccumulationContext(IReplayContexts.IReplayerHttpTransactionContext enclosingScope) {
            super(enclosingScope);
            setCurrentSpan("accumulatingResponse");
        }
    }

    public static class RequestTransformationContext
            extends DirectNestedSpanContext<IReplayContexts.IReplayerHttpTransactionContext>
            implements IReplayContexts.IRequestTransformationContext {
        public RequestTransformationContext(IReplayContexts.IReplayerHttpTransactionContext enclosingScope) {
            super(enclosingScope);
            setCurrentSpan("transformation");
        }
    }

    public static class ScheduledContext
            extends DirectNestedSpanContext<IReplayContexts.IReplayerHttpTransactionContext>
            implements IReplayContexts.IScheduledContext {
        public ScheduledContext(IReplayContexts.IReplayerHttpTransactionContext enclosingScope) {
            super(enclosingScope);
            setCurrentSpan("scheduled");
        }
    }

    public static class TargetRequestContext
            extends DirectNestedSpanContext<IReplayContexts.IReplayerHttpTransactionContext>
            implements IReplayContexts.ITargetRequestContext {
        public TargetRequestContext(IReplayContexts.IReplayerHttpTransactionContext enclosingScope) {
            super(enclosingScope);
            setCurrentSpan("targetTransaction");
        }
    }

    public static class RequestSendingContext
            extends DirectNestedSpanContext<IReplayContexts.ITargetRequestContext>
            implements IReplayContexts.IRequestSendingContext {
        public RequestSendingContext(IReplayContexts.ITargetRequestContext enclosingScope) {
            super(enclosingScope);
            setCurrentSpan("requestSending");
        }
    }

    public static class WaitingForHttpResponseContext
            extends DirectNestedSpanContext<IReplayContexts.ITargetRequestContext>
            implements IReplayContexts.IWaitingForHttpResponseContext {
        public WaitingForHttpResponseContext(IReplayContexts.ITargetRequestContext enclosingScope) {
            super(enclosingScope);
            setCurrentSpan("waitingForResponse");
        }
    }

    public static class ReceivingHttpResponseContext
            extends DirectNestedSpanContext<IReplayContexts.ITargetRequestContext>
            implements IReplayContexts.IReceivingHttpResponseContext {
        public ReceivingHttpResponseContext(IReplayContexts.ITargetRequestContext enclosingScope) {
            super(enclosingScope);
            setCurrentSpan("receivingRequest");
        }
    }

    public static class TupleHandlingContext
            extends DirectNestedSpanContext<IReplayContexts.IReplayerHttpTransactionContext>
            implements IReplayContexts.ITupleHandlingContext {
        public TupleHandlingContext(IReplayContexts.IReplayerHttpTransactionContext enclosingScope) {
            super(enclosingScope);
            setCurrentSpan("tupleHandling");
        }
    }
}
