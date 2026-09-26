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

// REBUILD-LIMBO(G10) -- inherited bodies remain marked and recoverable; the live G9
// replacement follows the marked regions. Javadoc stays outside the regions.
// Resolve each region to dead, keep, or refactor deliberately. If a member is deleted, delete its
// javadoc with it. See AGENTS.md section 8a.
// Test carried byte-identical. Unresolved: InstrumentationTest . Per AGENTS.md section 4 an inherited test may stay broken while the architectures are partly connected; this one is restored by the milestone that rebuilds its subject, keeping its assertions conceptually stable while changing the mechanics.
// Un-mark a member by deleting the delimiter lines around it and splitting this region; the
// code between them is verbatim, so blame survives. Read this before writing anything new

// REBUILD-LIMBO-START(G10)
/*

import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import org.opensearch.migrations.replay.traffic.source.ITrafficStreamWithKey;
import org.opensearch.migrations.testutils.SharedDockerImageNames;
import org.opensearch.migrations.tracing.InstrumentationTest;

import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.ConfluentKafkaContainer;

@Slf4j
@Testcontainers(disabledWithoutDocker = true)
@Tag("isolatedTest")
public class KafkaTrafficCaptureSourceLongTermTest extends InstrumentationTest {

    public static final int TEST_RECORD_COUNT = 10;
    public static final String TEST_GROUP_CONSUMER_ID = "TEST_GROUP_CONSUMER_ID";
    public static final int PRODUCER_SLEEP_INTERVAL_MS = 100;

    @Container
    // see
    // https://docs.confluent.io/platform/current/installation/versions-interoperability.html#cp-and-apache-kafka-compatibility
    private final ConfluentKafkaContainer embeddedKafkaBroker = new ConfluentKafkaContainer(SharedDockerImageNames.KAFKA);

    @Test
    @Tag("isolatedTest")
    public void testTrafficCaptureSource() throws Exception {
        String testTopicName = "TEST_TOPIC";

        var kafkaConsumerProps = KafkaTrafficCaptureSource.buildKafkaProperties(
            embeddedKafkaBroker.getBootstrapServers(),
            TEST_GROUP_CONSUMER_ID,
            "none",
            null,
            null,
            null
        );
        final long MAX_POLL_MS = 10000;
        kafkaConsumerProps.setProperty(KafkaTrafficCaptureSource.MAX_POLL_INTERVAL_KEY, MAX_POLL_MS + "");
        var kafkaConsumer = new KafkaConsumer<String, byte[]>(kafkaConsumerProps);
        var kafkaTrafficCaptureSource = new KafkaTrafficCaptureSource(
            rootContext,
            kafkaConsumer,
            testTopicName,
            Duration.ofMillis(MAX_POLL_MS)
        );

        var kafkaProducer = KafkaTestUtils.buildKafkaProducer(embeddedKafkaBroker.getBootstrapServers());
        var sendCompleteCount = new AtomicInteger(0);
        var scheduledIterationsCount = new AtomicInteger(0);
        var executor = Executors.newSingleThreadScheduledExecutor();
        executor.scheduleAtFixedRate(() -> {
            var i = scheduledIterationsCount.getAndIncrement();
            if (i >= TEST_RECORD_COUNT) {
                executor.shutdown();
            } else {
                KafkaTestUtils.produceKafkaRecord(testTopicName, kafkaProducer, i, sendCompleteCount);
            }
        }, 0, PRODUCER_SLEEP_INTERVAL_MS, TimeUnit.MILLISECONDS);

        for (int i = 0; i < TEST_RECORD_COUNT;) {
            var nextChunkFuture = kafkaTrafficCaptureSource.readNextTrafficStreamChunk(
                rootContext::createReadChunkContext
            );
            var recordsList = nextChunkFuture.get(
                (2 * TEST_RECORD_COUNT) * PRODUCER_SLEEP_INTERVAL_MS,
                TimeUnit.MILLISECONDS
            );
            for (int j = 0; j < recordsList.size(); ++j) {
                var trafficRecord = (ITrafficStreamWithKey) recordsList.get(j);
                Assertions.assertEquals(
                    KafkaTestUtils.getConnectionId(i + j),
                    trafficRecord.getStream().getConnectionId()
                );
            }
            log.info("Got " + recordsList.size() + " records and already had " + i);
            i += recordsList.size();
        }

        Assertions.assertEquals(TEST_RECORD_COUNT, sendCompleteCount.get());
        Assertions.assertThrows(TimeoutException.class, () -> {
            var rogueChunk = kafkaTrafficCaptureSource.readNextTrafficStreamChunk(rootContext::createReadChunkContext)
                .get(1, TimeUnit.SECONDS);
            if (rogueChunk.isEmpty()) {
                // TimeoutExceptions cannot be thrown by the supplier of the CompletableFuture today, BUT we
                // could long-poll on the broker for longer than the timeout value supplied in the get() call above
                throw new TimeoutException(
                    "read actually returned 0 items, but transforming this to a "
                        + "TimeoutException because either result would be valid."
                );
            }
            log.error("rogue chunk: " + rogueChunk);
        });
    }

}

*/
// REBUILD-LIMBO-END(G10)

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
