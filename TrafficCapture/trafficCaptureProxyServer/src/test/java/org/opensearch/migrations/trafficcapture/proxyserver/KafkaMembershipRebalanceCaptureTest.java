package org.opensearch.migrations.trafficcapture.proxyserver;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;

import org.opensearch.migrations.tracing.IContextTracker;
import org.opensearch.migrations.trafficcapture.IChannelConnectionCaptureSerializer;
import org.opensearch.migrations.trafficcapture.kafkaoffloader.CaptureKafkaPublisher;
import org.opensearch.migrations.trafficcapture.kafkaoffloader.CaptureMembershipAssignmentTracker;
import org.opensearch.migrations.trafficcapture.kafkaoffloader.KafkaCaptureFactory;
import org.opensearch.migrations.trafficcapture.kafkaoffloader.KafkaConfig;
import org.opensearch.migrations.trafficcapture.protos.TrafficRecord;
import org.opensearch.migrations.trafficcapture.proxyserver.testcontainers.KafkaContainerTestBase;
import org.opensearch.migrations.trafficcapture.proxyserver.testcontainers.annotations.KafkaContainerTest;

import io.netty.buffer.Unpooled;
import io.opentelemetry.api.OpenTelemetry;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.MockProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
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
    void rebalanceChangesOnlyNewConnectionRoutingAndDoesNotCompromiseCapture() throws Exception {
        var topic = "proxy-membership-" + UUID.randomUUID();
        var bootstrapServers = KAFKA.getContainer().getBootstrapServers();
        createTopic(bootstrapServers, topic);
        var rootContext = new RootCaptureContext(
            OpenTelemetry.noop(),
            IContextTracker.DO_NOTHING_TRACKER
        );
        var first = startFactory(rootContext, bootstrapServers, topic, "activation-a");
        try (
            first;
            var reader = new KafkaConsumer<String, byte[]>(readerProperties(bootstrapServers))
        ) {
            assignAllPartitions(reader, topic);

            var initialConnection = "initial-" + UUID.randomUUID();
            closeConnection(first.factory(), rootContext, initialConnection);
            var initialWriter = readWriter(reader, initialConnection);
            Assertions.assertEquals("activation-a:1", initialWriter);

            var connectionOpenedBeforeRebalance = "open-before-rebalance-" + UUID.randomUUID();
            var oldConnection = first.factory().createOffloader(
                rootContext.createConnectionContext(connectionOpenedBeforeRebalance, "proxy-a")
            );

            var second = startFactory(rootContext, bootstrapServers, topic, "activation-b");
            try (second) {
                String replacementWriter = null;
                for (int attempt = 0; attempt < 30 && replacementWriter == null; ++attempt) {
                    var connectionId = "after-second-member-" + attempt + "-" + UUID.randomUUID();
                    closeConnection(first.factory(), rootContext, connectionId);
                    var observedWriter = readWriter(reader, connectionId);
                    if (!initialWriter.equals(observedWriter)) {
                        replacementWriter = observedWriter;
                    } else {
                        Thread.sleep(100);
                    }
                }

                Assertions.assertTrue(replacementWriter.startsWith("activation-a:"), replacementWriter);
                Assertions.assertNotEquals(initialWriter, replacementWriter);
                Assertions.assertNull(first.captureFailure().get());
                Assertions.assertNull(first.unstableFailure().get());
                Assertions.assertNull(second.captureFailure().get());
                Assertions.assertNull(second.unstableFailure().get());

                closeOffloader(oldConnection);
                Assertions.assertEquals(
                    initialWriter,
                    readWriter(reader, connectionOpenedBeforeRebalance),
                    "A connection must retain the writer identity selected when it opened"
                );
            }
        } finally {
            deleteTopic(bootstrapServers, topic);
        }
    }

    @Test
    void coordinatorOutageDoesNotCompromiseCaptureAfterTheFirstAssignment() throws Exception {
        var topic = "proxy-membership-outage-" + UUID.randomUUID();
        var bootstrapServers = KAFKA.getContainer().getBootstrapServers();
        createTopic(bootstrapServers, topic);
        var rootContext = new RootCaptureContext(
            OpenTelemetry.noop(),
            IContextTracker.DO_NOTHING_TRACKER
        );
        var first = startFactoryWithRealMembership(
            rootContext,
            bootstrapServers,
            topic,
            "activation-outage-a"
        );
        try (first) {
            var initialConnection = "before-outage-" + UUID.randomUUID();
            closeConnection(first.factory(), rootContext, initialConnection);
            var initialWriter = writerFromMockProducer(first.producer(), initialConnection);
            Assertions.assertEquals("activation-outage-a:1", initialWriter);

            var connectionOpenedBeforeOutage = "open-before-outage-" + UUID.randomUUID();
            var oldConnection = first.factory().createOffloader(
                rootContext.createConnectionContext(connectionOpenedBeforeOutage, "proxy-a")
            );

            var containerId = KAFKA.getContainer().getContainerId();
            KAFKA.getContainer().getDockerClient().pauseContainerCmd(containerId).exec();
            try {
                // This exceeds the test consumer's Kafka group session timeout. It is not a
                // proxy membership-health deadline; capture must continue with the last assignment.
                Thread.sleep(TimeUnit.SECONDS.toMillis(9));
                var duringOutage = "during-outage-" + UUID.randomUUID();
                closeConnection(first.factory(), rootContext, duringOutage);
                Assertions.assertEquals(
                    initialWriter,
                    writerFromMockProducer(first.producer(), duringOutage)
                );
                Assertions.assertNull(first.captureFailure().get());
                Assertions.assertNull(first.unstableFailure().get());
            } finally {
                KAFKA.getContainer().getDockerClient().unpauseContainerCmd(containerId).exec();
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
                Assertions.assertNull(first.captureFailure().get());
                Assertions.assertNull(first.unstableFailure().get());
                Assertions.assertNull(second.captureFailure().get());
                Assertions.assertNull(second.unstableFailure().get());

                closeOffloader(oldConnection);
                Assertions.assertEquals(
                    initialWriter,
                    writerFromMockProducer(first.producer(), connectionOpenedBeforeOutage),
                    "A connection must retain its writer identity across membership loss and recovery"
                );
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
        var producerProperties = KafkaConfig.buildKafkaProperties(
            null,
            bootstrapServers,
            captureActivationId + "-producer",
            KafkaConfig.AUTH_TYPE_NONE,
            null,
            null
        );
        var producer = new KafkaProducer<String, byte[]>(producerProperties);
        var assignmentTracker = new CaptureMembershipAssignmentTracker();
        var parameters = new KafkaConfig.KafkaParameters();
        parameters.kafkaBrokers = bootstrapServers;
        parameters.kafkaClientId = captureActivationId;
        parameters.kafkaAuthType = KafkaConfig.AUTH_TYPE_NONE;
        var membershipConsumer = new KafkaConsumer<String, byte[]>(
            KafkaConfig.buildMembershipConsumerProperties(
                parameters,
                captureActivationId,
                topic,
                assignmentTracker
            )
        );
        var captureFailure = new AtomicReference<Throwable>();
        var unstableFailure = new AtomicReference<Throwable>();
        var factory = new KafkaCaptureFactory(
            rootContext,
            captureActivationId,
            producer,
            membershipConsumer,
            assignmentTracker,
            1,
            topic,
            1024 * 1024,
            Duration.ofSeconds(1),
            captureFailure::set,
            unstableFailure::set
        );
        CompletableFuture.supplyAsync(factory::getPublisher)
            .get(WAIT_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        return new FactoryHarness(factory, captureFailure, unstableFailure);
    }

    private static MockFactoryHarness startFactoryWithRealMembership(
        RootCaptureContext rootContext,
        String bootstrapServers,
        String topic,
        String captureActivationId
    ) throws Exception {
        var producer = mockProducer(topic, 4);
        var assignmentTracker = new CaptureMembershipAssignmentTracker();
        var parameters = new KafkaConfig.KafkaParameters();
        parameters.kafkaBrokers = bootstrapServers;
        parameters.kafkaClientId = captureActivationId;
        parameters.kafkaAuthType = KafkaConfig.AUTH_TYPE_NONE;
        var consumerProperties = KafkaConfig.buildMembershipConsumerProperties(
            parameters,
            captureActivationId,
            topic,
            assignmentTracker
        );
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
            assignmentTracker,
            1,
            topic,
            1024 * 1024,
            Duration.ofSeconds(1),
            captureFailure::set,
            unstableFailure::set
        );
        CompletableFuture.supplyAsync(factory::getPublisher)
            .get(WAIT_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        return new MockFactoryHarness(factory, producer, captureFailure, unstableFailure);
    }

    private static void closeConnection(
        KafkaCaptureFactory factory,
        RootCaptureContext rootContext,
        String connectionId
    ) throws Exception {
        var offloader = factory.createOffloader(
            rootContext.createConnectionContext(connectionId, "proxy")
        );
        var payload = Unpooled.wrappedBuffer(new byte[] { 1 });
        try {
            offloader.addReadEvent(Instant.now(), payload);
        } finally {
            payload.release();
        }
        closeOffloader(offloader);
    }

    private static void closeOffloader(
        IChannelConnectionCaptureSerializer<RecordMetadata> offloader
    ) throws Exception {
        offloader.addCloseEvent(Instant.now());
        offloader.flushCommitAndResetStream(true)
            .get(WAIT_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
    }

    private static String readWriter(
        KafkaConsumer<String, byte[]> reader,
        String connectionId
    ) throws Exception {
        var deadline = System.nanoTime() + WAIT_TIMEOUT.toNanos();
        while (System.nanoTime() < deadline) {
            for (var record : reader.poll(Duration.ofMillis(250))) {
                if (connectionId.equals(record.key())
                    && CaptureKafkaPublisher.isRecordType(
                        record.headers(),
                        CaptureKafkaPublisher.TRAFFIC_RECORD_TYPE
                    )) {
                    return TrafficRecord.parseFrom(record.value()).getWriterNodeId();
                }
            }
        }
        throw new AssertionError("Timed out waiting for TrafficRecord for " + connectionId);
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
            var connectionId = "after-membership-recovery-" + attempt++ + "-" + UUID.randomUUID();
            closeConnection(factory, rootContext, connectionId);
            var writer = writerFromMockProducer(producer, connectionId);
            if (!previousWriter.equals(writer)) {
                return writer;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("Timed out waiting for a replacement Kafka assignment");
    }

    private static String writerFromMockProducer(
        MockProducer<String, byte[]> producer,
        String connectionId
    ) throws Exception {
        for (int index = producer.history().size() - 1; index >= 0; --index) {
            var record = producer.history().get(index);
            if (connectionId.equals(record.key())
                && CaptureKafkaPublisher.isRecordType(
                    record.headers(),
                    CaptureKafkaPublisher.TRAFFIC_RECORD_TYPE
                )) {
                return TrafficRecord.parseFrom(record.value()).getWriterNodeId();
            }
        }
        throw new AssertionError("No TrafficRecord was published for " + connectionId);
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
            java.util.Set.of(),
            java.util.Set.of()
        );
        return new MockProducer<>(
            cluster,
            true,
            null,
            new StringSerializer(),
            new ByteArraySerializer()
        );
    }

    private record FactoryHarness(
        KafkaCaptureFactory factory,
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
}
