package org.opensearch.migrations.replay.kafka;

import com.google.protobuf.InvalidProtocolBufferException;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.opensearch.migrations.replay.ITrafficCaptureSource;
import org.opensearch.migrations.trafficcapture.protos.TrafficStream;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.Collections;
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
    private Stream<TrafficStream> supplierStream;

    public KafkaProtobufConsumer(Consumer<String, byte[]> consumer, String topic) {
        assert topic != null;
        this.consumer = consumer;
        this.topic = topic;
    }

    public static KafkaProtobufConsumer buildKafkaConsumer(String brokers, String topic, String groupId, boolean enableMSKAuth, String propertyFilePath) {
        if (brokers == null && topic == null && groupId == null) {
            return null;
        }
        if (brokers == null || topic == null || groupId == null) {
            throw new RuntimeException("To enable a Kafka traffic source, the following parameters are required " +
                "[--kafka-traffic-brokers, --kafka-traffic-topic, --kafka-traffic-group-id]");
        }
        var kafkaProps = buildKafkaProperties(brokers, groupId, enableMSKAuth, propertyFilePath);
        return new KafkaProtobufConsumer(new KafkaConsumer<>(kafkaProps), topic);
    }

    public static Properties buildKafkaProperties(String brokers, String groupId, boolean enableMSKAuth, String propertyFilePath) {
        var kafkaProps = new Properties();
        kafkaProps.setProperty(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, brokers);
        kafkaProps.setProperty("key.deserializer", "org.apache.kafka.common.serialization.StringDeserializer");
        kafkaProps.setProperty("value.deserializer", "org.apache.kafka.common.serialization.ByteArrayDeserializer");
        kafkaProps.setProperty(ConsumerConfig.GROUP_ID_CONFIG, groupId);
        kafkaProps.setProperty(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        // Required for using SASL auth with MSK public endpoint
        if (enableMSKAuth) {
            kafkaProps.setProperty("security.protocol", "SASL_SSL");
            kafkaProps.setProperty("sasl.mechanism", "AWS_MSK_IAM");
            kafkaProps.setProperty("sasl.jaas.config", "software.amazon.msk.auth.iam.IAMLoginModule required;");
            kafkaProps.setProperty("sasl.client.callback.handler.class", "software.amazon.msk.auth.iam.IAMClientCallbackHandler");
        }
        if (propertyFilePath != null) {
            try (InputStream input = new FileInputStream(propertyFilePath)) {
                kafkaProps.load(input);
            } catch (IOException ex) {
                log.error("Unable to load properties from kafka properties file.");
            }
        }
        return kafkaProps;
    }

    @Override
    public Stream<TrafficStream> consumeTrafficFromSource() {
        try {
            consumer.subscribe(Collections.singleton(topic));
            AtomicInteger trafficStreamsRead = new AtomicInteger();
            Stream<Stream<TrafficStream>> generatedStream = Stream.generate((Supplier) () -> {
                var records = consumer.poll(CONSUMER_POLL_TIMEOUT);
                Stream<TrafficStream> trafficStream = StreamSupport.stream(records.spliterator(), false).map(record -> {
                    try {
                        TrafficStream ts = TrafficStream.parseFrom(record.value());
                        if (log.isTraceEnabled()) {
                            log.trace("Parsed traffic stream #" + (trafficStreamsRead.incrementAndGet()) + ": "+ts);
                        }
                        return ts;
                    } catch (InvalidProtocolBufferException e) {
                        log.error("Unable to parse incoming traffic stream with error: ", e);
                        return null;
                    }
                });
                return trafficStream;
            });
            supplierStream = generatedStream.flatMap(stream -> stream);
            return supplierStream;
        } catch (Exception e) {
            log.error("Unexpected exception: ", e);
        }
        return Stream.empty();
    }

    @Override
    public void close() throws IOException {
        if (supplierStream != null) {
            supplierStream.close();
        }
        consumer.close();
        log.info("This consumer closed successfully.");
    }

}
