package org.opensearch.migrations.replay.kafka;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Collections;
import java.util.HashMap;

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

/**
 * Tests for synthetic close drain accounting correctness (Plan tests #1, #2, #3, #5).
 */
class SyntheticCloseAccountingTest extends InstrumentationTest {

    private static final String TOPIC = "test-topic";

    /**
     * Test #1: Force a connection with non-zero sessionNumber. Assert onSessionClosed
     * decrements outstandingSyntheticCloseSessions.
     */
    @Test
    void syntheticClose_counterDecrements_withNonZeroSessionNumber() throws Exception {
        var mc = new MockConsumer<String, byte[]>(OffsetResetStrategy.EARLIEST);
        var tp = new TopicPartition(TOPIC, 0);
        mc.updateBeginningOffsets(new HashMap<>(Collections.singletonMap(tp, 0L)));

        try (var source = new KafkaTrafficCaptureSource(rootContext, mc, TOPIC, Duration.ofHours(1))) {
            // Manually register a synthetic close with sessionNumber=2
            var sessionKey = "conn1:2:5";
            source.pendingSyntheticCloses.put(sessionKey, Boolean.TRUE);
            source.outstandingSyntheticCloseSessions.set(1);

            source.onSessionClosed("conn1", 2, 5);

            Assertions.assertEquals(0, source.outstandingSyntheticCloseSessions.get(),
                "onSessionClosed must decrement counter for non-zero sessionNumber");
        }
    }

    /**
     * Test #2: Register synthetic close (counter=1). Fire regular close first → counter=0.
     * Fire synthetic close → counter stays at 0 (no double-decrement).
     */
    @Test
    void syntheticClose_exactlyOneDecrement_regularBeforeSynthetic() throws Exception {
        var mc = new MockConsumer<String, byte[]>(OffsetResetStrategy.EARLIEST);
        var tp = new TopicPartition(TOPIC, 0);
        mc.updateBeginningOffsets(new HashMap<>(Collections.singletonMap(tp, 0L)));

        try (var source = new KafkaTrafficCaptureSource(rootContext, mc, TOPIC, Duration.ofHours(1))) {
            var sessionKey = "conn1:0:3";
            source.pendingSyntheticCloses.put(sessionKey, Boolean.TRUE);
            source.outstandingSyntheticCloseSessions.set(1);

            // Regular close fires first (same key)
            source.onSessionClosed("conn1", 0, 3);
            Assertions.assertEquals(0, source.outstandingSyntheticCloseSessions.get(),
                "First onSessionClosed must decrement counter to 0");

            // Synthetic close fires second — must NOT double-decrement
            source.onSessionClosed("conn1", 0, 3);
            Assertions.assertEquals(0, source.outstandingSyntheticCloseSessions.get(),
                "Second onSessionClosed must not double-decrement (counter stays at 0)");
        }
    }

    /**
     * Test #3: Same as #2 but reversed order — synthetic close fires first.
     */
    @Test
    void syntheticClose_exactlyOneDecrement_syntheticBeforeRegular() throws Exception {
        var mc = new MockConsumer<String, byte[]>(OffsetResetStrategy.EARLIEST);
        var tp = new TopicPartition(TOPIC, 0);
        mc.updateBeginningOffsets(new HashMap<>(Collections.singletonMap(tp, 0L)));

        try (var source = new KafkaTrafficCaptureSource(rootContext, mc, TOPIC, Duration.ofHours(1))) {
            var sessionKey = "conn1:0:3";
            source.pendingSyntheticCloses.put(sessionKey, Boolean.TRUE);
            source.outstandingSyntheticCloseSessions.set(1);

            // Synthetic close fires first
            source.onSessionClosed("conn1", 0, 3);
            Assertions.assertEquals(0, source.outstandingSyntheticCloseSessions.get(),
                "First onSessionClosed (synthetic path) must decrement counter to 0");

            // Regular close fires second — must NOT double-decrement
            source.onSessionClosed("conn1", 0, 3);
            Assertions.assertEquals(0, source.outstandingSyntheticCloseSessions.get(),
                "Second onSessionClosed (regular path) must not double-decrement");
        }
    }

    /**
     * Test #5: Enqueue N synthetic closes (counter=N). Fire onSessionClosed for each.
     * Assert counter reaches 0 and readNextTrafficStreamSynchronously returns real records.
     */
    @Test
    void outstandingSyntheticCloseSessions_reachesZeroAfterAllSessionsClose() throws Exception {
        var mc = new MockConsumer<String, byte[]>(OffsetResetStrategy.EARLIEST);
        var tp = new TopicPartition(TOPIC, 0);
        mc.updateBeginningOffsets(new HashMap<>(Collections.singletonMap(tp, 0L)));

        try (var source = new KafkaTrafficCaptureSource(rootContext, mc, TOPIC, Duration.ofHours(1))) {
            int N = 3;
            // Register N synthetic closes
            for (int i = 0; i < N; i++) {
                source.pendingSyntheticCloses.put("conn" + i + ":0:1", Boolean.TRUE);
            }
            source.outstandingSyntheticCloseSessions.set(N);

            // Verify empty batch while counter > 0
            mc.schedulePollTask(() -> {
                mc.rebalance(Collections.singletonList(tp));
                addRecord(mc, tp, 0);
            });
            var emptyResult = source.readNextTrafficStreamChunk(rootContext::createReadChunkContext).get();
            Assertions.assertTrue(emptyResult.isEmpty(),
                "Must return empty batch while outstandingSyntheticCloseSessions > 0");

            // Close all sessions
            for (int i = 0; i < N; i++) {
                source.onSessionClosed("conn" + i, 0, 1);
            }
            Assertions.assertEquals(0, source.outstandingSyntheticCloseSessions.get(),
                "Counter must reach 0 after all sessions close");

            // Now real records should be returned
            var realResult = source.readNextTrafficStreamChunk(rootContext::createReadChunkContext).get();
            Assertions.assertFalse(realResult.isEmpty(),
                "Real records must be returned after counter reaches 0");
        }
    }

    private static void addRecord(MockConsumer<String, byte[]> mc, TopicPartition tp, long offset) {
        var ts = TrafficStream.newBuilder()
            .setNodeId("n").setConnectionId("c").setNumberOfThisLastChunk(0)
            .addSubStream(TrafficObservation.newBuilder()
                .setTs(Timestamp.newBuilder().setSeconds(1).build())
                .setRead(ReadObservation.newBuilder()
                    .setData(ByteString.copyFrom("GET / HTTP/1.1\r\n\r\n", StandardCharsets.UTF_8))
                    .build()).build())
            .build();
        try (var baos = new ByteArrayOutputStream()) {
            ts.writeTo(baos);
            mc.addRecord(new ConsumerRecord<>(tp.topic(), tp.partition(), offset, "k", baos.toByteArray()));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
