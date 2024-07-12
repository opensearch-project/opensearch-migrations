package org.opensearch.migrations.trafficcapture.kafkaoffloader;

import java.nio.ByteBuffer;
import java.time.Instant;
import java.util.Arrays;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ForkJoinPool;

import com.google.protobuf.CodedOutputStream;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;

import org.opensearch.migrations.tracing.commoncontexts.IConnectionContext;
import org.opensearch.migrations.trafficcapture.CodedOutputStreamHolder;
import org.opensearch.migrations.trafficcapture.IChannelConnectionCaptureSerializer;
import org.opensearch.migrations.trafficcapture.IConnectionCaptureFactory;
import org.opensearch.migrations.trafficcapture.OrderedStreamLifecyleManager;
import org.opensearch.migrations.trafficcapture.StreamChannelConnectionCaptureSerializer;
import org.opensearch.migrations.trafficcapture.kafkaoffloader.tracing.IRootKafkaOffloaderContext;

import lombok.AllArgsConstructor;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class KafkaCaptureFactory implements IConnectionCaptureFactory<RecordMetadata> {

    private static final String DEFAULT_TOPIC_NAME_FOR_TRAFFIC = "logging-traffic-topic";
    // This value encapsulates overhead we should reserve for a given Producer record to account for record key bytes
    // and
    // general Kafka message overhead
    public static final int KAFKA_MESSAGE_OVERHEAD_BYTES = 500;

    private final IRootKafkaOffloaderContext rootScope;
    private final String nodeId;
    // Potential future optimization here to use a direct buffer (e.g. nio) instead of byte array
    private final Producer<String, byte[]> producer;
    private final String topicNameForTraffic;
    private final int bufferSize;

    public KafkaCaptureFactory(
        IRootKafkaOffloaderContext rootScope,
        String nodeId,
        Producer<String, byte[]> producer,
        String topicNameForTraffic,
        int messageSize
    ) {
        this.rootScope = rootScope;
        this.nodeId = nodeId;
        this.producer = producer;
        this.topicNameForTraffic = topicNameForTraffic;
        this.bufferSize = messageSize - KAFKA_MESSAGE_OVERHEAD_BYTES;
    }

    public KafkaCaptureFactory(
        IRootKafkaOffloaderContext rootScope,
        String nodeId,
        Producer<String, byte[]> producer,
        int messageSize
    ) {
        this(rootScope, nodeId, producer, DEFAULT_TOPIC_NAME_FOR_TRAFFIC, messageSize);
    }

    @Override
    public IChannelConnectionCaptureSerializer<RecordMetadata> createOffloader(IConnectionContext ctx) {
        return new StreamChannelConnectionCaptureSerializer<>(
            nodeId,
            ctx.getConnectionId(),
            new StreamManager(rootScope, ctx)
        );
    }

    @AllArgsConstructor
    static class CodedOutputStreamWrapper implements CodedOutputStreamHolder {
        private final CodedOutputStream codedOutputStream;
        private final ByteBuffer byteBuffer;

        @Override
        public int getOutputStreamBytesLimit() {
            return byteBuffer.limit();
        }

        @Override
        public @NonNull CodedOutputStream getOutputStream() {
            return codedOutputStream;
        }
    }

    class StreamManager extends OrderedStreamLifecyleManager<RecordMetadata> {
        IConnectionContext telemetryContext;
        IRootKafkaOffloaderContext rootScope;
        Instant startTime;

        public StreamManager(IRootKafkaOffloaderContext rootScope, IConnectionContext ctx) {
            // TODO - add https://opentelemetry.io/blog/2022/instrument-kafka-clients/
            this.rootScope = rootScope;
            this.telemetryContext = ctx;
            this.startTime = Instant.now();
        }

        @Override
        public CodedOutputStreamWrapper createStream() {
            telemetryContext.addEvent("streamCreated");

            ByteBuffer bb = ByteBuffer.allocate(bufferSize);
            return new CodedOutputStreamWrapper(CodedOutputStream.newInstance(bb), bb);
        }

        @Override
        public CompletableFuture<RecordMetadata> kickoffCloseStream(
            CodedOutputStreamHolder outputStreamHolder,
            int index
        ) {
            if (!(outputStreamHolder instanceof CodedOutputStreamWrapper)) {
                throw new IllegalArgumentException(
                    "Unknown outputStreamHolder sent back to StreamManager: " + outputStreamHolder
                );
            }
            var osh = (CodedOutputStreamWrapper) outputStreamHolder;

            final var connectionId = telemetryContext.getConnectionId();

            String recordId = String.format("%s.%d", connectionId, index);
            var byteBuffer = osh.byteBuffer;
            ProducerRecord<String, byte[]> kafkaRecord = new ProducerRecord<>(
                topicNameForTraffic,
                recordId,
                Arrays.copyOfRange(byteBuffer.array(), 0, byteBuffer.position())
            );
            log.debug("Sending Kafka producer record: {} for topic: {}", recordId, topicNameForTraffic);

            var flushContext = rootScope.createKafkaRecordContext(
                telemetryContext,
                topicNameForTraffic,
                recordId,
                kafkaRecord.value().length
            );
            /*
             * The default KafkaProducer comes with built-in retry and error-handling logic that suits many cases. From the
             * documentation here for retry: https://kafka.apache.org/35/javadoc/org/apache/kafka/clients/producer/KafkaProducer.html
             * "If the request fails, the producer can automatically retry. The retries setting defaults to Integer.MAX_VALUE,
             * and it's recommended to use delivery.timeout.ms to control retry behavior, instead of retries."
             *
             * Apart from this the KafkaProducer has logic for deciding whether an error is transient and should be
             * retried or not retried at all: https://kafka.apache.org/35/javadoc/org/apache/kafka/common/errors/RetriableException.html
             * as well as basic retry backoff
             */
            return sendFullyAsync(producer, kafkaRecord).whenComplete(((recordMetadata, throwable) -> {
                if (throwable != null) {
                    flushContext.addTraceException(throwable, true);
                    log.error("Error sending producer record: {}", recordId, throwable);
                } else {
                    log.debug(
                        "Kafka producer record: {} has finished sending for topic: {} and partition {}",
                        recordId,
                        recordMetadata.topic(),
                        recordMetadata.partition()
                    );
                }
                flushContext.close();
            }));
        }
    }

    // Producer Send will block on actions such as retrieving cluster metadata, allows running fully async
    public static <K, V> CompletableFuture<RecordMetadata> sendFullyAsync(
        Producer<K, V> producer,
        ProducerRecord<K, V> kafkaRecord
    ) {
        CompletableFuture<RecordMetadata> completableFuture = new CompletableFuture<>();

        ForkJoinPool.commonPool().execute(() -> {
            try {
                producer.send(kafkaRecord, (metadata, exception) -> {
                    if (exception != null) {
                        completableFuture.completeExceptionally(exception);
                    } else {
                        completableFuture.complete(metadata);
                    }
                });
            } catch (Exception exception) {
                completableFuture.completeExceptionally(exception);
            }
        });

        return completableFuture;
    }
}
