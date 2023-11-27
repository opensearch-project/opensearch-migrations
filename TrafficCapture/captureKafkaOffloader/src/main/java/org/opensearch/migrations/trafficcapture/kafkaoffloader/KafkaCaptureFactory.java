package org.opensearch.migrations.trafficcapture.kafkaoffloader;

import com.google.protobuf.CodedOutputStream;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.ContextKey;
import lombok.AllArgsConstructor;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.producer.Callback;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.opensearch.migrations.coreutils.MetricsAttributeKey;
import org.opensearch.migrations.coreutils.MetricsEvent;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.opensearch.migrations.trafficcapture.CodedOutputStreamHolder;
import org.opensearch.migrations.trafficcapture.IChannelConnectionCaptureSerializer;
import org.opensearch.migrations.trafficcapture.IConnectionCaptureFactory;
import org.opensearch.migrations.trafficcapture.OrderedStreamLifecyleManager;
import org.opensearch.migrations.trafficcapture.StreamChannelConnectionCaptureSerializer;
import org.opensearch.migrations.coreutils.MetricsLogger;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;


@Slf4j
public class KafkaCaptureFactory implements IConnectionCaptureFactory<RecordMetadata> {

    private static final ContextKey<String> RECORD_ID_KEY = ContextKey.named("recordId");
    private static final ContextKey<String> TOPIC_KEY = ContextKey.named("topic");
    private static final ContextKey<Integer> RECORD_SIZE_KEY = ContextKey.named("recordSize");
    public static final String TELEMETRY_SCOPE_NAME = "KafkaCapture";
    public static final Optional<MetricsLogger.SimpleMeteringClosure> METERING_CLOSURE_OP =
            Optional.of(new MetricsLogger.SimpleMeteringClosure(TELEMETRY_SCOPE_NAME));

    private static final MetricsLogger metricsLogger = new MetricsLogger("BacksideHandler");

    private static final String DEFAULT_TOPIC_NAME_FOR_TRAFFIC = "logging-traffic-topic";
    // This value encapsulates overhead we should reserve for a given Producer record to account for record key bytes and
    // general Kafka message overhead
    public static final int KAFKA_MESSAGE_OVERHEAD_BYTES = 500;

    private final String nodeId;
    // Potential future optimization here to use a direct buffer (e.g. nio) instead of byte array
    private final Producer<String, byte[]> producer;
    private final String topicNameForTraffic;
    private final int bufferSize;

    public KafkaCaptureFactory(String nodeId, Producer<String, byte[]> producer,
                               String topicNameForTraffic, int messageSize) {
        this.nodeId = nodeId;
        this.producer = producer;
        this.topicNameForTraffic = topicNameForTraffic;
        this.bufferSize = messageSize - KAFKA_MESSAGE_OVERHEAD_BYTES;
    }

    public KafkaCaptureFactory(String nodeId, Producer<String, byte[]> producer, int messageSize) {
        this(nodeId, producer, DEFAULT_TOPIC_NAME_FOR_TRAFFIC, messageSize);
    }

    @Override
    public IChannelConnectionCaptureSerializer<RecordMetadata> createOffloader(String connectionId) {
        var context = METERING_CLOSURE_OP.map(m->{
            Span offloaderSpan = GlobalOpenTelemetry.get().getTracer(TELEMETRY_SCOPE_NAME)
                    .spanBuilder("offloader").startSpan();
            offloaderSpan.setAttribute("offloaderConnectionId", connectionId);
            var c = Context.current().with(offloaderSpan);
            m.meterIncrementEvent(c, "offloader_created");
            m.meterDeltaEvent(c, "offloaders_active", 1);
            return c;
        }).orElse(null);
        return new StreamChannelConnectionCaptureSerializer<>(nodeId, connectionId,
                new StreamManager(context, connectionId));
    }

    @AllArgsConstructor
    static class CodedOutputStreamWrapper implements CodedOutputStreamHolder {
        private final CodedOutputStream codedOutputStream;
        private final ByteBuffer byteBuffer;
        final Context streamContext;
        @Override
        public @NonNull CodedOutputStream getOutputStream() {
            return codedOutputStream;
        }
    }

    class StreamManager extends OrderedStreamLifecyleManager<RecordMetadata> {
        Context telemetryContext;
        String connectionId;
        Instant startTime;

        public StreamManager(Context incomingTelemetryContext, String connectionId) {
            this.telemetryContext = incomingTelemetryContext;
            this.connectionId = connectionId;
            this.startTime = Instant.now();
        }

        @Override
        public void close() throws IOException {
            METERING_CLOSURE_OP.ifPresent(m->{
                m.meterHistogramMillis(telemetryContext, "connection_lifetime",
                        Duration.between(startTime, Instant.now()));
                m.meterDeltaEvent(telemetryContext, "offloaders_active", -1);
                m.meterIncrementEvent(telemetryContext, "offloader_closed");
            });
            Span.fromContext(telemetryContext).end();
        }

        @Override
        public CodedOutputStreamWrapper createStream() {
            var newStreamCtx = METERING_CLOSURE_OP.map(m-> {
                m.meterIncrementEvent(telemetryContext, "stream_created");
                try (var scope = telemetryContext.makeCurrent()) {
                    return Context.current().with(m.tracer.spanBuilder("recordStream").startSpan());
                }
            }).orElse(null);
            ByteBuffer bb = ByteBuffer.allocate(bufferSize);
            return new CodedOutputStreamWrapper(CodedOutputStream.newInstance(bb), bb, newStreamCtx);
        }

        @Override
        public CompletableFuture<RecordMetadata>
        kickoffCloseStream(CodedOutputStreamHolder outputStreamHolder, int index) {
            if (!(outputStreamHolder instanceof CodedOutputStreamWrapper)) {
                throw new IllegalArgumentException("Unknown outputStreamHolder sent back to StreamManager: " +
                        outputStreamHolder);
            }
            var osh = (CodedOutputStreamWrapper) outputStreamHolder;

            // Structured context for MetricsLogger
            try {
                String recordId = String.format("%s.%d", connectionId, index);
                var byteBuffer = osh.byteBuffer;
                ProducerRecord<String, byte[]> kafkaRecord = new ProducerRecord<>(topicNameForTraffic, recordId,
                        Arrays.copyOfRange(byteBuffer.array(), 0, byteBuffer.position()));
                // Used to essentially wrap Future returned by Producer to CompletableFuture
                var cf = new CompletableFuture<RecordMetadata>();
                log.debug("Sending Kafka producer record: {} for topic: {}", recordId, topicNameForTraffic);

                var flushContext = METERING_CLOSURE_OP.map(m-> {
                    Span.fromContext(osh.streamContext).end();
                    try (var scope = telemetryContext
                            .with(RECORD_ID_KEY, recordId)
                            .with(TOPIC_KEY, topicNameForTraffic)
                            .with(RECORD_SIZE_KEY, kafkaRecord.value().length).makeCurrent()) {
                        m.meterIncrementEvent(telemetryContext, "stream_flush_called");
                        return Context.current().with(m.tracer.spanBuilder("flushRecord").startSpan());
                    }
                }).orElse(null);

                // Async request to Kafka cluster
                producer.send(kafkaRecord, handleProducerRecordSent(cf, recordId, flushContext));
                metricsLogger.atSuccess(MetricsEvent.RECORD_SENT_TO_KAFKA)
                        .setAttribute(MetricsAttributeKey.CHANNEL_ID, connectionId)
                        .setAttribute(MetricsAttributeKey.TOPIC_NAME, topicNameForTraffic)
                        .setAttribute(MetricsAttributeKey.SIZE_IN_BYTES, kafkaRecord.value().length)
                        .setAttribute(MetricsAttributeKey.REQUEST_ID, recordId).emit();
                return cf;
            } catch (Exception e) {
                metricsLogger.atError(MetricsEvent.RECORD_FAILED_TO_KAFKA, e)
                        .setAttribute(MetricsAttributeKey.CHANNEL_ID, connectionId)
                        .setAttribute(MetricsAttributeKey.TOPIC_NAME, topicNameForTraffic).emit();
                throw e;
            }
        }

        /**
         * The default KafkaProducer comes with built-in retry and error-handling logic that suits many cases. From the
         * documentation here for retry: https://kafka.apache.org/35/javadoc/org/apache/kafka/clients/producer/KafkaProducer.html
         * "If the request fails, the producer can automatically retry. The retries setting defaults to Integer.MAX_VALUE,
         * and it's recommended to use delivery.timeout.ms to control retry behavior, instead of retries."
         * <p>
         * Apart from this the KafkaProducer has logic for deciding whether an error is transient and should be
         * retried or not retried at all: https://kafka.apache.org/35/javadoc/org/apache/kafka/common/errors/RetriableException.html
         * as well as basic retry backoff
         */
        private Callback handleProducerRecordSent(CompletableFuture<RecordMetadata> cf, String recordId,
                                                  Context flushContext) {
            return (metadata, exception) -> {
                METERING_CLOSURE_OP.ifPresent(m-> {
                    m.meterIncrementEvent(telemetryContext,
                            exception==null ? "stream_flush_success" : "stream_flush_failure");
                    m.meterIncrementEvent(telemetryContext,
                            exception==null ? "stream_flush_success_bytes" : "stream_flush_failure_bytes",
                            flushContext.get(RECORD_SIZE_KEY));
                    Span.fromContext(flushContext).end();
                });

                if (exception != null) {
                    log.error("Error sending producer record: {}", recordId, exception);
                    cf.completeExceptionally(exception);
                } else {
                    log.debug("Kafka producer record: {} has finished sending for topic: {} and partition {}",
                            recordId, metadata.topic(), metadata.partition());
                    cf.complete(metadata);
                }
            };
        }
    }
}
