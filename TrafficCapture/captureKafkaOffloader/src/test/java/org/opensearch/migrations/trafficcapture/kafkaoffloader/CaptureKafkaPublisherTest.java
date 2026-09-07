package org.opensearch.migrations.trafficcapture.kafkaoffloader;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.opensearch.migrations.trafficcapture.protos.ProxyLivenessSnapshotChunk;
import org.opensearch.migrations.trafficcapture.protos.ProxyNoMoreWrites;

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
    private static final String NODE_ID = "node";
    private static final int MESSAGE_SIZE = 1024;

    @Test
    void finalRecordMustBeAcknowledgedBeforeACompleteManifestCanOmitConnection() throws Exception {
        var producer = producer(false);
        var registry = new ProxyLivenessRegistry();
        var plan = PartitionRoutingPlan.forTopic(1, 1, NODE_ID);
        var publisher = publisher(producer, plan, registry);
        registry.register("connection", 0);

        var finalSend = publisher.publishTraffic("connection", 0, new byte[] { 1 }, true);
        awaitHistorySize(producer, 1);
        var manifestBeforeAcknowledgement = publisher.publishLivenessSnapshotNow();
        awaitHistorySize(producer, 2);

        assertEquals(List.of("connection"), registry.snapshot(0));
        assertFalse(finalSend.isDone());
        assertEquals(List.of("connection"), openConnections(producer.history().get(1)));

        assertTrue(producer.completeNext());
        finalSend.get(1, TimeUnit.SECONDS);
        assertEquals(List.of(), registry.snapshot(0));
        assertTrue(producer.completeNext());
        manifestBeforeAcknowledgement.get(1, TimeUnit.SECONDS);

        var manifestAfterAcknowledgement = publisher.publishLivenessSnapshotNow();
        awaitHistorySize(producer, 3);
        assertEquals(List.of(), openConnections(producer.history().get(2)));
        assertTrue(producer.completeNext());
        manifestAfterAcknowledgement.get(1, TimeUnit.SECONDS);
        publisher.close();
    }

    @Test
    void registrationPrecedesBothFirstTrafficAndAnyLaterManifestCopy() throws Exception {
        var producer = producer(true);
        var registry = new ProxyLivenessRegistry();
        var plan = PartitionRoutingPlan.forTopic(1, 1, NODE_ID);
        var publisher = publisher(producer, plan, registry);

        registry.register("connection", 0);
        publisher.publishLivenessSnapshotNow().get(1, TimeUnit.SECONDS);
        publisher.publishTraffic("connection", 0, new byte[] { 1 }, false)
            .get(1, TimeUnit.SECONDS);

        assertEquals(List.of("connection"), openConnections(producer.history().get(0)));
        assertTrue(CaptureKafkaPublisher.isRecordType(
            producer.history().get(1).headers(),
            CaptureKafkaPublisher.TRAFFIC_RECORD_TYPE
        ));
        publisher.close();
    }

    @Test
    void snapshotsCoverEveryShardPartitionIncludingEmptySets() throws Exception {
        var producer = producer(true);
        var registry = new ProxyLivenessRegistry();
        var plan = PartitionRoutingPlan.forTopic(3, 3, NODE_ID);
        var publisher = publisher(producer, plan, registry);
        var connection = "connection";
        int connectionPartition = plan.partitionFor(connection);
        registry.register(connection, connectionPartition);

        publisher.publishLivenessSnapshotNow().get(1, TimeUnit.SECONDS);

        assertEquals(3, producer.history().size());
        for (var record : producer.history()) {
            assertTrue(CaptureKafkaPublisher.isRecordType(
                record.headers(),
                CaptureKafkaPublisher.LIVENESS_RECORD_TYPE
            ));
            var chunk = ProxyLivenessSnapshotChunk.parseFrom(record.value());
            assertEquals(record.partition(), chunk.getPartition());
            assertEquals(plan.getRoutingPlanId(), chunk.getRoutingPlanId());
            assertEquals(0, chunk.getChunkIndex());
            assertEquals(1, chunk.getChunkCount());
            if (record.partition() == connectionPartition) {
                assertEquals(List.of(connection), chunk.getOpenConnectionsList()
                    .stream()
                    .map(com.google.protobuf.ByteString::toStringUtf8)
                    .toList());
            } else {
                assertEquals(0, chunk.getOpenConnectionsCount());
            }
        }
        publisher.close();
    }

    @Test
    void snapshotsCoverAssignedPartitionsAndRevokedPartitionsThatAreStillDraining() throws Exception {
        var producer = producer(true);
        var registry = new ProxyLivenessRegistry();
        var plan = PartitionRoutingPlan.forTopic(3, 3, NODE_ID);
        var assignment = new CapturePartitionAssignment(3, List.of(0, 1, 2));
        var publisher = publisher(producer, plan, assignment, registry);
        registry.register("draining", 2);
        assignment.replaceAssignedPartitions(List.of(0, 1));

        publisher.publishLivenessSnapshotNow().get(1, TimeUnit.SECONDS);

        assertEquals(List.of(0, 1, 2), producer.history().stream()
            .map(ProducerRecord::partition)
            .toList());
        assertEquals(
            List.of("draining"),
            openConnections(producer.history().get(2))
        );
        publisher.close();
    }

    @Test
    void snapshotChunksAreCompleteBoundedAndNonInterleaved() throws Exception {
        var producer = producer(true);
        var registry = new ProxyLivenessRegistry();
        var plan = PartitionRoutingPlan.forTopic(1, 1, NODE_ID);
        var publisher = publisher(producer, plan, registry);
        for (int i = 0; i < 40; ++i) {
            registry.register("connection-" + i + "-" + "x".repeat(30), 0);
        }

        publisher.publishLivenessSnapshotNow().get(1, TimeUnit.SECONDS);

        assertTrue(producer.history().size() > 1);
        int expectedChunks = producer.history().size();
        long expectedTimestamp = snapshotChunk(producer.history().get(0)).getEmittedAtMillis();
        for (int i = 0; i < expectedChunks; ++i) {
            var record = producer.history().get(i);
            var chunk = snapshotChunk(record);
            assertEquals(i, chunk.getChunkIndex());
            assertEquals(expectedChunks, chunk.getChunkCount());
            assertEquals(expectedTimestamp, chunk.getEmittedAtMillis());
            assertTrue(record.value().length <= MESSAGE_SIZE - KafkaCaptureFactory.KAFKA_MESSAGE_OVERHEAD_BYTES);
        }
        publisher.close();
    }

    @Test
    void snapshotTimestampsIncreaseWhenTheClockStallsOrMovesBackward() throws Exception {
        var producer = producer(true);
        var registry = new ProxyLivenessRegistry();
        var plan = PartitionRoutingPlan.forTopic(1, 1, NODE_ID);
        var publisher = publisher(producer, plan, registry, new SequenceClock(1234, 1234, 1200));

        publisher.publishLivenessSnapshotNow().get(1, TimeUnit.SECONDS);
        publisher.publishLivenessSnapshotNow().get(1, TimeUnit.SECONDS);
        publisher.publishLivenessSnapshotNow().get(1, TimeUnit.SECONDS);

        assertEquals(1234, snapshotChunk(producer.history().get(0)).getEmittedAtMillis());
        assertEquals(1235, snapshotChunk(producer.history().get(1)).getEmittedAtMillis());
        assertEquals(1236, snapshotChunk(producer.history().get(2)).getEmittedAtMillis());
        publisher.close();
    }

    @Test
    void noMoreWritesUsesTheOrderedControlLaneAndStrictPartitionTimestamp() throws Exception {
        var producer = producer(true);
        var registry = new ProxyLivenessRegistry();
        var plan = PartitionRoutingPlan.forTopic(1, 1, NODE_ID);
        var publisher = publisher(producer, plan, registry);

        publisher.publishLivenessSnapshotNow().get(1, TimeUnit.SECONDS);
        publisher.publishNoMoreWrites(NODE_ID, 0, "surviving-node")
            .get(1, TimeUnit.SECONDS);

        var record = producer.history().get(1);
        assertEquals(0, record.partition());
        assertTrue(CaptureKafkaPublisher.isRecordType(
            record.headers(),
            CaptureKafkaPublisher.NO_MORE_WRITES_RECORD_TYPE
        ));
        var declaration = ProxyNoMoreWrites.parseFrom(record.value());
        assertEquals(NODE_ID, declaration.getNodeId());
        assertEquals(0, declaration.getPartition());
        assertEquals("surviving-node", declaration.getDeclaredBy());
        assertEquals(1235, declaration.getEmittedAtMillis());
        publisher.close();
    }

    @Test
    void noMoreWritesRejectsInvalidIdentityAndPartition() {
        var producer = producer(true);
        var registry = new ProxyLivenessRegistry();
        var plan = PartitionRoutingPlan.forTopic(1, 1, NODE_ID);
        var publisher = publisher(producer, plan, registry);

        assertThrows(
            ExecutionException.class,
            () -> publisher.publishNoMoreWrites("", 0, NODE_ID).get(1, TimeUnit.SECONDS)
        );
        assertThrows(
            ExecutionException.class,
            () -> publisher.publishNoMoreWrites(NODE_ID, 1, NODE_ID).get(1, TimeUnit.SECONDS)
        );
        publisher.close();
    }

    @Test
    void trafficFailureKeepsConnectionOpenAndStopsDeclarations() throws Exception {
        var producer = producer(false);
        var registry = new ProxyLivenessRegistry();
        var plan = PartitionRoutingPlan.forTopic(1, 1, NODE_ID);
        var publisher = publisher(producer, plan, registry);
        registry.register("connection", 0);

        var finalSend = publisher.publishTraffic("connection", 0, new byte[] { 1 }, true);
        awaitHistorySize(producer, 1);
        assertTrue(producer.errorNext(new IllegalStateException("send failed")));

        assertThrows(ExecutionException.class, () -> finalSend.get(1, TimeUnit.SECONDS));
        assertEquals(List.of("connection"), registry.snapshot(0));
        var snapshot = publisher.publishLivenessSnapshotNow();
        assertThrows(ExecutionException.class, () -> snapshot.get(1, TimeUnit.SECONDS));
        assertEquals(1, producer.history().size());
        publisher.close();
    }

    @Test
    void terminalGateFailsInFlightTrafficWithoutRemovingTheConnection() throws Exception {
        var producer = producer(false);
        var registry = new ProxyLivenessRegistry();
        var plan = PartitionRoutingPlan.forTopic(1, 1, NODE_ID);
        var gate = new CaptureKafkaWriteGate(Duration.ofSeconds(1), new AtomicLong()::get);
        gate.recordSuccessfulPoll();
        var publisher = publisher(producer, plan, registry, gate);
        registry.register("connection", 0);

        var finalSend = publisher.publishTraffic("connection", 0, new byte[] { 1 }, true);
        awaitHistorySize(producer, 1);
        var terminalFailure = new IllegalStateException("membership lost");
        gate.trip(terminalFailure);

        var failure = assertThrows(
            ExecutionException.class,
            () -> finalSend.get(1, TimeUnit.SECONDS)
        );
        assertEquals(terminalFailure, failure.getCause());
        assertEquals(List.of("connection"), registry.snapshot(0));

        assertTrue(producer.completeNext());
        assertEquals(List.of("connection"), registry.snapshot(0));
        publisher.close();
    }

    @Test
    void aLaterSuccessfulPollCannotReopenAStalePublisher() throws Exception {
        var producer = producer(true);
        var registry = new ProxyLivenessRegistry();
        var plan = PartitionRoutingPlan.forTopic(1, 1, NODE_ID);
        var ticker = new AtomicLong();
        var gate = new CaptureKafkaWriteGate(Duration.ofSeconds(1), ticker::get);
        gate.recordSuccessfulPoll();
        var publisher = publisher(producer, plan, registry, gate);
        registry.register("connection", 0);

        publisher.publishTraffic("connection", 0, new byte[] { 1 }, false)
            .get(1, TimeUnit.SECONDS);
        ticker.set(Duration.ofSeconds(2).toNanos());
        assertThrows(
            ExecutionException.class,
            () -> publisher.publishTraffic("connection", 0, new byte[] { 2 }, false)
                .get(1, TimeUnit.SECONDS)
        );
        gate.recordSuccessfulPoll();
        assertThrows(
            ExecutionException.class,
            () -> publisher.publishTraffic("connection", 0, new byte[] { 3 }, false)
                .get(1, TimeUnit.SECONDS)
        );

        assertEquals(1, producer.history().size());
        publisher.close();
    }

    @Test
    void trafficRecordsUseExplicitPlanPartitionAndTypeHeader() throws Exception {
        var producer = producer(true);
        var registry = new ProxyLivenessRegistry();
        var plan = PartitionRoutingPlan.forTopic(8, 3, NODE_ID);
        var publisher = publisher(producer, plan, registry);
        var connection = "connection";
        int partition = plan.partitionFor(connection);
        registry.register(connection, partition);

        publisher.publishTraffic(connection, partition, new byte[] { 1, 2 }, false)
            .get(1, TimeUnit.SECONDS);

        ProducerRecord<String, byte[]> record = producer.history().get(0);
        assertEquals(partition, record.partition());
        assertTrue(plan.getSelectedPartitions().contains(record.partition()));
        assertTrue(CaptureKafkaPublisher.isRecordType(
            record.headers(),
            CaptureKafkaPublisher.TRAFFIC_RECORD_TYPE
        ));
        publisher.close();
    }

    @Test
    void trafficUsesThePartitionStoredAtAdmissionInsteadOfRecomputingTheHashRoute() throws Exception {
        var producer = producer(true);
        var registry = new ProxyLivenessRegistry();
        var plan = PartitionRoutingPlan.forTopic(8, 3, NODE_ID);
        var publisher = publisher(producer, plan, registry);
        var connection = "connection";
        int hashPartition = plan.partitionFor(connection);
        int admittedPartition = plan.getSelectedPartitions()
            .stream()
            .filter(partition -> partition != hashPartition)
            .findFirst()
            .orElseThrow();
        registry.register(connection, admittedPartition);

        publisher.publishTraffic(connection, admittedPartition, new byte[] { 1, 2 }, false)
            .get(1, TimeUnit.SECONDS);

        assertEquals(admittedPartition, producer.history().get(0).partition());
        assertThrows(
            ExecutionException.class,
            () -> publisher.publishTraffic(connection, hashPartition, new byte[] { 3 }, false)
                .get(1, TimeUnit.SECONDS)
        );
        publisher.close();
    }

    private static CaptureKafkaPublisher publisher(
        MockProducer<String, byte[]> producer,
        PartitionRoutingPlan plan,
        ProxyLivenessRegistry registry
    ) {
        return publisher(
            producer,
            plan,
            registry,
            Clock.fixed(Instant.ofEpochMilli(1234), ZoneOffset.UTC)
        );
    }

    private static CaptureKafkaPublisher publisher(
        MockProducer<String, byte[]> producer,
        PartitionRoutingPlan plan,
        CapturePartitionAssignment partitionAssignment,
        ProxyLivenessRegistry registry
    ) {
        return new CaptureKafkaPublisher(
            producer,
            TOPIC,
            NODE_ID,
            plan,
            partitionAssignment,
            registry,
            MESSAGE_SIZE,
            Duration.ofDays(1),
            Clock.fixed(Instant.ofEpochMilli(1234), ZoneOffset.UTC)
        );
    }

    private static CaptureKafkaPublisher publisher(
        MockProducer<String, byte[]> producer,
        PartitionRoutingPlan plan,
        ProxyLivenessRegistry registry,
        Clock clock
    ) {
        return new CaptureKafkaPublisher(
            producer,
            TOPIC,
            NODE_ID,
            plan,
            new CapturePartitionAssignment(
                plan.getTopicPartitionCount(),
                plan.getSelectedPartitions()
            ),
            registry,
            MESSAGE_SIZE,
            Duration.ofDays(1),
            clock
        );
    }

    private static CaptureKafkaPublisher publisher(
        MockProducer<String, byte[]> producer,
        PartitionRoutingPlan plan,
        ProxyLivenessRegistry registry,
        CaptureKafkaWriteGate writeGate
    ) {
        return new CaptureKafkaPublisher(
            producer,
            TOPIC,
            NODE_ID,
            plan,
            new CapturePartitionAssignment(
                plan.getTopicPartitionCount(),
                plan.getSelectedPartitions()
            ),
            registry,
            MESSAGE_SIZE,
            Duration.ofDays(1),
            Clock.fixed(Instant.ofEpochMilli(1234), ZoneOffset.UTC),
            writeGate
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

    private static List<String> openConnections(ProducerRecord<String, byte[]> record) throws Exception {
        return snapshotChunk(record)
            .getOpenConnectionsList()
            .stream()
            .map(com.google.protobuf.ByteString::toStringUtf8)
            .toList();
    }

    private static ProxyLivenessSnapshotChunk snapshotChunk(ProducerRecord<String, byte[]> record)
        throws Exception {
        return ProxyLivenessSnapshotChunk.parseFrom(record.value());
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
        private final long[] timestamps;
        private int index;

        private SequenceClock(long... timestamps) {
            this.timestamps = timestamps.clone();
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            if (!ZoneOffset.UTC.equals(zone)) {
                throw new IllegalArgumentException("Only UTC is supported");
            }
            return this;
        }

        @Override
        public synchronized Instant instant() {
            int current = Math.min(index++, timestamps.length - 1);
            return Instant.ofEpochMilli(timestamps[current]);
        }
    }
}
