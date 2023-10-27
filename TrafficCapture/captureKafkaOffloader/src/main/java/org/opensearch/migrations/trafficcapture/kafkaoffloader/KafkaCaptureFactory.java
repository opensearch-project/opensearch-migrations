package org.opensearch.migrations.trafficcapture.kafkaoffloader;

import com.google.protobuf.CodedOutputStream;
import lombok.AllArgsConstructor;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.producer.Callback;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.opensearch.migrations.trafficcapture.CodedOutputStreamHolder;
import org.opensearch.migrations.trafficcapture.IChannelConnectionCaptureSerializer;
import org.opensearch.migrations.trafficcapture.IConnectionCaptureFactory;
import org.opensearch.migrations.trafficcapture.OrderedStreamLifecyleManager;
import org.opensearch.migrations.trafficcapture.StreamChannelConnectionCaptureSerializer;
import org.opensearch.migrations.coreutils.MetricsLogger;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.concurrent.CompletableFuture;


@Slf4j
public class KafkaCaptureFactory implements IConnectionCaptureFactory {

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

    @AllArgsConstructor
    static class CodedOutputStreamWrapper implements CodedOutputStreamHolder {
        private final CodedOutputStream codedOutputStream;
        private final ByteBuffer byteBuffer;
        @Override
        public @NonNull CodedOutputStream getOutputStream() {
            return codedOutputStream;
        }
    }

    @AllArgsConstructor
    class StreamManager extends OrderedStreamLifecyleManager {
        String connectionId;

        @Override
        public CodedOutputStreamWrapper createStream() {
            ByteBuffer bb = ByteBuffer.allocate(bufferSize);
            return new CodedOutputStreamWrapper(CodedOutputStream.newInstance(bb), bb);
        }

        @Override
        public CompletableFuture<Object>
        kickoffCloseStream(CodedOutputStreamHolder outputStreamHolder, int index) {
            if (!(outputStreamHolder instanceof CodedOutputStreamWrapper)) {
                throw new RuntimeException("Unknown outputStreamHolder sent back to StreamManager: " +
                        outputStreamHolder);
            }
            var osh = (CodedOutputStreamWrapper) outputStreamHolder;

            // Structured context for MetricsLogger
            try {
                String recordId = String.format("%s.%d", connectionId, index);
                var byteBuffer = osh.byteBuffer;
                ProducerRecord<String, byte[]> record = new ProducerRecord<>(topicNameForTraffic, recordId,
                        Arrays.copyOfRange(byteBuffer.array(), 0, byteBuffer.position()));
                // Used to essentially wrap Future returned by Producer to CompletableFuture
                CompletableFuture cf = new CompletableFuture<>();
                log.debug("Sending Kafka producer record: {} for topic: {}", recordId, topicNameForTraffic);
                // Async request to Kafka cluster
                producer.send(record, handleProducerRecordSent(cf, recordId));
                metricsLogger.atSuccess()
                        .addKeyValue("channelId", connectionId)
                        .addKeyValue("topicName", topicNameForTraffic)
                        .addKeyValue("sizeInBytes", record.value().length)
                        .addKeyValue("diagnosticId", recordId)
                        .setMessage("Sent message to Kafka").log();
                return cf;
            } catch (Exception e) {
                metricsLogger.atError(e)
                        .addKeyValue("channelId", connectionId)
                        .addKeyValue("topicName", topicNameForTraffic)
                        .setMessage("Sending message to Kafka failed.").log();
                throw new RuntimeException(e);
            }
        }
    }

    @Override
    public IChannelConnectionCaptureSerializer createOffloader(String connectionId) {
        return new StreamChannelConnectionCaptureSerializer(nodeId, connectionId, new StreamManager(connectionId));
    }

    /**
     *  The default KafkaProducer comes with built-in retry and error-handling logic that suits many cases. From the
     *  documentation here for retry: https://kafka.apache.org/35/javadoc/org/apache/kafka/clients/producer/KafkaProducer.html
     *  "If the request fails, the producer can automatically retry. The retries setting defaults to Integer.MAX_VALUE,
     *  and it's recommended to use delivery.timeout.ms to control retry behavior, instead of retries."
     *
     *  Apart from this the KafkaProducer has logic for deciding whether an error is transient and should be
     *  retried or not retried at all: https://kafka.apache.org/35/javadoc/org/apache/kafka/common/errors/RetriableException.html
     *  as well as basic retry backoff
     */
    private Callback handleProducerRecordSent(CompletableFuture cf, String recordId) {
        return (metadata, exception) -> {
            if (exception != null) {
                log.error("Error sending producer record: {}", recordId, exception);
                cf.completeExceptionally(exception);
            }
            else {
                log.debug("Kafka producer record: {} has finished sending for topic: {} and partition {}",
                    recordId, metadata.topic(), metadata.partition());
                cf.complete(metadata);
            }
        };
    }

}
