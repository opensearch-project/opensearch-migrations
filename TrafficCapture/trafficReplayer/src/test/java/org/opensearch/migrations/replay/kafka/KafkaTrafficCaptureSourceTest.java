package org.opensearch.migrations.replay.kafka;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Properties;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import org.opensearch.migrations.replay.tracing.ChannelContextManager;
import org.opensearch.migrations.replay.tracing.ReplayContexts;
import org.opensearch.migrations.replay.traffic.source.ITrafficStreamWithKey;
import org.opensearch.migrations.tracing.InstrumentationTest;
import org.opensearch.migrations.trafficcapture.protos.ReadObservation;
import org.opensearch.migrations.trafficcapture.protos.TrafficObservation;
import org.opensearch.migrations.trafficcapture.protos.TrafficStream;

import com.google.protobuf.ByteString;
import com.google.protobuf.Timestamp;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.MockConsumer;
import org.apache.kafka.clients.consumer.OffsetResetStrategy;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

@Slf4j
class KafkaTrafficCaptureSourceTest extends InstrumentationTest {
    public static final int NUM_READ_ITEMS_BOUND = 1000;
    public static final String TEST_TOPIC_NAME = "TEST_TOPIC_NAME";

    private static final Duration TEST_TIMEOUT = Duration.ofSeconds(30);

    @Test
    public void testRecordToString() {
        var ts = TrafficStream.newBuilder().setConnectionId("c").setNodeId("n").setNumber(7).build();
        var tsk = new TrafficStreamKeyWithKafkaRecordId(
            k -> new ReplayContexts.KafkaRecordContext(
                rootContext,
                new ChannelContextManager(rootContext).retainOrCreateContext(k),
                "",
                1
            ),
            ts,
            1,
            2,
            123
        );
        Assertions.assertEquals("n.c.7|partition=2|offset=123", tsk.toString());
    }

    @Test
    public void testSupplyTrafficFromSource() throws Exception {
        int numTrafficStreams = 10;
        MockConsumer<String, byte[]> mockConsumer = new MockConsumer<>(OffsetResetStrategy.EARLIEST);
        try (
            var protobufConsumer = new KafkaTrafficCaptureSource(
                rootContext,
                mockConsumer,
                TEST_TOPIC_NAME,
                Duration.ofHours(1)
            )
        ) {
            initializeMockConsumerTopic(mockConsumer);

            List<Integer> substreamCounts = new ArrayList<>();
            // On a single poll() add records to the topic
            mockConsumer.schedulePollTask(() -> {
                // Required rebalance to add records to topic
                mockConsumer.rebalance(Collections.singletonList(new TopicPartition(TEST_TOPIC_NAME, 0)));
                addGeneratedTrafficStreamsToTopic(numTrafficStreams, 1, mockConsumer, substreamCounts);
                Assertions.assertEquals(substreamCounts.size(), numTrafficStreams);
            });

            AtomicInteger foundStreamsCount = new AtomicInteger(0);
            // This assertion will fail the test case if not completed within its duration, as would be the case if
            // there
            // were missing traffic streams. Its task currently is limited to the numTrafficStreams where it will stop
            // the stream

            var tsCount = new AtomicInteger();
            Assertions.assertTimeoutPreemptively(TEST_TIMEOUT, () -> {
                while (tsCount.get() < numTrafficStreams) {
                    protobufConsumer.readNextTrafficStreamChunk(rootContext::createReadChunkContext)
                        .get()
                        .stream()
                        .forEach(streamWithKey -> {
                            tsCount.incrementAndGet();
                            log.trace("Stream has substream count: " + streamWithKey.getStream().getSubStreamCount());
                            Assertions.assertInstanceOf(ITrafficStreamWithKey.class, streamWithKey);
                            Assertions.assertEquals(
                                streamWithKey.getStream().getSubStreamCount(),
                                substreamCounts.get(foundStreamsCount.getAndIncrement())
                            );
                        });
                }
            });
            Assertions.assertEquals(foundStreamsCount.get(), numTrafficStreams);
        }
    }

    @Test
    public void testSupplyTrafficWithUnformattedMessages() throws Exception {
        int numTrafficStreams = 10;
        MockConsumer<String, byte[]> mockConsumer = new MockConsumer<>(OffsetResetStrategy.EARLIEST);
        try (
            var protobufConsumer = new KafkaTrafficCaptureSource(
                rootContext,
                mockConsumer,
                TEST_TOPIC_NAME,
                Duration.ofHours(1)
            )
        ) {
            initializeMockConsumerTopic(mockConsumer);

            List<Integer> substreamCounts = new ArrayList<>();
            // On a single poll() add records to the topic
            mockConsumer.schedulePollTask(() -> {
                // Required rebalance to add records
                mockConsumer.rebalance(Collections.singletonList(new TopicPartition(TEST_TOPIC_NAME, 0)));

                // Add invalid records that can't be parsed and should be dropped
                int partitionOffset = 1;
                for (; partitionOffset < 3; partitionOffset++) {
                    mockConsumer.addRecord(
                        new ConsumerRecord(
                            TEST_TOPIC_NAME,
                            0,
                            partitionOffset,
                            Instant.now().toString(),
                            "Invalid Data".getBytes(StandardCharsets.UTF_8)
                        )
                    );
                }

                // Add valid records
                addGeneratedTrafficStreamsToTopic(numTrafficStreams, partitionOffset, mockConsumer, substreamCounts);
                Assertions.assertEquals(substreamCounts.size(), numTrafficStreams);
            });

            AtomicInteger foundStreamsCount = new AtomicInteger(0);
            // This assertion will fail the test case if not completed within its duration, as would be the case if
            // there
            // were missing traffic streams. Its task currently is limited to the numTrafficStreams where it will stop
            // the stream

            var tsCount = new AtomicInteger();
            Assertions.assertTimeoutPreemptively(TEST_TIMEOUT, () -> {
                while (tsCount.get() < numTrafficStreams) {
                    protobufConsumer.readNextTrafficStreamChunk(rootContext::createReadChunkContext)
                        .get()
                        .stream()
                        .forEach(streamWithKey -> {
                            tsCount.incrementAndGet();
                            log.trace("Stream has substream count: " + streamWithKey.getStream().getSubStreamCount());
                            Assertions.assertInstanceOf(ITrafficStreamWithKey.class, streamWithKey);
                            Assertions.assertEquals(
                                streamWithKey.getStream().getSubStreamCount(),
                                substreamCounts.get(foundStreamsCount.getAndIncrement())
                            );
                        });
                }
            });

            Assertions.assertEquals(foundStreamsCount.get(), numTrafficStreams);
        }
    }

    @Test
    public void testBuildPropertiesBaseCase() throws IOException {
        Properties props = KafkaTrafficCaptureSource.buildKafkaProperties("brokers", "groupId", false, null);
        Assertions.assertEquals("brokers", props.get(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG));
        Assertions.assertEquals(
            "org.apache.kafka.common.serialization.StringDeserializer",
            props.get("key.deserializer")
        );
        Assertions.assertEquals(
            "org.apache.kafka.common.serialization.ByteArrayDeserializer",
            props.get("value.deserializer")
        );
        Assertions.assertEquals("groupId", props.get(ConsumerConfig.GROUP_ID_CONFIG));
        Assertions.assertEquals("earliest", props.get(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG));
    }

    @Test
    public void testBuildPropertiesMSKAuthEnabled() throws IOException {
        Properties props = KafkaTrafficCaptureSource.buildKafkaProperties("brokers", "groupId", true, null);
        Assertions.assertEquals("brokers", props.get(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG));
        Assertions.assertEquals(
            "org.apache.kafka.common.serialization.StringDeserializer",
            props.get("key.deserializer")
        );
        Assertions.assertEquals(
            "org.apache.kafka.common.serialization.ByteArrayDeserializer",
            props.get("value.deserializer")
        );
        Assertions.assertEquals("groupId", props.get(ConsumerConfig.GROUP_ID_CONFIG));
        Assertions.assertEquals("earliest", props.get(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG));
        Assertions.assertEquals("SASL_SSL", props.get("security.protocol"));
        Assertions.assertEquals("AWS_MSK_IAM", props.get("sasl.mechanism"));
        Assertions.assertEquals("software.amazon.msk.auth.iam.IAMLoginModule required;", props.get("sasl.jaas.config"));
        Assertions.assertEquals(
            "software.amazon.msk.auth.iam.IAMClientCallbackHandler",
            props.get("sasl.client.callback.handler.class")
        );
    }

    @Test
    public void testBuildPropertiesWithProvidedPropertyFile() throws IOException {
        File simplePropertiesFile = new File("src/test/resources/kafka/simple-kafka.properties");
        Properties props = KafkaTrafficCaptureSource.buildKafkaProperties(
            "brokers",
            "groupId",
            true,
            simplePropertiesFile.getPath()
        );
        Assertions.assertEquals("brokers", props.get(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG));
        Assertions.assertEquals(
            "org.apache.kafka.common.serialization.StringDeserializer",
            props.get("key.deserializer")
        );
        Assertions.assertEquals(
            "org.apache.kafka.common.serialization.ByteArrayDeserializer",
            props.get("value.deserializer")
        );
        // Property file will not overwrite another specified command argument
        Assertions.assertEquals("groupId", props.get(ConsumerConfig.GROUP_ID_CONFIG));
        Assertions.assertEquals("earliest", props.get(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG));
        // Property file will not overwrite another specified command argument
        Assertions.assertEquals("SASL_SSL", props.get("security.protocol"));
        Assertions.assertEquals("AWS_MSK_IAM", props.get("sasl.mechanism"));
        Assertions.assertEquals("software.amazon.msk.auth.iam.IAMLoginModule required;", props.get("sasl.jaas.config"));
        Assertions.assertEquals(
            "software.amazon.msk.auth.iam.IAMClientCallbackHandler",
            props.get("sasl.client.callback.handler.class")
        );
        Assertions.assertEquals("3555", props.get("max.block.ms"));
    }

    private static TrafficStream makeTrafficStream(Instant t, String payload, int numReads) {
        var fixedTimestamp = Timestamp.newBuilder().setSeconds(t.getEpochSecond()).setNanos(t.getNano()).build();
        var builder = TrafficStream.newBuilder()
            .setNodeId("testNode")
            .setConnectionId("testStreamId")
            .setNumberOfThisLastChunk(1);
        for (int i = 0; i < numReads; ++i) {
            builder = builder.addSubStream(
                TrafficObservation.newBuilder()
                    .setTs(fixedTimestamp)
                    .setRead(
                        ReadObservation.newBuilder()
                            .setData(ByteString.copyFrom(payload.getBytes(StandardCharsets.UTF_8)))
                            .build()
                    )
                    .build()
            );
        }
        return builder.build();
    }

    private static byte[] makeTrafficStreamBytes(Instant t, String payload, int numReads) throws Exception {
        try (var baos = new ByteArrayOutputStream()) {
            makeTrafficStream(Instant.now(), payload, numReads).writeTo(baos);
            return baos.toByteArray();
        }
    }

    /**
     * This helper function will generate N (or numTrafficStreams) traffic streams and place each traffic stream into
     * one Consumer Record. The Consumer Records will then be added to the provided mockConsumer and simulate records
     * being added to the relevant Kafka topic
     *
     * @param numTrafficStreams
     * @param offsetStart
     * @param mockConsumer
     * @param substreamCountTracker
     */
    private static void addGeneratedTrafficStreamsToTopic(
        int numTrafficStreams,
        int offsetStart,
        MockConsumer<String, byte[]> mockConsumer,
        List<Integer> substreamCountTracker
    ) {
        Random random = new Random(2);
        Supplier<Integer> integerSupplier = () -> random.nextInt(NUM_READ_ITEMS_BOUND);
        for (int i = 0; i < numTrafficStreams; ++i) {
            var payload = ("" + (char) ('A' + (char) i)).repeat(10);
            byte[] data = new byte[0];
            try {
                int substreams = integerSupplier.get();
                substreamCountTracker.add(substreams);
                data = makeTrafficStreamBytes(Instant.now(), payload, substreams);
            } catch (Exception e) {
                e.printStackTrace();
            }
            var record = new ConsumerRecord(TEST_TOPIC_NAME, 0, offsetStart + i, Instant.now().toString(), data);
            log.trace("adding record");
            mockConsumer.addRecord(record);
        }
    }

    // Required initialization for working with Mock Consumer
    private void initializeMockConsumerTopic(MockConsumer<String, byte[]> mockConsumer) {
        HashMap<TopicPartition, Long> startOffsets = new HashMap<>();
        TopicPartition tp = new TopicPartition(TEST_TOPIC_NAME, 0);
        startOffsets.put(tp, 0L);
        mockConsumer.updateBeginningOffsets(startOffsets);
    }

    // -------------------------------------------------------------------------
    // Phase 3: Active connection tracking
    // -------------------------------------------------------------------------

    /**
     * After consuming records for N connections on partition 0, all N connection IDs
     * must appear in partitionToActiveConnections.get(0).
     * Before fix: partitionToActiveConnections is never populated.
     */
    @Test
    public void activeConnectionsTrackedPerPartition() throws Exception {
        MockConsumer<String, byte[]> mockConsumer = new MockConsumer<>(OffsetResetStrategy.EARLIEST);
        try (var source = new KafkaTrafficCaptureSource(rootContext, mockConsumer, TEST_TOPIC_NAME, Duration.ofHours(1))) {
            initializeMockConsumerTopic(mockConsumer);
            mockConsumer.schedulePollTask(() -> {
                mockConsumer.rebalance(Collections.singletonList(new TopicPartition(TEST_TOPIC_NAME, 0)));
                // 3 distinct connections
                for (int i = 0; i < 3; i++) {
                    var ts = TrafficStream.newBuilder()
                        .setNodeId("node1").setConnectionId("conn" + i).setNumberOfThisLastChunk(0)
                        .addSubStream(TrafficObservation.newBuilder()
                            .setTs(com.google.protobuf.Timestamp.newBuilder().setSeconds(1).build())
                            .setRead(ReadObservation.newBuilder()
                                .setData(com.google.protobuf.ByteString.copyFromUtf8("GET / HTTP/1.1\r\n\r\n"))
                                .build())
                            .build())
                        .build();
                    try (var baos = new ByteArrayOutputStream()) {
                        ts.writeTo(baos);
                        mockConsumer.addRecord(new ConsumerRecord<>(TEST_TOPIC_NAME, 0, i,
                            Instant.now().toString(), baos.toByteArray()));
                    } catch (Exception e) { throw new RuntimeException(e); }
                }
            });

            // Consume the records
            source.readNextTrafficStreamChunk(rootContext::createReadChunkContext).get();

            var active = source.partitionToActiveConnections.get(0);
            Assertions.assertNotNull(active, "partitionToActiveConnections must have an entry for partition 0");
            Assertions.assertEquals(3, active.size(),
                "all 3 connections must be tracked in partitionToActiveConnections");
        }
    }
}
