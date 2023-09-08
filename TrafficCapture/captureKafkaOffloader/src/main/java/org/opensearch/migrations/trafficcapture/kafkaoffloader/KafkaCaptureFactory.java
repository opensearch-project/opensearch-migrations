package org.opensearch.migrations.trafficcapture.kafkaoffloader;

import com.google.protobuf.CodedOutputStream;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.producer.Callback;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.opensearch.migrations.trafficcapture.IChannelConnectionCaptureSerializer;
import org.opensearch.migrations.trafficcapture.IConnectionCaptureFactory;
import org.opensearch.migrations.trafficcapture.StreamChannelConnectionCaptureSerializer;
import org.opensearch.migrations.coreutils.MetricsLogger;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.WeakHashMap;
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

    @Override
    public IChannelConnectionCaptureSerializer createOffloader(String connectionId) throws IOException {
        // This array is only an indirection to work around Java's constraint that lambda values are final
        CompletableFuture[] singleAggregateCfRef = new CompletableFuture[1];
        singleAggregateCfRef[0] = CompletableFuture.completedFuture(null);
        WeakHashMap<CodedOutputStream, ByteBuffer> codedStreamToByteStreamMap = new WeakHashMap<>();
        return new StreamChannelConnectionCaptureSerializer(nodeId, connectionId,
            () -> {
                ByteBuffer bb = ByteBuffer.allocate(bufferSize);
                var cos = CodedOutputStream.newInstance(bb);
                codedStreamToByteStreamMap.put(cos, bb);
                return cos;
            },
            (captureSerializerResult) -> {
                // Structured context for MetricsLogger
                try {
                    CodedOutputStream codedOutputStream = captureSerializerResult.getCodedOutputStream();
                    ByteBuffer byteBuffer = codedStreamToByteStreamMap.get(codedOutputStream);
                    codedStreamToByteStreamMap.remove(codedOutputStream);
                    String recordId = String.format("%s.%d", connectionId, captureSerializerResult.getTrafficStreamIndex());
                    ProducerRecord<String, byte[]> record = new ProducerRecord<>(topicNameForTraffic, recordId,
                        Arrays.copyOfRange(byteBuffer.array(), 0, byteBuffer.position()));
                    // Used to essentially wrap Future returned by Producer to CompletableFuture
                    CompletableFuture cf = new CompletableFuture<>();
                    log.debug("Sending Kafka producer record: {} for topic: {}", recordId, topicNameForTraffic);
                    // Async request to Kafka cluster
                    producer.send(record, handleProducerRecordSent(cf, recordId));
                    metricsLogger.atSuccess()
                            .addKeyValue("channelId", connectionId)
                            .addKeyValue("topic-name", topicNameForTraffic)
                            .addKeyValue("size", record.value().length)
                            .addKeyValue("diagnosticId", recordId)
                            .setMessage("Sent message to Kafka").log();
                    // Note that ordering is not guaranteed to be preserved here
                    // A more desirable way to cut off our tree of cf aggregation should be investigated
                    singleAggregateCfRef[0] = singleAggregateCfRef[0].isDone() ? cf : CompletableFuture.allOf(singleAggregateCfRef[0], cf);
                    return singleAggregateCfRef[0];
                } catch (Exception e) {
                    metricsLogger.atError(e)
                            .addKeyValue("channelId", connectionId)
                            .addKeyValue("topic-name", topicNameForTraffic)
                            .setMessage("Sending message to Kafka failed.").log();
                    throw new RuntimeException(e);
                }
            });
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
