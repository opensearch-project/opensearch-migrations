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
import org.opensearch.migrations.trafficcapture.model.CaptureOutputStreamMetadata;

import java.io.ByteArrayOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
public class KafkaCaptureFactory implements IConnectionCaptureFactory {

    private static final String DEFAULT_MUTATING_TOPIC = "logging-mutating-topic";
    private static final String DEFAULT_NON_MUTATING_TOPIC = "logging-non-mutating-topic";

    private final Producer<String, byte[]> producer;
    private final String mutatingTopicName;
    private final String nonMutatingTopicName;

    public KafkaCaptureFactory(String producerPropertiesPath, String mutatingTopicName, String nonMutatingTopicName) throws IOException {
        Properties producerProps = new Properties();
        try {
            producerProps.load(new FileReader(producerPropertiesPath));
        } catch (IOException e) {
            System.err.println("Unable to locate provided properties file path: " + producerPropertiesPath);
            throw e;
        }
        // There is likely some default timeout/retry settings we should configure here to reduce any potential blocking
        // i.e. the Kafka cluster is unavailable
        producer = new KafkaProducer<>(producerProps);
        this.mutatingTopicName = mutatingTopicName;
        this.nonMutatingTopicName = nonMutatingTopicName;
    }

    public KafkaCaptureFactory(String producerPropertiesPath) throws IOException {
        this(producerPropertiesPath, DEFAULT_MUTATING_TOPIC, DEFAULT_NON_MUTATING_TOPIC);
    }

    @Override
    public IChannelConnectionCaptureSerializer createOffloader(String connectionId) throws IOException {
        AtomicLong supplierCallCounter = new AtomicLong();
        Map<CodedOutputStream, ByteArrayOutputStream> codedStreamToByteStreamMap = new HashMap<>();
        return new StreamChannelConnectionCaptureSerializer(connectionId,
            () -> {
                // Potentially enhance to use java nio ByteBuffer here if we know size beforehand
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                var cos = CodedOutputStream.newInstance(baos);
                codedStreamToByteStreamMap.put(cos, baos);
                return cos;
            },
            (offloaderInput) -> {
                try {
                    CodedOutputStream cos = offloaderInput.getCodedOutputStream();
                    CaptureOutputStreamMetadata metadata = offloaderInput.getCaptureOutputStreamMetadata();
                    ByteArrayOutputStream byteStream = codedStreamToByteStreamMap.get(cos);
                    byte[] bytes = byteStream.toByteArray();
                    byteStream.close();
                    codedStreamToByteStreamMap.remove(cos);
                    String topic = metadata.isMutating() ? mutatingTopicName : nonMutatingTopicName;
                    ProducerRecord<String, byte[]> record = new ProducerRecord<>(topic,
                        String.format("%s_%d", connectionId, supplierCallCounter.incrementAndGet()), bytes);
                    // Used to essentially wrap Future returned by Producer to CompletableFuture
                    CompletableFuture cf = new CompletableFuture<>();
                    // Async request to Kafka cluster
                    producer.send(record, handleProducerRecordSent(cf));
                    return cf;
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });
    }

    private Callback handleProducerRecordSent(CompletableFuture cf) {
        return (metadata, exception) -> {
            cf.complete(metadata);
            // Add more logging and error handling here
            System.err.println(String.format("Kafka producer record has finished sending for topic: %s and partition %d",
                metadata.topic(), metadata.partition()));
        };
    }

}
