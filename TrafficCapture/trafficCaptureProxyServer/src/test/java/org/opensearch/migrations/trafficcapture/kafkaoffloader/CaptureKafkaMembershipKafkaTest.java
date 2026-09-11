package org.opensearch.migrations.trafficcapture.kafkaoffloader;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;

import org.opensearch.migrations.tracing.IContextTracker;
import org.opensearch.migrations.trafficcapture.protos.LivenessSnapshotChunk;
import org.opensearch.migrations.trafficcapture.proxyserver.RootCaptureContext;
import org.opensearch.migrations.trafficcapture.proxyserver.testcontainers.KafkaContainerTestBase;
import org.opensearch.migrations.trafficcapture.proxyserver.testcontainers.annotations.KafkaContainerTest;

import io.opentelemetry.api.OpenTelemetry;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.MockProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
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
    void replacementAssignmentRemainsInactiveUntilItsInitialManifestsAreAcknowledged()
        throws Exception {
        var topic = "proxy-manifest-ack-" + UUID.randomUUID();
        var bootstrapServers = KAFKA.getContainer().getBootstrapServers();
        createTopic(bootstrapServers, topic);
        var rootContext = new RootCaptureContext(
            OpenTelemetry.noop(),
            IContextTracker.DO_NOTHING_TRACKER
        );
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
                var pendingWriter = awaitPendingReplacementManifest(
                    first.producer(),
                    initialHistorySize,
                    initialWriter
                );

                Assertions.assertEquals(
                    initialWriter,
                    routingState.currentWriterNodeId(),
                    "A replacement assignment is not usable before every initial manifest is acknowledged"
                );

                completePendingSendsUntil(
                    first.producer(),
                    () -> !initialWriter.equals(routingState.currentWriterNodeId())
                );
                Assertions.assertTrue(
                    routingState.currentWriterNodeId().startsWith("activation-manual-a:"),
                    routingState.currentWriterNodeId()
                );
                Assertions.assertNotEquals(initialWriter, routingState.currentWriterNodeId());
                Assertions.assertTrue(
                    pendingWriter.startsWith("activation-manual-a:"),
                    pendingWriter
                );
                Assertions.assertNull(first.captureFailure().get());
                Assertions.assertNull(first.unstableFailure().get());
                Assertions.assertNull(second.captureFailure().get());
                Assertions.assertNull(second.unstableFailure().get());

                drainPendingSends(first.producer());
                Assertions.assertNull(first.captureFailure().get());
                Assertions.assertNull(first.unstableFailure().get());
                first.close();
            }
        } finally {
            deleteTopic(bootstrapServers, topic);
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
        Assertions.assertTrue(producer.completeNext());
        Assertions.assertTrue(producer.completeNext());
        awaitHistorySize(producer, 6);
        for (int partition = 0; partition < 4; ++partition) {
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
        CompletableFuture.supplyAsync(harness.factory()::getPublisher)
            .get(WAIT_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        return harness;
    }

    private static ManualFactoryHarness createFactory(
        RootCaptureContext rootContext,
        String bootstrapServers,
        String topic,
        String captureActivationId,
        MockProducer<String, byte[]> producer
    ) throws Exception {
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
            Duration.ofHours(1),
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

    private static String awaitPendingReplacementManifest(
        MockProducer<String, byte[]> producer,
        int initialHistorySize,
        String initialWriter
    ) throws Exception {
        var deadline = System.nanoTime() + WAIT_TIMEOUT.toNanos();
        while (System.nanoTime() < deadline) {
            var history = producer.history();
            for (int index = initialHistorySize; index < history.size(); ++index) {
                var record = history.get(index);
                if (!CaptureKafkaPublisher.isRecordType(
                    record.headers(),
                    CaptureKafkaPublisher.LIVENESS_RECORD_TYPE
                )) {
                    continue;
                }
                var writer = LivenessSnapshotChunk.parseFrom(record.value()).getWriterNodeId();
                if (!initialWriter.equals(writer)) {
                    return writer;
                }
            }
            Thread.sleep(10);
        }
        throw new AssertionError("Timed out waiting for an unacknowledged replacement manifest");
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
        return new MockProducer<>(
            new Cluster("membership-test", leaders, partitionInfo, Set.of(), Set.of()),
            autoComplete,
            null,
            new StringSerializer(),
            new ByteArraySerializer()
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
