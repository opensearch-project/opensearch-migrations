package org.opensearch.migrations.trafficcapture.kafkaoffloader;

import java.time.Duration;
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
import org.opensearch.migrations.trafficcapture.protos.CaptureRecord;
import org.opensearch.migrations.trafficcapture.proxyserver.RootCaptureContext;
import org.opensearch.migrations.trafficcapture.proxyserver.testcontainers.KafkaContainerTestBase;
import org.opensearch.migrations.trafficcapture.proxyserver.testcontainers.annotations.KafkaContainerTest;

import io.opentelemetry.api.OpenTelemetry;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.NewTopic;
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
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

@KafkaContainerTest
class CaptureKafkaMembershipKafkaTest {
    private static final Duration WAIT_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration TEST_TRAFFIC_FLUSH_INTERVAL = Duration.ofMinutes(5);
    private static final Duration TEST_HEARTBEAT_INTERVAL = Duration.ofHours(1);
    private static final Duration TEST_HEARTBEAT_EXPIRATION = Duration.ofHours(2);
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
    void replacementAssignmentRemainsInactiveUntilEveryInitialHeartbeatIsAcknowledged()
        throws Exception {
        var topic = "proxy-heartbeat-ack-" + UUID.randomUUID();
        var bootstrapServers = KAFKA.getContainer().getBootstrapServers();
        createTopic(bootstrapServers, topic);
        var rootContext = rootContext();
        var first = startFactoryWithManualAcknowledgements(
            rootContext,
            bootstrapServers,
            topic,
            "activation-manual-a"
        );
        try (first) {
            var routingState = first.factory().getPublisher().getRoutingState();
            var initialWriter = routingState.currentWriterNodeId();
            Assertions.assertEquals("activation-manual-a:1", initialWriter);
            int initialHistorySize = first.producer().history().size();

            var second = startFactoryWithAutomaticAcknowledgements(
                rootContext,
                bootstrapServers,
                topic,
                "activation-manual-b"
            );
            try (second) {
                var pendingWriter = awaitPendingReplacementHeartbeat(
                    first.producer(),
                    initialHistorySize,
                    initialWriter
                );

                var duringReplacement = routingState.routeNewConnection(
                    "connection-routed-while-replacement-heartbeats-are-pending"
                );
                Assertions.assertEquals(
                    initialWriter,
                    duringReplacement.writerNodeId(),
                    "Revocation and a pending replacement must not invalidate the last usable assignment"
                );
                first.factory().getPublisher().abandonUnpublishedConnection(duringReplacement);
                Assertions.assertEquals(
                    initialWriter,
                    routingState.currentWriterNodeId(),
                    "A replacement is not usable before every initial heartbeat is acknowledged"
                );

                completePendingSendsUntil(
                    first.producer(),
                    () -> !initialWriter.equals(routingState.currentWriterNodeId())
                );

                var newRoute = routingState.routeNewConnection("connection-after-replacement");
                Assertions.assertEquals(pendingWriter, newRoute.writerNodeId());
                Assertions.assertNotEquals(initialWriter, newRoute.writerNodeId());
                Assertions.assertTrue(
                    routingState.assignedPartitions().contains(newRoute.partition())
                );
                first.factory().getPublisher().abandonUnpublishedConnection(newRoute);
                assertEveryRecordUsesEnvelopeWithoutHeaders(first.producer().history());
                Assertions.assertNull(first.captureFailure().get());
                Assertions.assertNull(first.unstableFailure().get());
                Assertions.assertNull(second.captureFailure().get());
                Assertions.assertNull(second.unstableFailure().get());

                drainPendingSends(first.producer());
            }
        } finally {
            deleteTopic(bootstrapServers, topic);
        }
    }

    @Test
    void capabilityProbeAcceptsLogAppendTimeAndRejectsCreateTime() throws Exception {
        var bootstrapServers = KAFKA.getContainer().getBootstrapServers();
        var logAppendTopic = "proxy-log-append-time-" + UUID.randomUUID();
        var createTimeTopic = "proxy-create-time-" + UUID.randomUUID();
        createTopic(bootstrapServers, logAppendTopic, "LogAppendTime");
        createTopic(bootstrapServers, createTimeTopic, "CreateTime");

        var parameters = new KafkaConfig.KafkaParameters();
        parameters.kafkaBrokers = bootstrapServers;
        parameters.kafkaClientId = "capability-probe-test";
        parameters.kafkaAuthType = KafkaConfig.AUTH_TYPE_NONE;
        try (var producer = new KafkaProducer<String, byte[]>(
            KafkaConfig.buildKafkaProperties(parameters)
        )) {
            CaptureKafkaCapabilityProbe.publish(
                producer,
                logAppendTopic,
                "activation",
                List.of(0),
                () -> "log-append-time"
            ).get(WAIT_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);

            Assertions.assertThrows(
                java.util.concurrent.ExecutionException.class,
                () -> CaptureKafkaCapabilityProbe.publish(
                    producer,
                    createTimeTopic,
                    "activation",
                    List.of(0),
                    () -> "create-time"
                ).get(WAIT_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)
            );
        } finally {
            deleteTopic(bootstrapServers, logAppendTopic);
            deleteTopic(bootstrapServers, createTimeTopic);
        }
    }

    private static ManualFactoryHarness startFactoryWithManualAcknowledgements(
        RootCaptureContext rootContext,
        String bootstrapServers,
        String topic,
        String captureActivationId
    ) throws Exception {
        var producer = mockProducer(topic, 4, false);
        var harness = createFactory(
            rootContext,
            bootstrapServers,
            topic,
            captureActivationId,
            producer
        );

        awaitHistorySize(producer, 2);
        for (int index = 0; index < 2; ++index) {
            assertCapabilityProbe(
                producer.history().get(index),
                captureActivationId + ":PROBE"
            );
            Assertions.assertTrue(producer.completeNext());
        }

        awaitHistorySize(producer, 6);
        for (int index = 2; index < 6; ++index) {
            assertHeartbeat(producer.history().get(index), captureActivationId + ":1");
            Assertions.assertTrue(producer.completeNext());
        }
        harness.factory().publisherReady().get(WAIT_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        return harness;
    }

    private static ManualFactoryHarness startFactoryWithAutomaticAcknowledgements(
        RootCaptureContext rootContext,
        String bootstrapServers,
        String topic,
        String captureActivationId
    ) throws Exception {
        var producer = mockProducer(topic, 4, true);
        var harness = createFactory(
            rootContext,
            bootstrapServers,
            topic,
            captureActivationId,
            producer
        );
        harness.factory().readyForConnections()
            .get(WAIT_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        assertEveryRecordUsesEnvelopeWithoutHeaders(producer.history());
        return harness;
    }

    private static ManualFactoryHarness createFactory(
        RootCaptureContext rootContext,
        String bootstrapServers,
        String topic,
        String captureActivationId,
        MockProducer<String, byte[]> producer
    ) throws Exception {
        var parameters = new KafkaConfig.KafkaParameters();
        parameters.kafkaBrokers = bootstrapServers;
        parameters.kafkaClientId = captureActivationId;
        parameters.kafkaAuthType = KafkaConfig.AUTH_TYPE_NONE;
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
        return new ManualFactoryHarness(
            factory,
            producer,
            captureFailure,
            unstableFailure
        );
    }

    private static String awaitPendingReplacementHeartbeat(
        MockProducer<String, byte[]> producer,
        int initialHistorySize,
        String initialWriter
    ) throws Exception {
        var deadline = System.nanoTime() + WAIT_TIMEOUT.toNanos();
        while (System.nanoTime() < deadline) {
            var history = producer.history();
            for (int index = initialHistorySize; index < history.size(); ++index) {
                var record = history.get(index);
                assertNoHeaders(record);
                var envelope = CaptureRecord.parseFrom(record.value());
                if (envelope.hasWriterPartitionHeartbeat()) {
                    var writer = envelope.getWriterPartitionHeartbeat().getWriterNodeId();
                    if (!initialWriter.equals(writer)) {
                        return writer;
                    }
                }
            }
            Thread.sleep(10);
        }
        throw new AssertionError("Timed out waiting for an unacknowledged replacement heartbeat");
    }

    private static void completePendingSendsUntil(
        MockProducer<String, byte[]> producer,
        java.util.function.BooleanSupplier condition
    ) throws Exception {
        var deadline = System.nanoTime() + WAIT_TIMEOUT.toNanos();
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            if (!producer.completeNext()) {
                Thread.sleep(10);
            }
        }
        Assertions.assertTrue(condition.getAsBoolean());
    }

    private static void drainPendingSends(
        MockProducer<String, byte[]> producer
    ) throws Exception {
        var deadline = System.nanoTime() + WAIT_TIMEOUT.toNanos();
        int quietChecks = 0;
        while (quietChecks < 50 && System.nanoTime() < deadline) {
            if (producer.completeNext()) {
                quietChecks = 0;
            } else {
                ++quietChecks;
                Thread.sleep(10);
            }
        }
        Assertions.assertEquals(50, quietChecks, "Kafka sends did not quiesce");
    }

    private static void awaitHistorySize(
        MockProducer<String, byte[]> producer,
        int expected
    ) throws Exception {
        var deadline = System.nanoTime() + WAIT_TIMEOUT.toNanos();
        while (producer.history().size() < expected && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        Assertions.assertEquals(expected, producer.history().size());
    }

    private static void assertCapabilityProbe(
        ProducerRecord<String, byte[]> record,
        String expectedWriter
    ) throws Exception {
        assertNoHeaders(record);
        var envelope = CaptureRecord.parseFrom(record.value());
        Assertions.assertTrue(envelope.hasCaptureCapabilityProbe());
        Assertions.assertEquals(
            expectedWriter,
            envelope.getCaptureCapabilityProbe().getWriterNodeId()
        );
    }

    private static void assertHeartbeat(
        ProducerRecord<String, byte[]> record,
        String expectedWriter
    ) throws Exception {
        assertNoHeaders(record);
        var envelope = CaptureRecord.parseFrom(record.value());
        Assertions.assertTrue(envelope.hasWriterPartitionHeartbeat());
        Assertions.assertEquals(
            expectedWriter,
            envelope.getWriterPartitionHeartbeat().getWriterNodeId()
        );
        Assertions.assertEquals(
            record.partition().intValue(),
            Integer.parseInt(record.key().substring(record.key().lastIndexOf(':') + 1))
        );
    }

    private static void assertEveryRecordUsesEnvelopeWithoutHeaders(
        List<ProducerRecord<String, byte[]>> records
    ) throws Exception {
        for (var record : records) {
            assertNoHeaders(record);
            Assertions.assertNotEquals(
                CaptureRecord.PayloadCase.PAYLOAD_NOT_SET,
                CaptureRecord.parseFrom(record.value()).getPayloadCase()
            );
        }
    }

    private static void assertNoHeaders(ProducerRecord<String, byte[]> record) {
        Assertions.assertEquals(0, record.headers().toArray().length);
    }

    private static MockProducer<String, byte[]> mockProducer(
        String topic,
        int partitionCount,
        boolean autoComplete
    ) {
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
        var brokerTimestamp = new AtomicLong(1_000);
        return new MockProducer<>(
            new Cluster("membership-test", leaders, partitionInfo, Set.of(), Set.of()),
            autoComplete,
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
                            new org.apache.kafka.common.TopicPartition(
                                metadata.topic(),
                                metadata.partition()
                            ),
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
        createTopic(bootstrapServers, topic, "LogAppendTime");
    }

    private static void createTopic(
        String bootstrapServers,
        String topic,
        String timestampType
    ) throws Exception {
        try (var admin = AdminClient.create(adminProperties(bootstrapServers))) {
            admin.createTopics(List.of(
                new NewTopic(topic, 4, (short) 1).configs(
                    Map.of("message.timestamp.type", timestampType)
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

    private record ManualFactoryHarness(
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
