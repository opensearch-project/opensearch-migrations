package org.opensearch.migrations.trafficcapture.kafkaoffloader;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.LongUnaryOperator;

import org.opensearch.migrations.trafficcapture.protos.LivenessSnapshotChunk;
import org.opensearch.migrations.trafficcapture.protos.NoMoreWrites;

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
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CaptureKafkaPublisherTest {
    private static final String TOPIC = "traffic";
    private static final String ACTIVATION_ID = "activation";
    private static final int MESSAGE_SIZE = 1024;

    @Test
    void initialManifestMustBeAcknowledgedBeforeAssignmentBecomesUsable() throws Exception {
        var producer = producer(false);
        var routingState = new CaptureRoutingState(ACTIVATION_ID, 1);
        var publisher = publisher(producer, routingState);

        var install = publisher.installAssignment(List.of(0));
        awaitHistorySize(producer, 1);

        assertFalse(install.isDone());
        assertEquals(List.of(), routingState.assignedPartitions());
        assertThrows(IllegalStateException.class, () -> routingState.routeNewConnection("too-early"));
        var initialManifest = snapshotChunk(producer.history().get(0));
        assertEquals("activation:1", initialManifest.getWriterNodeId());
        assertEquals(0, initialManifest.getManifestCycle());
        assertEquals(0, initialManifest.getConnectionIdsCount());

        assertTrue(producer.completeNext());
        assertEquals("activation:1", install.get(1, TimeUnit.SECONDS));
        assertEquals(List.of(0), routingState.assignedPartitions());
        publisher.close();
    }

    @Test
    void manifestDeadlineCompromisesCaptureBeforeALateAcknowledgementCanActivateAssignment()
        throws Exception {
        var producer = producer(false);
        var routingState = new CaptureRoutingState(ACTIVATION_ID, 1);
        var writeGate = new CaptureKafkaWriteGate();
        var terminalFailure = new AtomicReference<Throwable>();
        writeGate.addTerminalFailureListener(terminalFailure::set);
        var publisher = new CaptureKafkaPublisher(
            producer,
            TOPIC,
            routingState,
            MESSAGE_SIZE,
            Duration.ofMillis(75),
            Duration.ofMillis(100),
            Clock.fixed(Instant.ofEpochMilli(1_234), ZoneOffset.UTC),
            writeGate,
            ignored -> {}
        );

        var install = publisher.installAssignment(List.of(0));
        awaitHistorySize(producer, 1);

        var failure = assertThrows(
            ExecutionException.class,
            () -> install.get(2, TimeUnit.SECONDS)
        ).getCause();
        assertTrue(failure instanceof TimeoutException);
        assertSame(failure, terminalFailure.get());
        assertEquals(List.of(), routingState.assignedPartitions());
        assertEquals(1, producer.history().size(), "The proxy must not resubmit the manifest");

        assertTrue(producer.completeNext(), "The original send may still acknowledge after the deadline");
        Thread.sleep(25);
        assertEquals(List.of(), routingState.assignedPartitions());
        assertEquals(1, producer.history().size());
        publisher.close();
    }

    @Test
    void chunkedManifestUsesMaximumBrokerTimestampAcrossAllChunks() throws Exception {
        var producer = new TimestampingProducer(index -> {
            if (index == 0) {
                return 1_000L;
            }
            return 5_001L - index;
        });
        var routingState = new CaptureRoutingState(ACTIVATION_ID, 1);
        var publisher = new CaptureKafkaPublisher(
            producer,
            TOPIC,
            routingState,
            650,
            Duration.ofDays(1),
            Duration.ofDays(2),
            Clock.fixed(Instant.ofEpochMilli(1_234), ZoneOffset.UTC),
            CaptureKafkaWriteGate.unrestricted(),
            ignored -> {}
        );
        publisher.installAssignment(List.of(0)).get(1, TimeUnit.SECONDS);
        for (int i = 0; i < 20; ++i) {
            routingState.routeNewConnection("connection-" + i + "-" + "x".repeat(40));
        }

        int beforeManifest = producer.history().size();
        publisher.publishLivenessSnapshotNow().get(1, TimeUnit.SECONDS);
        int chunkCount = producer.history().size() - beforeManifest;

        assertTrue(chunkCount > 1);
        assertEquals(5_000L, routingState.lastAcceptedManifestLogAppendTime("activation:1", 0));
        publisher.close();
    }

    @Test
    void lateManifestFailsClosedBeforeAnotherManifestCanStart() throws Exception {
        var producer = new TimestampingProducer(index -> index == 0 ? 1_000L : 61_000L);
        var routingState = new CaptureRoutingState(ACTIVATION_ID, 1);
        var publisher = new CaptureKafkaPublisher(
            producer,
            TOPIC,
            routingState,
            MESSAGE_SIZE,
            Duration.ofSeconds(30),
            Duration.ofSeconds(60),
            Clock.fixed(Instant.ofEpochMilli(1_234), ZoneOffset.UTC),
            CaptureKafkaWriteGate.unrestricted(),
            ignored -> {}
        );
        publisher.installAssignment(List.of(0)).get(1, TimeUnit.SECONDS);

        var late = publisher.publishLivenessSnapshotNow();
        assertThrows(ExecutionException.class, () -> late.get(1, TimeUnit.SECONDS));
        var queuedAfterFailure = publisher.publishLivenessSnapshotNow();
        assertThrows(
            ExecutionException.class,
            () -> queuedAfterFailure.get(1, TimeUnit.SECONDS)
        );

        assertEquals(2, producer.history().size());
        assertEquals(1_000L, routingState.lastAcceptedManifestLogAppendTime("activation:1", 0));
        publisher.close();
    }

    @Test
    void failedManifestDoesNotRefreshAcceptedBrokerTime() throws Exception {
        var producer = producer(false);
        var routingState = new CaptureRoutingState(ACTIVATION_ID, 1);
        var publisher = publisher(producer, routingState);
        installAndAcknowledge(producer, publisher, List.of(0));
        var failure = new IllegalStateException("manifest failed");

        var manifest = publisher.publishLivenessSnapshotNow();
        awaitHistorySize(producer, 2);
        assertTrue(producer.errorNext(failure));
        assertThrows(ExecutionException.class, () -> manifest.get(1, TimeUnit.SECONDS));

        assertEquals(1_234L, routingState.lastAcceptedManifestLogAppendTime("activation:1", 0));
        publisher.close();
    }

    @Test
    void missingInitialManifestBaselineIsAnUnstableProcessFailure() {
        var producer = producer(true);
        var routingState = new CaptureRoutingState(ACTIVATION_ID, 1);
        var assignment = routingState.prepareAssignment(List.of(0));
        routingState.prepareInitialManifests(assignment);
        routingState.activateAssignment(assignment);
        var route = routingState.routeNewConnection("connection");
        var unstableFailure = new AtomicReference<Throwable>();
        var publisher = publisher(producer, routingState, unstableFailure::set);
        var acknowledgement = new RecordMetadata(
            new TopicPartition(TOPIC, 0),
            0,
            0,
            1_000L,
            0,
            0
        );

        var observed = assertThrows(
            CorruptedCaptureStateException.class,
            () -> publisher.validateCriticalMutationTrafficAcknowledgement(route, acknowledgement)
        );

        assertSame(observed, unstableFailure.get());
        publisher.close();
    }

    @Test
    void replacementUsesANewWriterOnlyAfterItsInitialManifestIsAcknowledged() throws Exception {
        var producer = producer(false);
        var routingState = new CaptureRoutingState(ACTIVATION_ID, 1);
        var publisher = publisher(producer, routingState);
        installAndAcknowledge(producer, publisher, List.of(0));
        var beforeReplacement = routingState.routeNewConnection("before");

        var replacement = publisher.installAssignment(List.of(0));
        awaitHistorySize(producer, 2);
        var whileReplacementIsPending = routingState.routeNewConnection("during");

        assertEquals("activation:1", beforeReplacement.writerNodeId());
        assertEquals("activation:1", whileReplacementIsPending.writerNodeId());
        assertEquals("activation:2", snapshotChunk(producer.history().get(1)).getWriterNodeId());

        assertTrue(producer.completeNext());
        assertEquals("activation:2", replacement.get(1, TimeUnit.SECONDS));
        assertEquals("activation:2", routingState.routeNewConnection("after").writerNodeId());
        publisher.close();
    }

    @Test
    void finalRecordAcknowledgementPrecedesManifestOmission() throws Exception {
        var producer = producer(false);
        var routingState = new CaptureRoutingState(ACTIVATION_ID, 1);
        var publisher = publisher(producer, routingState);
        installAndAcknowledge(producer, publisher, List.of(0));
        var route = routingState.routeNewConnection("connection");

        var finalSend = publisher.publishTraffic(route, new byte[] { 1 }, true);
        awaitHistorySize(producer, 2);
        var manifestBeforeAcknowledgement = publisher.publishLivenessSnapshotNow();
        awaitHistorySize(producer, 3);

        assertEquals(List.of("connection"), connectionIds(producer.history().get(2)));
        assertEquals(List.of("connection"), routingState.snapshot(route.writerNodeId(), 0));

        assertTrue(producer.completeNext());
        finalSend.get(1, TimeUnit.SECONDS);
        assertEquals(List.of(), routingState.snapshot(route.writerNodeId(), 0));
        assertTrue(producer.completeNext());
        manifestBeforeAcknowledgement.get(1, TimeUnit.SECONDS);

        var manifestAfterAcknowledgement = publisher.publishLivenessSnapshotNow();
        awaitHistorySize(producer, 4);
        assertEquals(List.of(), connectionIds(producer.history().get(3)));
        assertTrue(producer.completeNext());
        manifestAfterAcknowledgement.get(1, TimeUnit.SECONDS);
        publisher.close();
    }

    @Test
    void completeManifestPublicationsDoNotOverlap() throws Exception {
        var producer = producer(false);
        var routingState = new CaptureRoutingState(ACTIVATION_ID, 1);
        var publisher = publisher(producer, routingState);
        installAndAcknowledge(producer, publisher, List.of(0));

        var first = publisher.publishLivenessSnapshotNow();
        var second = publisher.publishLivenessSnapshotNow();
        awaitHistorySize(producer, 2);
        assertEquals(2, producer.history().size());
        assertFalse(first.isDone());
        assertFalse(second.isDone());

        assertTrue(producer.completeNext());
        first.get(1, TimeUnit.SECONDS);
        awaitHistorySize(producer, 3);
        assertEquals(1, snapshotChunk(producer.history().get(1)).getManifestCycle());
        assertEquals(2, snapshotChunk(producer.history().get(2)).getManifestCycle());

        assertTrue(producer.completeNext());
        second.get(1, TimeUnit.SECONDS);
        publisher.close();
    }

    @Test
    void periodicManifestsCoverActiveAndDrainingWriterIdentities() throws Exception {
        var producer = producer(true);
        var routingState = new CaptureRoutingState(ACTIVATION_ID, 1);
        var publisher = publisher(producer, routingState);
        publisher.installAssignment(List.of(0)).get(1, TimeUnit.SECONDS);
        var oldRoute = routingState.routeNewConnection("old");
        publisher.installAssignment(List.of(0)).get(1, TimeUnit.SECONDS);
        var newRoute = routingState.routeNewConnection("new");

        publisher.publishLivenessSnapshotNow().get(1, TimeUnit.SECONDS);

        var periodicRecords = producer.history().subList(2, 4);
        assertEquals(List.of("activation:1", "activation:2"), periodicRecords.stream()
            .map(record -> {
                try {
                    return snapshotChunk(record).getWriterNodeId();
                } catch (Exception e) {
                    throw new AssertionError(e);
                }
            })
            .toList());
        assertEquals(List.of("old"), connectionIds(periodicRecords.get(0)));
        assertEquals(List.of("new"), connectionIds(periodicRecords.get(1)));
        assertEquals("activation:1", oldRoute.writerNodeId());
        assertEquals("activation:2", newRoute.writerNodeId());
        publisher.close();
    }

    @Test
    void drainedOldWriterPublishesFinalEmptyManifestBeforeSelfNoMoreWrites() throws Exception {
        var producer = producer(false);
        var routingState = new CaptureRoutingState(ACTIVATION_ID, 1);
        var publisher = publisher(producer, routingState);
        installAndAcknowledge(producer, publisher, List.of(0));

        var replacement = publisher.installAssignment(List.of(0));
        awaitHistorySize(producer, 2);
        assertTrue(producer.completeNext());
        assertEquals("activation:2", replacement.get(1, TimeUnit.SECONDS));

        awaitHistorySize(producer, 3);
        var finalManifestRecord = producer.history().get(2);
        var finalManifest = snapshotChunk(finalManifestRecord);
        assertEquals("activation:1", finalManifest.getWriterNodeId());
        assertEquals(0, finalManifest.getConnectionIdsCount());
        assertTrue(CaptureKafkaPublisher.isRecordType(
            finalManifestRecord.headers(),
            CaptureKafkaPublisher.LIVENESS_RECORD_TYPE
        ));
        assertEquals(
            CaptureRoutingState.WriterStatus.RETIRING,
            routingState.writerStatus("activation:1", 0)
        );

        assertTrue(producer.completeNext());
        awaitHistorySize(producer, 4);
        var noMoreWritesRecord = producer.history().get(3);
        assertTrue(CaptureKafkaPublisher.isRecordType(
            noMoreWritesRecord.headers(),
            CaptureKafkaPublisher.NO_MORE_WRITES_RECORD_TYPE
        ));
        assertEquals(
            "activation:1",
            headerValue(noMoreWritesRecord, CaptureKafkaPublisher.WRITER_NODE_ID_HEADER)
        );
        assertEquals(0, NoMoreWrites.parseFrom(noMoreWritesRecord.value()).getPartition());

        assertTrue(producer.completeNext());
        awaitWriterStatus(
            routingState,
            "activation:1",
            CaptureRoutingState.WriterStatus.RETIRED
        );
        publisher.close();
    }

    @Test
    void drainingWriterWaitsForItsTerminalTrafficAcknowledgementBeforeRetirement()
        throws Exception {
        var producer = producer(false);
        var routingState = new CaptureRoutingState(ACTIVATION_ID, 1);
        var publisher = publisher(producer, routingState);
        installAndAcknowledge(producer, publisher, List.of(0));
        var oldRoute = routingState.routeNewConnection("old");

        var replacement = publisher.installAssignment(List.of(0));
        awaitHistorySize(producer, 2);
        assertTrue(producer.completeNext());
        assertEquals("activation:2", replacement.get(1, TimeUnit.SECONDS));
        assertEquals(2, producer.history().size());

        var terminalTraffic = publisher.publishTraffic(oldRoute, new byte[] { 1 }, true);
        awaitHistorySize(producer, 3);
        assertEquals(
            CaptureRoutingState.WriterStatus.DRAINING,
            routingState.writerStatus("activation:1", 0)
        );
        assertEquals(List.of("old"), routingState.snapshot("activation:1", 0));

        assertTrue(producer.completeNext());
        terminalTraffic.get(1, TimeUnit.SECONDS);
        awaitHistorySize(producer, 4);
        assertEquals(
            CaptureRoutingState.WriterStatus.RETIRING,
            routingState.writerStatus("activation:1", 0)
        );
        assertEquals(0, snapshotChunk(producer.history().get(3)).getConnectionIdsCount());
        publisher.close();
    }

    @Test
    void orderlyRetirementPublishesFinalManifestAndNoMoreWritesForCurrentWriter()
        throws Exception {
        var producer = producer(false);
        var routingState = new CaptureRoutingState(ACTIVATION_ID, 1);
        var publisher = publisher(producer, routingState);
        installAndAcknowledge(producer, publisher, List.of(0));

        var retirement = publisher.retireAllWriters();
        awaitHistorySize(producer, 2);
        assertEquals(
            CaptureRoutingState.WriterStatus.RETIRING,
            routingState.writerStatus("activation:1", 0)
        );
        assertEquals(0, snapshotChunk(producer.history().get(1)).getConnectionIdsCount());
        assertFalse(retirement.isDone());

        assertTrue(producer.completeNext());
        awaitHistorySize(producer, 3);
        assertTrue(CaptureKafkaPublisher.isRecordType(
            producer.history().get(2).headers(),
            CaptureKafkaPublisher.NO_MORE_WRITES_RECORD_TYPE
        ));
        assertFalse(retirement.isDone());

        assertTrue(producer.completeNext());
        retirement.get(1, TimeUnit.SECONDS);
        assertEquals(
            CaptureRoutingState.WriterStatus.RETIRED,
            routingState.writerStatus("activation:1", 0)
        );
        publisher.close();
    }

    @Test
    void orderlyRetirementCoversEveryPartitionOfTheCurrentWriter() throws Exception {
        var producer = producer(false);
        var routingState = new CaptureRoutingState(ACTIVATION_ID, 3);
        var publisher = publisher(producer, routingState);
        installAndAcknowledge(producer, publisher, List.of(0, 2));

        var retirement = publisher.retireAllWriters();
        awaitHistorySize(producer, 3);
        assertFalse(retirement.isDone());

        assertTrue(producer.completeNext());
        awaitHistorySize(producer, 4);
        assertFalse(retirement.isDone());
        assertTrue(producer.completeNext());
        awaitHistorySize(producer, 5);
        assertTrue(producer.completeNext());
        awaitHistorySize(producer, 6);
        assertTrue(producer.completeNext());
        retirement.get(1, TimeUnit.SECONDS);

        var retirementRecords = producer.history().subList(2, 6);
        assertEquals(List.of(0, 0, 2, 2), retirementRecords.stream()
            .map(ProducerRecord::partition)
            .toList());
        for (int i = 0; i < retirementRecords.size(); i += 2) {
            var finalManifest = retirementRecords.get(i);
            var noMoreWrites = retirementRecords.get(i + 1);
            assertTrue(CaptureKafkaPublisher.isRecordType(
                finalManifest.headers(),
                CaptureKafkaPublisher.LIVENESS_RECORD_TYPE
            ));
            assertEquals(0, snapshotChunk(finalManifest).getConnectionIdsCount());
            assertTrue(CaptureKafkaPublisher.isRecordType(
                noMoreWrites.headers(),
                CaptureKafkaPublisher.NO_MORE_WRITES_RECORD_TYPE
            ));
        }
        assertEquals(
            CaptureRoutingState.WriterStatus.RETIRED,
            routingState.writerStatus("activation:1", 0)
        );
        assertEquals(
            CaptureRoutingState.WriterStatus.RETIRED,
            routingState.writerStatus("activation:1", 2)
        );
        publisher.close();
    }

    @Test
    void failedFinalManifestPreventsNoMoreWritesAndFailsOrderlyRetirement() throws Exception {
        var producer = producer(false);
        var routingState = new CaptureRoutingState(ACTIVATION_ID, 1);
        var publisher = publisher(producer, routingState);
        installAndAcknowledge(producer, publisher, List.of(0));

        var retirement = publisher.retireAllWriters();
        awaitHistorySize(producer, 2);
        assertTrue(producer.errorNext(new IllegalStateException("final manifest failed")));

        assertThrows(ExecutionException.class, () -> retirement.get(1, TimeUnit.SECONDS));
        assertEquals(2, producer.history().size());
        assertEquals(
            CaptureRoutingState.WriterStatus.RETIRING,
            routingState.writerStatus("activation:1", 0)
        );
        publisher.close();
    }

    @Test
    void failedNoMoreWritesLeavesWriterUnretiredAndFailsOrderlyRetirement() throws Exception {
        var producer = producer(false);
        var routingState = new CaptureRoutingState(ACTIVATION_ID, 1);
        var publisher = publisher(producer, routingState);
        installAndAcknowledge(producer, publisher, List.of(0));

        var retirement = publisher.retireAllWriters();
        awaitHistorySize(producer, 2);
        assertTrue(producer.completeNext());
        awaitHistorySize(producer, 3);
        assertTrue(producer.errorNext(new IllegalStateException("NoMoreWrites failed")));

        assertThrows(ExecutionException.class, () -> retirement.get(1, TimeUnit.SECONDS));
        assertEquals(
            CaptureRoutingState.WriterStatus.RETIRING,
            routingState.writerStatus("activation:1", 0)
        );
        publisher.close();
    }

    @Test
    void manifestDeadlineWhileNoMoreWritesIsPendingLeavesWriterUnretired() throws Exception {
        var producer = producer(false);
        var routingState = new CaptureRoutingState(ACTIVATION_ID, 1);
        var writeGate = new CaptureKafkaWriteGate();
        var terminalFailure = new AtomicReference<Throwable>();
        writeGate.addTerminalFailureListener(terminalFailure::set);
        var publisher = new CaptureKafkaPublisher(
            producer,
            TOPIC,
            routingState,
            MESSAGE_SIZE,
            Duration.ofMillis(150),
            Duration.ofMillis(200),
            Clock.fixed(Instant.ofEpochMilli(1_234), ZoneOffset.UTC),
            writeGate,
            ignored -> {}
        );
        installAndAcknowledge(producer, publisher, List.of(0));

        var retirement = publisher.retireAllWriters();
        awaitHistorySize(producer, 2);
        assertTrue(producer.completeNext());
        awaitHistorySize(producer, 3);

        var failure = assertThrows(
            ExecutionException.class,
            () -> retirement.get(2, TimeUnit.SECONDS)
        ).getCause();
        assertTrue(failure instanceof TimeoutException);
        assertSame(failure, terminalFailure.get());
        assertEquals(
            CaptureRoutingState.WriterStatus.RETIRING,
            routingState.writerStatus("activation:1", 0)
        );
        assertEquals(3, producer.history().size(), "The proxy must not resubmit NoMoreWrites");

        assertTrue(producer.completeNext(), "The original NoMoreWrites may acknowledge after the deadline");
        Thread.sleep(25);
        assertEquals(
            CaptureRoutingState.WriterStatus.RETIRING,
            routingState.writerStatus("activation:1", 0)
        );
        publisher.close();
    }

    @Test
    void snapshotChunksUseStableProtocolFieldsAndStayWithinThePayloadLimit() throws Exception {
        var producer = producer(true);
        var routingState = new CaptureRoutingState(ACTIVATION_ID, 1);
        var publisher = publisher(producer, routingState);
        publisher.installAssignment(List.of(0)).get(1, TimeUnit.SECONDS);
        for (int i = 0; i < 40; ++i) {
            routingState.routeNewConnection("connection-" + i + "-" + "x".repeat(30));
        }

        publisher.publishLivenessSnapshotNow().get(1, TimeUnit.SECONDS);

        var periodicRecords = producer.history().subList(1, producer.history().size());
        assertTrue(periodicRecords.size() > 1);
        int expectedChunks = periodicRecords.size();
        for (int i = 0; i < expectedChunks; ++i) {
            var record = periodicRecords.get(i);
            var chunk = snapshotChunk(record);
            assertEquals("activation:1", chunk.getWriterNodeId());
            assertEquals(0, chunk.getPartition());
            assertEquals(1, chunk.getManifestCycle());
            assertEquals(i, chunk.getChunkIndex());
            assertEquals(expectedChunks, chunk.getChunkCount());
            assertTrue(record.value().length <= MESSAGE_SIZE - KafkaCaptureFactory.KAFKA_MESSAGE_OVERHEAD_BYTES);
        }
        publisher.close();
    }

    @Test
    void trafficUsesTheImmutableRouteSelectedWhenTheConnectionStarts() throws Exception {
        var producer = producer(true);
        var routingState = new CaptureRoutingState(ACTIVATION_ID, 3);
        var publisher = publisher(producer, routingState);
        publisher.installAssignment(List.of(0, 2)).get(1, TimeUnit.SECONDS);
        var route = routingState.routeNewConnection("connection");

        publisher.publishTraffic(route, new byte[] { 1, 2 }, false)
            .get(1, TimeUnit.SECONDS);

        var trafficRecord = producer.history().get(2);
        assertEquals(route.partition(), trafficRecord.partition());
        assertTrue(CaptureKafkaPublisher.isRecordType(
            trafficRecord.headers(),
            CaptureKafkaPublisher.TRAFFIC_RECORD_TYPE
        ));
        publisher.close();
    }

    @Test
    void trafficFailureDoesNotRemoveTheConnection() throws Exception {
        var producer = producer(false);
        var routingState = new CaptureRoutingState(ACTIVATION_ID, 1);
        var publisher = publisher(producer, routingState);
        installAndAcknowledge(producer, publisher, List.of(0));
        var route = routingState.routeNewConnection("connection");

        var finalSend = publisher.publishTraffic(route, new byte[] { 1 }, true);
        awaitHistorySize(producer, 2);
        assertTrue(producer.errorNext(new IllegalStateException("send failed")));

        assertThrows(ExecutionException.class, () -> finalSend.get(1, TimeUnit.SECONDS));
        assertEquals(List.of("connection"), routingState.snapshot(route.writerNodeId(), 0));
        publisher.close();
    }

    @Test
    void trafficAfterAnAcceptedTerminalRecordIsAnUnstableProcessFailure() throws Exception {
        var producer = producer(false);
        var routingState = new CaptureRoutingState(ACTIVATION_ID, 1);
        var unstableFailure = new AtomicReference<Throwable>();
        var publisher = publisher(producer, routingState, unstableFailure::set);
        installAndAcknowledge(producer, publisher, List.of(0));
        var route = routingState.routeNewConnection("connection");

        publisher.publishTraffic(route, new byte[] { 1 }, true);
        awaitHistorySize(producer, 2);
        var invalid = publisher.publishTraffic(route, new byte[] { 2 }, false);

        var failure = assertThrows(
            ExecutionException.class,
            () -> invalid.get(1, TimeUnit.SECONDS)
        ).getCause();
        assertTrue(failure.getMessage().contains("followed the terminal record"));
        assertEquals(failure, unstableFailure.get());
        assertEquals(2, producer.history().size());
        publisher.close();
    }

    @Test
    void corruptedWriterRetirementStateIsAnUnstableProcessFailure() throws Exception {
        var producer = producer(false);
        var routingState = new CaptureRoutingState(ACTIVATION_ID, 1);
        var assignment = routingState.prepareAssignment(List.of(0));
        var initialManifest = routingState.prepareInitialManifests(assignment).getFirst();
        routingState.activateAssignment(assignment);
        var unstableFailure = new AtomicReference<Throwable>();
        var publisher = publisher(producer, routingState, unstableFailure::set);

        var retirement = publisher.retireAllWriters();
        awaitHistorySize(producer, 1);
        assertTrue(producer.completeNext());
        awaitHistorySize(producer, 2);

        routingState.completeWriterRetirement(initialManifest);
        assertTrue(producer.completeNext());

        var failure = assertThrows(
            ExecutionException.class,
            () -> retirement.get(1, TimeUnit.SECONDS)
        ).getCause();
        assertTrue(failure instanceof CorruptedCaptureStateException);
        assertEquals(failure, unstableFailure.get());
        publisher.close();
    }

    @Test
    void diagnosticManifestTimestampsIncreaseWhenClockMovesBackward() throws Exception {
        var producer = producer(true);
        var routingState = new CaptureRoutingState(ACTIVATION_ID, 1);
        var publisher = publisher(
            producer,
            routingState,
            new SequenceClock(1234, 1234, 1200)
        );
        publisher.installAssignment(List.of(0)).get(1, TimeUnit.SECONDS);
        publisher.publishLivenessSnapshotNow().get(1, TimeUnit.SECONDS);
        publisher.publishLivenessSnapshotNow().get(1, TimeUnit.SECONDS);

        assertEquals(1234, snapshotChunk(producer.history().get(0)).getEmittedAtMillis());
        assertEquals(1235, snapshotChunk(producer.history().get(1)).getEmittedAtMillis());
        assertEquals(1236, snapshotChunk(producer.history().get(2)).getEmittedAtMillis());
        publisher.close();
    }

    private static void installAndAcknowledge(
        MockProducer<String, byte[]> producer,
        CaptureKafkaPublisher publisher,
        List<Integer> partitions
    ) throws Exception {
        int expectedHistory = producer.history().size() + partitions.size();
        var install = publisher.installAssignment(partitions);
        awaitHistorySize(producer, expectedHistory);
        for (int i = 0; i < partitions.size(); ++i) {
            assertTrue(producer.completeNext());
        }
        install.get(1, TimeUnit.SECONDS);
    }

    private static CaptureKafkaPublisher publisher(
        MockProducer<String, byte[]> producer,
        CaptureRoutingState routingState
    ) {
        return publisher(
            producer,
            routingState,
            Clock.fixed(Instant.ofEpochMilli(1234), ZoneOffset.UTC)
        );
    }

    private static CaptureKafkaPublisher publisher(
        MockProducer<String, byte[]> producer,
        CaptureRoutingState routingState,
        Clock clock
    ) {
        return publisher(producer, routingState, clock, ignored -> {});
    }

    private static CaptureKafkaPublisher publisher(
        MockProducer<String, byte[]> producer,
        CaptureRoutingState routingState,
        Consumer<Throwable> unstableProcessFailureCallback
    ) {
        return publisher(
            producer,
            routingState,
            Clock.fixed(Instant.ofEpochMilli(1234), ZoneOffset.UTC),
            unstableProcessFailureCallback
        );
    }

    private static CaptureKafkaPublisher publisher(
        MockProducer<String, byte[]> producer,
        CaptureRoutingState routingState,
        Clock clock,
        Consumer<Throwable> unstableProcessFailureCallback
    ) {
        return new CaptureKafkaPublisher(
            producer,
            TOPIC,
            routingState,
            MESSAGE_SIZE,
            Duration.ofDays(1),
            Duration.ofDays(2),
            clock,
            CaptureKafkaWriteGate.unrestricted(),
            unstableProcessFailureCallback
        );
    }

    private static MockProducer<String, byte[]> producer(boolean autoComplete) {
        return new LogAppendTimeMockProducer(
            autoComplete,
            null,
            new StringSerializer(),
            new ByteArraySerializer()
        );
    }

    private static List<String> connectionIds(ProducerRecord<String, byte[]> record) throws Exception {
        return snapshotChunk(record)
            .getConnectionIdsList()
            .stream()
            .map(com.google.protobuf.ByteString::toStringUtf8)
            .toList();
    }

    private static LivenessSnapshotChunk snapshotChunk(ProducerRecord<String, byte[]> record)
        throws Exception {
        return LivenessSnapshotChunk.parseFrom(record.value());
    }

    private static String headerValue(ProducerRecord<String, byte[]> record, String name) {
        var header = record.headers().lastHeader(name);
        return header == null ? null : new String(header.value(), java.nio.charset.StandardCharsets.UTF_8);
    }

    private static void awaitHistorySize(MockProducer<String, byte[]> producer, int expected)
        throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
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

    private static final class SequenceClock extends Clock {
        private final long[] values;
        private int index;

        private SequenceClock(long... values) {
            this.values = values.clone();
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return Instant.ofEpochMilli(millis());
        }

        @Override
        public long millis() {
            int current = Math.min(index, values.length - 1);
            index++;
            return values[current];
        }
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
            var metadata = new RecordMetadata(
                new TopicPartition(record.topic(), record.partition()),
                0,
                0,
                timestampForSendIndex.applyAsLong(sendIndex++),
                0,
                record.value().length
            );
            callback.onCompletion(metadata, null);
            return CompletableFuture.completedFuture(metadata);
        }
    }

    private static final class LogAppendTimeMockProducer extends MockProducer<String, byte[]> {
        private LogAppendTimeMockProducer(
            boolean autoComplete,
            org.apache.kafka.clients.producer.Partitioner partitioner,
            StringSerializer keySerializer,
            ByteArraySerializer valueSerializer
        ) {
            super(autoComplete, partitioner, keySerializer, valueSerializer);
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
}
