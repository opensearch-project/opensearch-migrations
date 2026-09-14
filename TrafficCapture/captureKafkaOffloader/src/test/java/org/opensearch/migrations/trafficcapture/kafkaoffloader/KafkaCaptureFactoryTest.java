package org.opensearch.migrations.trafficcapture.kafkaoffloader;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import org.opensearch.migrations.trafficcapture.IChannelConnectionCaptureSerializer;
import org.opensearch.migrations.trafficcapture.kafkaoffloader.tracing.TestRootKafkaOffloaderContext;
import org.opensearch.migrations.trafficcapture.protos.CaptureRecord;
import org.opensearch.migrations.trafficcapture.protos.TrafficStream;
import org.opensearch.migrations.trafficcapture.tracing.ConnectionContext;

import com.google.protobuf.InvalidProtocolBufferException;
import io.netty.buffer.Unpooled;
import io.netty.util.concurrent.DefaultEventExecutor;
import org.apache.kafka.clients.consumer.MockConsumer;
import org.apache.kafka.clients.consumer.OffsetResetStrategy;
import org.apache.kafka.clients.producer.Callback;
import org.apache.kafka.clients.producer.MockProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.Cluster;
import org.apache.kafka.common.Node;
import org.apache.kafka.common.PartitionInfo;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KafkaCaptureFactoryTest {
    private static final String ACTIVATION_ID = "capture-activation";
    private static final String TOPIC = KafkaCaptureFactory.DEFAULT_TOPIC_NAME_FOR_TRAFFIC;
    private static final int MAXIMUM_KAFKA_MESSAGE_SIZE = 1024 * 1024;
    private static final Duration TEST_TIMEOUT = Duration.ofSeconds(5);

    @Test
    void defaultFactoryIntervalsMatchTheCaptureProtocol() {
        assertEquals(
            Duration.ofSeconds(5),
            KafkaCaptureFactory.DEFAULT_TRAFFIC_STREAM_FLUSH_INTERVAL
        );
        assertEquals(
            Duration.ofSeconds(10),
            KafkaCaptureFactory.DEFAULT_HEARTBEAT_INTERVAL
        );
        assertEquals(
            Duration.ofSeconds(30),
            KafkaCaptureFactory.DEFAULT_HEARTBEAT_EXPIRATION_INTERVAL
        );
    }

    @Test
    void startupCapabilityProbesAreAcknowledgedBeforeMembershipStarts() throws Exception {
        var producer = new ProtocolProducer(partitionInfo(TOPIC, 4));
        producer.holdCapabilityProbes = true;
        var consumer = configuredConsumer(TOPIC, 4);
        var factory = newFactory(producer, consumer, ignored -> {}, ignored -> {});
        try {
            awaitRecordCount(producer, CaptureRecord.PayloadCase.CAPTURECAPABILITYPROBE, 2);

            assertEquals(Set.of(), consumer.subscription());
            for (var record : recordsOfType(
                producer,
                CaptureRecord.PayloadCase.CAPTURECAPABILITYPROBE
            )) {
                assertEquals(0, record.headers().toArray().length);
                var probe = envelope(record).getCaptureCapabilityProbe();
                assertEquals(ACTIVATION_ID + ":PROBE", probe.getWriterNodeId());
                assertFalse(probe.getProbeId().isBlank());
            }

            producer.completeAllCapabilityProbes();

            awaitCondition(() -> consumer.subscription().equals(Set.of(TOPIC)));
            factory.readyForConnections().get(TEST_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            assertEquals(
                2,
                recordsOfType(producer, CaptureRecord.PayloadCase.CAPTURECAPABILITYPROBE).size()
            );
        } finally {
            factory.close();
        }
    }

    @Test
    void firstAssignmentIsNotUsableUntilEveryInitialHeartbeatIsAcknowledged() throws Exception {
        var producer = new ProtocolProducer(partitionInfo(TOPIC, 4));
        producer.holdHeartbeats = true;
        var consumer = configuredConsumer(TOPIC, 4);
        var factory = newFactory(producer, consumer, ignored -> {}, ignored -> {});
        try {
            awaitRecordCount(producer, CaptureRecord.PayloadCase.WRITERPARTITIONHEARTBEAT, 4);
            assertFalse(factory.readyForConnections().isDone());

            for (int i = 0; i < 3; ++i) {
                producer.completeNextHeartbeat();
            }
            assertFalse(factory.readyForConnections().isDone());

            producer.completeNextHeartbeat();
            factory.readyForConnections().get(TEST_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);

            var heartbeats = recordsOfType(
                producer,
                CaptureRecord.PayloadCase.WRITERPARTITIONHEARTBEAT
            );
            assertEquals(
                List.of(0, 1, 2, 3),
                heartbeats.stream().map(ProducerRecord::partition).sorted().toList()
            );
            for (var record : heartbeats) {
                assertEquals(0, record.headers().toArray().length);
                var heartbeat = envelope(record).getWriterPartitionHeartbeat();
                assertEquals(ACTIVATION_ID + ":1", heartbeat.getWriterNodeId());
                assertEquals(
                    KafkaCaptureFactory.DEFAULT_HEARTBEAT_INTERVAL.toMillis(),
                    heartbeat.getHeartbeatIntervalMillis()
                );
            }
        } finally {
            factory.close();
        }
    }

    @Test
    void trafficUsesTheCaptureRecordEnvelopeWithoutHeadersOrPartitionFields() throws Exception {
        try (var harness = readyHarness()) {
            var offloader = harness.createOffloader("connection-a");
            var payload = Unpooled.wrappedBuffer(
                "GET / HTTP/1.1\r\n\r\n".getBytes(StandardCharsets.UTF_8)
            );
            try {
                var publication = harness.onEventLoop(() -> {
                    offloader.addReadEvent(Instant.EPOCH, payload);
                    offloader.addCloseEvent(Instant.EPOCH.plusMillis(1));
                    return offloader.flushCommitAndResetStream(true);
                });
                publication.get(TEST_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            } finally {
                payload.release();
            }

            var trafficRecords = recordsOfType(
                harness.producer,
                CaptureRecord.PayloadCase.TRAFFICSTREAM
            );
            assertEquals(1, trafficRecords.size());
            var kafkaRecord = trafficRecords.get(0);
            assertEquals(0, kafkaRecord.headers().toArray().length);
            assertEquals("connection-a", kafkaRecord.key());

            var trafficStream = envelope(kafkaRecord).getTrafficStream();
            assertEquals("connection-a", trafficStream.getConnectionId());
            assertEquals(ACTIVATION_ID + ":1", trafficStream.getNodeId());
            assertNull(TrafficStream.getDescriptor().findFieldByName("partition"));
            assertEquals(List.of(1L, 2L), observationSequences(trafficStream));
            assertTrue(trafficStream.getSubStream(0).hasRead());
            assertTrue(trafficStream.getSubStream(1).hasClose());
            assertTrue(trafficStream.hasNumberOfThisLastChunk());
            assertEquals(
                kafkaRecord.partition(),
                harness.producer.acknowledgedPartition(kafkaRecord)
            );
        }
    }

    @Test
    void recordsForOneConnectionPublishAndAcknowledgeInOrder() throws Exception {
        var producer = new ProtocolProducer(partitionInfo(TOPIC, 4));
        try (var harness = readyHarness(producer, ignored -> {}, ignored -> {})) {
            producer.holdTraffic = true;
            var offloader = harness.createOffloader("ordered-connection");
            var firstPayload = Unpooled.wrappedBuffer("request".getBytes(StandardCharsets.UTF_8));
            CompletableFuture<RecordMetadata> firstPublication;
            CompletableFuture<RecordMetadata> finalPublication;
            try {
                firstPublication = harness.onEventLoop(() -> {
                    offloader.addReadEvent(Instant.EPOCH, firstPayload);
                    return offloader.flushCommitAndResetStream(false);
                });
                finalPublication = harness.onEventLoop(() -> {
                    offloader.addCloseEvent(Instant.EPOCH.plusMillis(1));
                    return offloader.flushCommitAndResetStream(true);
                });
            } finally {
                firstPayload.release();
            }

            awaitRecordCount(producer, CaptureRecord.PayloadCase.TRAFFICSTREAM, 1);
            assertFalse(firstPublication.isDone());
            assertFalse(finalPublication.isDone());

            var firstAcknowledgement = producer.completeNextTraffic();
            firstPublication.get(TEST_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            harness.onEventLoop(() -> {
                offloader.validateCriticalMutationTrafficAcknowledgement(firstAcknowledgement);
                return null;
            });

            awaitRecordCount(producer, CaptureRecord.PayloadCase.TRAFFICSTREAM, 2);
            assertFalse(finalPublication.isDone());
            producer.completeNextTraffic();
            finalPublication.get(TEST_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);

            var trafficRecords = recordsOfType(producer, CaptureRecord.PayloadCase.TRAFFICSTREAM);
            var first = envelope(trafficRecords.get(0)).getTrafficStream();
            var second = envelope(trafficRecords.get(1)).getTrafficStream();
            assertEquals(List.of(1L), observationSequences(first));
            assertEquals(List.of(2L), observationSequences(second));
            assertTrue(first.getSubStream(0).hasRead());
            assertTrue(second.getSubStream(0).hasClose());
            assertFalse(first.hasNumberOfThisLastChunk());
            assertTrue(second.hasNumberOfThisLastChunk());
        }
    }

    @Test
    void staleCriticalMutationAcknowledgementPermanentlyFailsCapture() throws Exception {
        var captureFailure = new AtomicReference<Throwable>();
        var producer = new ProtocolProducer(partitionInfo(TOPIC, 4));
        try (var harness = readyHarness(producer, captureFailure::set, ignored -> {})) {
            producer.holdTraffic = true;
            var offloader = harness.createOffloader("stale-ack");
            var payload = Unpooled.wrappedBuffer("mutation".getBytes(StandardCharsets.UTF_8));
            CompletableFuture<RecordMetadata> publication;
            try {
                publication = harness.onEventLoop(() -> {
                    offloader.addReadEvent(Instant.EPOCH, payload);
                    return offloader.flushCommitAndResetStream(false);
                });
            } finally {
                payload.release();
            }

            awaitRecordCount(producer, CaptureRecord.PayloadCase.TRAFFICSTREAM, 1);
            var trafficRecord = recordsOfType(
                producer,
                CaptureRecord.PayloadCase.TRAFFICSTREAM
            ).get(0);
            var writerNodeId = envelope(trafficRecord).getTrafficStream().getNodeId();
            var heartbeatTimestamp = producer.lastHeartbeatTimestamp(
                writerNodeId,
                trafficRecord.partition()
            );
            var staleAcknowledgement = producer.completeNextTrafficAt(
                heartbeatTimestamp
                    + KafkaCaptureFactory.DEFAULT_HEARTBEAT_EXPIRATION_INTERVAL.toMillis()
            );
            publication.get(TEST_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);

            var validation = assertThrows(
                ExecutionException.class,
                () -> harness.onEventLoop(() -> {
                    offloader.validateCriticalMutationTrafficAcknowledgement(staleAcknowledgement);
                    return null;
                })
            );
            var failure = assertInstanceOf(IllegalStateException.class, validation.getCause());
            assertSame(failure, captureFailure.get());
            assertThrows(
                IllegalStateException.class,
                () -> harness.factory.createOffloader(connectionContext("another-connection"))
            );
        }
    }

    @Test
    void kafkaTrafficFailureInvokesTheCaptureFailurePolicyAndStopsNewConnections()
        throws Exception {
        var writeFailure = new IllegalStateException("producer write failed");
        var captureFailure = new AtomicReference<Throwable>();
        var unstableFailure = new AtomicReference<Throwable>();
        var producer = new ProtocolProducer(partitionInfo(TOPIC, 4));
        try (var harness = readyHarness(producer, captureFailure::set, unstableFailure::set)) {
            producer.nextTrafficFailure = writeFailure;
            var offloader = harness.createOffloader("failed-write");
            var payload = Unpooled.wrappedBuffer("captured".getBytes(StandardCharsets.UTF_8));
            CompletableFuture<RecordMetadata> publication;
            try {
                publication = harness.onEventLoop(() -> {
                    offloader.addReadEvent(Instant.EPOCH, payload);
                    return offloader.flushCommitAndResetStream(false);
                });
            } finally {
                payload.release();
            }

            var observed = assertThrows(
                ExecutionException.class,
                () -> publication.get(TEST_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)
            );
            assertSame(writeFailure, observed.getCause());
            awaitCondition(() -> captureFailure.get() != null);
            assertSame(writeFailure, captureFailure.get());
            assertNull(unstableFailure.get());
            assertThrows(
                IllegalStateException.class,
                () -> harness.factory.createOffloader(connectionContext("after-failure"))
            );
        }
    }

    @Test
    void producerErrorInvokesTheUnstableProcessPolicyInsteadOfTheCaptureFailurePolicy()
        throws Exception {
        var producerError = new AssertionError("producer owner failed");
        var captureFailure = new AtomicReference<Throwable>();
        var unstableFailure = new AtomicReference<Throwable>();
        var producer = new ProtocolProducer(partitionInfo(TOPIC, 4));
        try (var harness = readyHarness(producer, captureFailure::set, unstableFailure::set)) {
            producer.nextTrafficError = producerError;
            var offloader = harness.createOffloader("unstable-write");
            var payload = Unpooled.wrappedBuffer("captured".getBytes(StandardCharsets.UTF_8));
            CompletableFuture<RecordMetadata> publication;
            try {
                publication = harness.onEventLoop(() -> {
                    offloader.addReadEvent(Instant.EPOCH, payload);
                    return offloader.flushCommitAndResetStream(false);
                });
            } finally {
                payload.release();
            }

            var observed = assertThrows(
                ExecutionException.class,
                () -> publication.get(TEST_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)
            );
            assertSame(producerError, observed.getCause());
            awaitCondition(() -> unstableFailure.get() != null);
            assertSame(producerError, unstableFailure.get());
            assertNull(captureFailure.get());
        }
    }

    @Test
    void finalRecordWithoutCloseObservationReportsAnUnstableProcess() throws Exception {
        var unstableFailure = new AtomicReference<Throwable>();
        try (var harness = readyHarness(
            new ProtocolProducer(partitionInfo(TOPIC, 4)),
            ignored -> {},
            unstableFailure::set
        )) {
            var offloader = harness.createOffloader("missing-close");

            var publication = harness.onEventLoop(
                () -> offloader.flushCommitAndResetStream(true)
            );
            var observed = assertThrows(
                ExecutionException.class,
                () -> publication.get(TEST_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)
            );

            assertInstanceOf(IllegalStateException.class, observed.getCause());
            assertSame(observed.getCause(), unstableFailure.get());
            assertEquals(
                List.of(),
                recordsOfType(harness.producer, CaptureRecord.PayloadCase.TRAFFICSTREAM)
            );
        }
    }

    @Test
    void closingBeforeStartupQualificationClosesUntransferredKafkaResourcesOnce()
        throws Exception {
        var producer = new ProtocolProducer(partitionInfo(TOPIC, 4));
        producer.holdCapabilityProbes = true;
        var consumer = configuredConsumer(TOPIC, 4);
        var factory = newFactory(producer, consumer, ignored -> {}, ignored -> {});

        awaitRecordCount(producer, CaptureRecord.PayloadCase.CAPTURECAPABILITYPROBE, 2);
        factory.close();
        factory.close();

        assertEquals(1, producer.closeCalls.get());
        assertEquals(1, consumer.closeCalls.get());
        assertTrue(consumer.closed());
        assertThrows(
            ExecutionException.class,
            () -> factory.readyForConnections().get(
                TEST_TIMEOUT.toMillis(),
                TimeUnit.MILLISECONDS
            )
        );
    }

    @Test
    void closingAfterReadinessClosesTransferredKafkaResourcesOnce() throws Exception {
        var producer = new ProtocolProducer(partitionInfo(TOPIC, 4));
        var consumer = configuredConsumer(TOPIC, 4);
        var factory = newFactory(producer, consumer, ignored -> {}, ignored -> {});
        factory.readyForConnections().get(TEST_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);

        factory.close();
        factory.close();

        assertEquals(1, producer.closeCalls.get());
        assertEquals(1, consumer.closeCalls.get());
        assertTrue(consumer.closed());
        assertTrue(producer.closed());
    }

    @Test
    void membershipFailureBeforeTheFirstAssignmentClosesTheInitializingPublisher()
        throws Exception {
        var producer = new ProtocolProducer(partitionInfo(TOPIC, 4));
        var consumer = new TrackingMockConsumer();
        consumer.schedulePollTask(() -> {
            throw new IllegalStateException("membership initialization failed");
        });
        var captureFailure = new AtomicReference<Throwable>();
        var factory = newFactory(producer, consumer, captureFailure::set, ignored -> {});
        try {
            var observed = assertThrows(
                ExecutionException.class,
                () -> factory.readyForConnections().get(
                    TEST_TIMEOUT.toMillis(),
                    TimeUnit.MILLISECONDS
                )
            );
            assertEquals("membership initialization failed", observed.getCause().getMessage());
            awaitCondition(() -> captureFailure.get() != null);
            assertSame(observed.getCause(), captureFailure.get());
            awaitCondition(
                () -> producer.closeCalls.get() == 1 && consumer.closeCalls.get() == 1
            );
        } finally {
            factory.close();
        }
    }

    private static FactoryHarness readyHarness() throws Exception {
        return readyHarness(
            new ProtocolProducer(partitionInfo(TOPIC, 4)),
            ignored -> {},
            ignored -> {}
        );
    }

    private static FactoryHarness readyHarness(
        ProtocolProducer producer,
        Consumer<Throwable> captureFailureCallback,
        Consumer<Throwable> unstableProcessFailureCallback
    ) throws Exception {
        var consumer = configuredConsumer(TOPIC, 4);
        var factory = newFactory(
            producer,
            consumer,
            captureFailureCallback,
            unstableProcessFailureCallback
        );
        factory.readyForConnections().get(TEST_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        return new FactoryHarness(producer, consumer, factory);
    }

    private static KafkaCaptureFactory newFactory(
        ProtocolProducer producer,
        TrackingMockConsumer consumer,
        Consumer<Throwable> captureFailureCallback,
        Consumer<Throwable> unstableProcessFailureCallback
    ) {
        return new KafkaCaptureFactory(
            TestRootKafkaOffloaderContext.noTracking(),
            ACTIVATION_ID,
            producer,
            consumer,
            TOPIC,
            MAXIMUM_KAFKA_MESSAGE_SIZE,
            captureFailureCallback,
            unstableProcessFailureCallback
        );
    }

    private static TrackingMockConsumer configuredConsumer(String topic, int partitionCount) {
        var consumer = new TrackingMockConsumer();
        var partitions = java.util.stream.IntStream.range(0, partitionCount)
            .mapToObj(partition -> new TopicPartition(topic, partition))
            .toList();
        consumer.updateBeginningOffsets(
            partitions.stream().collect(Collectors.toMap(partition -> partition, ignored -> 0L))
        );
        consumer.schedulePollTask(() -> consumer.rebalance(partitions));
        return consumer;
    }

    private static ConnectionContext connectionContext(String connectionId) {
        return new ConnectionContext(
            TestRootKafkaOffloaderContext.noTracking(),
            connectionId,
            "source-node"
        );
    }

    private static CaptureRecord envelope(ProducerRecord<String, byte[]> record) {
        try {
            return CaptureRecord.parseFrom(record.value());
        } catch (InvalidProtocolBufferException e) {
            throw new AssertionError("Kafka value was not a CaptureRecord", e);
        }
    }

    private static List<Long> observationSequences(TrafficStream trafficStream) {
        return trafficStream.getSubStreamList()
            .stream()
            .map(observation -> observation.getConnectionObservationSequence())
            .toList();
    }

    private static List<ProducerRecord<String, byte[]>> recordsOfType(
        ProtocolProducer producer,
        CaptureRecord.PayloadCase payloadCase
    ) {
        return producer.history()
            .stream()
            .filter(record -> envelope(record).getPayloadCase() == payloadCase)
            .toList();
    }

    private static void awaitRecordCount(
        ProtocolProducer producer,
        CaptureRecord.PayloadCase payloadCase,
        int expected
    ) throws InterruptedException {
        awaitCondition(() -> recordsOfType(producer, payloadCase).size() >= expected);
        assertEquals(expected, recordsOfType(producer, payloadCase).size());
    }

    private static void awaitCondition(java.util.function.BooleanSupplier condition)
        throws InterruptedException {
        var deadline = System.nanoTime() + TEST_TIMEOUT.toNanos();
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            Thread.sleep(1);
        }
        assertTrue(condition.getAsBoolean());
    }

    private static List<PartitionInfo> partitionInfo(String topic, int count) {
        var leaders = List.of(
            new Node(0, "broker-0", 9092),
            new Node(1, "broker-1", 9092)
        );
        return java.util.stream.IntStream.range(0, count)
            .mapToObj(partition -> {
                var leader = leaders.get(partition % leaders.size());
                return new PartitionInfo(
                    topic,
                    partition,
                    leader,
                    new Node[] { leader },
                    new Node[] { leader }
                );
            })
            .toList();
    }

    private static List<Node> leaders(List<PartitionInfo> partitions) {
        return partitions.stream().map(PartitionInfo::leader).distinct().toList();
    }

    private static final class FactoryHarness implements AutoCloseable {
        private final ProtocolProducer producer;
        private final TrackingMockConsumer consumer;
        private final KafkaCaptureFactory factory;
        private final DefaultEventExecutor connectionEventLoop = new DefaultEventExecutor();
        private final AtomicReference<Throwable> asynchronousFailure = new AtomicReference<>();

        private FactoryHarness(
            ProtocolProducer producer,
            TrackingMockConsumer consumer,
            KafkaCaptureFactory factory
        ) {
            this.producer = producer;
            this.consumer = consumer;
            this.factory = factory;
        }

        private IChannelConnectionCaptureSerializer<RecordMetadata> createOffloader(
            String connectionId
        ) throws Exception {
            var offloader = factory.createOffloader(connectionContext(connectionId));
            onEventLoop(() -> {
                offloader.bindToConnectionEventLoop(
                    connectionEventLoop,
                    asynchronousFailure::set
                );
                return null;
            });
            return offloader;
        }

        private <T> T onEventLoop(Callable<T> action) throws Exception {
            return connectionEventLoop.submit(action)
                .get(TEST_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        }

        @Override
        public void close() {
            factory.close();
            connectionEventLoop.shutdownGracefully(0, 1, TimeUnit.SECONDS)
                .syncUninterruptibly();
            assertTrue(consumer.closed());
        }
    }

    private static final class TrackingMockConsumer extends MockConsumer<String, byte[]> {
        private final AtomicInteger closeCalls = new AtomicInteger();

        private TrackingMockConsumer() {
            super(OffsetResetStrategy.EARLIEST);
        }

        @Override
        public void close(Duration timeout) {
            closeCalls.incrementAndGet();
            super.close(timeout);
        }
    }

    private static final class ProtocolProducer extends MockProducer<String, byte[]> {
        private record PendingSend(
            ProducerRecord<String, byte[]> record,
            Callback callback,
            CompletableFuture<RecordMetadata> result
        ) {}

        private final Queue<PendingSend> pendingCapabilityProbes = new ArrayDeque<>();
        private final Queue<PendingSend> pendingHeartbeats = new ArrayDeque<>();
        private final Queue<PendingSend> pendingTraffic = new ArrayDeque<>();
        private final Map<String, Long> heartbeatTimestamps = new java.util.HashMap<>();
        private final Map<ProducerRecord<String, byte[]>, Integer> acknowledgedPartitions =
            new java.util.IdentityHashMap<>();
        private final AtomicInteger closeCalls = new AtomicInteger();
        private long nextBrokerTimestamp = 1_000;
        private long nextOffset;
        private volatile boolean holdCapabilityProbes;
        private volatile boolean holdHeartbeats;
        private volatile boolean holdTraffic;
        private volatile RuntimeException nextTrafficFailure;
        private volatile Error nextTrafficError;

        private ProtocolProducer(List<PartitionInfo> partitions) {
            super(
                new Cluster("test", leaders(partitions), partitions, Set.of(), Set.of()),
                true,
                null,
                new StringSerializer(),
                new ByteArraySerializer()
            );
        }

        @Override
        public synchronized Future<RecordMetadata> send(
            ProducerRecord<String, byte[]> record,
            Callback callback
        ) {
            super.send(record, (ignoredMetadata, ignoredFailure) -> {});
            var payloadCase = envelope(record).getPayloadCase();
            if (payloadCase == CaptureRecord.PayloadCase.TRAFFICSTREAM) {
                if (nextTrafficError != null) {
                    var failure = nextTrafficError;
                    nextTrafficError = null;
                    throw failure;
                }
                if (nextTrafficFailure != null) {
                    var failure = nextTrafficFailure;
                    nextTrafficFailure = null;
                    callback.onCompletion(null, failure);
                    return CompletableFuture.failedFuture(failure);
                }
            }

            var pending = new PendingSend(record, callback, new CompletableFuture<>());
            if (payloadCase == CaptureRecord.PayloadCase.CAPTURECAPABILITYPROBE
                && holdCapabilityProbes) {
                pendingCapabilityProbes.add(pending);
            } else if (payloadCase == CaptureRecord.PayloadCase.WRITERPARTITIONHEARTBEAT
                && holdHeartbeats) {
                pendingHeartbeats.add(pending);
            } else if (payloadCase == CaptureRecord.PayloadCase.TRAFFICSTREAM && holdTraffic) {
                pendingTraffic.add(pending);
            } else {
                complete(pending, nextBrokerTimestamp++);
            }
            return pending.result();
        }

        private synchronized void completeAllCapabilityProbes() {
            while (!pendingCapabilityProbes.isEmpty()) {
                complete(pendingCapabilityProbes.remove(), nextBrokerTimestamp++);
            }
        }

        private synchronized RecordMetadata completeNextHeartbeat() {
            return complete(pendingHeartbeats.remove(), nextBrokerTimestamp++);
        }

        private synchronized RecordMetadata completeNextTraffic() {
            return complete(pendingTraffic.remove(), nextBrokerTimestamp++);
        }

        private synchronized RecordMetadata completeNextTrafficAt(long timestamp) {
            return complete(pendingTraffic.remove(), timestamp);
        }

        private RecordMetadata complete(PendingSend pending, long timestamp) {
            var metadata = new RecordMetadata(
                new TopicPartition(pending.record().topic(), pending.record().partition()),
                nextOffset++,
                0,
                timestamp,
                pending.record().key() == null ? 0 : pending.record().key().length(),
                pending.record().value().length
            );
            var payload = envelope(pending.record());
            if (payload.hasWriterPartitionHeartbeat()) {
                var heartbeat = payload.getWriterPartitionHeartbeat();
                heartbeatTimestamps.put(
                    heartbeatKey(heartbeat.getWriterNodeId(), pending.record().partition()),
                    timestamp
                );
            }
            acknowledgedPartitions.put(pending.record(), metadata.partition());
            pending.callback().onCompletion(metadata, null);
            pending.result().complete(metadata);
            return metadata;
        }

        private synchronized long lastHeartbeatTimestamp(String writerNodeId, int partition) {
            return heartbeatTimestamps.get(heartbeatKey(writerNodeId, partition));
        }

        private synchronized int acknowledgedPartition(ProducerRecord<String, byte[]> record) {
            return acknowledgedPartitions.get(record);
        }

        private static String heartbeatKey(String writerNodeId, int partition) {
            return writerNodeId + ":" + partition;
        }

        @Override
        public synchronized void close(Duration timeout) {
            closeCalls.incrementAndGet();
            super.close(timeout);
        }
    }
}
