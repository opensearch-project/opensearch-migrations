package org.opensearch.migrations.replay.kafka;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Properties;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import org.opensearch.migrations.replay.util.TrafficChannelKeyFormatter;
import org.opensearch.migrations.trafficcapture.protos.ReadObservation;
import org.opensearch.migrations.trafficcapture.protos.TrafficObservation;
import org.opensearch.migrations.trafficcapture.protos.TrafficStream;

import com.google.protobuf.ByteString;
import com.google.protobuf.Timestamp;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.jetbrains.annotations.NotNull;

@Slf4j
public class KafkaTestUtils {

    public static final String TEST_GROUP_PRODUCER_ID = "TEST_GROUP_PRODUCER_ID";
    private static final String FAKE_READ_PACKET_DATA = "Fake pa";
    public static final String TEST_NODE_ID = "TestNodeId";
    public static final String TEST_TRAFFIC_STREAM_ID_STRING = "TEST_TRAFFIC_STREAM";

    static Producer<String, byte[]> buildKafkaProducer(String bootstrapServers) {
        var kafkaProps = new Properties();
        kafkaProps.put(
            ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG,
            "org.apache.kafka.common.serialization.StringSerializer"
        );
        kafkaProps.put(
            ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG,
            "org.apache.kafka.common.serialization.ByteArraySerializer"
        );
        // Property details:
        // https://docs.confluent.io/platform/current/installation/configuration/producer-configs.html#delivery-timeout-ms
        kafkaProps.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 10000);
        kafkaProps.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, 5000);
        kafkaProps.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, 10000);
        kafkaProps.put(ProducerConfig.CLIENT_ID_CONFIG, TEST_GROUP_PRODUCER_ID);
        kafkaProps.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        try {
            return new KafkaProducer(kafkaProps);
        } catch (Exception e) {
            log.atError().setCause(e).log();
            System.exit(1);
            throw e;
        }
    }

    static String getConnectionId(int i) {
        return TEST_TRAFFIC_STREAM_ID_STRING + "_" + i;
    }

    static TrafficStream makeTestTrafficStreamWithFixedTime(Instant t, int i) {
        var timestamp = Timestamp.newBuilder().setSeconds(t.getEpochSecond()).setNanos(t.getNano()).build();
        var tsb = TrafficStream.newBuilder().setNumber(i);
        // TODO - add something for setNumberOfThisLastChunk. There's no point in doing that now though
        // because the code doesn't make any distinction between the very last one and the previous ones
        return tsb.setNodeId(TEST_NODE_ID)
            .setConnectionId(getConnectionId(i))
            .addSubStream(
                TrafficObservation.newBuilder()
                    .setTs(timestamp)
                    .setRead(
                        ReadObservation.newBuilder()
                            .setData(ByteString.copyFrom(FAKE_READ_PACKET_DATA.getBytes(StandardCharsets.UTF_8)))
                            .build()
                    )
                    .build()
            )
            .build();

    }

    @SneakyThrows
    public static void writeTrafficStreamRecord(
        Producer<String, byte[]> kafkaProducer,
        TrafficStream trafficStream,
        String TEST_TOPIC_NAME,
        String recordId
    ) {
        while (true) {
            try {
                var record = new ProducerRecord(TEST_TOPIC_NAME, recordId, trafficStream.toByteArray());
                var tsKeyStr = TrafficChannelKeyFormatter.format(
                    trafficStream.getNodeId(),
                    trafficStream.getConnectionId()
                );
                log.info("sending record with trafficStream=" + tsKeyStr);
                var sendFuture = kafkaProducer.send(record, (metadata, exception) -> {
                    log.atInfo().setCause(exception)
                        .setMessage("completed send of TrafficStream with key={} metadata={}")
                        .addArgument(tsKeyStr)
                        .addArgument(metadata)
                        .log();
                });
                var recordMetadata = sendFuture.get();
                log.info("finished publishing record... metadata=" + recordMetadata);
                break;
            } catch (Exception e) {
                log.error("Caught exception while trying to publish a record to Kafka.  Blindly retrying.");
                continue;
            }
        }
    }

    static Future produceKafkaRecord(
        String testTopicName,
        Producer<String, byte[]> kafkaProducer,
        int i,
        AtomicInteger sendCompleteCount
    ) {
        final var timestamp = Instant.now().plus(Duration.ofDays(i));
        var trafficStream = KafkaTestUtils.makeTestTrafficStreamWithFixedTime(timestamp, i);
        var record = new ProducerRecord(testTopicName, makeKey(i), trafficStream.toByteArray());
        return kafkaProducer.send(record, (metadata, exception) -> { sendCompleteCount.incrementAndGet(); });
    }

    @NotNull
    private static String makeKey(int i) {
        return "KEY_" + i;
    }
}
