package org.opensearch.migrations.replay.kafka;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import org.opensearch.migrations.replay.kafkasource.KafkaConsumerSourcePort;
import org.opensearch.migrations.testutils.SharedDockerImageNames;
import org.opensearch.migrations.trafficcapture.protos.CaptureRecord;
import org.opensearch.migrations.trafficcapture.protos.TrafficStream;

import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.ConfluentKafkaContainer;


@Testcontainers(disabledWithoutDocker = true)
@Tag("isolatedTest")
public class KafkaTrafficCaptureSourceLongTermTest {
    static final int TEST_RECORD_COUNT = 10;

    @Container
    private final ConfluentKafkaContainer kafka =
        new ConfluentKafkaContainer(SharedDockerImageNames.KAFKA);

    @Test
    void currentKafkaPortReadsEveryRecordInOrderWithBrokerAppendTime()
        throws Exception {
        var topic = "g9-long-term-" + System.nanoTime();
        createLogAppendTimeTopic(kafka.getBootstrapServers(), topic);
        try (var producer = KafkaTestUtils.buildKafkaProducer(
            kafka.getBootstrapServers()
        )) {
            for (var index = 0; index < TEST_RECORD_COUNT; index++) {
                producer.send(new ProducerRecord<>(
                    topic,
                    "key-" + index,
                    capture(index).toByteArray()
                )).get(10, TimeUnit.SECONDS);
            }
        }

        var partition = new TopicPartition(topic, 0);
        var consumer = new KafkaConsumer<String, byte[]>(
            consumerProperties(kafka.getBootstrapServers(), "g9-long-term")
        );
        var port = new KafkaConsumerSourcePort(consumer, Duration.ofSeconds(2));
        try {
            consumer.assign(Set.of(partition));
            consumer.seekToBeginning(Set.of(partition));
            var records = new ArrayList<
                org.opensearch.migrations.replay.kafkasource.PolledKafkaRecord
            >();
            for (var attempt = 0;
                attempt < 10 && records.size() < TEST_RECORD_COUNT;
                attempt++) {
                records.addAll(port.poll().getOrDefault(partition, List.of()));
            }

            Assertions.assertEquals(TEST_RECORD_COUNT, records.size());
            for (var index = 0; index < records.size(); index++) {
                var record = records.get(index);
                Assertions.assertEquals(index, record.offset());
                Assertions.assertTrue(record.logAppendTimeMillis() > 0);
                Assertions.assertTrue(record.serializedSizeBytes() > 0);
                Assertions.assertEquals(
                    "connection-" + index,
                    record.envelope().getTrafficStream().getConnectionId()
                );
            }
        } finally {
            port.close();
        }
    }

    static Properties consumerProperties(String bootstrapServers, String group) {
        var properties = new Properties();
        properties.setProperty(
            ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG,
            bootstrapServers
        );
        properties.setProperty(
            ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG,
            "org.apache.kafka.common.serialization.StringDeserializer"
        );
        properties.setProperty(
            ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG,
            "org.apache.kafka.common.serialization.ByteArrayDeserializer"
        );
        properties.setProperty(ConsumerConfig.GROUP_ID_CONFIG, group);
        properties.setProperty(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
        properties.setProperty(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        properties.setProperty(ConsumerConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, "10000");
        properties.setProperty(ConsumerConfig.REQUEST_TIMEOUT_MS_CONFIG, "5000");
        return properties;
    }

    static void createLogAppendTimeTopic(String bootstrapServers, String topic)
        throws Exception {
        try (var admin = Admin.create(Map.of(
            AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG,
            bootstrapServers
        ))) {
            admin.createTopics(List.of(
                new NewTopic(topic, 1, (short) 1).configs(
                    Map.of("message.timestamp.type", "LogAppendTime")
                )
            )).all().get(30, TimeUnit.SECONDS);
        }
    }

    static CaptureRecord capture(int index) {
        return CaptureRecord.newBuilder()
            .setTrafficStream(
                TrafficStream.newBuilder()
                    .setNodeId("writer")
                    .setConnectionId("connection-" + index)
                    .setNumber(0)
            )
            .build();
    }
}
