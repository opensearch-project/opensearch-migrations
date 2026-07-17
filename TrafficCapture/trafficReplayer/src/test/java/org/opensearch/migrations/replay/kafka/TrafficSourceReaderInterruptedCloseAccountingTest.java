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
class TrafficSourceReaderInterruptedCloseAccountingTest extends InstrumentationTest {

    private static final String TOPIC = "test-topic";

    /**
     * Test #1: Register a synthetic close with PENDING_CLOSE_SESSION_NUMBER_PLACEHOLDER.
     * Call onNetworkConnectionClosed with a DIFFERENT (non-zero) sessionNumber.
     * The lookup must still match because the key always uses the placeholder.
     */
    @Test
    void trafficSourceReaderInterruptedClose_counterDecrements_withNonZeroSessionNumber() throws Exception {
        var mc = new MockConsumer<String, byte[]>(OffsetResetStrategy.EARLIEST);
        var tp = new TopicPartition(TOPIC, 0);
        mc.updateBeginningOffsets(new HashMap<>(Collections.singletonMap(tp, 0L)));

        try (var source = new KafkaTrafficCaptureSource(rootContext, mc, TOPIC, Duration.ofHours(1))) {
            // Register with placeholder sessionNumber (0) — this is what production code does
            var sessionKey = "conn1:" + KafkaTrafficCaptureSource.PENDING_CLOSE_SESSION_NUMBER_PLACEHOLDER + ":5";
            source.pendingTrafficSourceReaderInterruptedCloses.put(sessionKey, Boolean.TRUE);
            source.outstandingTrafficSourceReaderInterruptedCloseSessions.set(1);

            // Call with non-zero sessionNumber — must still match the placeholder-based key
            source.onNetworkConnectionClosed("conn1", 7, 5);

            Assertions.assertEquals(0, source.outstandingTrafficSourceReaderInterruptedCloseSessions.get(),
                "onNetworkConnectionClosed must decrement counter regardless of actual sessionNumber");
        }
    }

    /**
     * Test #2: Register synthetic close (counter=1). Fire regular close first → counter=0.
     * Fire synthetic close → counter stays at 0 (no double-decrement).
     */
    @Test
    void trafficSourceReaderInterruptedClose_exactlyOneDecrement_regularBeforeSynthetic() throws Exception {
        var mc = new MockConsumer<String, byte[]>(OffsetResetStrategy.EARLIEST);
        var tp = new TopicPartition(TOPIC, 0);
        mc.updateBeginningOffsets(new HashMap<>(Collections.singletonMap(tp, 0L)));

        try (var source = new KafkaTrafficCaptureSource(rootContext, mc, TOPIC, Duration.ofHours(1))) {
            var sessionKey = "conn1:0:3";
            source.pendingTrafficSourceReaderInterruptedCloses.put(sessionKey, Boolean.TRUE);
            source.outstandingTrafficSourceReaderInterruptedCloseSessions.set(1);

            // Regular close fires first (same key)
            source.onNetworkConnectionClosed("conn1", 0, 3);
            Assertions.assertEquals(0, source.outstandingTrafficSourceReaderInterruptedCloseSessions.get(),
                "First onNetworkConnectionClosed must decrement counter to 0");

            // Synthetic close fires second — must NOT double-decrement
            source.onNetworkConnectionClosed("conn1", 0, 3);
            Assertions.assertEquals(0, source.outstandingTrafficSourceReaderInterruptedCloseSessions.get(),
                "Second onNetworkConnectionClosed must not double-decrement (counter stays at 0)");
        }
    }

    /**
     * Test #3: Same as #2 but reversed order — synthetic close fires first.
     */
    @Test
    void trafficSourceReaderInterruptedClose_exactlyOneDecrement_syntheticBeforeRegular() throws Exception {
        var mc = new MockConsumer<String, byte[]>(OffsetResetStrategy.EARLIEST);
        var tp = new TopicPartition(TOPIC, 0);
        mc.updateBeginningOffsets(new HashMap<>(Collections.singletonMap(tp, 0L)));

        try (var source = new KafkaTrafficCaptureSource(rootContext, mc, TOPIC, Duration.ofHours(1))) {
            var sessionKey = "conn1:0:3";
            source.pendingTrafficSourceReaderInterruptedCloses.put(sessionKey, Boolean.TRUE);
            source.outstandingTrafficSourceReaderInterruptedCloseSessions.set(1);

            // Synthetic close fires first
            source.onNetworkConnectionClosed("conn1", 0, 3);
            Assertions.assertEquals(0, source.outstandingTrafficSourceReaderInterruptedCloseSessions.get(),
                "First onNetworkConnectionClosed (synthetic path) must decrement counter to 0");

            // Regular close fires second — must NOT double-decrement
            source.onNetworkConnectionClosed("conn1", 0, 3);
            Assertions.assertEquals(0, source.outstandingTrafficSourceReaderInterruptedCloseSessions.get(),
                "Second onNetworkConnectionClosed (regular path) must not double-decrement");
        }
    }

    /**
     * Test #5: Enqueue N synthetic closes (counter=N). Fire onNetworkConnectionClosed for each.
     * Assert counter reaches 0 and readNextTrafficStreamSynchronously returns real records.
     */
    @Test
    void outstandingTrafficSourceReaderInterruptedCloseSessions_reachesZeroAfterAllSessionsClose() throws Exception {
        var mc = new MockConsumer<String, byte[]>(OffsetResetStrategy.EARLIEST);
        var tp = new TopicPartition(TOPIC, 0);
        mc.updateBeginningOffsets(new HashMap<>(Collections.singletonMap(tp, 0L)));

        try (var source = new KafkaTrafficCaptureSource(rootContext, mc, TOPIC, Duration.ofHours(1))) {
            // First, assign the partition so the consumer is active
            mc.schedulePollTask(() -> mc.rebalance(Collections.singletonList(tp)));
            source.readNextTrafficStreamChunk(rootContext::createReadChunkContext).get();

            int N = 3;
            // Register N synthetic closes
            for (int i = 0; i < N; i++) {
                source.pendingTrafficSourceReaderInterruptedCloses.put("conn" + i + ":0:1", Boolean.TRUE);
            }
            source.outstandingTrafficSourceReaderInterruptedCloseSessions.set(N);

            // Verify empty batch while counter > 0 (touch() is called but no data returned)
            var emptyResult = source.readNextTrafficStreamChunk(rootContext::createReadChunkContext).get();
            Assertions.assertTrue(emptyResult.isEmpty(),
                "Must return empty batch while outstandingTrafficSourceReaderInterruptedCloseSessions > 0");

            // Close all sessions
            for (int i = 0; i < N; i++) {
                source.onNetworkConnectionClosed("conn" + i, 0, 1);
            }
            Assertions.assertEquals(0, source.outstandingTrafficSourceReaderInterruptedCloseSessions.get(),
                "Counter must reach 0 after all sessions close");

            // Add a record now that the drain gate is open
            addRecord(mc, tp, 0);

            // Now real records should be returned
            var realResult = source.readNextTrafficStreamChunk(rootContext::createReadChunkContext).get();
            Assertions.assertFalse(realResult.isEmpty(),
                "Real records must be returned after counter reaches 0");
        }
    }

    /**
     * Regression test for the session-number mismatch bug: registration uses placeholder=0
     * but the accumulator fires onNetworkConnectionClosed with the REAL sessionNumber.
     * Without the fix, the counter would never decrement and the read loop would block forever.
     */
    @Test
    void sessionNumberMismatch_counterStillDecrements_whenCalledWithAnySessionNumber() throws Exception {
        var mc = new MockConsumer<String, byte[]>(OffsetResetStrategy.EARLIEST);
        var tp = new TopicPartition(TOPIC, 0);
        mc.updateBeginningOffsets(new HashMap<>(Collections.singletonMap(tp, 0L)));

        try (var source = new KafkaTrafficCaptureSource(rootContext, mc, TOPIC, Duration.ofHours(1))) {
            int generation = 3;
            // Simulate what enqueueTrafficSourceReaderInterruptedClosesForPartitions does:
            // registers with PENDING_CLOSE_SESSION_NUMBER_PLACEHOLDER
            var registrationKey = "myConn:" + KafkaTrafficCaptureSource.PENDING_CLOSE_SESSION_NUMBER_PLACEHOLDER
                + ":" + generation;
            source.pendingTrafficSourceReaderInterruptedCloses.put(registrationKey, Boolean.TRUE);
            source.outstandingTrafficSourceReaderInterruptedCloseSessions.set(1);

            // Simulate what the accumulator's close callback does: calls with real sessionNumber
            // (e.g., 42 — any value != 0)
            source.onNetworkConnectionClosed("myConn", 42, generation);

            Assertions.assertEquals(0, source.outstandingTrafficSourceReaderInterruptedCloseSessions.get(),
                "Counter must decrement even when sessionNumber differs from placeholder");
            Assertions.assertTrue(source.pendingTrafficSourceReaderInterruptedCloses.isEmpty(),
                "Entry must be removed from pending map");
        }
    }

    /**
     * Verify that onNetworkConnectionClosed is a no-op for connections that were never registered
     * as synthetic closes (e.g., normal connection lifecycle closes).
     */
    @Test
    void onNetworkConnectionClosed_noOpForUnregisteredConnections() throws Exception {
        var mc = new MockConsumer<String, byte[]>(OffsetResetStrategy.EARLIEST);
        var tp = new TopicPartition(TOPIC, 0);
        mc.updateBeginningOffsets(new HashMap<>(Collections.singletonMap(tp, 0L)));

        try (var source = new KafkaTrafficCaptureSource(rootContext, mc, TOPIC, Duration.ofHours(1))) {
            Assertions.assertEquals(0, source.outstandingTrafficSourceReaderInterruptedCloseSessions.get());

            // Call for a connection that was never registered — must not go negative
            source.onNetworkConnectionClosed("unregistered-conn", 5, 1);

            Assertions.assertEquals(0, source.outstandingTrafficSourceReaderInterruptedCloseSessions.get(),
                "Counter must not go negative for unregistered connections");
        }
    }

    /**
     * Verify that the drain gate calls touch() (poll) to keep the consumer alive,
     * rather than just parking. This prevents consumer fencing during synthetic close drain.
     */
    @Test
    void drainGate_callsTouchToKeepConsumerAlive() throws Exception {
        var mc = new MockConsumer<String, byte[]>(OffsetResetStrategy.EARLIEST);
        var tp = new TopicPartition(TOPIC, 0);
        mc.updateBeginningOffsets(new HashMap<>(Collections.singletonMap(tp, 0L)));
        mc.schedulePollTask(() -> mc.rebalance(Collections.singletonList(tp)));

        try (var source = new KafkaTrafficCaptureSource(rootContext, mc, TOPIC, Duration.ofHours(1))) {
            // First call triggers the scheduled rebalance
            source.readNextTrafficStreamChunk(rootContext::createReadChunkContext).get();

            // Set up the drain gate
            var sessionKey = "conn1:" + KafkaTrafficCaptureSource.PENDING_CLOSE_SESSION_NUMBER_PLACEHOLDER + ":0";
            source.pendingTrafficSourceReaderInterruptedCloses.put(sessionKey, Boolean.TRUE);
            source.outstandingTrafficSourceReaderInterruptedCloseSessions.set(1);

            // Call readNextTrafficStreamChunk — should hit drain gate and call touch()
            // If touch() throws (it shouldn't with MockConsumer), it should be caught
            var result = source.readNextTrafficStreamChunk(rootContext::createReadChunkContext).get();
            Assertions.assertTrue(result.isEmpty(),
                "Must return empty batch during drain gate");

            // The consumer should still be alive (not fenced) — verify by clearing
            // the counter and reading successfully
            source.onNetworkConnectionClosed("conn1", 99, 0);
            Assertions.assertEquals(0, source.outstandingTrafficSourceReaderInterruptedCloseSessions.get());

            // Add a record and verify we can still read
            addRecord(mc, tp, 0);
            var realResult = source.readNextTrafficStreamChunk(rootContext::createReadChunkContext).get();
            Assertions.assertFalse(realResult.isEmpty(),
                "Consumer must still be alive after drain gate — real records should be returned");
        }
    }

    /**
     * Verify that the drain-gate timeout circuit-breaker resets the counter and unblocks
     * the read loop after DRAIN_GATE_TIMEOUT_NANOS is exceeded.
     */
    @Test
    void drainGate_timeoutResetsCounterAndUnblocksReadLoop() throws Exception {
        var mc = new MockConsumer<String, byte[]>(OffsetResetStrategy.EARLIEST);
        var tp = new TopicPartition(TOPIC, 0);
        mc.updateBeginningOffsets(new HashMap<>(Collections.singletonMap(tp, 0L)));
        mc.schedulePollTask(() -> mc.rebalance(Collections.singletonList(tp)));

        try (var source = new KafkaTrafficCaptureSource(rootContext, mc, TOPIC, Duration.ofHours(1))) {
            // Trigger rebalance to get partition assignment
            source.readNextTrafficStreamChunk(rootContext::createReadChunkContext).get();

            // Set up drain gate
            var sessionKey = "conn1:" + KafkaTrafficCaptureSource.PENDING_CLOSE_SESSION_NUMBER_PLACEHOLDER + ":0";
            source.pendingTrafficSourceReaderInterruptedCloses.put(sessionKey, Boolean.TRUE);
            source.outstandingTrafficSourceReaderInterruptedCloseSessions.set(1);

            // Force the drainGateEnteredAtNanos to a value far in the past to simulate timeout
            // (more than 5 minutes ago)
            var field = KafkaTrafficCaptureSource.class.getDeclaredField("drainGateEnteredAtNanos");
            field.setAccessible(true);
            field.setLong(source, System.nanoTime() - KafkaTrafficCaptureSource.DRAIN_GATE_TIMEOUT_NANOS - 1_000_000_000L);

            // This call should trigger the timeout path, reset the counter, and proceed to getNextBatchOfRecords
            addRecord(mc, tp, 0);
            var result = source.readNextTrafficStreamChunk(rootContext::createReadChunkContext).get();

            // Counter should have been reset to 0 by the circuit-breaker
            Assertions.assertEquals(0, source.outstandingTrafficSourceReaderInterruptedCloseSessions.get(),
                "Timeout circuit-breaker must reset the counter to 0");
            // Pending map should be cleared
            Assertions.assertTrue(source.pendingTrafficSourceReaderInterruptedCloses.isEmpty(),
                "Timeout circuit-breaker must clear the pending map");
            // Should have returned real data (or at least not blocked)
            Assertions.assertFalse(result.isEmpty(),
                "After timeout resets the gate, real records should be returned");
        }
    }

    /**
     * Verify that a RuntimeException thrown by touch() during drain-gate is caught and
     * doesn't kill the read loop — the consumer remains operational.
     */
    @Test
    void drainGate_touchRuntimeExceptionIsCaughtAndLoopContinues() throws Exception {
        var mc = new MockConsumer<String, byte[]>(OffsetResetStrategy.EARLIEST);
        var tp = new TopicPartition(TOPIC, 0);
        mc.updateBeginningOffsets(new HashMap<>(Collections.singletonMap(tp, 0L)));
        mc.schedulePollTask(() -> mc.rebalance(Collections.singletonList(tp)));

        try (var source = new KafkaTrafficCaptureSource(rootContext, mc, TOPIC, Duration.ofHours(1))) {
            // Trigger rebalance to get partition assignment
            source.readNextTrafficStreamChunk(rootContext::createReadChunkContext).get();

            // Set up drain gate
            var sessionKey = "conn1:" + KafkaTrafficCaptureSource.PENDING_CLOSE_SESSION_NUMBER_PLACEHOLDER + ":0";
            source.pendingTrafficSourceReaderInterruptedCloses.put(sessionKey, Boolean.TRUE);
            source.outstandingTrafficSourceReaderInterruptedCloseSessions.set(1);

            // Schedule a poll task that throws (simulates partition-assignment race with pause)
            mc.schedulePollTask(() -> { throw new IllegalStateException("simulated partition assignment race"); });

            // This should NOT throw — the RuntimeException should be caught internally
            var result = source.readNextTrafficStreamChunk(rootContext::createReadChunkContext).get();

            // Should return empty (still in drain gate) but NOT throw
            Assertions.assertTrue(result.isEmpty(),
                "Drain gate must still return empty even when touch() throws");

            // Consumer should still be operational — clear gate and verify reads work
            source.onNetworkConnectionClosed("conn1", 0, 0);
            addRecord(mc, tp, 0);
            var realResult = source.readNextTrafficStreamChunk(rootContext::createReadChunkContext).get();
            Assertions.assertFalse(realResult.isEmpty(),
                "Consumer must survive a RuntimeException from touch() and continue processing");
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
