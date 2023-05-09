package org.opensearch.migrations.trafficcapture.kafkaoffloader;

import com.google.protobuf.CodedOutputStream;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.producer.Callback;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.opensearch.migrations.trafficcapture.IChannelConnectionCaptureSerializer;
import org.opensearch.migrations.trafficcapture.IConnectionCaptureFactory;
import org.opensearch.migrations.trafficcapture.StreamChannelConnectionCaptureSerializer;

import java.io.FileReader;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Properties;
import java.util.WeakHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

@Slf4j
public class KafkaCaptureFactory implements IConnectionCaptureFactory {

    private static final String DEFAULT_TOPIC_NAME_FOR_TRAFFIC = "logging-traffic-topic";

    // Potential future optimization here to use a direct buffer (e.g. nio) instead of byte array
    private final Producer<String, byte[]> producer;
    private final String topicNameForTraffic;

    public KafkaCaptureFactory(String producerPropertiesPath, String topicNameForTraffic) throws IOException {
        Properties producerProps = new Properties();
        try {
            producerProps.load(new FileReader(producerPropertiesPath));
        } catch (IOException e) {
            log.error("Unable to locate provided Kafka producer properties file path: " + producerPropertiesPath);
            throw e;
        }
        // There is likely some default timeout/retry settings we should configure here to reduce any potential blocking
        // i.e. the Kafka cluster is unavailable
        producer = new KafkaProducer<>(producerProps);
        this.topicNameForTraffic = topicNameForTraffic;
    }

    public KafkaCaptureFactory(String producerPropertiesPath) throws IOException {
        this(producerPropertiesPath, DEFAULT_TOPIC_NAME_FOR_TRAFFIC);
    }

    @Override
    public IChannelConnectionCaptureSerializer createOffloader(String connectionId) throws IOException {
        AtomicLong supplierCallCounter = new AtomicLong();
        AtomicReference<CompletableFuture> aggregateCf = new AtomicReference<>(CompletableFuture.completedFuture(null));
        WeakHashMap<CodedOutputStream, ByteBuffer> codedStreamToByteStreamMap = new WeakHashMap<>();
        return new StreamChannelConnectionCaptureSerializer(connectionId, 100,
            () -> {
                ByteBuffer bb = ByteBuffer.allocate(1024 * 1024);
                var cos = CodedOutputStream.newInstance(bb);
                codedStreamToByteStreamMap.put(cos, bb);
                return cos;
            },
            (codedOutputStream) -> {
                try {
                    ByteBuffer byteBuffer = codedStreamToByteStreamMap.get(codedOutputStream);
                    codedStreamToByteStreamMap.remove(codedOutputStream);
                    String recordId = String.format("%s_%d", connectionId, supplierCallCounter.incrementAndGet());
                    ProducerRecord<String, byte[]> record = new ProducerRecord<>(topicNameForTraffic, recordId,
                        Arrays.copyOfRange(byteBuffer.array(), 0, byteBuffer.position()));
                    // Used to essentially wrap Future returned by Producer to CompletableFuture
                    CompletableFuture cf = new CompletableFuture<>();
                    // Async request to Kafka cluster
                    producer.send(record, handleProducerRecordSent(cf, recordId));
                    // TODO more reliable way to cut off CF tree
                    aggregateCf.set(aggregateCf.get().isDone() ? cf : CompletableFuture.allOf(aggregateCf.get(), cf));
                    return aggregateCf.get();
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
    }

    private Callback handleProducerRecordSent(CompletableFuture cf, String recordId) {
        return (metadata, exception) -> {
            cf.complete(metadata);

            if (exception != null) {
                log.error(String.format("Error sending producer record: %s", recordId), exception);
            }
            else if (log.isDebugEnabled()) {
                log.debug(String.format("Kafka producer record: %s has finished sending for topic: %s and partition %d",
                    recordId, metadata.topic(), metadata.partition()));
            }
        };
    }

}
