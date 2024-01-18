package org.opensearch.migrations.replay;

import com.google.protobuf.ByteString;
import com.google.protobuf.Timestamp;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.consumer.MockConsumer;
import org.apache.kafka.clients.consumer.OffsetResetStrategy;
import org.apache.kafka.common.PartitionInfo;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.opensearch.migrations.trafficcapture.protos.ReadObservation;
import org.opensearch.migrations.trafficcapture.protos.TrafficObservation;
import org.opensearch.migrations.trafficcapture.protos.TrafficStream;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.io.PipedOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

@Slf4j
class KafkaPrinterTest {
    final static String FAKE_READ_PACKET_DATA = "abcdefgh\n";
    public static final int NUM_READ_ITEMS_BOUND = 1000;
    public static final String TEST_TOPIC_NAME = "TEST_TOPIC_NAME";
    public static final int NUM_PROTOBUF_OBJECTS = 10;

    private static TrafficStream makeTrafficStream(Instant t, String payload, int numReads) {
        var fixedTimestamp = Timestamp.newBuilder()
                .setSeconds(t.getEpochSecond())
                .setNanos(t.getNano())
                .build();
        var builder = TrafficStream.newBuilder()
                .setConnectionId("testConnectionId")
                .setNodeId("testNodeId")
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
            underlyingConsumer.accept(stream.map(msg->{log.trace("read msg"); count++; return msg;}));
        }
    }

    @Test
    public void testStreamFormatting() throws Exception {
        Random random = new Random(2);
        var numTrafficStreams = 10;
        var kafkaConsumer = makeKafkaConsumer(numTrafficStreams, () -> random.nextInt(NUM_READ_ITEMS_BOUND));
        var capturedRecords = new HashMap<KafkaPrinter.Partition, KafkaPrinter.PartitionTracker>();
        capturedRecords.put(new KafkaPrinter.Partition(TEST_TOPIC_NAME, 0), new KafkaPrinter.PartitionTracker(0,50));
        var delimitedOutputBytes = getOutputFromConsumer(kafkaConsumer, numTrafficStreams, capturedRecords);
        validateNumberOfTrafficStreamsEmitted(NUM_PROTOBUF_OBJECTS, delimitedOutputBytes);
    }

    private byte[] getOutputFromConsumer(org.apache.kafka.clients.consumer.Consumer<String,byte[]> kafkaConsumer,
                                         int expectedMessageCount, Map<KafkaPrinter.Partition, KafkaPrinter.PartitionTracker> capturedRecords)
            throws Exception
    {
        try (var baos = new ByteArrayOutputStream()) {
            var wrappedConsumer = new CountingConsumer(KafkaPrinter.getDelimitedProtoBufOutputter(baos, capturedRecords));
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

    private org.apache.kafka.clients.consumer.Consumer<String, byte[]>
    makeKafkaConsumer(int numTrafficStreams, Supplier<Integer> numReadGenerator)
            throws Exception
    {
        var mockConsumer = new MockConsumer(OffsetResetStrategy.EARLIEST);
        var topicPartition = new TopicPartition(TEST_TOPIC_NAME, 0);
        var tpList = List.of(topicPartition);
        mockConsumer.assign(tpList);
        mockConsumer.updateBeginningOffsets(Map.of(topicPartition, 0L));
        for (int i=0; i<numTrafficStreams; ++i) {
            var payload = (""+(char)('A'+(char)i)).repeat(10);
            var data = makeTrafficStreamBytes(Instant.now(), payload, numReadGenerator.get());
            var record = new ConsumerRecord(TEST_TOPIC_NAME, 0, 1+i, Instant.now().toString(), data);
            log.trace("adding record");
            mockConsumer.addRecord(record);
        }
        return mockConsumer;
    }
}
