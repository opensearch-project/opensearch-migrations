package org.opensearch.migrations.trafficcapture.kafkaoffloader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import org.opensearch.migrations.trafficcapture.kafkaoffloader.tracing.TestRootKafkaOffloaderContext;
import org.opensearch.migrations.trafficcapture.protos.TrafficRecord;
import org.opensearch.migrations.trafficcapture.tracing.ConnectionContext;

import io.netty.buffer.Unpooled;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.MockConsumer;
import org.apache.kafka.clients.consumer.OffsetResetStrategy;
import org.apache.kafka.clients.producer.Callback;
import org.apache.kafka.clients.producer.MockProducer;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.Cluster;
import org.apache.kafka.common.Node;
import org.apache.kafka.common.PartitionInfo;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.record.AbstractRecords;
import org.apache.kafka.common.record.CompressionType;
import org.apache.kafka.common.record.RecordBatch;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

@Slf4j
public class KafkaCaptureFactoryTest {

    public static final String TEST_NODE_ID_STRING = "test_node_id";
    private String connectionId = "0242c0fffea82008-0000000a-00000003-62993a3207f92af6-9093ce33";
    private String topic = "test_topic";

    @Test
    public void testLargeRequestIsWithinKafkaMessageSizeLimit() throws IOException, ExecutionException,
        InterruptedException {
        final var referenceTimestamp = Instant.now(Clock.systemUTC());

        int maxAllowableMessageSize = 1024 * 1024;
        MockProducer<String, byte[]> producer = createMockProducer(true);
        KafkaCaptureFactory kafkaCaptureFactory = createFactory(producer, maxAllowableMessageSize);
        var serializer = kafkaCaptureFactory.createOffloader(createCtx());

        var testStr =
            "{ \"create\": { \"_index\": \"office-index\" } }\n{ \"title\": \"Malone's Cones\", \"year\": 2013 }\n"
                .repeat(15000);
        var fakeDataBytes = testStr.getBytes(StandardCharsets.UTF_8);
        Assertions.assertTrue(fakeDataBytes.length > 1024 * 1024);
        var bb = Unpooled.wrappedBuffer(fakeDataBytes);
        serializer.addReadEvent(referenceTimestamp, bb);
        serializer.addCloseEvent(referenceTimestamp);
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

    @Test
    void orderlyRetirementStopsNewConnectionsAndRetiresEveryAssignedWriter() throws Exception {
        var producer = createMockProducer(true);
        var factory = createFactory(producer, 1024 * 1024);

        factory.retireForOrderlyShutdown().get(5, TimeUnit.SECONDS);

        Assertions.assertThrows(
            IllegalStateException.class,
            () -> factory.createOffloader(createCtx())
        );
        Assertions.assertEquals(
            4,
            producer.history()
                .stream()
                .filter(record -> CaptureKafkaPublisher.isRecordType(
                    record.headers(),
                    CaptureKafkaPublisher.NO_MORE_WRITES_RECORD_TYPE
                ))
                .count()
        );
        factory.close();
    }

    @Test
    void orderlyRetirementLetsTheProxyDisconnectConnectionsWhileMembershipCloses() throws Exception {
        var producer = createMockProducer(true);
        var topicName = KafkaCaptureFactory.DEFAULT_TOPIC_NAME_FOR_TRAFFIC;
        var partitionInfo = partitionInfo(topicName, 4);
        var topicPartitions = partitionInfo.stream()
            .map(info -> new TopicPartition(info.topic(), info.partition()))
            .toList();
        var membershipCloseStarted = new CountDownLatch(1);
        var allowMembershipClose = new CountDownLatch(1);
        var membershipConsumer = new MockConsumer<String, byte[]>(OffsetResetStrategy.EARLIEST) {
            @Override
            public void close(Duration timeout) {
                membershipCloseStarted.countDown();
                try {
                    if (!allowMembershipClose.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("Test did not release membership close");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Interrupted while waiting to close membership", e);
                }
                super.close(timeout);
            }
        };
        membershipConsumer.updateBeginningOffsets(
            topicPartitions.stream().collect(Collectors.toMap(partition -> partition, ignored -> 0L))
        );
        membershipConsumer.schedulePollTask(() -> membershipConsumer.rebalance(topicPartitions));
        var factory = new KafkaCaptureFactory(
            TestRootKafkaOffloaderContext.noTracking(),
            TEST_NODE_ID_STRING,
            producer,
            membershipConsumer,
            assignmentTracker(),
            1,
            topicName,
            1024 * 1024,
            KafkaCaptureFactory.DEFAULT_LIVENESS_SNAPSHOT_INTERVAL,
            ignored -> {},
            ignored -> {}
        );
        factory.publisherReady().get(5, TimeUnit.SECONDS);
        var invocationExecutor = Executors.newSingleThreadExecutor();
        try {
            var retirement = invocationExecutor.submit(factory::retireForOrderlyShutdown)
                .get(1, TimeUnit.SECONDS);

            Assertions.assertTrue(membershipCloseStarted.await(1, TimeUnit.SECONDS));
            Assertions.assertFalse(retirement.isDone());

            allowMembershipClose.countDown();
            retirement.get(5, TimeUnit.SECONDS);
        } finally {
            allowMembershipClose.countDown();
            invocationExecutor.shutdownNow();
            factory.close();
        }
    }

    @Test
    void finalRecordWithoutTerminalCloseObservationReportsAnUnstableProcess() throws Exception {
        var producer = createMockProducer(true);
        var unstableFailure = new AtomicReference<Throwable>();
        var factory = createFactory(
            producer,
            1024 * 1024,
            ignored -> {},
            unstableFailure::set
        );
        var serializer = factory.createOffloader(createCtx());

        var result = serializer.flushCommitAndResetStream(true);

        var failure = Assertions.assertThrows(
            ExecutionException.class,
            () -> result.get(5, TimeUnit.SECONDS)
        ).getCause();
        Assertions.assertInstanceOf(IllegalStateException.class, failure);
        Assertions.assertSame(failure, unstableFailure.get());
        Assertions.assertEquals(List.of(), trafficRecords(producer));
        factory.close();
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
        var producer = createControllableProducer();
        KafkaCaptureFactory kafkaCaptureFactory = createFactory(producer, 1024 * 1024);
        var offloader = kafkaCaptureFactory.createOffloader(createCtx());

        List<FutureTask<RecordMetadata>> recordSentFutures = new ArrayList<>(3);

        List<CountDownLatch> latches = Arrays.asList(
            new CountDownLatch(1),
            new CountDownLatch(1),
            new CountDownLatch(1)
        );

        var latchIterator = latches.iterator();
        producer.setSendHandler((record, callback) -> {
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

        producer.close();
    }

    @Test
    public void testOffloaderFlushCommitIsNonBlockingOnKafkaProducer() throws IOException, InterruptedException,
        ExecutionException, TimeoutException {
        var producer = createControllableProducer();
        KafkaCaptureFactory kafkaCaptureFactory = createFactory(producer, 1024 * 1024);
        var offloader = kafkaCaptureFactory.createOffloader(createCtx());

        List<FutureTask<RecordMetadata>> recordSentFutures = new ArrayList<>(3);

        ReentrantLock producerLock = new ReentrantLock(true);
        CountDownLatch latch = new CountDownLatch(1);

        // Start with producer locked to ensure offloader api is non-blocking
        producerLock.lock();

        producer.setSendHandler((record, callback) -> {
            producerLock.lock();
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
        producer.close();
    }

    private RecordMetadata generateRecordMetadata(String topicName, int partition) {
        TopicPartition topicPartition = new TopicPartition(topicName, partition);
        return new RecordMetadata(topicPartition, 0, 0, 1, 0, 0);
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
        MockProducer<String, byte[]> producer = createMockProducer(true);
        KafkaCaptureFactory kafkaCaptureFactory = createFactory(producer, maxAllowableMessageSize);
        var serializer = kafkaCaptureFactory.createOffloader(createCtx());

        // Create a payload that will fragment into multiple records (~2MB with 1MB buffer)
        var testStr = "x".repeat(2 * 1024 * 1024);
        var fakeDataBytes = testStr.getBytes(StandardCharsets.UTF_8);
        var bb = Unpooled.wrappedBuffer(fakeDataBytes);
        serializer.addReadEvent(referenceTimestamp, bb);
        serializer.addCloseEvent(referenceTimestamp);
        var future = serializer.flushCommitAndResetStream(true);
        future.get();

        // Should produce multiple records (fragments)
        var trafficRecords = trafficRecords(producer);
        Assertions.assertTrue(trafficRecords.size() > 1,
            "Expected multiple fragments but got " + trafficRecords.size());

        // All fragments must have the same key (connectionId without index)
        Set<String> uniqueKeys = trafficRecords.stream()
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
        MockProducer<String, byte[]> producer1MB = createMockProducer(true);
        KafkaCaptureFactory factory1MB = createFactory(producer1MB, 1024 * 1024);
        var serializer1MB = factory1MB.createOffloader(createCtx());
        var bb1 = Unpooled.wrappedBuffer(fakeDataBytes);
        serializer1MB.addReadEvent(referenceTimestamp, bb1);
        serializer1MB.addCloseEvent(referenceTimestamp);
        serializer1MB.flushCommitAndResetStream(true).get();
        int fragments1MB = trafficRecords(producer1MB).size();
        bb1.release();
        producer1MB.close();

        // With 8MB buffer -> single record (payload fits in one buffer)
        MockProducer<String, byte[]> producer8MB = createMockProducer(true);
        KafkaCaptureFactory factory8MB = createFactory(producer8MB, 8 * 1024 * 1024);
        var serializer8MB = factory8MB.createOffloader(createCtx());
        var bb8 = Unpooled.wrappedBuffer(fakeDataBytes);
        serializer8MB.addReadEvent(referenceTimestamp, bb8);
        serializer8MB.addCloseEvent(referenceTimestamp);
        serializer8MB.flushCommitAndResetStream(true).get();
        int fragments8MB = trafficRecords(producer8MB).size();
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
        MockProducer<String, byte[]> producer = createMockProducer(true);
        KafkaCaptureFactory kafkaCaptureFactory = createFactory(producer, maxMessageSize);
        var serializer = kafkaCaptureFactory.createOffloader(createCtx());

        // 7MB payload - should fit in a single 8MB buffer
        var testStr = "x".repeat(7 * 1024 * 1024);
        var fakeDataBytes = testStr.getBytes(StandardCharsets.UTF_8);
        var bb = Unpooled.wrappedBuffer(fakeDataBytes);
        serializer.addReadEvent(referenceTimestamp, bb);
        serializer.addCloseEvent(referenceTimestamp);
        var future = serializer.flushCommitAndResetStream(true);
        future.get();

        // Should produce exactly 1 record
        var trafficRecords = trafficRecords(producer);
        Assertions.assertEquals(1, trafficRecords.size(),
            "7MB payload with 8MB buffer should produce 1 record");

        // Verify record size is within max.request.size=8MB
        ProducerRecord<String, byte[]> record = trafficRecords.get(0);
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

        MockProducer<String, byte[]> producer = createMockProducer(true);
        KafkaCaptureFactory kafkaCaptureFactory = createFactory(producer, 1024 * 1024);

        var ctx1 = new ConnectionContext(new TestRootKafkaOffloaderContext(), "conn-alpha", "node1");
        var ctx2 = new ConnectionContext(new TestRootKafkaOffloaderContext(), "conn-beta", "node1");

        var offloader1 = kafkaCaptureFactory.createOffloader(ctx1);
        var offloader2 = kafkaCaptureFactory.createOffloader(ctx2);

        byte[] payload = "small-payload".getBytes(StandardCharsets.UTF_8);
        var bb1 = Unpooled.wrappedBuffer(payload);
        offloader1.addReadEvent(referenceTimestamp, bb1);
        offloader1.addCloseEvent(referenceTimestamp);
        offloader1.flushCommitAndResetStream(true).get();
        bb1.release();

        var bb2 = Unpooled.wrappedBuffer(payload);
        offloader2.addReadEvent(referenceTimestamp, bb2);
        offloader2.addCloseEvent(referenceTimestamp);
        offloader2.flushCommitAndResetStream(true).get();
        bb2.release();

        var trafficRecords = trafficRecords(producer);
        Assertions.assertEquals(2, trafficRecords.size());
        String key1 = trafficRecords.get(0).key();
        String key2 = trafficRecords.get(1).key();
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

        MockProducer<String, byte[]> producer = createMockProducer(true);
        KafkaCaptureFactory kafkaCaptureFactory = createFactory(producer, 1024 * 1024);
        var serializer = kafkaCaptureFactory.createOffloader(createCtx());

        // Small payload that fits in a single buffer — no fragmentation
        byte[] payload = "tiny".getBytes(StandardCharsets.UTF_8);
        var bb = Unpooled.wrappedBuffer(payload);
        serializer.addReadEvent(referenceTimestamp, bb);
        serializer.addCloseEvent(referenceTimestamp);
        serializer.flushCommitAndResetStream(true).get();

        var trafficRecords = trafficRecords(producer);
        Assertions.assertEquals(1, trafficRecords.size(),
            "Small payload should produce exactly 1 record");
        Assertions.assertEquals("test", trafficRecords.get(0).key(),
            "Single-fragment record key should be the connectionId");
        var record = trafficRecords.get(0);
        var stream = TrafficRecord.parseFrom(record.value());
        Assertions.assertTrue(stream.hasPartition());
        Assertions.assertEquals(record.partition(), stream.getPartition());

        bb.release();
        producer.close();
    }

    @Test
    public void testNullConnectionIdFailsFastAtCreation() {
        MockProducer<String, byte[]> producer = createMockProducer(true);
        KafkaCaptureFactory kafkaCaptureFactory = createFactory(producer, 1024 * 1024);
        var nullCtx = new ConnectionContext(new TestRootKafkaOffloaderContext(), null, "test");

        var exception = Assertions.assertThrows(NullPointerException.class,
            () -> kafkaCaptureFactory.createOffloader(nullCtx));
        Assertions.assertTrue(exception.getMessage().contains("partition locality"));

        producer.close();
    }

    @Test
    public void capabilityProbesAreAcknowledgedBeforeGroupMembershipStarts() throws Exception {
        var topicName = KafkaCaptureFactory.DEFAULT_TOPIC_NAME_FOR_TRAFFIC;
        var metadata = partitionInfo(topicName, 4);
        var metadataRefreshes = new AtomicInteger();
        var producer = new LogAppendTimeMockProducer(
            new Cluster("test", leaders(metadata), metadata, Set.of(), Set.of()),
            false,
            null,
            new StringSerializer(),
            new ByteArraySerializer()
        ) {
            @Override
            public List<PartitionInfo> partitionsFor(String requestedTopic) {
                metadataRefreshes.incrementAndGet();
                return super.partitionsFor(requestedTopic);
            }
        };
        var membershipConsumer = new MockConsumer<String, byte[]>(OffsetResetStrategy.EARLIEST);
        var topicPartitions = metadata.stream()
            .map(info -> new TopicPartition(info.topic(), info.partition()))
            .toList();
        membershipConsumer.updateBeginningOffsets(
            topicPartitions.stream().collect(Collectors.toMap(partition -> partition, ignored -> 0L))
        );
        membershipConsumer.schedulePollTask(() -> membershipConsumer.rebalance(topicPartitions));
        var factory = new KafkaCaptureFactory(
            TestRootKafkaOffloaderContext.noTracking(),
            TEST_NODE_ID_STRING,
            producer,
            membershipConsumer,
            assignmentTracker(),
            1,
            topicName,
            1024 * 1024,
            KafkaCaptureFactory.DEFAULT_LIVENESS_SNAPSHOT_INTERVAL,
            ignored -> {},
            ignored -> {}
        );

        awaitHistorySize(producer, 2);
        Assertions.assertEquals(Set.of(), membershipConsumer.subscription());
        Assertions.assertTrue(producer.history().stream().allMatch(record ->
            CaptureKafkaPublisher.isRecordType(
                record.headers(),
                org.opensearch.migrations.trafficcapture.protos.CaptureRecordTypes.CAPABILITY_PROBE_RECORD_TYPE
            )
        ));

        Assertions.assertTrue(producer.completeNext());
        Assertions.assertEquals(Set.of(), membershipConsumer.subscription());
        Assertions.assertTrue(producer.completeNext());

        awaitCondition(() -> membershipConsumer.subscription().equals(Set.of(topicName)));
        awaitHistorySize(producer, 6);
        Assertions.assertTrue(metadataRefreshes.get() >= 2);
        Assertions.assertTrue(producer.history().subList(2, 6).stream().allMatch(record ->
            CaptureKafkaPublisher.isRecordType(
                record.headers(),
                CaptureKafkaPublisher.LIVENESS_RECORD_TYPE
            )
        ));
        for (int i = 0; i < 4; ++i) {
            Assertions.assertTrue(producer.completeNext());
        }
        factory.publisherReady().get(1, TimeUnit.SECONDS);
        factory.close();
    }

    @Test
    public void newlyCurrentBrokerLeaderIsProbedBeforeGroupMembershipStarts() throws Exception {
        var topicName = KafkaCaptureFactory.DEFAULT_TOPIC_NAME_FOR_TRAFFIC;
        var leader0 = new Node(0, "broker-0", 9092);
        var leader1 = new Node(1, "broker-1", 9092);
        var leader2 = new Node(2, "broker-2", 9092);
        var initialMetadata = List.of(
            partitionInfo(topicName, 0, leader0),
            partitionInfo(topicName, 1, leader1)
        );
        var changedMetadata = List.of(
            partitionInfo(topicName, 0, leader0),
            partitionInfo(topicName, 1, leader2)
        );
        var metadataRefreshes = new AtomicInteger();
        var producer = new LogAppendTimeMockProducer(
            new Cluster("test", List.of(leader0, leader1, leader2), initialMetadata, Set.of(), Set.of()),
            false,
            null,
            new StringSerializer(),
            new ByteArraySerializer()
        ) {
            @Override
            public List<PartitionInfo> partitionsFor(String requestedTopic) {
                return metadataRefreshes.getAndIncrement() == 0
                    ? initialMetadata
                    : changedMetadata;
            }
        };
        var membershipConsumer = new MockConsumer<String, byte[]>(OffsetResetStrategy.EARLIEST);
        var topicPartitions = changedMetadata.stream()
            .map(info -> new TopicPartition(info.topic(), info.partition()))
            .toList();
        membershipConsumer.updateBeginningOffsets(
            topicPartitions.stream().collect(Collectors.toMap(partition -> partition, ignored -> 0L))
        );
        membershipConsumer.schedulePollTask(() -> membershipConsumer.rebalance(topicPartitions));
        var factory = new KafkaCaptureFactory(
            TestRootKafkaOffloaderContext.noTracking(),
            TEST_NODE_ID_STRING,
            producer,
            membershipConsumer,
            assignmentTracker(),
            1,
            topicName,
            1024 * 1024,
            KafkaCaptureFactory.DEFAULT_LIVENESS_SNAPSHOT_INTERVAL,
            ignored -> {},
            ignored -> {}
        );

        awaitHistorySize(producer, 2);
        Assertions.assertEquals(Set.of(), membershipConsumer.subscription());
        Assertions.assertTrue(producer.completeNext());
        Assertions.assertTrue(producer.completeNext());

        awaitHistorySize(producer, 4);
        Assertions.assertEquals(Set.of(), membershipConsumer.subscription());
        Assertions.assertEquals(
            List.of(0, 1, 0, 1),
            producer.history().stream().map(ProducerRecord::partition).toList()
        );
        Assertions.assertTrue(producer.completeNext());
        Assertions.assertEquals(Set.of(), membershipConsumer.subscription());
        Assertions.assertTrue(producer.completeNext());

        awaitCondition(() -> membershipConsumer.subscription().equals(Set.of(topicName)));
        factory.close();
    }

    @Test
    public void connectionAttemptBeforeTheFirstAssignmentPermanentlyFailsCapture() throws Exception {
        var topicMetadata = partitionInfo(topic, 3);
        var producer = new LogAppendTimeMockProducer(
            new Cluster("test", leaders(topicMetadata), topicMetadata, Set.of(), Set.of()),
            true,
            null,
            new StringSerializer(),
            new ByteArraySerializer()
        );
        var membershipConsumer = new MockConsumer<String, byte[]>(OffsetResetStrategy.EARLIEST);
        var partition0 = new TopicPartition(topic, 0);
        var partition1 = new TopicPartition(topic, 1);
        var partition2 = new TopicPartition(topic, 2);
        var permitAssignment = new CountDownLatch(1);
        membershipConsumer.updateBeginningOffsets(Map.of(
            partition0,
            0L,
            partition1,
            0L,
            partition2,
            0L
        ));
        membershipConsumer.schedulePollTask(() -> {
            awaitLatchWithTestFailOnTimeout(permitAssignment);
            membershipConsumer.rebalance(List.of(partition0, partition1, partition2))
            ;
        });
        var captureFailure = new AtomicReference<Throwable>();
        var factory = new KafkaCaptureFactory(
            TestRootKafkaOffloaderContext.noTracking(),
            TEST_NODE_ID_STRING,
            producer,
            membershipConsumer,
            assignmentTracker(),
            1,
            topic,
            1024 * 1024,
            KafkaCaptureFactory.DEFAULT_LIVENESS_SNAPSHOT_INTERVAL,
            captureFailure::set,
            ignored -> {}
        );

        var startupFailure = Assertions.assertThrows(
            IllegalStateException.class,
            () -> factory.createOffloader(createCtx())
        );
        Assertions.assertEquals(startupFailure, captureFailure.get());

        permitAssignment.countDown();
        Assertions.assertThrows(
            ExecutionException.class,
            () -> factory.publisherReady().get(5, TimeUnit.SECONDS)
        );
        Assertions.assertThrows(
            IllegalStateException.class,
            () -> factory.createOffloader(createCtx())
        );
        factory.close();
        Assertions.assertTrue(membershipConsumer.closed());
        Assertions.assertEquals(List.of(), trafficRecords(producer));
    }

    @Test
    public void membershipInitializationFailureClosesTheUnpublishedPublisher() throws Exception {
        var membershipConsumer = new MockConsumer<String, byte[]>(OffsetResetStrategy.EARLIEST);
        var closeCalls = new AtomicInteger();
        var producerClosed = new CountDownLatch(1);
        var producer = new LogAppendTimeMockProducer(
            true,
            null,
            new StringSerializer(),
            new ByteArraySerializer()
        ) {
            @Override
            public List<PartitionInfo> partitionsFor(String ignoredTopic) {
                var leader = new Node(0, "broker-0", 9092);
                return List.of(new PartitionInfo(
                    topic,
                    0,
                    leader,
                    new Node[] { leader },
                    new Node[] { leader }
                ));
            }

            @Override
            public void close(Duration timeout) {
                closeCalls.incrementAndGet();
                try {
                    super.close(timeout);
                } finally {
                    producerClosed.countDown();
                }
            }
        };
        membershipConsumer.schedulePollTask(() -> {
            throw new IllegalStateException("membership initialization failed");
        });
        var captureFailure = new AtomicReference<Throwable>();
        var captureFailureReported = new CountDownLatch(1);
        var factory = new KafkaCaptureFactory(
            TestRootKafkaOffloaderContext.noTracking(),
            TEST_NODE_ID_STRING,
            producer,
            membershipConsumer,
            assignmentTracker(),
            1,
            topic,
            1024 * 1024,
            KafkaCaptureFactory.DEFAULT_LIVENESS_SNAPSHOT_INTERVAL,
            failure -> {
                captureFailure.set(failure);
                captureFailureReported.countDown();
            },
            ignored -> {}
        );

        Assertions.assertThrows(
            ExecutionException.class,
            () -> factory.publisherReady().get(5, TimeUnit.SECONDS)
        );
        Assertions.assertTrue(captureFailureReported.await(5, TimeUnit.SECONDS));
        Assertions.assertEquals("membership initialization failed", captureFailure.get().getMessage());
        Assertions.assertTrue(producerClosed.await(5, TimeUnit.SECONDS));
        int closeCallsAfterFailure = closeCalls.get();
        Assertions.assertTrue(closeCallsAfterFailure >= 1);

        factory.close();

        Assertions.assertEquals(closeCallsAfterFailure, closeCalls.get());
        Assertions.assertTrue(membershipConsumer.closed());
    }

    @Test
    public void producerWriteFailurePermanentlyFailsCaptureAndReportsTheProcessFailure() throws Exception {
        var writeFailure = new IllegalStateException("producer write failed");
        var topicName = KafkaCaptureFactory.DEFAULT_TOPIC_NAME_FOR_TRAFFIC;
        var topicPartitions = partitionInfo(topicName, 4);
        var producer = new LogAppendTimeMockProducer(
            new Cluster("test", leaders(topicPartitions), topicPartitions, Set.of(), Set.of()),
            true,
            null,
            new StringSerializer(),
            new ByteArraySerializer()
        ) {
            @Override
            public synchronized java.util.concurrent.Future<RecordMetadata> send(
                ProducerRecord<String, byte[]> record,
                Callback callback
            ) {
                if (CaptureKafkaPublisher.isRecordType(
                    record.headers(),
                    CaptureKafkaPublisher.LIVENESS_RECORD_TYPE
                ) || CaptureKafkaPublisher.isRecordType(
                    record.headers(),
                    org.opensearch.migrations.trafficcapture.protos.CaptureRecordTypes.CAPABILITY_PROBE_RECORD_TYPE
                )) {
                    var metadata = new RecordMetadata(
                        new TopicPartition(record.topic(), record.partition()),
                        0,
                        0,
                        1,
                        0,
                        0
                    );
                    callback.onCompletion(metadata, null);
                    return CompletableFuture.completedFuture(metadata);
                }
                callback.onCompletion(null, writeFailure);
                return CompletableFuture.failedFuture(writeFailure);
            }
        };
        var captureFailure = new AtomicReference<Throwable>();
        var factory = createFactory(producer, 1024 * 1024, captureFailure::set);
        var offloader = factory.createOffloader(createCtx());
        var payload = Unpooled.wrappedBuffer("captured".getBytes(StandardCharsets.UTF_8));

        offloader.addReadEvent(Instant.EPOCH, payload);
        var published = offloader.flushCommitAndResetStream(false);
        payload.release();

        var executionFailure = Assertions.assertThrows(
            ExecutionException.class,
            () -> published.get(5, TimeUnit.SECONDS)
        );
        Assertions.assertEquals(writeFailure, executionFailure.getCause());
        Assertions.assertEquals(writeFailure, captureFailure.get());
        Assertions.assertThrows(
            IllegalStateException.class,
            () -> factory.createOffloader(createCtx())
        );
        factory.close();
    }

    @Test
    public void producerErrorReportsAnUnstableProcessInsteadOfApplyingCaptureFailurePolicy()
        throws Exception {
        var unstableError = new AssertionError("producer owner failed");
        var topicName = KafkaCaptureFactory.DEFAULT_TOPIC_NAME_FOR_TRAFFIC;
        var topicPartitions = partitionInfo(topicName, 4);
        var producer = new LogAppendTimeMockProducer(
            new Cluster("test", leaders(topicPartitions), topicPartitions, Set.of(), Set.of()),
            true,
            null,
            new StringSerializer(),
            new ByteArraySerializer()
        ) {
            @Override
            public synchronized java.util.concurrent.Future<RecordMetadata> send(
                ProducerRecord<String, byte[]> record,
                Callback callback
            ) {
                if (CaptureKafkaPublisher.isRecordType(
                    record.headers(),
                    CaptureKafkaPublisher.LIVENESS_RECORD_TYPE
                ) || CaptureKafkaPublisher.isRecordType(
                    record.headers(),
                    org.opensearch.migrations.trafficcapture.protos.CaptureRecordTypes.CAPABILITY_PROBE_RECORD_TYPE
                )) {
                    var metadata = new RecordMetadata(
                        new TopicPartition(record.topic(), record.partition()),
                        0,
                        0,
                        1,
                        0,
                        0
                    );
                    callback.onCompletion(metadata, null);
                    return CompletableFuture.completedFuture(metadata);
                }
                throw unstableError;
            }
        };
        var captureFailure = new AtomicReference<Throwable>();
        var unstableFailure = new AtomicReference<Throwable>();
        var factory = createFactory(
            producer,
            1024 * 1024,
            captureFailure::set,
            unstableFailure::set
        );
        var offloader = factory.createOffloader(createCtx());
        var payload = Unpooled.wrappedBuffer("captured".getBytes(StandardCharsets.UTF_8));

        offloader.addReadEvent(Instant.EPOCH, payload);
        var published = offloader.flushCommitAndResetStream(false);
        payload.release();

        var executionFailure = Assertions.assertThrows(
            ExecutionException.class,
            () -> published.get(5, TimeUnit.SECONDS)
        );
        Assertions.assertEquals(unstableError, executionFailure.getCause());
        Assertions.assertEquals(unstableError, unstableFailure.get());
        Assertions.assertEquals(null, captureFailure.get());
        factory.close();
    }

    @SneakyThrows
    private KafkaCaptureFactory createFactory(Producer<String, byte[]> producer, int messageSize) {
        return createFactory(producer, messageSize, ignored -> {});
    }

    @SneakyThrows
    private KafkaCaptureFactory createFactory(
        Producer<String, byte[]> producer,
        int messageSize,
        Consumer<Throwable> captureFailureCallback
    ) {
        return createFactory(producer, messageSize, captureFailureCallback, ignored -> {});
    }

    @SneakyThrows
    private KafkaCaptureFactory createFactory(
        Producer<String, byte[]> producer,
        int messageSize,
        Consumer<Throwable> captureFailureCallback,
        Consumer<Throwable> unstableProcessFailureCallback
    ) {
        var topicName = KafkaCaptureFactory.DEFAULT_TOPIC_NAME_FOR_TRAFFIC;
        var partitionInfo = partitionInfo(topicName, 4);
        var membershipConsumer = new MockConsumer<String, byte[]>(OffsetResetStrategy.EARLIEST);
        var topicPartitions = partitionInfo.stream()
            .map(info -> new TopicPartition(info.topic(), info.partition()))
            .toList();
        membershipConsumer.updateBeginningOffsets(
            topicPartitions.stream().collect(Collectors.toMap(partition -> partition, ignored -> 0L))
        );
        membershipConsumer.schedulePollTask(() -> membershipConsumer.rebalance(topicPartitions));
        var factory = new KafkaCaptureFactory(
            TestRootKafkaOffloaderContext.noTracking(),
            TEST_NODE_ID_STRING,
            producer,
            membershipConsumer,
            assignmentTracker(),
            1,
            topicName,
            messageSize,
            KafkaCaptureFactory.DEFAULT_LIVENESS_SNAPSHOT_INTERVAL,
            captureFailureCallback,
            unstableProcessFailureCallback
        );
        factory.publisherReady().get(5, TimeUnit.SECONDS);
        return factory;
    }

    private MockProducer<String, byte[]> createMockProducer(boolean autoComplete) {
        var partitionInfo = partitionInfo(KafkaCaptureFactory.DEFAULT_TOPIC_NAME_FOR_TRAFFIC, 4);
        var cluster = new Cluster("test", leaders(partitionInfo), partitionInfo, Set.of(), Set.of());
        return new LogAppendTimeMockProducer(
            cluster,
            autoComplete,
            null,
            new StringSerializer(),
            new ByteArraySerializer()
        );
    }

    private ControllableProducer createControllableProducer() {
        var partitionInfo = partitionInfo(KafkaCaptureFactory.DEFAULT_TOPIC_NAME_FOR_TRAFFIC, 4);
        var cluster = new Cluster("test", leaders(partitionInfo), partitionInfo, Set.of(), Set.of());
        return new ControllableProducer(cluster);
    }

    @FunctionalInterface
    private interface SendHandler {
        Future<RecordMetadata> send(ProducerRecord<String, byte[]> record, Callback callback);
    }

    private static class ControllableProducer extends LogAppendTimeMockProducer {
        private volatile SendHandler sendHandler;

        private ControllableProducer(Cluster cluster) {
            super(cluster, true, null, new StringSerializer(), new ByteArraySerializer());
        }

        private void setSendHandler(SendHandler sendHandler) {
            this.sendHandler = sendHandler;
        }

        @Override
        public synchronized Future<RecordMetadata> send(
            ProducerRecord<String, byte[]> record,
            Callback callback
        ) {
            var currentHandler = sendHandler;
            return currentHandler == null
                ? super.send(record, callback)
                : currentHandler.send(record, callback);
        }
    }

    private static class LogAppendTimeMockProducer extends MockProducer<String, byte[]> {
        private LogAppendTimeMockProducer(
            Cluster cluster,
            boolean autoComplete,
            org.apache.kafka.clients.producer.Partitioner partitioner,
            StringSerializer keySerializer,
            ByteArraySerializer valueSerializer
        ) {
            super(cluster, autoComplete, partitioner, keySerializer, valueSerializer);
        }

        private LogAppendTimeMockProducer(
            boolean autoComplete,
            org.apache.kafka.clients.producer.Partitioner partitioner,
            StringSerializer keySerializer,
            ByteArraySerializer valueSerializer
        ) {
            super(autoComplete, partitioner, keySerializer, valueSerializer);
        }

        @Override
        public synchronized java.util.concurrent.Future<RecordMetadata> send(
            ProducerRecord<String, byte[]> record,
            Callback callback
        ) {
            return super.send(record, (metadata, failure) -> {
                if (failure != null || metadata == null) {
                    callback.onCompletion(metadata, failure);
                    return;
                }
                var brokerTimestamp = record.timestamp() != null && record.timestamp() > 0
                    ? record.timestamp()
                    : 1L;
                callback.onCompletion(
                    new RecordMetadata(
                        new TopicPartition(metadata.topic(), metadata.partition()),
                        metadata.offset(),
                        0,
                        brokerTimestamp,
                        metadata.serializedKeySize(),
                        metadata.serializedValueSize()
                    ),
                    null
                );
            });
        }
    }

    private static List<ProducerRecord<String, byte[]>> trafficRecords(
        MockProducer<String, byte[]> producer
    ) {
        return producer.history()
            .stream()
            .filter(record -> CaptureKafkaPublisher.isRecordType(
                record.headers(),
                CaptureKafkaPublisher.TRAFFIC_RECORD_TYPE
            ))
            .toList();
    }

    private static void awaitHistorySize(MockProducer<String, byte[]> producer, int expected)
        throws InterruptedException {
        awaitCondition(() -> producer.history().size() >= expected);
        Assertions.assertEquals(expected, producer.history().size());
    }

    private static void awaitCondition(java.util.function.BooleanSupplier condition)
        throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            Thread.sleep(1);
        }
        Assertions.assertTrue(condition.getAsBoolean());
    }

    private static List<PartitionInfo> partitionInfo(String topicName, int count) {
        var leaders = List.of(
            new Node(0, "broker-0", 9092),
            new Node(1, "broker-1", 9092)
        );
        return java.util.stream.IntStream.range(0, count)
            .mapToObj(partition -> {
                var leader = leaders.get(partition % leaders.size());
                return new PartitionInfo(
                    topicName,
                    partition,
                    leader,
                    new Node[] { leader },
                    new Node[] { leader }
                );
            })
            .toList();
    }

    private static PartitionInfo partitionInfo(String topicName, int partition, Node leader) {
        return new PartitionInfo(
            topicName,
            partition,
            leader,
            new Node[] { leader },
            new Node[] { leader }
        );
    }

    private static List<Node> leaders(List<PartitionInfo> partitionInfo) {
        return partitionInfo.stream()
            .map(PartitionInfo::leader)
            .distinct()
            .toList();
    }

    private static CaptureMembershipAssignmentTracker assignmentTracker() {
        var tracker = new CaptureMembershipAssignmentTracker();
        tracker.replaceMembers(Set.of(TEST_NODE_ID_STRING));
        return tracker;
    }
}
