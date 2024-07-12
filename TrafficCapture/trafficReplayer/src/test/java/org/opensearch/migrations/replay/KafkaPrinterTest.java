package org.opensearch.migrations.replay;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.Stream;

import com.google.protobuf.ByteString;
import com.google.protobuf.CodedOutputStream;
import com.google.protobuf.Timestamp;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.MockConsumer;
import org.apache.kafka.clients.consumer.OffsetResetStrategy;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import org.opensearch.migrations.trafficcapture.protos.ReadObservation;
import org.opensearch.migrations.trafficcapture.protos.TrafficObservation;
import org.opensearch.migrations.trafficcapture.protos.TrafficStream;

import lombok.extern.slf4j.Slf4j;

@Slf4j
class KafkaPrinterTest {
    final static String FAKE_READ_PACKET_DATA = "abcdefgh\n";
    public static final int NUM_READ_ITEMS_BOUND = 1000;
    public static final String TEST_TOPIC_NAME = "TEST_TOPIC_NAME";
    public static final int NUM_PROTOBUF_OBJECTS = 10;

    private static TrafficStream makeTrafficStream(Instant t, String payload, int numReads) {
        var fixedTimestamp = Timestamp.newBuilder().setSeconds(t.getEpochSecond()).setNanos(t.getNano()).build();
        var builder = TrafficStream.newBuilder()
            .setConnectionId("testConnectionId")
            .setNodeId("testNodeId")
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
            makeTrafficStream(Instant.now(), payload, 10).writeTo(baos);
            return baos.toByteArray();
        }
    }

    private static class CountingConsumer implements Consumer<Stream<ConsumerRecord<String, byte[]>>> {
        final java.util.function.Consumer<Stream<ConsumerRecord<String, byte[]>>> underlyingConsumer;
        int count;

        public CountingConsumer(Consumer<Stream<ConsumerRecord<String, byte[]>>> underlyingConsumer) {
            this.underlyingConsumer = underlyingConsumer;
        }

        @Override
        public void accept(Stream<ConsumerRecord<String, byte[]>> stream) {
            underlyingConsumer.accept(stream.map(msg -> {
                log.trace("read msg");
                count++;
                return msg;
            }));
        }
    }

    @Test
    public void testStreamFormatting() throws Exception {
        Random random = new Random(2);
        var numTrafficStreams = 10;
        var kafkaConsumer = makeKafkaConsumer(Map.of(0, numTrafficStreams), () -> random.nextInt(NUM_READ_ITEMS_BOUND));
        var emptyPartitionLimits = new HashMap<TopicPartition, KafkaPrinter.PartitionTracker>();
        var delimitedOutputBytes = getOutputFromConsumer(kafkaConsumer, numTrafficStreams, emptyPartitionLimits);
        validateNumberOfTrafficStreamsEmitted(NUM_PROTOBUF_OBJECTS, delimitedOutputBytes);
    }

    @Test
    public void testSinglePartitionLimiting() throws Exception {
        Random random = new Random(3);
        // Use larger number of streams than recordLimit cutoff
        var numTrafficStreams = 20;
        var recordLimit = 10;
        var kafkaConsumer = makeKafkaConsumer(Map.of(0, numTrafficStreams), () -> random.nextInt(NUM_READ_ITEMS_BOUND));
        var partitionLimits = new HashMap<TopicPartition, KafkaPrinter.PartitionTracker>();
        TopicPartition partition0 = new TopicPartition(TEST_TOPIC_NAME, 0);
        partitionLimits.put(partition0, new KafkaPrinter.PartitionTracker(0, recordLimit));
        var delimitedOutputBytes = getOutputFromConsumer(kafkaConsumer, numTrafficStreams, partitionLimits);
        Assertions.assertEquals(recordLimit, partitionLimits.get(partition0).currentRecordCount);
        validateNumberOfTrafficStreamsEmitted(NUM_PROTOBUF_OBJECTS, delimitedOutputBytes);
    }

    @Test
    public void testMultiplePartitionLimiting() throws Exception {
        Random random = new Random(4);
        // Use larger number of streams than recordLimit cutoff
        var numTrafficStreamsPartition0 = 30;
        var numTrafficStreamsPartition1 = 20;
        var numTrafficStreamsPartition2 = 0;
        var totalTrafficStreams = numTrafficStreamsPartition0 + numTrafficStreamsPartition1
            + numTrafficStreamsPartition2;
        var recordLimitPartition0 = 17;
        var recordLimitPartition1 = 8;
        var recordLimitPartition2 = 0;
        var totalLimitTrafficStreams = recordLimitPartition0 + recordLimitPartition1 + recordLimitPartition2;

        var kafkaConsumer = makeKafkaConsumer(
            Map.of(0, numTrafficStreamsPartition0, 1, numTrafficStreamsPartition1, 2, numTrafficStreamsPartition2),
            () -> random.nextInt(NUM_READ_ITEMS_BOUND)
        );
        var partitionLimits = new HashMap<TopicPartition, KafkaPrinter.PartitionTracker>();
        TopicPartition partition0 = new TopicPartition(TEST_TOPIC_NAME, 0);
        TopicPartition partition1 = new TopicPartition(TEST_TOPIC_NAME, 1);
        TopicPartition partition2 = new TopicPartition(TEST_TOPIC_NAME, 2);
        partitionLimits.put(partition0, new KafkaPrinter.PartitionTracker(0, recordLimitPartition0));
        partitionLimits.put(partition1, new KafkaPrinter.PartitionTracker(0, recordLimitPartition1));
        partitionLimits.put(partition2, new KafkaPrinter.PartitionTracker(0, recordLimitPartition2));
        var delimitedOutputBytes = getOutputFromConsumer(kafkaConsumer, totalTrafficStreams, partitionLimits);
        Assertions.assertEquals(recordLimitPartition0, partitionLimits.get(partition0).currentRecordCount);
        Assertions.assertEquals(recordLimitPartition1, partitionLimits.get(partition1).currentRecordCount);
        Assertions.assertEquals(recordLimitPartition2, partitionLimits.get(partition2).currentRecordCount);
        validateNumberOfTrafficStreamsEmitted(totalLimitTrafficStreams, delimitedOutputBytes);
    }

    private byte[] getOutputFromConsumer(
        org.apache.kafka.clients.consumer.Consumer<String, byte[]> kafkaConsumer,
        int expectedMessageCount,
        Map<TopicPartition, KafkaPrinter.PartitionTracker> capturedRecords
    ) throws Exception {
        try (var baos = new ByteArrayOutputStream()) {
            var wrappedConsumer = new CountingConsumer(
                KafkaPrinter.getDelimitedProtoBufOutputter(
                    capturedRecords,
                    Map.of(0, CodedOutputStream.newInstance(baos)),
                    false
                )
            );
            while (wrappedConsumer.count < expectedMessageCount) {
                KafkaPrinter.processNextChunkOfKafkaEvents(kafkaConsumer, wrappedConsumer);
            }
            return baos.toByteArray();
        }
    }

    private void validateNumberOfTrafficStreamsEmitted(int expectedNumProtobufObjects, byte[] delimitedOutputBytes)
        throws Exception {
        int count = 0;
        try (var bais = new ByteArrayInputStream(delimitedOutputBytes)) {
            while (true) {
                var builder = TrafficStream.newBuilder();
                if (!builder.mergeDelimitedFrom(bais)) {
                    Assertions.assertEquals(expectedNumProtobufObjects, count);
                    return;
                }
                Assertions.assertNotNull(builder.build());
                ++count;
            }
        }
    }

    private org.apache.kafka.clients.consumer.Consumer<String, byte[]> makeKafkaConsumer(
        Map<Integer, Integer> partitionIdToNumTrafficStreams,
        Supplier<Integer> numReadGenerator
    ) throws Exception {
        var mockConsumer = new MockConsumer(OffsetResetStrategy.EARLIEST);
        var tpList = new ArrayList<TopicPartition>();
        var offsetMap = new HashMap<TopicPartition, Long>();
        for (int partitionId : partitionIdToNumTrafficStreams.keySet()) {
            var topicPartition = new TopicPartition(TEST_TOPIC_NAME, partitionId);
            tpList.add(topicPartition);
            offsetMap.put(topicPartition, 0L);
        }
        mockConsumer.assign(tpList);
        mockConsumer.updateBeginningOffsets(offsetMap);
        for (Map.Entry<Integer, Integer> partitionEntry : partitionIdToNumTrafficStreams.entrySet()) {
            var partitionId = partitionEntry.getKey();
            var numTrafficStreams = partitionEntry.getValue();
            for (int i = 0; i < numTrafficStreams; ++i) {
                var payload = ("" + (char) ('A' + (char) i)).repeat(10);
                var data = makeTrafficStreamBytes(Instant.now(), payload, numReadGenerator.get());
                var record = new ConsumerRecord(TEST_TOPIC_NAME, partitionId, 1 + i, Instant.now().toString(), data);
                log.trace("adding record");
                mockConsumer.addRecord(record);
            }
        }
        return mockConsumer;
    }
}
