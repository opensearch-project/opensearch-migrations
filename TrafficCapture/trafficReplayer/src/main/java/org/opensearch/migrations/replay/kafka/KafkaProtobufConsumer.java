package org.opensearch.migrations.replay.kafka;

import com.google.protobuf.InvalidProtocolBufferException;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.opensearch.migrations.replay.ITrafficCaptureSource;
import org.opensearch.migrations.trafficcapture.protos.TrafficStream;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.Collections;
import java.util.Objects;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

@Slf4j
public class KafkaProtobufConsumer implements ITrafficCaptureSource {

    public static final Duration CONSUMER_POLL_TIMEOUT = Duration.ofSeconds(1);
    private final Consumer<String, byte[]> consumer;
    private final String topic;
    private final KafkaBehavioralPolicy behavioralPolicy;


    public KafkaProtobufConsumer(Consumer<String, byte[]> consumer, String topic) {
        assert topic != null;
        this.consumer = consumer;
        this.topic = topic;
        this.behavioralPolicy = new KafkaBehavioralPolicy();
    }

    public KafkaProtobufConsumer(Consumer<String, byte[]> consumer, String topic, KafkaBehavioralPolicy behavioralPolicy) {
        assert topic != null;
        this.consumer = consumer;
        this.topic = topic;
        this.behavioralPolicy = behavioralPolicy;
    }

    public static KafkaProtobufConsumer buildKafkaConsumer(@NonNull String brokers, @NonNull String topic, @NonNull String groupId,
        boolean enableMSKAuth, String propertyFilePath, KafkaBehavioralPolicy behavioralPolicy) throws IOException {
        var kafkaProps = buildKafkaProperties(brokers, groupId, enableMSKAuth, propertyFilePath);
        return new KafkaProtobufConsumer(new KafkaConsumer<>(kafkaProps), topic, behavioralPolicy);
    }

    public static Properties buildKafkaProperties(@NonNull String brokers, @NonNull String groupId, boolean enableMSKAuth,
        String propertyFilePath) throws IOException {
        var kafkaProps = new Properties();
        kafkaProps.setProperty("key.deserializer", "org.apache.kafka.common.serialization.StringDeserializer");
        kafkaProps.setProperty("value.deserializer", "org.apache.kafka.common.serialization.ByteArrayDeserializer");
        kafkaProps.setProperty(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        if (propertyFilePath != null) {
            try (InputStream input = new FileInputStream(propertyFilePath)) {
                kafkaProps.load(input);
            } catch (IOException ex) {
                log.error("Unable to load properties from kafka properties file with path: {}", propertyFilePath);
                throw ex;
            }
        }
        // Required for using SASL auth with MSK public endpoint
        if (enableMSKAuth) {
            kafkaProps.setProperty("security.protocol", "SASL_SSL");
            kafkaProps.setProperty("sasl.mechanism", "AWS_MSK_IAM");
            kafkaProps.setProperty("sasl.jaas.config", "software.amazon.msk.auth.iam.IAMLoginModule required;");
            kafkaProps.setProperty("sasl.client.callback.handler.class", "software.amazon.msk.auth.iam.IAMClientCallbackHandler");
        }
        kafkaProps.setProperty(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, brokers);
        kafkaProps.setProperty(ConsumerConfig.GROUP_ID_CONFIG, groupId);
        return kafkaProps;
    }

    @Override
    @SuppressWarnings("unchecked")
    public Stream<TrafficStream> supplyTrafficFromSource() {
        try {
            consumer.subscribe(Collections.singleton(topic));
            AtomicInteger trafficStreamsRead = new AtomicInteger();
            Stream<Stream<TrafficStream>> generatedStream = Stream.generate((Supplier) () -> {
                ConsumerRecords<String, byte[]> records;
                try {
                    records = consumer.poll(CONSUMER_POLL_TIMEOUT);
                }
                catch (RuntimeException e) {
                    // Generally this block is reserved for exceptions that Kafka deems are unrecoverable
                    log.warn("Unable to poll the topic: {} with our Kafka consumer. Swallowing and awaiting next " +
                        "metadata refresh to try again. Exception: {}", topic, e);
                    records = new ConsumerRecords<>(Collections.emptyMap());
                }
                Stream<TrafficStream> trafficStream = StreamSupport.stream(records.spliterator(), false).map(record -> {
                    try {
                        TrafficStream ts = TrafficStream.parseFrom(record.value());
                        // Ensure we increment trafficStreamsRead even at a higher log level
                        log.trace("Parsed traffic stream #{}: {}", trafficStreamsRead.incrementAndGet(), ts);
                        return ts;
                    } catch (InvalidProtocolBufferException e) {
                        RuntimeException recordError = behavioralPolicy.onInvalidKafkaRecord(record, e);
                        if (recordError != null) {
                            throw recordError;
                        }
                        return null;
                    }
                }).filter(Objects::nonNull);
                return trafficStream;
            }).takeWhile(Objects::nonNull);
            return generatedStream.flatMap(stream -> stream);
        } catch (Exception e) {
            log.error("Terminating Kafka traffic stream");
            throw e;
        }
    }

    @Override
    public void close() throws IOException {
        consumer.close();
        log.info("Kafka consumer closed successfully.");
    }

}
