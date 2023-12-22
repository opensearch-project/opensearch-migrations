package org.opensearch.migrations.replay.tracing;

import org.opensearch.migrations.replay.datatypes.ITrafficStreamKey;
import org.opensearch.migrations.replay.datatypes.UniqueReplayerRequestKey;
import org.opensearch.migrations.tracing.DirectNestedSpanContext;
import org.opensearch.migrations.tracing.IndirectNestedSpanContext;

public class Contexts {

    private Contexts() {}

    public static class KafkaRecordContext extends DirectNestedSpanContext<IChannelKeyContext>
            implements IContexts.IKafkaRecordContext {
        final String recordId;

        public KafkaRecordContext(IChannelKeyContext enclosingScope, String recordId) {
            super(enclosingScope);
            this.recordId = recordId;
            setCurrentSpan("Kafka", "recordLifetime");
        }

        @Override
        public String getRecordId() {
            return recordId;
        }
    }

    public static class TrafficStreamsLifecycleContext
            extends IndirectNestedSpanContext<IContexts.IKafkaRecordContext, IChannelKeyContext>
            implements IContexts.ITrafficStreamsLifecycleContext {
        private final ITrafficStreamKey trafficStreamKey;

        public TrafficStreamsLifecycleContext(IContexts.IKafkaRecordContext enclosingScope,
                                              ITrafficStreamKey trafficStreamKey) {
            super(enclosingScope);
            this.trafficStreamKey = trafficStreamKey;
            setCurrentSpan("KafkaRecords", "trafficStreamLifetime");
        }

        @Override
        public IChannelKeyContext getChannelKeyContext() {
            return getLogicalEnclosingScope();
        }

        @Override
        public ITrafficStreamKey getTrafficStreamKey() {
            return trafficStreamKey;
        }

        @Override
        public IChannelKeyContext getLogicalEnclosingScope() {
            return getImmediateEnclosingScope().getLogicalEnclosingScope();
        }
    }

    public static class HttpTransactionContext
            extends IndirectNestedSpanContext<IContexts.ITrafficStreamsLifecycleContext, IChannelKeyContext>
            implements IContexts.IReplayerHttpTransactionContext {
        final UniqueReplayerRequestKey replayerRequestKey;

        public HttpTransactionContext(IContexts.ITrafficStreamsLifecycleContext enclosingScope,
                                      UniqueReplayerRequestKey replayerRequestKey) {
            super(enclosingScope);
            this.replayerRequestKey = replayerRequestKey;
            setCurrentSpan("Accumulator", "httpTransaction");
        }

        public IChannelKeyContext getChannelKeyContext() {
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
        public IChannelKeyContext getLogicalEnclosingScope() {
            return getImmediateEnclosingScope().getLogicalEnclosingScope();
        }
    }

    public static class RequestAccumulationContext
            extends DirectNestedSpanContext<IContexts.IReplayerHttpTransactionContext>
            implements IContexts.IRequestAccumulationContext {
        public RequestAccumulationContext(IContexts.IReplayerHttpTransactionContext enclosingScope) {
            super(enclosingScope);
            setCurrentSpan("Accumulator", "accumulatingRequest");
        }
    }

    public static class ResponseAccumulationContext
            extends DirectNestedSpanContext<IContexts.IReplayerHttpTransactionContext>
            implements IContexts.IResponseAccumulationContext {
        public ResponseAccumulationContext(IContexts.IReplayerHttpTransactionContext enclosingScope) {
            super(enclosingScope);
            setCurrentSpan("Accumulator", "accumulatingResponse");
        }
    }

    public static class RequestTransformationContext
            extends DirectNestedSpanContext<IContexts.IReplayerHttpTransactionContext>
            implements IContexts.IRequestTransformationContext {
        public RequestTransformationContext(IContexts.IReplayerHttpTransactionContext enclosingScope) {
            super(enclosingScope);
            setCurrentSpan("HttpTransformer", "transformation");
        }
    }

    public static class ScheduledContext
            extends DirectNestedSpanContext<IContexts.IReplayerHttpTransactionContext>
            implements IContexts.IScheduledContext {
        public ScheduledContext(IContexts.IReplayerHttpTransactionContext enclosingScope) {
            super(enclosingScope);
            setCurrentSpan("RequestSender", "scheduled");
        }
    }

    public static class TargetRequestContext
            extends DirectNestedSpanContext<IContexts.IReplayerHttpTransactionContext>
            implements IContexts.ITargetRequestContext {
        public TargetRequestContext(IContexts.IReplayerHttpTransactionContext enclosingScope) {
            super(enclosingScope);
            setCurrentSpan("RequestSender", "targetTransaction");
        }
    }

    public static class RequestSendingContext
            extends DirectNestedSpanContext<IContexts.ITargetRequestContext>
            implements IContexts.IRequestSendingContext {
        public RequestSendingContext(IContexts.ITargetRequestContext enclosingScope) {
            super(enclosingScope);
            setCurrentSpan("RequestSender","requestSending");
        }
    }

    public static class WaitingForHttpResponseContext
            extends DirectNestedSpanContext<IContexts.ITargetRequestContext>
            implements IContexts.IWaitingForHttpResponseContext {
        public WaitingForHttpResponseContext(IContexts.ITargetRequestContext enclosingScope) {
            super(enclosingScope);
            setCurrentSpan("RequestSender", "waitingForResponse");
        }
    }

    public static class ReceivingHttpResponseContext
            extends DirectNestedSpanContext<IContexts.ITargetRequestContext>
            implements IContexts.IReceivingHttpResponseContext {
        public ReceivingHttpResponseContext(IContexts.ITargetRequestContext enclosingScope) {
            super(enclosingScope);
            setCurrentSpan("HttpSender", "receivingRequest");
        }
    }

    public static class TupleHandlingContext
            extends DirectNestedSpanContext<IContexts.IReplayerHttpTransactionContext>
            implements IContexts.ITupleHandlingContext {
        public TupleHandlingContext(IContexts.IReplayerHttpTransactionContext enclosingScope) {
            super(enclosingScope);
            setCurrentSpan("TrafficReplayer", "tupleHandling");
        }
    }
}
