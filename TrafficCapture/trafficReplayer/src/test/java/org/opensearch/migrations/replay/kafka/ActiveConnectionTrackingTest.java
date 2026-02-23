package org.opensearch.migrations.replay.kafka;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.HashMap;

import org.opensearch.migrations.replay.datatypes.ITrafficStreamKey;
import org.opensearch.migrations.replay.traffic.expiration.ScopedConnectionIdKey;
import org.opensearch.migrations.tracing.InstrumentationTest;
import org.opensearch.migrations.trafficcapture.protos.ReadObservation;
import org.opensearch.migrations.trafficcapture.protos.TrafficObservation;
import org.opensearch.migrations.trafficcapture.protos.TrafficStream;

import com.google.protobuf.ByteString;
import com.google.protobuf.Timestamp;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.MockConsumer;
import org.apache.kafka.clients.consumer.OffsetResetStrategy;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tests #11, #12: Edge-path tests for partitionToActiveConnections tracking.
 */
class ActiveConnectionTrackingTest extends InstrumentationTest {

    private static final String TOPIC = "test-topic";

    /**
     * Test #11: Consume multiple streams for the same connection (keep-alive reuse).
     * Assert the connection remains in partitionToActiveConnections across keep-alive requests.
     */
    @Test
    void partitionToActiveConnections_connectionTrackedAcrossKeepAlive() throws Exception {
        var mc = new MockConsumer<String, byte[]>(OffsetResetStrategy.EARLIEST);
        var tp = new TopicPartition(TOPIC, 0);
        mc.updateBeginningOffsets(new HashMap<>(Collections.singletonMap(tp, 0L)));

        try (var source = new KafkaTrafficCaptureSource(rootContext, mc, TOPIC, Duration.ofHours(1))) {
            mc.schedulePollTask(() -> {
                mc.rebalance(Collections.singletonList(tp));
                // Two streams for the same connection (keep-alive reuse)
                for (int i = 0; i < 2; i++) {
                    var ts = TrafficStream.newBuilder()
                        .setNodeId("node1").setConnectionId("keep-alive-conn").setNumberOfThisLastChunk(0)
                        .addSubStream(TrafficObservation.newBuilder()
                            .setTs(Timestamp.newBuilder().setSeconds(1 + i).build())
                            .setRead(ReadObservation.newBuilder()
                                .setData(ByteString.copyFrom("GET / HTTP/1.1\r\n\r\n", StandardCharsets.UTF_8))
                                .build())
                            .build())
                        .build();
                    try (var baos = new ByteArrayOutputStream()) {
                        ts.writeTo(baos);
                        mc.addRecord(new ConsumerRecord<>(TOPIC, 0, i, "k", baos.toByteArray()));
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                }
            });

            source.readNextTrafficStreamChunk(rootContext::createReadChunkContext).get();

            var active = source.partitionToActiveConnections.get(0);
            Assertions.assertNotNull(active);
            var connKey = new ScopedConnectionIdKey("node1", "keep-alive-conn");
            Assertions.assertTrue(active.contains(connKey),
                "Connection must remain in partitionToActiveConnections across keep-alive streams");
        }
    }

    /**
     * Test #12: Add two connections to partitionToActiveConnections for the same partition.
     * Fire onConnectionDone for one connection. Assert only that connection is removed.
     */
    @Test
    void onConnectionDone_removesCorrectKeyFromActiveConnections() throws Exception {
        var mc = new MockConsumer<String, byte[]>(OffsetResetStrategy.EARLIEST);
        var tp = new TopicPartition(TOPIC, 0);
        mc.updateBeginningOffsets(new HashMap<>(Collections.singletonMap(tp, 0L)));

        try (var source = new KafkaTrafficCaptureSource(rootContext, mc, TOPIC, Duration.ofHours(1))) {
            mc.schedulePollTask(() -> {
                mc.rebalance(Collections.singletonList(tp));
                // Two distinct connections on partition 0
                for (int i = 0; i < 2; i++) {
                    var ts = TrafficStream.newBuilder()
                        .setNodeId("node1").setConnectionId("conn" + i).setNumberOfThisLastChunk(0)
                        .addSubStream(TrafficObservation.newBuilder()
                            .setTs(Timestamp.newBuilder().setSeconds(1).build())
                            .setRead(ReadObservation.newBuilder()
                                .setData(ByteString.copyFrom("GET / HTTP/1.1\r\n\r\n", StandardCharsets.UTF_8))
                                .build())
                            .build())
                        .build();
                    try (var baos = new ByteArrayOutputStream()) {
                        ts.writeTo(baos);
                        mc.addRecord(new ConsumerRecord<>(TOPIC, 0, i, "k", baos.toByteArray()));
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                }
            });

            source.readNextTrafficStreamChunk(rootContext::createReadChunkContext).get();

            var active = source.partitionToActiveConnections.get(0);
            Assertions.assertEquals(2, active.size(), "Both connections must be tracked");

            // Fire onConnectionDone for conn0 only
            var tsk = mock(ITrafficStreamKey.class);
            when(tsk.getNodeId()).thenReturn("node1");
            when(tsk.getConnectionId()).thenReturn("conn0");
            source.onConnectionDone(tsk);

            Assertions.assertEquals(1, active.size(), "Only one connection should remain after onConnectionDone");
            Assertions.assertFalse(active.contains(new ScopedConnectionIdKey("node1", "conn0")),
                "conn0 must be removed");
            Assertions.assertTrue(active.contains(new ScopedConnectionIdKey("node1", "conn1")),
                "conn1 must still be present");
        }
    }
}
