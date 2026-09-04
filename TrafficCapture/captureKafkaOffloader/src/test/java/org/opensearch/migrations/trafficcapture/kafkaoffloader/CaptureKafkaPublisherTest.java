package org.opensearch.migrations.trafficcapture.kafkaoffloader;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import org.opensearch.migrations.trafficcapture.protos.ProxyLivenessSnapshotChunk;

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
    void finalRecordMustBeAcknowledgedBeforeConnectionCanBeOmitted() throws Exception {
        var producer = producer(false);
        var registry = new ProxyLivenessRegistry();
        var plan = PartitionRoutingPlan.forTopic(1, 1, NODE_ID);
        var publisher = publisher(producer, plan, registry);
        registry.register("connection", 0);

        var finalSend = publisher.publishTraffic("connection", 0, new byte[] { 1 }, true);
        awaitHistorySize(producer, 1);

        assertEquals(List.of("connection"), registry.snapshot(0));
        assertFalse(finalSend.isDone());

        assertTrue(producer.completeNext());
        finalSend.get(1, TimeUnit.SECONDS);
        assertEquals(List.of(), registry.snapshot(0));
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
        for (int i = 0; i < expectedChunks; ++i) {
            var record = producer.history().get(i);
            var chunk = ProxyLivenessSnapshotChunk.parseFrom(record.value());
            assertEquals(i, chunk.getChunkIndex());
            assertEquals(expectedChunks, chunk.getChunkCount());
            assertTrue(record.value().length <= MESSAGE_SIZE - KafkaCaptureFactory.KAFKA_MESSAGE_OVERHEAD_BYTES);
        }
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

    private static CaptureKafkaPublisher publisher(
        MockProducer<String, byte[]> producer,
        PartitionRoutingPlan plan,
        ProxyLivenessRegistry registry
    ) {
        return new CaptureKafkaPublisher(
            producer,
            TOPIC,
            NODE_ID,
            plan,
            registry,
            MESSAGE_SIZE,
            Duration.ofDays(1),
            Clock.fixed(Instant.ofEpochMilli(1234), ZoneOffset.UTC)
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

    private static void awaitHistorySize(MockProducer<String, byte[]> producer, int expected)
        throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
        while (producer.history().size() < expected && System.nanoTime() < deadline) {
            Thread.sleep(1);
        }
        assertEquals(expected, producer.history().size());
    }
}
