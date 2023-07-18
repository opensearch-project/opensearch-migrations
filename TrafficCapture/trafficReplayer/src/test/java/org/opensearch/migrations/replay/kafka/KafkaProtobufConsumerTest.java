package org.opensearch.migrations.replay.kafka;

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
import org.junit.jupiter.api.Timeout;
import org.opensearch.migrations.trafficcapture.protos.ReadObservation;
import org.opensearch.migrations.trafficcapture.protos.TrafficObservation;
import org.opensearch.migrations.trafficcapture.protos.TrafficStream;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Properties;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static java.util.concurrent.TimeUnit.MILLISECONDS;

@Slf4j
class KafkaProtobufConsumerTest {
    public static final int NUM_READ_ITEMS_BOUND = 1000;
    public static final String TEST_TOPIC_NAME = "TEST_TOPIC_NAME";

    @Test
    @Timeout(value = 1000, unit = MILLISECONDS)
    public void testSupplyTrafficFromSource() {
        int numTrafficStreams = 10;
        MockConsumer<String, byte[]> mockConsumer = new MockConsumer<>(OffsetResetStrategy.EARLIEST);
        KafkaProtobufConsumer protobufConsumer = new KafkaProtobufConsumer(mockConsumer, TEST_TOPIC_NAME, new KafkaBehavioralPolicy());
        // Update required beginning offsets
        HashMap<TopicPartition, Long> startOffsets = new HashMap<>();
        TopicPartition tp = new TopicPartition(TEST_TOPIC_NAME, 0);
        startOffsets.put(tp, 0L);
        mockConsumer.updateBeginningOffsets(startOffsets);

        Stream<TrafficStream> trafficStream = protobufConsumer.supplyTrafficFromSource();
        List<Integer> substreamCounts = new ArrayList<>();
        mockConsumer.schedulePollTask(() -> {
            // Required rebalance to add records
            mockConsumer.rebalance(Collections.singletonList(new TopicPartition(TEST_TOPIC_NAME, 0)));
            addRecordsToTopic(numTrafficStreams, 1, mockConsumer, substreamCounts);
            Assertions.assertEquals(substreamCounts.size(), numTrafficStreams);
        });

        AtomicInteger foundStreamsCount = new AtomicInteger(0);
        trafficStream.forEach(stream -> {
            log.trace("Stream has substream count: " + stream.getSubStreamCount());
            Assertions.assertTrue(stream instanceof TrafficStream);
            Assertions.assertEquals(stream.getSubStreamCount(), substreamCounts.get(foundStreamsCount.get()));
            foundStreamsCount.getAndIncrement();
            if (foundStreamsCount.get() == numTrafficStreams) {
                try {
                    protobufConsumer.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        });
    }

    @Test
    @Timeout(value = 1000, unit = MILLISECONDS)
    public void testSupplyTrafficWithUnformattedMessages() {
        int numTrafficStreams = 10;
        MockConsumer<String, byte[]> mockConsumer = new MockConsumer<>(OffsetResetStrategy.EARLIEST);
        KafkaProtobufConsumer protobufConsumer = new KafkaProtobufConsumer(mockConsumer, TEST_TOPIC_NAME, new KafkaBehavioralPolicy());
        // Update required beginning offsets
        HashMap<TopicPartition, Long> startOffsets = new HashMap<>();
        TopicPartition tp = new TopicPartition(TEST_TOPIC_NAME, 0);
        startOffsets.put(tp, 0L);
        mockConsumer.updateBeginningOffsets(startOffsets);

        Stream<TrafficStream> trafficStream = protobufConsumer.supplyTrafficFromSource();
        List<Integer> substreamCounts = new ArrayList<>();
        mockConsumer.schedulePollTask(() -> {
            // Required rebalance to add records
            mockConsumer.rebalance(Collections.singletonList(new TopicPartition(TEST_TOPIC_NAME, 0)));
            // Invalid records to be dropped
            mockConsumer.addRecord(new ConsumerRecord(TEST_TOPIC_NAME, 0, 1, Instant.now().toString(),
                "Invalid Data".getBytes(StandardCharsets.UTF_8)));
            mockConsumer.addRecord(new ConsumerRecord(TEST_TOPIC_NAME, 0, 2, Instant.now().toString(),
                "Invalid Data".getBytes(StandardCharsets.UTF_8)));

            addRecordsToTopic(numTrafficStreams, 3, mockConsumer, substreamCounts);
            Assertions.assertEquals(substreamCounts.size(), numTrafficStreams);
        });

        AtomicInteger foundStreamsCount = new AtomicInteger(0);
        trafficStream.forEach(stream -> {
            log.trace("Stream has substream count: " + stream.getSubStreamCount());
            Assertions.assertTrue(stream instanceof TrafficStream);
            Assertions.assertEquals(stream.getSubStreamCount(), substreamCounts.get(foundStreamsCount.get()));
            foundStreamsCount.getAndIncrement();
            if (foundStreamsCount.get() == numTrafficStreams) {
                try {
                    protobufConsumer.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        });
    }

    @Test
    public void testBuildPropertiesBaseCase() {
        Properties props = KafkaProtobufConsumer.buildKafkaProperties("brokers", "groupId", false, null);
        Assertions.assertEquals(5, props.size());
        Assertions.assertEquals("brokers", props.get(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG));
        Assertions.assertEquals("org.apache.kafka.common.serialization.StringDeserializer", props.get("key.deserializer"));
        Assertions.assertEquals("org.apache.kafka.common.serialization.ByteArrayDeserializer", props.get("value.deserializer"));
        Assertions.assertEquals("groupId", props.get(ConsumerConfig.GROUP_ID_CONFIG));
        Assertions.assertEquals("earliest", props.get(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG));
    }

    @Test
    public void testBuildPropertiesMSKAuthEnabled() {
        Properties props = KafkaProtobufConsumer.buildKafkaProperties("brokers", "groupId", true, null);
        Assertions.assertEquals(9, props.size());
        Assertions.assertEquals("brokers", props.get(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG));
        Assertions.assertEquals("org.apache.kafka.common.serialization.StringDeserializer", props.get("key.deserializer"));
        Assertions.assertEquals("org.apache.kafka.common.serialization.ByteArrayDeserializer", props.get("value.deserializer"));
        Assertions.assertEquals("groupId", props.get(ConsumerConfig.GROUP_ID_CONFIG));
        Assertions.assertEquals("earliest", props.get(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG));
        Assertions.assertEquals("SASL_SSL", props.get("security.protocol"));
        Assertions.assertEquals("AWS_MSK_IAM", props.get("sasl.mechanism"));
        Assertions.assertEquals("software.amazon.msk.auth.iam.IAMLoginModule required;", props.get("sasl.jaas.config"));
        Assertions.assertEquals("software.amazon.msk.auth.iam.IAMClientCallbackHandler", props.get("sasl.client.callback.handler.class"));
    }

    @Test
    public void testBuildPropertiesWithProvidedPropertyFile() {
        File simplePropertiesFile = new File("src/test/resources/kafka/simple-kafka.properties");
        Properties props = KafkaProtobufConsumer.buildKafkaProperties("brokers", "groupId", false, simplePropertiesFile.getPath());
        Assertions.assertEquals(7, props.size());
        Assertions.assertEquals("brokers", props.get(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG));
        Assertions.assertEquals("org.apache.kafka.common.serialization.StringDeserializer", props.get("key.deserializer"));
        Assertions.assertEquals("org.apache.kafka.common.serialization.ByteArrayDeserializer", props.get("value.deserializer"));
        Assertions.assertEquals("KafkaLoggingConsumerGroup", props.get(ConsumerConfig.GROUP_ID_CONFIG));
        Assertions.assertEquals("earliest", props.get(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG));
        Assertions.assertEquals("SASL_SSL2", props.get("security.protocol"));
        Assertions.assertEquals("3555", props.get("max.block.ms"));
    }

    private static TrafficStream makeTrafficStream(Instant t, String payload, int numReads) {
        var fixedTimestamp = Timestamp.newBuilder()
                .setSeconds(t.getEpochSecond())
                .setNanos(t.getNano())
                .build();
        var builder = TrafficStream.newBuilder()
                .setNodeId("testNode")
                .setConnectionId("testStreamId")
                .setNumberOfThisLastChunk(1);
        for (int i = 0; i < numReads; ++i) {
            builder = builder.addSubStream(TrafficObservation.newBuilder().setTs(fixedTimestamp)
                    .setRead(ReadObservation.newBuilder()
                            .setData(ByteString.copyFrom(payload.getBytes(StandardCharsets.UTF_8)))
                            .build())
                    .build());
        }
        return builder.build();
    }

    private static byte[] makeTrafficStreamBytes(Instant t, String payload, int numReads) throws Exception {
        try (var baos = new ByteArrayOutputStream()) {
            makeTrafficStream(Instant.now(), payload, numReads).writeTo(baos);
            return baos.toByteArray();
        }
    }

    private static void addRecordsToTopic(int numTrafficStreams, int offsetStart, MockConsumer<String, byte[]> mockConsumer, List<Integer> substreamCountTracker) {
        Random random = new Random(2);
        Supplier<Integer> integerSupplier = () -> random.nextInt(NUM_READ_ITEMS_BOUND);
        for (int i = 0; i < numTrafficStreams; ++i) {
            var payload = (""+(char)('A'+(char)i)).repeat(10);
            byte[] data = new byte[0];
            try {
                int substreams = integerSupplier.get();
                substreamCountTracker.add(substreams);
                data = makeTrafficStreamBytes(Instant.now(), payload, substreams);
            } catch (Exception e) {
                e.printStackTrace();
            }
            var record = new ConsumerRecord(TEST_TOPIC_NAME, 0, offsetStart+i, Instant.now().toString(), data);
            log.trace("adding record");
            mockConsumer.addRecord(record);
        }
    }
}