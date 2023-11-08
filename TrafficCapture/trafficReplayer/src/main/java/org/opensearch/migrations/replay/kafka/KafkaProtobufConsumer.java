package org.opensearch.migrations.replay.kafka;

import com.google.protobuf.InvalidProtocolBufferException;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.opensearch.migrations.coreutils.MetricsAttributeKey;
import org.opensearch.migrations.coreutils.MetricsEvent;
import org.opensearch.migrations.coreutils.MetricsLogger;
import org.opensearch.migrations.replay.datatypes.ITrafficStreamKey;
import org.opensearch.migrations.replay.traffic.source.ISimpleTrafficCaptureSource;
import org.opensearch.migrations.replay.traffic.source.ITrafficStreamWithKey;
import org.opensearch.migrations.replay.traffic.source.TrafficStreamWithEmbeddedKey;
import org.opensearch.migrations.trafficcapture.protos.TrafficStream;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

@Slf4j
public class KafkaProtobufConsumer implements ISimpleTrafficCaptureSource {

    public static final Duration CONSUMER_POLL_TIMEOUT = Duration.ofSeconds(1);
    private final Consumer<String, byte[]> kafkaConsumer;
    private final String topic;
    private final KafkaBehavioralPolicy behavioralPolicy;
    private final AtomicInteger trafficStreamsRead;
    private static final MetricsLogger metricsLogger = new MetricsLogger("KafkaProtobufConsumer");


    public KafkaProtobufConsumer(Consumer<String, byte[]> kafkaConsumer, String topic) {
        this(kafkaConsumer, topic, new KafkaBehavioralPolicy());
    }

    public KafkaProtobufConsumer(Consumer<String, byte[]> kafkaConsumer, @NonNull String topic,
                                 KafkaBehavioralPolicy behavioralPolicy) {
        this.kafkaConsumer = kafkaConsumer;
        this.topic = topic;
        this.behavioralPolicy = behavioralPolicy;
        kafkaConsumer.subscribe(Collections.singleton(topic));
        trafficStreamsRead = new AtomicInteger();
    }

    public static KafkaProtobufConsumer buildKafkaConsumer(@NonNull String brokers,
                                                           @NonNull String topic,
                                                           @NonNull String groupId,
                                                           boolean enableMSKAuth,
                                                           String propertyFilePath,
                                                           KafkaBehavioralPolicy behavioralPolicy) throws IOException {
        var kafkaProps = buildKafkaProperties(brokers, groupId, enableMSKAuth, propertyFilePath);
        return new KafkaProtobufConsumer(new KafkaConsumer<>(kafkaProps), topic, behavioralPolicy);
    }

    public static Properties buildKafkaProperties(@NonNull String brokers,
                                                  @NonNull String groupId,
                                                  boolean enableMSKAuth,
                                                  String propertyFilePath) throws IOException {
        var kafkaProps = new Properties();
        kafkaProps.setProperty("key.deserializer", "org.apache.kafka.common.serialization.StringDeserializer");
        kafkaProps.setProperty("value.deserializer", "org.apache.kafka.common.serialization.ByteArrayDeserializer");
        kafkaProps.setProperty(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
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
    public CompletableFuture<List<ITrafficStreamWithKey>> readNextTrafficStreamChunk() {
        return CompletableFuture.supplyAsync(this::readNextTrafficStreamSynchronously);
    }

    public List<ITrafficStreamWithKey> readNextTrafficStreamSynchronously() {
        try {
            ConsumerRecords<String, byte[]> records;
            records = safePollWithSwallowedRuntimeExceptions();
            Stream<ITrafficStreamWithKey> trafficStream = StreamSupport.stream(records.spliterator(), false)
                    .map(kafkaRecord -> {
                        try {
                            TrafficStream ts = TrafficStream.parseFrom(kafkaRecord.value());
                            // Ensure we increment trafficStreamsRead even at a higher log level
                            log.trace("Parsed traffic stream #{}: {}", trafficStreamsRead.incrementAndGet(), ts);
                            metricsLogger.atSuccess(MetricsEvent.PARSED_TRAFFIC_STREAM_FROM_KAFKA)
                                    .setAttribute(MetricsAttributeKey.CONNECTION_ID, ts.getConnectionId())
                                    .setAttribute(MetricsAttributeKey.TOPIC_NAME, this.topic)
                                    .setAttribute(MetricsAttributeKey.SIZE_IN_BYTES, ts.getSerializedSize()).emit();
                            return (ITrafficStreamWithKey) new TrafficStreamWithEmbeddedKey(ts);
                        } catch (InvalidProtocolBufferException e) {
                            RuntimeException recordError = behavioralPolicy.onInvalidKafkaRecord(kafkaRecord, e);
                            metricsLogger.atError(MetricsEvent.PARSING_TRAFFIC_STREAM_FROM_KAFKA_FAILED, recordError)
                                    .setAttribute(MetricsAttributeKey.TOPIC_NAME, this.topic).emit();
                            if (recordError != null) {
                                throw recordError;
                            }
                            return null;
                        }
            }).filter(Objects::nonNull);
            // This simple commit should be removed when logic is in place for using commitTrafficStream()
            kafkaConsumer.commitSync();
            return trafficStream.collect(Collectors.<ITrafficStreamWithKey>toList());
        } catch (Exception e) {
            log.error("Terminating Kafka traffic stream");
            throw e;
        }
    }

    private ConsumerRecords<String, byte[]> safePollWithSwallowedRuntimeExceptions() {
        ConsumerRecords<String, byte[]> records;
        try {
            records = kafkaConsumer.poll(CONSUMER_POLL_TIMEOUT);
            log.info("Kafka consumer poll has fetched {} records", records.count());
        } catch (RuntimeException e) {
            log.atWarn().setCause(e).setMessage("Unable to poll the topic: {} with our Kafka consumer. " +
                    "Swallowing and awaiting next metadata refresh to try again.").addArgument(topic).log();
            records = new ConsumerRecords<>(Collections.emptyMap());
        }
        return records;
    }

    @Override
    public void commitTrafficStream(ITrafficStreamKey trafficStreamKey) {
        kafkaConsumer.commitSync(Map.of());
    }

    @Override
    public void close() throws IOException {
        kafkaConsumer.close();
        log.info("Kafka consumer closed successfully.");
    }
}
