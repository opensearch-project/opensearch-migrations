package org.opensearch.migrations.trafficcapture.kafkaoffloader;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.LongUnaryOperator;

import org.opensearch.migrations.tracing.InMemoryInstrumentationBundle;
import org.opensearch.migrations.trafficcapture.kafkaoffloader.tracing.CapturePublishingMetrics;
import org.opensearch.migrations.trafficcapture.kafkaoffloader.tracing.CapturePublishingMetrics.PublishOutcome;
import org.opensearch.migrations.trafficcapture.kafkaoffloader.tracing.CapturePublishingMetrics.RecordKind;
import org.opensearch.migrations.trafficcapture.protos.CaptureRecord;
import org.opensearch.migrations.trafficcapture.protos.CloseObservation;
import org.opensearch.migrations.trafficcapture.protos.TrafficObservation;
import org.opensearch.migrations.trafficcapture.protos.TrafficStream;

import com.google.protobuf.Timestamp;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.sdk.metrics.data.MetricData;
import org.apache.kafka.clients.producer.Callback;
import org.apache.kafka.clients.producer.MockProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CaptureKafkaPublisherTest {
    private static final String TOPIC = "traffic";
    private static final String ACTIVATION_ID = "activation";
    private static final int MESSAGE_SIZE = 1024 * 1024;
    private static final Duration LONG_HEARTBEAT_INTERVAL = Duration.ofDays(1);
    private static final Duration LONG_EXPIRATION_INTERVAL = Duration.ofDays(2);
    private static final CapturePublishingMetrics NOOP_METRICS =
        new CapturePublishingMetrics(io.opentelemetry.api.OpenTelemetry.noop().getMeter("test"));

    @Test
    void initialHeartbeatsMustAllBeAcknowledgedBeforeRoutingGenerationBecomesUsable()
        throws Exception {
        var producer = producer(false);
        var routingState = new CaptureRoutingState(ACTIVATION_ID, 3);
        try (var publisher = publisher(producer, routingState)) {
            var install = publisher.initializeRoutingGeneration(List.of(0, 2));
            awaitHistorySize(producer, 2);

            assertFalse(install.isDone());
            assertEquals(List.of(), routingState.activeRoutingPartitions());
            assertHeartbeat(producer.history().get(0), "activation:1", 0, LONG_HEARTBEAT_INTERVAL);
            assertHeartbeat(producer.history().get(1), "activation:1", 2, LONG_HEARTBEAT_INTERVAL);

            assertTrue(producer.completeNext());
            assertFalse(install.isDone());
            assertTrue(producer.completeNext());

            assertEquals("activation:1", install.get(1, TimeUnit.SECONDS));
            assertEquals(List.of(0, 2), routingState.activeRoutingPartitions());
        }
    }

    @Test
    void replacementRoutingGenerationsAreInitializedInMembershipCallbackOrder()
        throws Exception {
        var producer = producer(false);
        var routingState = new CaptureRoutingState(ACTIVATION_ID, 2);
        try (var publisher = publisher(producer, routingState)) {
            var first = publisher.initializeRoutingGeneration(List.of(0));
            var second = publisher.initializeRoutingGeneration(List.of(1));
            awaitHistorySize(producer, 1);

            assertFalse(first.isDone());
            assertFalse(second.isDone());
            assertHeartbeat(producer.history().get(0), "activation:1", 0, LONG_HEARTBEAT_INTERVAL);

            assertTrue(producer.completeNext());
            assertEquals("activation:1", first.get(1, TimeUnit.SECONDS));
            awaitHistorySize(producer, 2);
            assertHeartbeat(producer.history().get(1), "activation:2", 1, LONG_HEARTBEAT_INTERVAL);

            assertTrue(producer.completeNext());
            assertEquals("activation:2", second.get(1, TimeUnit.SECONDS));
            assertEquals("activation:2", routingState.currentWriterNodeId());
            assertEquals(List.of(1), routingState.activeRoutingPartitions());
        }
    }

    @Test
    void heartbeatAcknowledgementDeadlinePermanentlyFailsThePublisher() throws Exception {
        var producer = producer(false);
        var routingState = new CaptureRoutingState(ACTIVATION_ID, 1);
        try (var telemetry = new InMemoryInstrumentationBundle(false, true)) {
            var metrics = new CapturePublishingMetrics(
                telemetry.openTelemetrySdk.getMeter("captureProxy")
            );
            var writeGate = new CaptureKafkaWriteGate(metrics);
            var terminalFailure = new AtomicReference<Throwable>();
            writeGate.addTerminalFailureListener(terminalFailure::set);
            try (var publisher = new CaptureKafkaPublisher(
                producer,
                TOPIC,
                routingState,
                MESSAGE_SIZE,
                Duration.ofMillis(20),
                Duration.ofMillis(75),
                Clock.fixed(Instant.ofEpochMilli(1_234), ZoneOffset.UTC),
                metrics,
                writeGate,
                ignored -> {}
            )) {
                var install = publisher.initializeRoutingGeneration(List.of(0));
                awaitHistorySize(producer, 1);

                var failure = assertThrows(
                    ExecutionException.class,
                    () -> install.get(2, TimeUnit.SECONDS)
                ).getCause();
                assertInstanceOf(TimeoutException.class, failure);
                assertSame(failure, terminalFailure.get());
                assertEquals(List.of(), routingState.activeRoutingPartitions());
                assertEquals(1, producer.history().size(), "The application never resubmits a timed-out send");

                assertTrue(producer.completeNext(), "Kafka may acknowledge after the application deadline");
                Thread.sleep(25);
                assertEquals(List.of(), routingState.activeRoutingPartitions());
                assertEquals(1, producer.history().size());
                assertMetricPoint(
                    telemetry.getFinishedMetrics(),
                    CapturePublishingMetrics.MetricNames.HEARTBEAT_DEADLINE_VIOLATIONS,
                    Attributes.empty(),
                    1
                );
                assertMetricPoint(
                    telemetry.getFinishedMetrics(),
                    CapturePublishingMetrics.MetricNames.PUBLISHER_FAILURES,
                    Attributes.of(CapturePublishingMetrics.REASON, "heartbeat_deadline"),
                    1
                );
                assertMetricPoint(
                    telemetry.getFinishedMetrics(),
                    CapturePublishingMetrics.MetricNames.RECORDS_AWAITING_ACKNOWLEDGEMENT,
                    Attributes.of(CapturePublishingMetrics.RECORD_KIND, "heartbeat"),
                    0
                );
            }
        }
    }

    @Test
    void acknowledgementThatArrivesBeforeDeadlineWinsEvenIfPublisherThreadProcessesItLater()
        throws Exception {
        var producer = new CallbackThenBlockProducer(Duration.ofMillis(100));
        var routingState = new CaptureRoutingState(ACTIVATION_ID, 1);
        try (var publisher = new CaptureKafkaPublisher(
            producer,
            TOPIC,
            routingState,
            MESSAGE_SIZE,
            Duration.ofMillis(20),
            Duration.ofMillis(75),
            Clock.fixed(Instant.ofEpochMilli(1_234), ZoneOffset.UTC),
            NOOP_METRICS,
            CaptureKafkaWriteGate.unrestricted(NOOP_METRICS),
            ignored -> {}
        )) {
            assertEquals(
                "activation:1",
                publisher.initializeRoutingGeneration(List.of(0)).get(1, TimeUnit.SECONDS)
            );
            assertEquals(List.of(0), routingState.activeRoutingPartitions());
        }
    }

    @Test
    void heartbeatDeadlineStartsAfterTheProducerAcceptsTheSubmission() throws Exception {
        var producer = new BlockedSendProducer();
        var routingState = new CaptureRoutingState(ACTIVATION_ID, 1);
        var writeGate = new CaptureKafkaWriteGate(NOOP_METRICS);
        var terminalFailure = new AtomicReference<Throwable>();
        var terminalFailureObserved = new CountDownLatch(1);
        writeGate.addTerminalFailureListener(failure -> {
            terminalFailure.set(failure);
            terminalFailureObserved.countDown();
        });
        try (var publisher = new CaptureKafkaPublisher(
            producer,
            TOPIC,
            routingState,
            MESSAGE_SIZE,
            Duration.ofMillis(20),
            Duration.ofMillis(75),
            Clock.fixed(Instant.ofEpochMilli(1_234), ZoneOffset.UTC),
            NOOP_METRICS,
            writeGate,
            ignored -> {}
        )) {
            try {
                var initialization = publisher.initializeRoutingGeneration(List.of(0));
                assertTrue(producer.sendEntered.await(1, TimeUnit.SECONDS));
                assertFalse(
                    terminalFailureObserved.await(150, TimeUnit.MILLISECONDS),
                    "the acknowledgement deadline must not run while Producer.send() is still blocked"
                );

                producer.allowSendToReturn.countDown();
                assertTrue(terminalFailureObserved.await(1, TimeUnit.SECONDS));

                assertInstanceOf(TimeoutException.class, terminalFailure.get());
                assertSame(terminalFailure.get(), writeGate.failureIfNotWritable());
                assertEquals(List.of(), routingState.activeRoutingPartitions());

                var failure = assertThrows(
                    ExecutionException.class,
                    () -> initialization.get(1, TimeUnit.SECONDS)
                ).getCause();
                assertInstanceOf(TimeoutException.class, failure);
            } finally {
                producer.allowSendToReturn.countDown();
            }
        }
    }

    @Test
    void periodicHeartbeatWaitsForThePreviousHeartbeatAcknowledgement() throws Exception {
        var producer = producer(false);
        var routingState = new CaptureRoutingState(ACTIVATION_ID, 1);
        try (var publisher = new CaptureKafkaPublisher(
            producer,
            TOPIC,
            routingState,
            MESSAGE_SIZE,
            Duration.ofMillis(20),
            Duration.ofMillis(500),
            Clock.fixed(Instant.ofEpochMilli(1_234), ZoneOffset.UTC),
            NOOP_METRICS,
            CaptureKafkaWriteGate.unrestricted(NOOP_METRICS),
            ignored -> {}
        )) {
            var install = publisher.initializeRoutingGeneration(List.of(0));
            awaitHistorySize(producer, 1);
            assertTrue(producer.completeNext());
            install.get(1, TimeUnit.SECONDS);

            awaitHistorySize(producer, 2);
            Thread.sleep(80);
            assertEquals(2, producer.history().size());

            assertTrue(producer.completeNext());
            awaitHistorySize(producer, 3);
            assertHeartbeat(producer.history().get(2), "activation:1", 0, Duration.ofMillis(20));
        }
    }

    @Test
    void heartbeatBrokerTimeAtExpirationBoundaryCompromisesCapture() throws Exception {
        var producer = new TimestampingProducer(sendIndex -> sendIndex == 0 ? 1_000L : 6_000L);
        var routingState = new CaptureRoutingState(ACTIVATION_ID, 1);
        try (var telemetry = new InMemoryInstrumentationBundle(false, true)) {
            var metrics = new CapturePublishingMetrics(
                telemetry.openTelemetrySdk.getMeter("captureProxy")
            );
            var writeGate = new CaptureKafkaWriteGate(metrics);
            var terminalFailure = new AtomicReference<Throwable>();
            writeGate.addTerminalFailureListener(terminalFailure::set);
            try (var publisher = new CaptureKafkaPublisher(
                producer,
                TOPIC,
                routingState,
                MESSAGE_SIZE,
                Duration.ofMillis(10),
                Duration.ofSeconds(5),
                Clock.fixed(Instant.ofEpochMilli(1_234), ZoneOffset.UTC),
                metrics,
                writeGate,
                ignored -> {}
            )) {
                publisher.initializeRoutingGeneration(List.of(0)).get(1, TimeUnit.SECONDS);
                awaitHistorySize(producer, 2);
                awaitValue(terminalFailure, 1, TimeUnit.SECONDS);

                assertInstanceOf(IllegalStateException.class, terminalFailure.get());
                var writerPartition = new CaptureRoutingState.WriterPartition("activation:1", 0);
                assertEquals(1_000L, routingState.lastAcceptedHeartbeatLogAppendTime(writerPartition));
                assertMetricPoint(
                    telemetry.getFinishedMetrics(),
                    CapturePublishingMetrics.MetricNames.BROKER_TIME_VIOLATIONS,
                    Attributes.empty(),
                    1
                );
                assertMetricPoint(
                    telemetry.getFinishedMetrics(),
                    CapturePublishingMetrics.MetricNames.PUBLISHER_FAILURES,
                    Attributes.of(CapturePublishingMetrics.REASON, "broker_time"),
                    1
                );
            }
        }
    }

    @Test
    @org.junit.jupiter.api.parallel.ResourceLock("CaptureKafkaPublisher")
    void healthyLifecycleEmitsConservedMetricsAndBoundedOperationalEvents() throws Exception {
        var messages = new ArrayList<String>();
        var appender = new org.apache.logging.log4j.core.appender.AbstractAppender(
            "capture-publisher-test",
            null,
            org.apache.logging.log4j.core.layout.PatternLayout.createDefaultLayout(),
            false,
            org.apache.logging.log4j.core.config.Property.EMPTY_ARRAY
        ) {
            @Override
            public void append(org.apache.logging.log4j.core.LogEvent event) {
                messages.add(event.getMessage().getFormattedMessage());
            }
        };
        var logger = (org.apache.logging.log4j.core.Logger)
            org.apache.logging.log4j.LogManager.getLogger(CaptureKafkaPublisher.class);
        appender.start();
        logger.addAppender(appender);
        try (var telemetry = new InMemoryInstrumentationBundle(false, true)) {
            var metrics = new CapturePublishingMetrics(
                telemetry.openTelemetrySdk.getMeter("captureProxy")
            );
            var producer = producer(false);
            var routingState = new CaptureRoutingState(ACTIVATION_ID, 1);
            var gate = new CaptureKafkaWriteGate(metrics);
            try (var publisher = new CaptureKafkaPublisher(
                producer,
                TOPIC,
                routingState,
                MESSAGE_SIZE,
                LONG_HEARTBEAT_INTERVAL,
                LONG_EXPIRATION_INTERVAL,
                Clock.fixed(Instant.ofEpochMilli(1_234), ZoneOffset.UTC),
                metrics,
                gate,
                ignored -> {}
            )) {
                publisher.reportHealthNow().get(1, TimeUnit.SECONDS);
                installAndAcknowledge(producer, publisher, List.of(0));
                var route = publisher.routeNewConnection("connection");
                var publication = publisher.publishTraffic(route, traffic(route, true), true);
                awaitHistorySize(producer, 2);
                publisher.reportHealthNow().get(1, TimeUnit.SECONDS);
                assertTrue(producer.completeNext());
                publication.get(1, TimeUnit.SECONDS);
                publisher.retireAllWriters().get(1, TimeUnit.SECONDS);

                var recorded = telemetry.getFinishedMetrics();
                assertMetricPoint(
                    recorded,
                    CapturePublishingMetrics.MetricNames.RECORDS_ACCEPTED,
                    Attributes.of(CapturePublishingMetrics.RECORD_KIND, "heartbeat"),
                    1
                );
                assertMetricPoint(
                    recorded,
                    CapturePublishingMetrics.MetricNames.RECORDS_ACCEPTED,
                    Attributes.of(CapturePublishingMetrics.RECORD_KIND, "traffic"),
                    1
                );
                assertMetricPoint(
                    recorded,
                    CapturePublishingMetrics.MetricNames.COMPLETIONS,
                    Attributes.of(
                        CapturePublishingMetrics.RECORD_KIND,
                        RecordKind.TRAFFIC.name().toLowerCase(java.util.Locale.ROOT),
                        CapturePublishingMetrics.OUTCOME,
                        PublishOutcome.ACKNOWLEDGED.name().toLowerCase(java.util.Locale.ROOT)
                    ),
                    1
                );
                assertMetricPoint(
                    recorded,
                    CapturePublishingMetrics.MetricNames.WRITERS_ACTIVE,
                    Attributes.empty(),
                    0
                );
                assertMetricPoint(
                    recorded,
                    CapturePublishingMetrics.MetricNames.WRITERS_DRAINING,
                    Attributes.empty(),
                    0
                );
                assertMetricPoint(
                    recorded,
                    CapturePublishingMetrics.MetricNames.WRITERS_RETIRED,
                    Attributes.empty(),
                    1
                );
                assertTrue(messages.stream().anyMatch(message ->
                    message.startsWith("event=capture_generation_prepared ")
                ));
                assertTrue(messages.stream().anyMatch(message ->
                    message.startsWith("event=capture_generation_activated ")
                ));
                assertTrue(messages.stream().anyMatch(message ->
                    message.startsWith("event=capture_health ")
                        && message.contains("lastHeartbeatBrokerTime=none")
                ));
                assertTrue(messages.stream().anyMatch(message ->
                    message.startsWith("event=capture_health ")
                        && message.contains("inFlightRecords=1")
                        && message.contains("activeConnections=1")
                        && !message.contains("lastHeartbeatBrokerTime=none")
                ));
                assertTrue(messages.stream().anyMatch(message ->
                    message.startsWith("event=capture_writer_draining ")
                ));
                assertTrue(messages.stream().anyMatch(message ->
                    message.startsWith("event=capture_writer_retired ")
                ));
            }
        } finally {
            logger.removeAppender(appender);
            appender.stop();
        }
    }

    @Test
    void applicationVisibleKafkaFailureStopsAllFutureWritesWithoutResubmission() throws Exception {
        var producer = producer(false);
        var routingState = new CaptureRoutingState(ACTIVATION_ID, 1);
        try (var publisher = publisher(producer, routingState)) {
            installAndAcknowledge(producer, publisher, List.of(0));
            var route = routingState.routeNewConnection("connection");
            var first = publisher.publishTraffic(route, traffic(route, false), false);
            awaitHistorySize(producer, 2);

            var sendFailure = new IllegalStateException("send failed");
            assertTrue(producer.errorNext(sendFailure));
            assertSame(
                sendFailure,
                assertThrows(ExecutionException.class, () -> first.get(1, TimeUnit.SECONDS)).getCause()
            );

            var rejected = publisher.publishTraffic(route, traffic(route, false), false);
            assertSame(
                sendFailure,
                assertThrows(ExecutionException.class, () -> rejected.get(1, TimeUnit.SECONDS)).getCause()
            );
            assertEquals(2, producer.history().size());
        }
    }

    @Test
    void trafficUsesCaptureRecordEnvelopeAndNoKafkaTypeHeaders() throws Exception {
        var producer = producer(false);
        var routingState = new CaptureRoutingState(ACTIVATION_ID, 1);
        try (var publisher = publisher(producer, routingState)) {
            installAndAcknowledge(producer, publisher, List.of(0));
            var route = routingState.routeNewConnection("connection");
            var expected = traffic(route, false);

            var publication = publisher.publishTraffic(route, expected, false);
            awaitHistorySize(producer, 2);
            var record = producer.history().get(1);

            assertEquals(TOPIC, record.topic());
            assertEquals(route.partition(), record.partition());
            assertEquals(route.connectionId(), record.key());
            assertEquals(0, record.headers().toArray().length);
            var captureRecord = CaptureRecord.parseFrom(record.value());
            assertTrue(captureRecord.hasTrafficStream());
            assertEquals(expected, captureRecord.getTrafficStream());

            assertTrue(producer.completeNext());
            publication.get(1, TimeUnit.SECONDS);
        }
    }

    @Test
    void finalTrafficAcknowledgementRemovesTheConnectionAndAllowsOldWriterRetirement() throws Exception {
        var producer = producer(false);
        var routingState = new CaptureRoutingState(ACTIVATION_ID, 1);
        try (var publisher = publisher(producer, routingState)) {
            installAndAcknowledge(producer, publisher, List.of(0));
            var oldRoute = routingState.routeNewConnection("connection");

            var replacement = publisher.initializeRoutingGeneration(List.of(0));
            awaitHistorySize(producer, 2);
            assertTrue(producer.completeNext());
            assertEquals("activation:2", replacement.get(1, TimeUnit.SECONDS));
            assertEquals(
                CaptureRoutingState.WriterStatus.DRAINING,
                routingState.writerStatus(oldRoute.writerNodeId(), oldRoute.partition())
            );

            var finalPublication = publisher.publishTraffic(oldRoute, traffic(oldRoute, true), true);
            awaitHistorySize(producer, 3);
            assertEquals(1, routingState.size());
            assertTrue(producer.completeNext());
            finalPublication.get(1, TimeUnit.SECONDS);

            awaitWriterStatus(
                routingState,
                oldRoute.writerNodeId(),
                CaptureRoutingState.WriterStatus.RETIRED
            );
            assertEquals(0, routingState.size());
            assertEquals(3, producer.history().size(), "Retirement publishes no Kafka terminal record");
        }
    }

    @Test
    void orderlyRetirementIsEntirelyLocalAfterConnectionsAreGone() throws Exception {
        var producer = producer(false);
        var routingState = new CaptureRoutingState(ACTIVATION_ID, 2);
        try (var publisher = publisher(producer, routingState)) {
            installAndAcknowledge(producer, publisher, List.of(0, 1));
            var recordCountBeforeRetirement = producer.history().size();

            publisher.retireAllWriters().get(1, TimeUnit.SECONDS);

            assertTrue(routingState.allWriterPartitionsRetired());
            assertEquals(recordCountBeforeRetirement, producer.history().size());
        }
    }

    @Test
    void mismatchedTrafficIdentityIsAnUnstableProcessFailure() throws Exception {
        var producer = producer(false);
        var routingState = new CaptureRoutingState(ACTIVATION_ID, 1);
        var unstableFailure = new AtomicReference<Throwable>();
        try (var publisher = publisher(producer, routingState, unstableFailure::set)) {
            installAndAcknowledge(producer, publisher, List.of(0));
            var route = routingState.routeNewConnection("connection");
            var mismatched = traffic(route, false).toBuilder().setConnectionId("other").build();

            var publication = publisher.publishTraffic(route, mismatched, false);
            var failure = assertThrows(
                ExecutionException.class,
                () -> publication.get(1, TimeUnit.SECONDS)
            ).getCause();

            assertInstanceOf(CorruptedCaptureStateException.class, failure);
            assertSame(failure, unstableFailure.get());
            assertEquals(1, producer.history().size());
        }
    }

    @Test
    void criticalMutationValidationRequiresMatchingPartitionAndPositiveFreshBrokerTime() throws Exception {
        var producer = producer(false);
        var routingState = new CaptureRoutingState(ACTIVATION_ID, 2);
        try (var publisher = publisher(producer, routingState)) {
            installAndAcknowledge(producer, publisher, List.of(0));
            var route = routingState.routeNewConnection("connection");

            publisher.validateCriticalMutationTrafficAcknowledgement(
                route,
                metadata(route.partition(), 1_001L)
            );
            assertThrows(
                IllegalStateException.class,
                () -> publisher.validateCriticalMutationTrafficAcknowledgement(
                    route,
                    metadata(route.partition() + 1, 1_001L)
                )
            );
        }
    }

    @Test
    void payloadLimitFailurePermanentlyStopsPublisher() throws Exception {
        var producer = producer(false);
        var routingState = new CaptureRoutingState(ACTIVATION_ID, 1);
        try (var publisher = new CaptureKafkaPublisher(
            producer,
            TOPIC,
            routingState,
            KafkaCaptureFactory.KAFKA_MESSAGE_OVERHEAD_BYTES + 8,
            LONG_HEARTBEAT_INTERVAL,
            LONG_EXPIRATION_INTERVAL,
            Clock.fixed(Instant.ofEpochMilli(1_234), ZoneOffset.UTC),
            NOOP_METRICS,
            CaptureKafkaWriteGate.unrestricted(NOOP_METRICS),
            ignored -> {}
        )) {
            installAndAcknowledge(producer, publisher, List.of(0));
            var route = routingState.routeNewConnection("connection");

            var publication = publisher.publishTraffic(route, traffic(route, false), false);
            assertInstanceOf(
                IllegalArgumentException.class,
                assertThrows(
                    ExecutionException.class,
                    () -> publication.get(1, TimeUnit.SECONDS)
                ).getCause()
            );
            assertEquals(1, producer.history().size());
        }
    }

    @Test
    void constructorRejectsInvalidHeartbeatIntervals() {
        var producer = producer(true);
        var routingState = new CaptureRoutingState(ACTIVATION_ID, 1);

        assertThrows(
            IllegalArgumentException.class,
            () -> new CaptureKafkaPublisher(
                producer,
                TOPIC,
                routingState,
                MESSAGE_SIZE,
                Duration.ofSeconds(30),
                Duration.ofSeconds(30),
                NOOP_METRICS,
                ignored -> {}
            )
        );
    }

    private static CaptureKafkaPublisher publisher(
        MockProducer<String, byte[]> producer,
        CaptureRoutingState routingState
    ) {
        return publisher(producer, routingState, ignored -> {});
    }

    private static CaptureKafkaPublisher publisher(
        MockProducer<String, byte[]> producer,
        CaptureRoutingState routingState,
        Consumer<Throwable> unstableProcessFailureCallback
    ) {
        return new CaptureKafkaPublisher(
            producer,
            TOPIC,
            routingState,
            MESSAGE_SIZE,
            LONG_HEARTBEAT_INTERVAL,
            LONG_EXPIRATION_INTERVAL,
            Clock.fixed(Instant.ofEpochMilli(1_234), ZoneOffset.UTC),
            NOOP_METRICS,
            CaptureKafkaWriteGate.unrestricted(NOOP_METRICS),
            unstableProcessFailureCallback
        );
    }

    private static TrafficStream traffic(
        CaptureRoutingState.ConnectionRoute route,
        boolean terminal
    ) {
        var builder = TrafficStream.newBuilder()
            .setNodeId(route.writerNodeId())
            .setConnectionId(route.connectionId())
            .setNumberOfThisLastChunk(terminal ? 1 : 0)
            .addSubStream(
                TrafficObservation.newBuilder()
                    .setTs(Timestamp.newBuilder().setSeconds(1))
                    .setConnectionObservationSequence(1)
            );
        if (terminal) {
            builder.setSubStream(
                0,
                builder.getSubStream(0).toBuilder()
                    .setClose(CloseObservation.getDefaultInstance())
                    .build()
            );
        }
        return builder.build();
    }

    private static void assertHeartbeat(
        ProducerRecord<String, byte[]> record,
        String writerNodeId,
        int partition,
        Duration interval
    ) throws Exception {
        assertEquals(TOPIC, record.topic());
        assertEquals(partition, record.partition());
        assertEquals(writerNodeId + ":heartbeat:" + partition, record.key());
        assertEquals(0, record.headers().toArray().length);
        var captureRecord = CaptureRecord.parseFrom(record.value());
        assertTrue(captureRecord.hasWriterPartitionHeartbeat());
        var heartbeat = captureRecord.getWriterPartitionHeartbeat();
        assertEquals(writerNodeId, heartbeat.getWriterNodeId());
        assertEquals(interval.toMillis(), heartbeat.getHeartbeatIntervalMillis());
        assertEquals(1_234L, heartbeat.getEmittedAtMillis());
    }

    private static void installAndAcknowledge(
        MockProducer<String, byte[]> producer,
        CaptureKafkaPublisher publisher,
        List<Integer> partitions
    ) throws Exception {
        var expectedHistory = producer.history().size() + partitions.size();
        var install = publisher.initializeRoutingGeneration(partitions);
        awaitHistorySize(producer, expectedHistory);
        for (int ignored : partitions) {
            assertTrue(producer.completeNext());
        }
        install.get(1, TimeUnit.SECONDS);
    }

    private static RecordMetadata metadata(int partition, long timestamp) {
        return new RecordMetadata(
            new TopicPartition(TOPIC, partition),
            0,
            0,
            timestamp,
            0,
            1
        );
    }

    private static MockProducer<String, byte[]> producer(boolean autoComplete) {
        return new LogAppendTimeMockProducer(
            autoComplete,
            new StringSerializer(),
            new ByteArraySerializer()
        );
    }

    private static void awaitHistorySize(MockProducer<String, byte[]> producer, int expected)
        throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (producer.history().size() < expected && System.nanoTime() < deadline) {
            Thread.sleep(1);
        }
        assertEquals(expected, producer.history().size());
    }

    private static void awaitWriterStatus(
        CaptureRoutingState state,
        String writerNodeId,
        CaptureRoutingState.WriterStatus expected
    ) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
        while (state.writerStatus(writerNodeId, 0) != expected && System.nanoTime() < deadline) {
            Thread.sleep(1);
        }
        assertEquals(expected, state.writerStatus(writerNodeId, 0));
    }

    private static void awaitValue(
        AtomicReference<?> reference,
        long timeout,
        TimeUnit unit
    ) throws InterruptedException {
        long deadline = System.nanoTime() + unit.toNanos(timeout);
        while (reference.get() == null && System.nanoTime() < deadline) {
            Thread.sleep(1);
        }
        assertFalse(reference.get() == null);
    }

    private static void assertMetricPoint(
        Collection<MetricData> metrics,
        String name,
        Attributes attributes,
        long expected
    ) {
        var actual = metrics.stream()
            .filter(metric -> metric.getName().equals(name))
            .flatMap(metric -> metric.getLongSumData().getPoints().stream())
            .filter(point -> point.getAttributes().equals(attributes))
            .findFirst()
            .orElseThrow(() -> new AssertionError("Missing metric " + name + " " + attributes))
            .getValue();
        assertEquals(expected, actual);
    }

    private static final class TimestampingProducer extends MockProducer<String, byte[]> {
        private final LongUnaryOperator timestampForSendIndex;
        private long sendIndex;

        private TimestampingProducer(LongUnaryOperator timestampForSendIndex) {
            super(false, null, new StringSerializer(), new ByteArraySerializer());
            this.timestampForSendIndex = timestampForSendIndex;
        }

        @Override
        public synchronized Future<RecordMetadata> send(
            ProducerRecord<String, byte[]> record,
            Callback callback
        ) {
            super.send(record, (ignoredMetadata, ignoredFailure) -> {});
            var metadata = metadata(
                record.partition(),
                timestampForSendIndex.applyAsLong(sendIndex++)
            );
            callback.onCompletion(metadata, null);
            return CompletableFuture.completedFuture(metadata);
        }
    }

    private static final class CallbackThenBlockProducer extends MockProducer<String, byte[]> {
        private final Duration publisherThreadBlock;

        private CallbackThenBlockProducer(Duration publisherThreadBlock) {
            super(false, null, new StringSerializer(), new ByteArraySerializer());
            this.publisherThreadBlock = publisherThreadBlock;
        }

        @Override
        public synchronized Future<RecordMetadata> send(
            ProducerRecord<String, byte[]> record,
            Callback callback
        ) {
            super.send(record, (ignoredMetadata, ignoredFailure) -> {});
            var metadata = metadata(record.partition(), 1_000L);
            callback.onCompletion(metadata, null);
            try {
                Thread.sleep(publisherThreadBlock.toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
            return CompletableFuture.completedFuture(metadata);
        }
    }

    private static final class BlockedSendProducer extends MockProducer<String, byte[]> {
        private final CountDownLatch sendEntered = new CountDownLatch(1);
        private final CountDownLatch allowSendToReturn = new CountDownLatch(1);

        private BlockedSendProducer() {
            super(false, null, new StringSerializer(), new ByteArraySerializer());
        }

        @Override
        public synchronized Future<RecordMetadata> send(
            ProducerRecord<String, byte[]> record,
            Callback callback
        ) {
            super.send(record, (ignoredMetadata, ignoredFailure) -> {});
            sendEntered.countDown();
            try {
                if (!allowSendToReturn.await(2, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("test did not release the blocked producer send");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
            return new CompletableFuture<>();
        }
    }

    private static final class LogAppendTimeMockProducer extends MockProducer<String, byte[]> {
        private LogAppendTimeMockProducer(
            boolean autoComplete,
            StringSerializer keySerializer,
            ByteArraySerializer valueSerializer
        ) {
            super(autoComplete, null, keySerializer, valueSerializer);
        }

        @Override
        public synchronized Future<RecordMetadata> send(
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
                        1_000L + metadata.offset(),
                        metadata.serializedKeySize(),
                        metadata.serializedValueSize()
                    ),
                    null
                );
            });
        }
    }
}
