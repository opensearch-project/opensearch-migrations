package org.opensearch.migrations.trafficcapture.proxyserver;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;

import org.opensearch.migrations.tracing.IContextTracker;
import org.opensearch.migrations.trafficcapture.IChannelConnectionCaptureSerializer;
import org.opensearch.migrations.trafficcapture.kafkaoffloader.KafkaCaptureFactory;
import org.opensearch.migrations.trafficcapture.kafkaoffloader.KafkaConfig;
import org.opensearch.migrations.trafficcapture.protos.CaptureRecord;
import org.opensearch.migrations.trafficcapture.protos.TrafficStream;
import org.opensearch.migrations.trafficcapture.proxyserver.testcontainers.KafkaContainerTestBase;
import org.opensearch.migrations.trafficcapture.proxyserver.testcontainers.annotations.KafkaContainerTest;

import io.netty.buffer.Unpooled;
import io.netty.util.concurrent.DefaultEventExecutor;
import io.opentelemetry.api.OpenTelemetry;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.Callback;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.MockProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.Cluster;
import org.apache.kafka.common.Node;
import org.apache.kafka.common.PartitionInfo;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

@KafkaContainerTest
class KafkaMembershipRebalanceCaptureTest {
    private static final Duration WAIT_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration TEST_TRAFFIC_FLUSH_INTERVAL = Duration.ofMinutes(5);
    private static final Duration TEST_HEARTBEAT_INTERVAL = Duration.ofSeconds(10);
    private static final Duration TEST_HEARTBEAT_EXPIRATION = Duration.ofSeconds(30);
    private static final KafkaContainerTestBase KAFKA = new KafkaContainerTestBase();

    @BeforeAll
    static void startKafka() {
        KAFKA.start();
    }

    @AfterAll
    static void stopKafka() {
        KAFKA.stop();
    }

    @Test
    void rebalanceChangesOnlyNewConnectionRoutingAndPreservesExistingConnectionRoute()
        throws Exception {
        var topic = "proxy-membership-" + UUID.randomUUID();
        var bootstrapServers = KAFKA.getContainer().getBootstrapServers();
        createTopic(bootstrapServers, topic);
        var rootContext = rootContext();
        var first = startFactory(rootContext, bootstrapServers, topic, "activation-a");
        try (
            first;
            var reader = new KafkaConsumer<String, byte[]>(readerProperties(bootstrapServers))
        ) {
            assignAllPartitions(reader, topic);

            var initialConnection = "initial-" + UUID.randomUUID();
            closeConnection(first.factory(), rootContext, initialConnection);
            var initialTraffic = readTraffic(reader, initialConnection);
            var initialWriter = initialTraffic.trafficStream().getNodeId();
            Assertions.assertEquals("activation-a:1", initialWriter);

            var connectionOpenedBeforeRebalance =
                "open-before-rebalance-" + UUID.randomUUID();
            var existingConnection = OpenConnection.open(
                first.factory(),
                rootContext,
                connectionOpenedBeforeRebalance
            );
            try (existingConnection) {
                existingConnection.addReadByteAndFlush();
                var existingTrafficBeforeRebalance = readTraffic(
                    reader,
                    connectionOpenedBeforeRebalance
                );
                Assertions.assertEquals(
                    initialWriter,
                    existingTrafficBeforeRebalance.trafficStream().getNodeId()
                );

                var second = startFactory(
                    rootContext,
                    bootstrapServers,
                    topic,
                    "activation-b"
                );
                try (second) {
                    String replacementWriter = null;
                    for (int attempt = 0; attempt < 30 && replacementWriter == null; ++attempt) {
                        var connectionId =
                            "after-second-member-" + attempt + "-" + UUID.randomUUID();
                        closeConnection(first.factory(), rootContext, connectionId);
                        var observedWriter = readTraffic(reader, connectionId)
                            .trafficStream()
                            .getNodeId();
                        if (!initialWriter.equals(observedWriter)) {
                            replacementWriter = observedWriter;
                        } else {
                            Thread.sleep(100);
                        }
                    }

                    Assertions.assertNotNull(replacementWriter);
                    Assertions.assertTrue(
                        replacementWriter.startsWith("activation-a:"),
                        replacementWriter
                    );
                    Assertions.assertNotEquals(initialWriter, replacementWriter);

                    existingConnection.closeCapture();
                    var oldTrafficAfterRebalance = readTraffic(
                        reader,
                        connectionOpenedBeforeRebalance
                    );
                    Assertions.assertEquals(
                        initialWriter,
                        oldTrafficAfterRebalance.trafficStream().getNodeId(),
                        "A connection retains the writer identity selected when it opened"
                    );
                    Assertions.assertEquals(
                        existingTrafficBeforeRebalance.record().partition(),
                        oldTrafficAfterRebalance.record().partition(),
                        "A connection retains the Kafka partition selected when it opened"
                    );
                    assertNoFailures(first);
                    assertNoFailures(second);
                }
            }
        } finally {
            deleteTopic(bootstrapServers, topic);
        }
    }

    @Test
    void coordinatorOutageAndEmptyPollsDoNotInvalidateTheLastUsableAssignment()
        throws Exception {
        var topic = "proxy-membership-outage-" + UUID.randomUUID();
        var bootstrapServers = KAFKA.getContainer().getBootstrapServers();
        createTopic(bootstrapServers, topic);
        var rootContext = rootContext();
        var first = startFactoryWithRealMembership(
            rootContext,
            bootstrapServers,
            topic,
            "activation-outage-a"
        );
        try (first) {
            var initialConnection = "before-outage-" + UUID.randomUUID();
            closeConnection(first.factory(), rootContext, initialConnection);
            var initialTraffic = trafficFromMockProducer(first.producer(), initialConnection);
            var initialWriter = initialTraffic.trafficStream().getNodeId();
            Assertions.assertEquals("activation-outage-a:1", initialWriter);

            Thread.sleep(350);
            var afterEmptyPolls = "after-empty-polls-" + UUID.randomUUID();
            closeConnection(first.factory(), rootContext, afterEmptyPolls);
            Assertions.assertEquals(
                initialWriter,
                trafficFromMockProducer(first.producer(), afterEmptyPolls)
                    .trafficStream()
                    .getNodeId(),
                "Empty membership polls must not change or invalidate capture routing"
            );

            var connectionOpenedBeforeOutage =
                "open-before-outage-" + UUID.randomUUID();
            var existingConnection = OpenConnection.open(
                first.factory(),
                rootContext,
                connectionOpenedBeforeOutage
            );
            try (existingConnection) {
                var containerId = KAFKA.getContainer().getContainerId();
                KAFKA.getContainer().getDockerClient().pauseContainerCmd(containerId).exec();
                try {
                    // This exceeds the test consumer's Kafka group session timeout. Membership
                    // health is not a capture gate; the last usable assignment remains valid.
                    Thread.sleep(TimeUnit.SECONDS.toMillis(9));
                    var duringOutage = "during-outage-" + UUID.randomUUID();
                    closeConnection(first.factory(), rootContext, duringOutage);
                    Assertions.assertEquals(
                        initialWriter,
                        trafficFromMockProducer(first.producer(), duringOutage)
                            .trafficStream()
                            .getNodeId()
                    );
                    assertNoFailures(first);
                } finally {
                    KAFKA.getContainer().getDockerClient()
                        .unpauseContainerCmd(containerId)
                        .exec();
                }

                var second = startFactoryWithRealMembership(
                    rootContext,
                    bootstrapServers,
                    topic,
                    "activation-outage-b"
                );
                try (second) {
                    var replacementWriter = awaitReplacementWriter(
                        first.factory(),
                        first.producer(),
                        rootContext,
                        initialWriter
                    );
                    Assertions.assertTrue(
                        replacementWriter.startsWith("activation-outage-a:"),
                        replacementWriter
                    );
                    Assertions.assertNotEquals(initialWriter, replacementWriter);

                    existingConnection.closeCapture();
                    Assertions.assertEquals(
                        initialWriter,
                        trafficFromMockProducer(
                            first.producer(),
                            connectionOpenedBeforeOutage
                        ).trafficStream().getNodeId(),
                        "A connection retains its route across membership loss and recovery"
                    );
                    assertNoFailures(first);
                    assertNoFailures(second);
                }
            }
        } finally {
            deleteTopic(bootstrapServers, topic);
        }
    }

    @Test
    void staleAssignmentOverlapChangesLoadBalancingOnly() throws Exception {
        var topic = "proxy-stale-assignment-overlap-" + UUID.randomUUID();
        var bootstrapServers = KAFKA.getContainer().getBootstrapServers();
        createTopic(bootstrapServers, topic);
        var rootContext = rootContext();
        var first = startFactory(rootContext, bootstrapServers, topic, "activation-stale");
        try (
            first;
            var reader = new KafkaConsumer<String, byte[]>(readerProperties(bootstrapServers))
        ) {
            assignAllPartitions(reader, topic);

            first.membershipConsumer().wakeup();
            var second = startFactory(
                rootContext,
                bootstrapServers,
                topic,
                "activation-current"
            );
            try (second) {
                var sharedConnectionId = "same-routing-key-" + UUID.randomUUID();
                closeConnection(first.factory(), rootContext, sharedConnectionId);
                closeConnection(second.factory(), rootContext, sharedConnectionId);

                var records = readTraffic(reader, sharedConnectionId, 2);
                var stale = records.stream()
                    .filter(record -> record.trafficStream().getNodeId().startsWith(
                        "activation-stale:"
                    ))
                    .findFirst()
                    .orElseThrow();
                var current = records.stream()
                    .filter(record -> record.trafficStream().getNodeId().startsWith(
                        "activation-current:"
                    ))
                    .findFirst()
                    .orElseThrow();

                Assertions.assertEquals(
                    stale.record().partition(),
                    current.record().partition(),
                    "The same routing key may be accepted by a stale and a current assignment; "
                        + "distinct writer identities preserve capture correctness"
                );
                Assertions.assertNotEquals(
                    stale.trafficStream().getNodeId(),
                    current.trafficStream().getNodeId()
                );
                assertNoFailures(first);
                assertNoFailures(second);
            }
        } finally {
            deleteTopic(bootstrapServers, topic);
        }
    }

    private static FactoryHarness startFactory(
        RootCaptureContext rootContext,
        String bootstrapServers,
        String topic,
        String captureActivationId
    ) throws Exception {
        var producer = new KafkaProducer<String, byte[]>(
            KafkaConfig.buildKafkaProperties(
                null,
                bootstrapServers,
                captureActivationId + "-producer",
                KafkaConfig.AUTH_TYPE_NONE,
                null,
                null
            )
        );
        var parameters = membershipParameters(bootstrapServers, captureActivationId);
        var membershipConsumer = new KafkaConsumer<String, byte[]>(
            KafkaConfig.buildMembershipConsumerProperties(parameters, topic)
        );
        var captureFailure = new AtomicReference<Throwable>();
        var unstableFailure = new AtomicReference<Throwable>();
        var factory = new KafkaCaptureFactory(
            rootContext,
            captureActivationId,
            producer,
            membershipConsumer,
            topic,
            1024 * 1024,
            TEST_TRAFFIC_FLUSH_INTERVAL,
            TEST_HEARTBEAT_INTERVAL,
            TEST_HEARTBEAT_EXPIRATION,
            captureFailure::set,
            unstableFailure::set
        );
        factory.readyForConnections().get(WAIT_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        return new FactoryHarness(
            factory,
            membershipConsumer,
            captureFailure,
            unstableFailure
        );
    }

    private static MockFactoryHarness startFactoryWithRealMembership(
        RootCaptureContext rootContext,
        String bootstrapServers,
        String topic,
        String captureActivationId
    ) throws Exception {
        var producer = mockProducer(topic, 4);
        var parameters = membershipParameters(bootstrapServers, captureActivationId);
        var consumerProperties = KafkaConfig.buildMembershipConsumerProperties(parameters, topic);
        consumerProperties.put(ConsumerConfig.SESSION_TIMEOUT_MS_CONFIG, 6000);
        consumerProperties.put(ConsumerConfig.HEARTBEAT_INTERVAL_MS_CONFIG, 1000);
        var membershipConsumer = new KafkaConsumer<String, byte[]>(consumerProperties);
        var captureFailure = new AtomicReference<Throwable>();
        var unstableFailure = new AtomicReference<Throwable>();
        var factory = new KafkaCaptureFactory(
            rootContext,
            captureActivationId,
            producer,
            membershipConsumer,
            topic,
            1024 * 1024,
            TEST_TRAFFIC_FLUSH_INTERVAL,
            TEST_HEARTBEAT_INTERVAL,
            TEST_HEARTBEAT_EXPIRATION,
            captureFailure::set,
            unstableFailure::set
        );
        factory.readyForConnections().get(WAIT_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        return new MockFactoryHarness(
            factory,
            producer,
            captureFailure,
            unstableFailure
        );
    }

    private static KafkaConfig.KafkaParameters membershipParameters(
        String bootstrapServers,
        String captureActivationId
    ) {
        var parameters = new KafkaConfig.KafkaParameters();
        parameters.kafkaBrokers = bootstrapServers;
        parameters.kafkaClientId = captureActivationId;
        parameters.kafkaAuthType = KafkaConfig.AUTH_TYPE_NONE;
        return parameters;
    }

    private static void closeConnection(
        KafkaCaptureFactory factory,
        RootCaptureContext rootContext,
        String connectionId
    ) throws Exception {
        try (var connection = OpenConnection.open(factory, rootContext, connectionId)) {
            connection.addReadByte();
            connection.closeCapture();
        }
    }

    private static TrafficRecord readTraffic(
        KafkaConsumer<String, byte[]> reader,
        String connectionId
    ) throws Exception {
        return readTraffic(reader, connectionId, 1).getFirst();
    }

    private static List<TrafficRecord> readTraffic(
        KafkaConsumer<String, byte[]> reader,
        String connectionId,
        int expectedCount
    ) throws Exception {
        var matching = new java.util.ArrayList<TrafficRecord>();
        var deadline = System.nanoTime() + WAIT_TIMEOUT.toNanos();
        while (matching.size() < expectedCount && System.nanoTime() < deadline) {
            for (var record : reader.poll(Duration.ofMillis(250))) {
                assertNoHeadersAndValidEnvelope(record);
                var envelope = CaptureRecord.parseFrom(record.value());
                if (connectionId.equals(record.key()) && envelope.hasTrafficStream()) {
                    matching.add(new TrafficRecord(record, envelope.getTrafficStream()));
                }
            }
        }
        Assertions.assertEquals(expectedCount, matching.size());
        return List.copyOf(matching);
    }

    private static String awaitReplacementWriter(
        KafkaCaptureFactory factory,
        MockProducer<String, byte[]> producer,
        RootCaptureContext rootContext,
        String previousWriter
    ) throws Exception {
        var deadline = System.nanoTime() + WAIT_TIMEOUT.toNanos();
        int attempt = 0;
        while (System.nanoTime() < deadline) {
            var connectionId =
                "after-membership-recovery-" + attempt++ + "-" + UUID.randomUUID();
            closeConnection(factory, rootContext, connectionId);
            var writer = trafficFromMockProducer(producer, connectionId)
                .trafficStream()
                .getNodeId();
            if (!previousWriter.equals(writer)) {
                return writer;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("Timed out waiting for a replacement Kafka assignment");
    }

    private static TrafficRecord trafficFromMockProducer(
        MockProducer<String, byte[]> producer,
        String connectionId
    ) throws Exception {
        for (int index = producer.history().size() - 1; index >= 0; --index) {
            var record = producer.history().get(index);
            assertNoHeadersAndValidEnvelope(record);
            var envelope = CaptureRecord.parseFrom(record.value());
            if (connectionId.equals(record.key()) && envelope.hasTrafficStream()) {
                return new TrafficRecord(
                    new ConsumerRecord<>(
                        record.topic(),
                        record.partition(),
                        index,
                        record.key(),
                        record.value()
                    ),
                    envelope.getTrafficStream()
                );
            }
        }
        throw new AssertionError("No TrafficStream was published for " + connectionId);
    }

    private static void assertNoHeadersAndValidEnvelope(
        ConsumerRecord<String, byte[]> record
    ) throws Exception {
        Assertions.assertEquals(0, record.headers().toArray().length);
        Assertions.assertNotEquals(
            CaptureRecord.PayloadCase.PAYLOAD_NOT_SET,
            CaptureRecord.parseFrom(record.value()).getPayloadCase()
        );
    }

    private static void assertNoHeadersAndValidEnvelope(
        ProducerRecord<String, byte[]> record
    ) throws Exception {
        Assertions.assertEquals(0, record.headers().toArray().length);
        Assertions.assertNotEquals(
            CaptureRecord.PayloadCase.PAYLOAD_NOT_SET,
            CaptureRecord.parseFrom(record.value()).getPayloadCase()
        );
    }

    private static void assignAllPartitions(KafkaConsumer<String, byte[]> reader, String topic) {
        var partitions = reader.partitionsFor(topic)
            .stream()
            .map(info -> new TopicPartition(info.topic(), info.partition()))
            .toList();
        reader.assign(partitions);
        reader.seekToBeginning(partitions);
    }

    private static Properties readerProperties(String bootstrapServers) {
        var properties = new Properties();
        properties.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        properties.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        properties.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class);
        properties.put(ConsumerConfig.GROUP_ID_CONFIG, "capture-test-reader-" + UUID.randomUUID());
        properties.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        properties.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        return properties;
    }

    private static MockProducer<String, byte[]> mockProducer(String topic, int partitionCount) {
        var leaders = List.of(
            new Node(0, "broker-0", 9092),
            new Node(1, "broker-1", 9093)
        );
        var partitionInfo = IntStream.range(0, partitionCount)
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
        var cluster = new Cluster(
            "membership-test",
            leaders,
            partitionInfo,
            Set.of(),
            Set.of()
        );
        var brokerTimestamp = new AtomicLong(1_000);
        return new MockProducer<>(
            cluster,
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
                return super.send(record, (metadata, failure) -> {
                    if (failure != null || metadata == null) {
                        callback.onCompletion(metadata, failure);
                        return;
                    }
                    callback.onCompletion(
                        new RecordMetadata(
                            new TopicPartition(metadata.topic(), metadata.partition()),
                            metadata.offset(),
                            0,
                            brokerTimestamp.getAndIncrement(),
                            metadata.serializedKeySize(),
                            metadata.serializedValueSize()
                        ),
                        null
                    );
                });
            }
        };
    }

    private static RootCaptureContext rootContext() {
        return new RootCaptureContext(
            OpenTelemetry.noop(),
            IContextTracker.DO_NOTHING_TRACKER
        );
    }

    private static void createTopic(String bootstrapServers, String topic) throws Exception {
        try (var admin = AdminClient.create(adminProperties(bootstrapServers))) {
            admin.createTopics(List.of(
                new NewTopic(topic, 4, (short) 1).configs(
                    Map.of("message.timestamp.type", "LogAppendTime")
                )
            )).all().get(WAIT_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        }
    }

    private static void deleteTopic(String bootstrapServers, String topic) {
        try (var admin = AdminClient.create(adminProperties(bootstrapServers))) {
            admin.deleteTopics(List.of(topic)).all()
                .get(WAIT_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (Exception ignored) {
            // The shared test broker is stopped after the test class even if cleanup cannot finish.
        }
    }

    private static Properties adminProperties(String bootstrapServers) {
        var properties = new Properties();
        properties.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        return properties;
    }

    private static void assertNoFailures(FactoryHarness harness) {
        Assertions.assertNull(harness.captureFailure().get());
        Assertions.assertNull(harness.unstableFailure().get());
    }

    private static void assertNoFailures(MockFactoryHarness harness) {
        Assertions.assertNull(harness.captureFailure().get());
        Assertions.assertNull(harness.unstableFailure().get());
    }

    private record TrafficRecord(
        ConsumerRecord<String, byte[]> record,
        TrafficStream trafficStream
    ) {}

    private record FactoryHarness(
        KafkaCaptureFactory factory,
        KafkaConsumer<String, byte[]> membershipConsumer,
        AtomicReference<Throwable> captureFailure,
        AtomicReference<Throwable> unstableFailure
    ) implements AutoCloseable {
        @Override
        public void close() {
            factory.close();
        }
    }

    private record MockFactoryHarness(
        KafkaCaptureFactory factory,
        MockProducer<String, byte[]> producer,
        AtomicReference<Throwable> captureFailure,
        AtomicReference<Throwable> unstableFailure
    ) implements AutoCloseable {
        @Override
        public void close() {
            factory.close();
        }
    }

    private static final class OpenConnection implements AutoCloseable {
        private final IChannelConnectionCaptureSerializer<RecordMetadata> capture;
        private final DefaultEventExecutor eventLoop;
        private final AtomicReference<Throwable> asynchronousFailure = new AtomicReference<>();
        private boolean captureClosed;

        private OpenConnection(
            IChannelConnectionCaptureSerializer<RecordMetadata> capture,
            DefaultEventExecutor eventLoop
        ) {
            this.capture = capture;
            this.eventLoop = eventLoop;
        }

        static OpenConnection open(
            KafkaCaptureFactory factory,
            RootCaptureContext rootContext,
            String connectionId
        ) throws Exception {
            var eventLoop = new DefaultEventExecutor();
            var connection = new OpenConnection(
                factory.createOffloader(
                    rootContext.createConnectionContext(connectionId, "proxy")
                ),
                eventLoop
            );
            eventLoop.submit(() ->
                connection.capture.bindToConnectionEventLoop(
                    eventLoop,
                    connection.asynchronousFailure::set
                )
            ).get(WAIT_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            return connection;
        }

        void addReadByte() throws Exception {
            eventLoop.submit(() -> {
                var payload = Unpooled.wrappedBuffer(new byte[] { 1 });
                try {
                    capture.addReadEvent(Instant.now(), payload);
                } finally {
                    payload.release();
                }
                return null;
            }).get(WAIT_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        }

        void addReadByteAndFlush() throws Exception {
            var publication = eventLoop.submit(() -> {
                var payload = Unpooled.wrappedBuffer(new byte[] { 1 });
                try {
                    capture.addReadEvent(Instant.now(), payload);
                } finally {
                    payload.release();
                }
                return capture.flushCommitAndResetStream(false);
            }).get(WAIT_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            publication.get(WAIT_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            Assertions.assertNull(asynchronousFailure.get());
        }

        void closeCapture() throws Exception {
            if (captureClosed) {
                return;
            }
            captureClosed = true;
            var publication = eventLoop.submit(() -> {
                capture.addCloseEvent(Instant.now());
                return capture.flushCommitAndResetStream(true);
            }).get(WAIT_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            publication.get(WAIT_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            Assertions.assertNull(asynchronousFailure.get());
        }

        @Override
        public void close() throws Exception {
            try {
                closeCapture();
            } finally {
                eventLoop.shutdownGracefully().get(
                    WAIT_TIMEOUT.toMillis(),
                    TimeUnit.MILLISECONDS
                );
            }
        }
    }
}
