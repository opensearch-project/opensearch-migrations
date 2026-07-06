package org.opensearch.migrations.trafficcapture.kafkaoffloader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;

import org.opensearch.migrations.trafficcapture.kafkaoffloader.tracing.TestRootKafkaOffloaderContext;
import org.opensearch.migrations.trafficcapture.tracing.ConnectionContext;

import io.netty.buffer.Unpooled;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.producer.Callback;
import org.apache.kafka.clients.producer.MockProducer;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.record.AbstractRecords;
import org.apache.kafka.common.record.CompressionType;
import org.apache.kafka.common.record.RecordBatch;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@Slf4j
@ExtendWith(MockitoExtension.class)
public class KafkaCaptureFactoryTest {

    public static final String TEST_NODE_ID_STRING = "test_node_id";
    @Mock
    private Producer<String, byte[]> mockProducer;
    private String connectionId = "0242c0fffea82008-0000000a-00000003-62993a3207f92af6-9093ce33";
    private String topic = "test_topic";

    @Test
    public void testLargeRequestIsWithinKafkaMessageSizeLimit() throws IOException, ExecutionException,
        InterruptedException {
        final var referenceTimestamp = Instant.now(Clock.systemUTC());

        int maxAllowableMessageSize = 1024 * 1024;
        MockProducer<String, byte[]> producer = new MockProducer<>(
            true,
            null,
            new StringSerializer(),
            new ByteArraySerializer()
        );
        KafkaCaptureFactory kafkaCaptureFactory = new KafkaCaptureFactory(
            TestRootKafkaOffloaderContext.noTracking(),
            TEST_NODE_ID_STRING,
            producer,
            maxAllowableMessageSize
        );
        var serializer = kafkaCaptureFactory.createOffloader(createCtx());

        var testStr =
            "{ \"create\": { \"_index\": \"office-index\" } }\n{ \"title\": \"Malone's Cones\", \"year\": 2013 }\n"
                .repeat(15000);
        var fakeDataBytes = testStr.getBytes(StandardCharsets.UTF_8);
        Assertions.assertTrue(fakeDataBytes.length > 1024 * 1024);
        var bb = Unpooled.wrappedBuffer(fakeDataBytes);
        serializer.addReadEvent(referenceTimestamp, bb);
        var future = serializer.flushCommitAndResetStream(true);
        future.get();
        for (ProducerRecord<String, byte[]> record : producer.history()) {
            int recordSize = calculateRecordSize(record, null);
            Assertions.assertTrue(recordSize <= maxAllowableMessageSize);
            int worstCaseKeyRecordSize = calculateRecordSize(record, connectionId);
            Assertions.assertTrue(worstCaseKeyRecordSize <= maxAllowableMessageSize);
        }
        bb.release();
        producer.close();
    }

    private static ConnectionContext createCtx() {
        return new ConnectionContext(new TestRootKafkaOffloaderContext(), "test", "test");
    }

    /**
     * This size calculation is based off the KafkaProducer client request size validation check done when Producer
     * records are sent. This validation appears to be consistent for several versions now, here is a reference to
     * version 3.5 at the time of writing this: https://github.com/apache/kafka/blob/trunk/clients/src/main/java/org/apache/kafka/clients/producer/KafkaProducer.java#L1002-L1003.
     * It is, however, subject to change which may make this test scenario more suited for an integration test where
     * a KafkaProducer does not need to be mocked.
     */
    private int calculateRecordSize(ProducerRecord<String, byte[]> record, String recordKeySubstitute) {
        StringSerializer stringSerializer = new StringSerializer();
        ByteArraySerializer byteArraySerializer = new ByteArraySerializer();
        String recordKey = recordKeySubstitute == null ? record.key() : recordKeySubstitute;
        byte[] serializedKey = stringSerializer.serialize(record.topic(), record.headers(), recordKey);
        byte[] serializedValue = byteArraySerializer.serialize(record.topic(), record.headers(), record.value());
        stringSerializer.close();
        byteArraySerializer.close();
        return AbstractRecords.estimateSizeInBytesUpperBound(
            RecordBatch.CURRENT_MAGIC_VALUE,
            CompressionType.NONE,
            serializedKey,
            serializedValue,
            record.headers().toArray()
        );
    }

    @Test
    public void testLinearOffloadingIsSuccessful() throws IOException, InterruptedException, ExecutionException,
        TimeoutException {
        KafkaCaptureFactory kafkaCaptureFactory = new KafkaCaptureFactory(
            TestRootKafkaOffloaderContext.noTracking(),
            TEST_NODE_ID_STRING,
            mockProducer,
            1024 * 1024
        );
        var offloader = kafkaCaptureFactory.createOffloader(createCtx());

        List<FutureTask<RecordMetadata>> recordSentFutures = new ArrayList<>(3);

        List<CountDownLatch> latches = Arrays.asList(
            new CountDownLatch(1),
            new CountDownLatch(1),
            new CountDownLatch(1)
        );

        var latchIterator = latches.iterator();
        when(mockProducer.send(any(), any())).thenAnswer(invocation -> {
            Object[] args = invocation.getArguments();
            ProducerRecord<String, byte[]> record = (ProducerRecord) args[0];
            Callback callback = (Callback) args[1];

            var recordMetadata = generateRecordMetadata(record.topic(), 1);
            var future = new FutureTask<>(() -> {
                callback.onCompletion(recordMetadata, null);
                return recordMetadata;
            });

            recordSentFutures.add(future);

            latchIterator.next().countDown();
            return future;
        });

        Instant ts = Instant.now();
        byte[] fakeDataBytes = "FakeData".getBytes(StandardCharsets.UTF_8);
        var bb = Unpooled.wrappedBuffer(fakeDataBytes);
        offloader.addReadEvent(ts, bb);
        var cf1 = offloader.flushCommitAndResetStream(false);
        offloader.addReadEvent(ts, bb);
        var cf2 = offloader.flushCommitAndResetStream(false);
        offloader.addReadEvent(ts, bb);
        var cf3 = offloader.flushCommitAndResetStream(false);
        bb.release();

        Assertions.assertEquals(false, cf1.isDone());
        Assertions.assertEquals(false, cf2.isDone());
        Assertions.assertEquals(false, cf3.isDone());

        awaitLatchWithTestFailOnTimeout(latches.get(0));
        recordSentFutures.get(0).run();
        cf1.get(1, TimeUnit.SECONDS);

        Assertions.assertEquals(true, cf1.isDone());
        Assertions.assertEquals(false, cf2.isDone());
        Assertions.assertEquals(false, cf3.isDone());

        awaitLatchWithTestFailOnTimeout(latches.get(1));
        recordSentFutures.get(1).run();
        cf2.get(1, TimeUnit.SECONDS);

        Assertions.assertEquals(true, cf1.isDone());
        Assertions.assertEquals(true, cf2.isDone());
        Assertions.assertEquals(false, cf3.isDone());

        awaitLatchWithTestFailOnTimeout(latches.get(2));
        recordSentFutures.get(2).run();
        cf3.get(1, TimeUnit.SECONDS);

        Assertions.assertEquals(true, cf1.isDone());
        Assertions.assertEquals(true, cf2.isDone());
        Assertions.assertEquals(true, cf3.isDone());

        mockProducer.close();
    }

    @Test
    public void testOffloaderFlushCommitIsNonBlockingOnKafkaProducer() throws IOException, InterruptedException,
        ExecutionException, TimeoutException {
        KafkaCaptureFactory kafkaCaptureFactory = new KafkaCaptureFactory(
            TestRootKafkaOffloaderContext.noTracking(),
            TEST_NODE_ID_STRING,
            mockProducer,
            1024 * 1024
        );
        var offloader = kafkaCaptureFactory.createOffloader(createCtx());

        List<FutureTask<RecordMetadata>> recordSentFutures = new ArrayList<>(3);

        ReentrantLock producerLock = new ReentrantLock(true);
        CountDownLatch latch = new CountDownLatch(1);

        // Start with producer locked to ensure offloader api is non-blocking
        producerLock.lock();

        when(mockProducer.send(any(), any())).thenAnswer(invocation -> {
            producerLock.lock();
            Object[] args = invocation.getArguments();
            ProducerRecord<String, byte[]> record = (ProducerRecord) args[0];
            Callback callback = (Callback) args[1];

            var recordMetadata = generateRecordMetadata(record.topic(), 1);
            var future = new FutureTask<>(() -> {
                callback.onCompletion(recordMetadata, null);
                return recordMetadata;
            });
            recordSentFutures.add(future);

            latch.countDown();
            producerLock.unlock();
            return future;
        });

        Instant ts = Instant.now();
        byte[] fakeDataBytes = "FakeData".getBytes(StandardCharsets.UTF_8);
        var bb = Unpooled.wrappedBuffer(fakeDataBytes);
        offloader.addReadEvent(ts, bb);
        var cf1 = offloader.flushCommitAndResetStream(false);
        bb.release();

        Assertions.assertEquals(false, cf1.isDone());

        producerLock.unlock();

        awaitLatchWithTestFailOnTimeout(latch);
        recordSentFutures.get(0).run();
        cf1.get(1, TimeUnit.SECONDS);

        Assertions.assertEquals(true, cf1.isDone());
        mockProducer.close();
    }

    private RecordMetadata generateRecordMetadata(String topicName, int partition) {
        TopicPartition topicPartition = new TopicPartition(topicName, partition);
        return new RecordMetadata(topicPartition, 0, 0, 0, 0, 0);
    }

    @SneakyThrows
    private void awaitLatchWithTestFailOnTimeout(CountDownLatch latch) {
        boolean successful = latch.await(1, TimeUnit.SECONDS);
        Assertions.assertTrue(successful);
    }

    @Test
    public void testAllFragmentsUseSameKafkaKeyForPartitionLocality() throws IOException, ExecutionException,
        InterruptedException {
        final var referenceTimestamp = Instant.now(Clock.systemUTC());

        int maxAllowableMessageSize = 1024 * 1024;
        MockProducer<String, byte[]> producer = new MockProducer<>(
            true, null, new StringSerializer(), new ByteArraySerializer()
        );
        KafkaCaptureFactory kafkaCaptureFactory = new KafkaCaptureFactory(
            TestRootKafkaOffloaderContext.noTracking(),
            TEST_NODE_ID_STRING,
            producer,
            maxAllowableMessageSize
        );
        var serializer = kafkaCaptureFactory.createOffloader(createCtx());

        // Create a payload that will fragment into multiple records (~2MB with 1MB buffer)
        var testStr = "x".repeat(2 * 1024 * 1024);
        var fakeDataBytes = testStr.getBytes(StandardCharsets.UTF_8);
        var bb = Unpooled.wrappedBuffer(fakeDataBytes);
        serializer.addReadEvent(referenceTimestamp, bb);
        var future = serializer.flushCommitAndResetStream(true);
        future.get();

        // Should produce multiple records (fragments)
        Assertions.assertTrue(producer.history().size() > 1,
            "Expected multiple fragments but got " + producer.history().size());

        // All fragments must have the same key (connectionId without index)
        Set<String> uniqueKeys = producer.history().stream()
            .map(ProducerRecord::key)
            .collect(Collectors.toSet());
        Assertions.assertEquals(1, uniqueKeys.size(),
            "All fragments should use the same Kafka key for partition locality, but got: " + uniqueKeys);

        // The key should be exactly the connectionId (not connectionId.index)
        String recordKey = uniqueKeys.iterator().next();
        Assertions.assertEquals("test", recordKey,
            "Kafka key should be exactly the connectionId");

        bb.release();
        producer.close();
    }

    @Test
    public void testLargerBufferSizeReducesFragmentation() throws IOException, ExecutionException,
        InterruptedException {
        final var referenceTimestamp = Instant.now(Clock.systemUTC());

        // 5MB payload
        var testStr = "x".repeat(5 * 1024 * 1024);
        var fakeDataBytes = testStr.getBytes(StandardCharsets.UTF_8);

        // With 1MB buffer -> many fragments
        MockProducer<String, byte[]> producer1MB = new MockProducer<>(
            true, null, new StringSerializer(), new ByteArraySerializer()
        );
        KafkaCaptureFactory factory1MB = new KafkaCaptureFactory(
            TestRootKafkaOffloaderContext.noTracking(),
            TEST_NODE_ID_STRING,
            producer1MB,
            1024 * 1024
        );
        var serializer1MB = factory1MB.createOffloader(createCtx());
        var bb1 = Unpooled.wrappedBuffer(fakeDataBytes);
        serializer1MB.addReadEvent(referenceTimestamp, bb1);
        serializer1MB.flushCommitAndResetStream(true).get();
        int fragments1MB = producer1MB.history().size();
        bb1.release();
        producer1MB.close();

        // With 8MB buffer -> single record (payload fits in one buffer)
        MockProducer<String, byte[]> producer8MB = new MockProducer<>(
            true, null, new StringSerializer(), new ByteArraySerializer()
        );
        KafkaCaptureFactory factory8MB = new KafkaCaptureFactory(
            TestRootKafkaOffloaderContext.noTracking(),
            TEST_NODE_ID_STRING,
            producer8MB,
            8 * 1024 * 1024
        );
        var serializer8MB = factory8MB.createOffloader(createCtx());
        var bb8 = Unpooled.wrappedBuffer(fakeDataBytes);
        serializer8MB.addReadEvent(referenceTimestamp, bb8);
        serializer8MB.flushCommitAndResetStream(true).get();
        int fragments8MB = producer8MB.history().size();
        bb8.release();
        producer8MB.close();

        // 1MB buffer should produce many more fragments than 8MB buffer
        Assertions.assertTrue(fragments1MB > 4,
            "Expected >4 fragments with 1MB buffer for 5MB payload, got " + fragments1MB);
        Assertions.assertEquals(1, fragments8MB,
            "Expected 1 record with 8MB buffer for 5MB payload, got " + fragments8MB);
    }

    @Test
    public void testMaxRequestSizeWithLargeBufferProducesValidRecords() throws IOException, ExecutionException,
        InterruptedException {
        final var referenceTimestamp = Instant.now(Clock.systemUTC());

        int maxMessageSize = 8 * 1024 * 1024; // 8MB
        MockProducer<String, byte[]> producer = new MockProducer<>(
            true, null, new StringSerializer(), new ByteArraySerializer()
        );
        KafkaCaptureFactory kafkaCaptureFactory = new KafkaCaptureFactory(
            TestRootKafkaOffloaderContext.noTracking(),
            TEST_NODE_ID_STRING,
            producer,
            maxMessageSize
        );
        var serializer = kafkaCaptureFactory.createOffloader(createCtx());

        // 7MB payload - should fit in a single 8MB buffer
        var testStr = "x".repeat(7 * 1024 * 1024);
        var fakeDataBytes = testStr.getBytes(StandardCharsets.UTF_8);
        var bb = Unpooled.wrappedBuffer(fakeDataBytes);
        serializer.addReadEvent(referenceTimestamp, bb);
        var future = serializer.flushCommitAndResetStream(true);
        future.get();

        // Should produce exactly 1 record
        Assertions.assertEquals(1, producer.history().size(),
            "7MB payload with 8MB buffer should produce 1 record");

        // Verify record size is within max.request.size=8MB
        ProducerRecord<String, byte[]> record = producer.history().get(0);
        int recordSize = calculateRecordSize(record, null);
        Assertions.assertTrue(recordSize <= maxMessageSize,
            "Record size " + recordSize + " exceeds max message size " + maxMessageSize);

        bb.release();
        producer.close();
    }

    @Test
    public void testDifferentConnectionsProduceDifferentKeys() throws IOException, ExecutionException,
        InterruptedException {
        final var referenceTimestamp = Instant.now(Clock.systemUTC());

        MockProducer<String, byte[]> producer = new MockProducer<>(
            true, null, new StringSerializer(), new ByteArraySerializer()
        );
        KafkaCaptureFactory kafkaCaptureFactory = new KafkaCaptureFactory(
            TestRootKafkaOffloaderContext.noTracking(),
            TEST_NODE_ID_STRING,
            producer,
            1024 * 1024
        );

        var ctx1 = new ConnectionContext(new TestRootKafkaOffloaderContext(), "conn-alpha", "node1");
        var ctx2 = new ConnectionContext(new TestRootKafkaOffloaderContext(), "conn-beta", "node1");

        var offloader1 = kafkaCaptureFactory.createOffloader(ctx1);
        var offloader2 = kafkaCaptureFactory.createOffloader(ctx2);

        byte[] payload = "small-payload".getBytes(StandardCharsets.UTF_8);
        var bb1 = Unpooled.wrappedBuffer(payload);
        offloader1.addReadEvent(referenceTimestamp, bb1);
        offloader1.flushCommitAndResetStream(true).get();
        bb1.release();

        var bb2 = Unpooled.wrappedBuffer(payload);
        offloader2.addReadEvent(referenceTimestamp, bb2);
        offloader2.flushCommitAndResetStream(true).get();
        bb2.release();

        Assertions.assertEquals(2, producer.history().size());
        String key1 = producer.history().get(0).key();
        String key2 = producer.history().get(1).key();
        Assertions.assertEquals("conn-alpha", key1);
        Assertions.assertEquals("conn-beta", key2);
        Assertions.assertNotEquals(key1, key2,
            "Different connections must produce different Kafka keys");

        producer.close();
    }

    @Test
    public void testSingleFragmentUsesConnectionIdAsKey() throws IOException, ExecutionException,
        InterruptedException {
        final var referenceTimestamp = Instant.now(Clock.systemUTC());

        MockProducer<String, byte[]> producer = new MockProducer<>(
            true, null, new StringSerializer(), new ByteArraySerializer()
        );
        KafkaCaptureFactory kafkaCaptureFactory = new KafkaCaptureFactory(
            TestRootKafkaOffloaderContext.noTracking(),
            TEST_NODE_ID_STRING,
            producer,
            1024 * 1024
        );
        var serializer = kafkaCaptureFactory.createOffloader(createCtx());

        // Small payload that fits in a single buffer — no fragmentation
        byte[] payload = "tiny".getBytes(StandardCharsets.UTF_8);
        var bb = Unpooled.wrappedBuffer(payload);
        serializer.addReadEvent(referenceTimestamp, bb);
        serializer.flushCommitAndResetStream(true).get();

        Assertions.assertEquals(1, producer.history().size(),
            "Small payload should produce exactly 1 record");
        Assertions.assertEquals("test", producer.history().get(0).key(),
            "Single-fragment record key should be the connectionId");

        bb.release();
        producer.close();
    }
}
