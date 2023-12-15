package org.opensearch.migrations.replay.tracing;

import org.opensearch.migrations.replay.datatypes.ITrafficStreamKey;
import org.opensearch.migrations.replay.datatypes.UniqueReplayerRequestKey;
import org.opensearch.migrations.tracing.ISpanWithParentGenerator;

public class Contexts {

    private Contexts() {}

    public static class KafkaRecordContext extends DirectNestedSpanContext<IChannelKeyContext>
            implements IContexts.IKafkaRecordContext {
        final String recordId;

        public KafkaRecordContext(IChannelKeyContext enclosingScope, String recordId,
                                  ISpanWithParentGenerator spanGenerator) {
            super(enclosingScope);
            this.recordId = recordId;
            setCurrentSpan(spanGenerator);
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
                                              ITrafficStreamKey trafficStreamKey,
                                              ISpanWithParentGenerator spanGenerator) {
            super(enclosingScope);
            this.trafficStreamKey = trafficStreamKey;
            setCurrentSpan(spanGenerator);
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
            return enclosingScope.getLogicalEnclosingScope();
        }
    }

    public static class HttpTransactionContext
            extends IndirectNestedSpanContext<IContexts.ITrafficStreamsLifecycleContext, IChannelKeyContext>
            implements IContexts.IReplayerHttpTransactionContext {
        final UniqueReplayerRequestKey replayerRequestKey;

        public HttpTransactionContext(IContexts.ITrafficStreamsLifecycleContext enclosingScope,
                                      UniqueReplayerRequestKey replayerRequestKey,
                                      ISpanWithParentGenerator spanGenerator) {
            super(enclosingScope);
            this.replayerRequestKey = replayerRequestKey;
            setCurrentSpan(spanGenerator);
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
            return enclosingScope.getLogicalEnclosingScope();
        }
    }

    public static class RequestAccumulationContext
            extends DirectNestedSpanContext<IContexts.IReplayerHttpTransactionContext>
            implements IContexts.IRequestAccumulationContext {
        public RequestAccumulationContext(IContexts.IReplayerHttpTransactionContext enclosingScope,
                                          ISpanWithParentGenerator spanGenerator) {
            super(enclosingScope);
            setCurrentSpan(spanGenerator);
        }
    }

    public static class ResponseAccumulationContext
            extends DirectNestedSpanContext<IContexts.IReplayerHttpTransactionContext>
            implements IContexts.IResponseAccumulationContext {
        public ResponseAccumulationContext(IContexts.IReplayerHttpTransactionContext enclosingScope,
                                          ISpanWithParentGenerator spanGenerator) {
            super(enclosingScope);
            setCurrentSpan(spanGenerator);
        }
    }

    public static class RequestTransformationContext
            extends DirectNestedSpanContext<IContexts.IReplayerHttpTransactionContext>
            implements IContexts.IRequestTransformationContext {
        public RequestTransformationContext(IContexts.IReplayerHttpTransactionContext enclosingScope,
                                            ISpanWithParentGenerator spanGenerator) {
            super(enclosingScope);
            setCurrentSpan(spanGenerator);
        }
    }

    public static class WaitingForHttpResponseContext
            extends DirectNestedSpanContext<IContexts.IReplayerHttpTransactionContext>
            implements IContexts.IWaitingForHttpResponseContext {
    public WaitingForHttpResponseContext(IContexts.IReplayerHttpTransactionContext enclosingScope,
                                         ISpanWithParentGenerator spanGenerator) {
            super(enclosingScope);
            setCurrentSpan(spanGenerator);
        }
    }

    public static class ReceivingHttpResponseContext
            extends DirectNestedSpanContext<IContexts.IReplayerHttpTransactionContext>
            implements IContexts.IReceivingHttpResponseContext {
        public ReceivingHttpResponseContext(IContexts.IReplayerHttpTransactionContext enclosingScope,
                                            ISpanWithParentGenerator spanGenerator) {
            super(enclosingScope);
            setCurrentSpan(spanGenerator);
        }
    }

    public static class RequestSendingContext
            extends DirectNestedSpanContext<IContexts.IReplayerHttpTransactionContext>
            implements IContexts.IRequestSendingContext {
        public RequestSendingContext(IContexts.IReplayerHttpTransactionContext enclosingScope,
                                     ISpanWithParentGenerator spanGenerator) {
            super(enclosingScope);
            setCurrentSpan(spanGenerator);
        }
    }

    public static class TupleHandlingContext
            extends DirectNestedSpanContext<IContexts.IReplayerHttpTransactionContext>
            implements IContexts.ITupleHandlingContext {
        public TupleHandlingContext(IContexts.IReplayerHttpTransactionContext enclosingScope,
                                    ISpanWithParentGenerator spanGenerator) {
            super(enclosingScope);
            setCurrentSpan(spanGenerator);
        }
    }
}
