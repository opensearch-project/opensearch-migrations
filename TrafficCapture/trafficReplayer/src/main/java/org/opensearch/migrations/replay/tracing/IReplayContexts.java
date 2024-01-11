package org.opensearch.migrations.replay.tracing;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.AttributesBuilder;
import io.opentelemetry.api.metrics.LongCounter;
import io.opentelemetry.api.metrics.LongUpDownCounter;
import io.opentelemetry.api.metrics.MeterProvider;
import org.opensearch.migrations.replay.datatypes.ISourceTrafficChannelKey;
import org.opensearch.migrations.replay.datatypes.ITrafficStreamKey;
import org.opensearch.migrations.replay.datatypes.UniqueReplayerRequestKey;
import org.opensearch.migrations.tracing.CommonScopedMetricInstruments;
import org.opensearch.migrations.tracing.IInstrumentConstructor;
import org.opensearch.migrations.tracing.IScopedInstrumentationAttributes;
import org.opensearch.migrations.tracing.IWithTypedEnclosingScope;
import org.opensearch.migrations.tracing.commoncontexts.IConnectionContext;
import org.opensearch.migrations.tracing.commoncontexts.IHttpTransactionContext;

import java.time.Instant;

public class IReplayContexts {
//
//    public static class ScopeNames {
//        private ScopeNames() {}
//
//        public static final String KAFKA_RECORD_SCOPE = "KafkaRecord";
//        public static final String TRAFFIC_STREAM_LIFETIME_SCOPE = "TrafficStreamLifetime";
//        public static final String ACCUMULATOR_SCOPE = "Accumulator";
//        public static final String HTTP_TRANSFORMER_SCOPE = "HttpTransformer";
//        public static final String REQUEST_SENDER_SCOPE = "RequestSender";
//        public static final String TRAFFIC_REPLAYER_SCOPE = "TrafficReplayer";
//    }

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
        public static final String ACTIVE_TARGET_CONNECTIONS = "activeTargetConnections";
        public static final String BYTES_WRITTEN_TO_TARGET = "bytesWrittenToTarget";
        public static final String BYTES_READ_FROM_TARGET = "bytesReadFromTarget";
    }

    public interface IAccumulationScope<S extends IInstrumentConstructor> extends IScopedInstrumentationAttributes<S> {
        String SCOPE_NAME2 = "Replay";

        @Override
        default String getScopeName() {
            return SCOPE_NAME2;
        }
    }

    public interface IChannelKeyContext<S extends IInstrumentConstructor>
            extends IAccumulationScope<S>,
                    IConnectionContext<S> {
        String ACTIVITY_NAME = ActivityNames.CHANNEL;

        class MetricInstruments extends CommonScopedMetricInstruments {
            final LongUpDownCounter activeChannelCounter;
            public MetricInstruments(MeterProvider meterProvider) {
                super(meterProvider, SCOPE_NAME2, ACTIVITY_NAME);
                var meter = meterProvider.get(SCOPE_NAME2);
                activeChannelCounter = meter
                        .upDownCounterBuilder(MetricNames.ACTIVE_TARGET_CONNECTIONS).build();
            }
        }

        @Override default String getActivityName() { return ACTIVITY_NAME;}

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

    public interface IKafkaRecordContext<S extends IInstrumentConstructor>
            extends IAccumulationScope<S>,
                    IWithTypedEnclosingScope<S, IChannelKeyContext<S>>
    {
        String ACTIVITY_NAME = ActivityNames.RECORD_LIFETIME;

        class MetricInstruments extends CommonScopedMetricInstruments {
            public MetricInstruments(MeterProvider meterProvider) {
                super(meterProvider, SCOPE_NAME2, ACTIVITY_NAME);
            }
        }

        @Override default String getActivityName() { return ACTIVITY_NAME;}

        static final AttributeKey<String> RECORD_ID_KEY = AttributeKey.stringKey("recordId");

        String getRecordId();

        default AttributesBuilder fillAttributes(AttributesBuilder builder) {
            return IAccumulationScope.super.fillAttributes(builder.put(RECORD_ID_KEY, getRecordId()));
        }
    }

    public interface ITrafficStreamsLifecycleContext<S extends IInstrumentConstructor>
            extends IAccumulationScope<S>,
                    IWithTypedEnclosingScope<S, IChannelKeyContext<S>> {
        String ACTIVITY_NAME = ActivityNames.TRAFFIC_STREAM_LIFETIME;

        class MetricInstruments extends CommonScopedMetricInstruments {
            public MetricInstruments(MeterProvider meterProvider) {
                super(meterProvider, SCOPE_NAME2, ACTIVITY_NAME);
            }
        }

        @Override default String getActivityName() { return ACTIVITY_NAME;}
        ITrafficStreamKey getTrafficStreamKey();
        IChannelKeyContext<S> getChannelKeyContext();
        default String getConnectionId() {
            return getChannelKey().getConnectionId();
        }
        default ISourceTrafficChannelKey getChannelKey() {
            return getChannelKeyContext().getChannelKey();
        }
    }

    public interface IReplayerHttpTransactionContext<S extends IInstrumentConstructor>
            extends IHttpTransactionContext<S>,
                    IAccumulationScope<S>,
                    IWithTypedEnclosingScope<S, IChannelKeyContext<S>> {
        AttributeKey<Long> REPLAYER_REQUEST_INDEX_KEY = AttributeKey.longKey("replayerRequestIndex");

        ITupleHandlingContext createTupleContext();

        class MetricInstruments extends CommonScopedMetricInstruments {
            public MetricInstruments(MeterProvider meterProvider) {
                super(meterProvider, SCOPE_NAME2, ACTIVITY_NAME);
            }
        }

        String ACTIVITY_NAME = ActivityNames.HTTP_TRANSACTION;
        @Override default String getActivityName() { return ACTIVITY_NAME;}

        UniqueReplayerRequestKey getReplayerRequestKey();
        IChannelKeyContext<S> getChannelKeyContext();
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
        default AttributesBuilder fillAttributes(AttributesBuilder builder) {
            return IHttpTransactionContext.super.fillAttributes(
                    builder.put(REPLAYER_REQUEST_INDEX_KEY, replayerRequestIndex()));
        }
    }

    public interface IRequestAccumulationContext<S extends IInstrumentConstructor>
            extends IAccumulationScope<S>,
                    IWithTypedEnclosingScope<S, IReplayerHttpTransactionContext<S>> {
        String ACTIVITY_NAME = ActivityNames.ACCUMULATING_REQUEST;

        class MetricInstruments extends CommonScopedMetricInstruments {
            public MetricInstruments(MeterProvider meterProvider) {
                super(meterProvider, SCOPE_NAME2, ACTIVITY_NAME);
            }
        }

        @Override
        default String getActivityName() { return ACTIVITY_NAME;}
    }

    public interface IResponseAccumulationContext<S extends IInstrumentConstructor>
            extends IAccumulationScope<S>,
                    IWithTypedEnclosingScope<S, IReplayerHttpTransactionContext<S>> {
        String ACTIVITY_NAME = ActivityNames.ACCUMULATING_RESPONSE;

        class MetricInstruments extends CommonScopedMetricInstruments {
            public MetricInstruments(MeterProvider meterProvider) {
                super(meterProvider, SCOPE_NAME2, ACTIVITY_NAME);
            }
        }

        @Override
        default String getActivityName() { return ACTIVITY_NAME;}
    }

    public interface IRequestTransformationContext<S extends IInstrumentConstructor>
            extends IAccumulationScope<S>,
                    IWithTypedEnclosingScope<S, IReplayerHttpTransactionContext<S>> {
        String ACTIVITY_NAME = ActivityNames.TRANSFORMATION;

        class MetricInstruments extends CommonScopedMetricInstruments {
            public MetricInstruments(MeterProvider meterProvider) {
                super(meterProvider, SCOPE_NAME2, ACTIVITY_NAME);
            }
        }

        @Override default String getActivityName() { return ACTIVITY_NAME;}

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

    public interface IScheduledContext<S extends IInstrumentConstructor>
            extends IAccumulationScope<S>,
                    IWithTypedEnclosingScope<S, IReplayerHttpTransactionContext<S>> {
        String ACTIVITY_NAME = ActivityNames.SCHEDULED;

        class MetricInstruments extends CommonScopedMetricInstruments {
            public MetricInstruments(MeterProvider meterProvider) {
                super(meterProvider, SCOPE_NAME2, ACTIVITY_NAME);
            }
        }

        @Override
        default String getActivityName() { return ACTIVITY_NAME;}
    }

    public interface ITargetRequestContext<S extends IInstrumentConstructor>
            extends IAccumulationScope<S>,
                    IWithTypedEnclosingScope<S, IReplayerHttpTransactionContext<S>> {
        String ACTIVITY_NAME = ActivityNames.TARGET_TRANSACTION;

        @Override default String getActivityName() { return ACTIVITY_NAME;}

        void onBytesSent(int size);
        void onBytesReceived(int size);
    }

    public interface IRequestSendingContext<S extends IInstrumentConstructor>
            extends IAccumulationScope<S>,
                    IWithTypedEnclosingScope<S, ITargetRequestContext<S>> {
        String ACTIVITY_NAME = ActivityNames.REQUEST_SENDING;
        @Override default String getActivityName() { return ACTIVITY_NAME;}
    }

    public interface IWaitingForHttpResponseContext<S extends IInstrumentConstructor>
            extends IAccumulationScope<S>,
                    IWithTypedEnclosingScope<S, ITargetRequestContext<S>> {
        String ACTIVITY_NAME = ActivityNames.WAITING_FOR_RESPONSE;
        @Override default String getActivityName() { return ACTIVITY_NAME;}
    }

    public interface IReceivingHttpResponseContext<S extends IInstrumentConstructor>
            extends IAccumulationScope<S>,
                    IWithTypedEnclosingScope<S, ITargetRequestContext<S>> {
        String ACTIVITY_NAME = ActivityNames.RECEIVING_RESPONSE;
        @Override default String getActivityName() { return ACTIVITY_NAME;}
    }

    public interface ITupleHandlingContext<S extends IInstrumentConstructor>
            extends IAccumulationScope<S>,
                    IWithTypedEnclosingScope<S, IReplayerHttpTransactionContext<S>> {
        String ACTIVITY_NAME = ActivityNames.TUPLE_HANDLING;
        @Override default String getActivityName() { return ACTIVITY_NAME; }
    }
}
