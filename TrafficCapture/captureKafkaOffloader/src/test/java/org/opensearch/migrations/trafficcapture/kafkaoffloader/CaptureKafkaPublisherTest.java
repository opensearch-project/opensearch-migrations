package org.opensearch.migrations.trafficcapture.kafkaoffloader;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import org.opensearch.migrations.trafficcapture.protos.LivenessSnapshotChunk;

import org.apache.kafka.clients.producer.MockProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
        assertThrows(IllegalStateException.class, () -> routingState.admitConnection("too-early"));
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
    void replacementUsesANewWriterOnlyAfterItsInitialManifestIsAcknowledged() throws Exception {
        var producer = producer(false);
        var routingState = new CaptureRoutingState(ACTIVATION_ID, 1);
        var publisher = publisher(producer, routingState);
        installAndAcknowledge(producer, publisher, List.of(0));
        var beforeReplacement = routingState.admitConnection("before");

        var replacement = publisher.installAssignment(List.of(0));
        awaitHistorySize(producer, 2);
        var whileReplacementIsPending = routingState.admitConnection("during");

        assertEquals("activation:1", beforeReplacement.writerNodeId());
        assertEquals("activation:1", whileReplacementIsPending.writerNodeId());
        assertEquals("activation:2", snapshotChunk(producer.history().get(1)).getWriterNodeId());

        assertTrue(producer.completeNext());
        assertEquals("activation:2", replacement.get(1, TimeUnit.SECONDS));
        assertEquals("activation:2", routingState.admitConnection("after").writerNodeId());
        publisher.close();
    }

    @Test
    void finalRecordAcknowledgementPrecedesManifestOmission() throws Exception {
        var producer = producer(false);
        var routingState = new CaptureRoutingState(ACTIVATION_ID, 1);
        var publisher = publisher(producer, routingState);
        installAndAcknowledge(producer, publisher, List.of(0));
        var route = routingState.admitConnection("connection");

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
        var oldRoute = routingState.admitConnection("old");
        publisher.installAssignment(List.of(0)).get(1, TimeUnit.SECONDS);
        var newRoute = routingState.admitConnection("new");

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
    void snapshotChunksUseStableProtocolFieldsAndStayWithinThePayloadLimit() throws Exception {
        var producer = producer(true);
        var routingState = new CaptureRoutingState(ACTIVATION_ID, 1);
        var publisher = publisher(producer, routingState);
        publisher.installAssignment(List.of(0)).get(1, TimeUnit.SECONDS);
        for (int i = 0; i < 40; ++i) {
            routingState.admitConnection("connection-" + i + "-" + "x".repeat(30));
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
    void trafficUsesTheImmutableRouteSelectedAtConnectionAdmission() throws Exception {
        var producer = producer(true);
        var routingState = new CaptureRoutingState(ACTIVATION_ID, 3);
        var publisher = publisher(producer, routingState);
        publisher.installAssignment(List.of(0, 2)).get(1, TimeUnit.SECONDS);
        var route = routingState.admitConnection("connection");

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
        var route = routingState.admitConnection("connection");

        var finalSend = publisher.publishTraffic(route, new byte[] { 1 }, true);
        awaitHistorySize(producer, 2);
        assertTrue(producer.errorNext(new IllegalStateException("send failed")));

        assertThrows(ExecutionException.class, () -> finalSend.get(1, TimeUnit.SECONDS));
        assertEquals(List.of("connection"), routingState.snapshot(route.writerNodeId(), 0));
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
        return new CaptureKafkaPublisher(
            producer,
            TOPIC,
            routingState,
            MESSAGE_SIZE,
            Duration.ofDays(1),
            clock
        );
    }

    private static MockProducer<String, byte[]> producer(boolean autoComplete) {
        return new MockProducer<>(
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

    private static void awaitHistorySize(MockProducer<String, byte[]> producer, int expected)
        throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
        while (producer.history().size() < expected && System.nanoTime() < deadline) {
            Thread.sleep(1);
        }
        assertEquals(expected, producer.history().size());
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
}
