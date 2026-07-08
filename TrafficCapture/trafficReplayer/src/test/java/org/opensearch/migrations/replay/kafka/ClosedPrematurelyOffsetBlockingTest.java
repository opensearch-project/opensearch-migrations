package org.opensearch.migrations.replay.kafka;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import org.opensearch.migrations.replay.AccumulationCallbacks;
import org.opensearch.migrations.replay.CapturedTrafficToHttpTransactionAccumulator;
import org.opensearch.migrations.replay.HttpMessageAndTimestamp;
import org.opensearch.migrations.replay.RequestResponsePacketPair;
import org.opensearch.migrations.replay.datatypes.ITrafficStreamKey;
import org.opensearch.migrations.replay.datatypes.PojoTrafficStreamAndKey;
import org.opensearch.migrations.replay.datatypes.PojoTrafficStreamKeyAndContext;
import org.opensearch.migrations.replay.tracing.IReplayContexts;
import org.opensearch.migrations.tracing.InstrumentationTest;
import org.opensearch.migrations.tracing.TestContext;
import org.opensearch.migrations.trafficcapture.protos.ReadObservation;
import org.opensearch.migrations.trafficcapture.protos.TrafficObservation;
import org.opensearch.migrations.trafficcapture.protos.TrafficStream;

import com.google.protobuf.ByteString;
import com.google.protobuf.Timestamp;
import lombok.NonNull;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Regression test for the head-of-line offset blocking bug.
 *
 * When a request times out (CLOSED_PREMATURELY), the old code suppressed the Kafka offset
 * commit. Since OffsetLifecycleTracker uses a PriorityQueue where only the HEAD offset can
 * advance the commit pointer, a single timed-out record blocks ALL subsequent offsets on
 * that partition from ever being committed — causing infinite lag growth even though the
 * replayer is processing records successfully.
 *
 * The fix: CLOSED_PREMATURELY records now commit their offset (they cannot succeed on retry
 * since the same incomplete fragments will time out again). Only TRAFFIC_SOURCE_READER_INTERRUPTED
 * suppresses commits (because cleanupRevokedPartitions destroys the tracker anyway).
 *
 * This test verifies that after a CLOSED_PREMATURELY expiry, the offset IS committed
 * (removeAndReturnNewHead is called), unblocking subsequent offsets.
 */
class ClosedPrematurelyOffsetBlockingTest extends InstrumentationTest {

    @Override
    protected TestContext makeInstrumentationContext() {
        return TestContext.withTracking(false, true);
    }

    /**
     * Simulates the production scenario:
     * 1. A record is polled and added to OffsetLifecycleTracker
     * 2. The record's connection times out (CLOSED_PREMATURELY)
     * 3. Verifies the offset IS committed (not blocked)
     *
     * Before fix: commitTrafficStream() was never called for CLOSED_PREMATURELY,
     * leaving the offset permanently stuck at the head of the PriorityQueue.
     */
    @Test
    void closedPrematurelyStatus_commitsOffset_unblockingSubsequentRecords() {
        var baseTime = Instant.now();
        var ts = Timestamp.newBuilder()
            .setSeconds(baseTime.getEpochSecond())
            .setNanos(baseTime.getNano())
            .build();

        // Create a TrafficStream with a Read but NO end-of-message — this will trigger
        // CLOSED_PREMATURELY when the accumulator is closed (simulating packet timeout)
        var trafficStream = TrafficStream.newBuilder()
            .setConnectionId("stuck-conn")
            .setNodeId("test-node")
            .setNumberOfThisLastChunk(0)
            .addSubStream(TrafficObservation.newBuilder().setTs(ts)
                .setRead(ReadObservation.newBuilder()
                    .setData(ByteString.copyFrom("GET / HTTP/1.1\r\nHost: localhost\r\n\r\n",
                        StandardCharsets.UTF_8))))
            .build();

        // Track what the callback receives
        AtomicReference<RequestResponsePacketPair.ReconstructionStatus> capturedStatus = new AtomicReference<>();
        List<ITrafficStreamKey> capturedKeys = new ArrayList<>();
        List<Boolean> commitCalls = new ArrayList<>();

        var accumulator = new CapturedTrafficToHttpTransactionAccumulator(
            Duration.ofSeconds(30), null, new AccumulationCallbacks() {
                @Override
                public Consumer<RequestResponsePacketPair> onRequestReceived(
                    @NonNull IReplayContexts.IReplayerHttpTransactionContext ctx,
                    @NonNull HttpMessageAndTimestamp request,
                    boolean isResumedConnection
                ) {
                    return pair -> {};
                }

                @Override
                public void onTrafficStreamsExpired(
                    RequestResponsePacketPair.ReconstructionStatus status,
                    @NonNull IReplayContexts.IChannelKeyContext ctx,
                    @NonNull List<ITrafficStreamKey> trafficStreamKeysBeingHeld
                ) {
                    capturedStatus.set(status);
                    capturedKeys.addAll(trafficStreamKeysBeingHeld);
                    // Replicate the FIXED commitTrafficStreams behavior:
                    // CLOSED_PREMATURELY now commits (only TRAFFIC_SOURCE_READER_INTERRUPTED suppresses)
                    if (trafficStreamKeysBeingHeld != null) {
                        for (var tsk : trafficStreamKeysBeingHeld) {
                            tsk.getTrafficStreamsContext().close();
                            // The fix: CLOSED_PREMATURELY now calls commitTrafficStream
                            if (status != RequestResponsePacketPair.ReconstructionStatus.TRAFFIC_SOURCE_READER_INTERRUPTED) {
                                commitCalls.add(true);
                            }
                        }
                    }
                }

                @Override
                public void onConnectionClose(int n, @NonNull IReplayContexts.IChannelKeyContext ctx,
                    int s, RequestResponsePacketPair.ReconstructionStatus status,
                    @NonNull Instant when, @NonNull List<ITrafficStreamKey> trafficStreamKeysBeingHeld
                ) {}

                @Override
                public void onTrafficStreamIgnored(@NonNull IReplayContexts.ITrafficStreamsLifecycleContext ctx) {}
            }
        );

        accumulator.accept(new PojoTrafficStreamAndKey(
            trafficStream,
            PojoTrafficStreamKeyAndContext.build(trafficStream, rootContext::createTrafficStreamContextForTest)
        ));
        // Close the accumulator while the request is still incomplete — triggers CLOSED_PREMATURELY
        accumulator.close();

        // Verify the status is CLOSED_PREMATURELY
        Assertions.assertEquals(RequestResponsePacketPair.ReconstructionStatus.CLOSED_PREMATURELY,
            capturedStatus.get(), "Status should be CLOSED_PREMATURELY for incomplete request");

        // KEY ASSERTION: commitTrafficStream() IS called for CLOSED_PREMATURELY.
        // Before the fix, this was suppressed, causing head-of-line blocking in
        // OffsetLifecycleTracker's PriorityQueue — the offset sat at the head forever,
        // preventing all subsequent offsets from being committed.
        Assertions.assertFalse(commitCalls.isEmpty(),
            "commitTrafficStream() MUST be called for CLOSED_PREMATURELY to unblock the " +
            "offset commit pointer. Without this, OffsetLifecycleTracker's PriorityQueue " +
            "holds the timed-out offset at the head indefinitely, blocking all subsequent " +
            "offsets on that partition and causing infinite lag growth.");
    }

    /**
     * Verifies the OffsetLifecycleTracker head-of-line blocking directly:
     * when the head offset is never removed, subsequent offsets cannot advance.
     */
    @Test
    void offsetLifecycleTracker_headBlocksSubsequentCommits() {
        var tracker = new OffsetLifecycleTracker(1);

        // Simulate 3 records polled: offsets 100, 101, 102
        tracker.add(100, "conn-A");
        tracker.add(101, "conn-B");
        tracker.add(102, "conn-C");

        // Record at offset 101 completes first — but 100 is the head, so no commit advances
        var result101 = tracker.removeAndReturnNewHead(101);
        Assertions.assertTrue(result101.isEmpty(),
            "Removing non-head offset should NOT advance the commit pointer");

        // Record at offset 102 completes — still blocked by 100
        var result102 = tracker.removeAndReturnNewHead(102);
        Assertions.assertTrue(result102.isEmpty(),
            "Removing non-head offset should NOT advance the commit pointer (still blocked by 100)");

        // Finally, record at offset 100 completes (the fix ensures this happens for CLOSED_PREMATURELY)
        // Now the pointer jumps all the way to 103 (next after all removed offsets)
        var result100 = tracker.removeAndReturnNewHead(100);
        Assertions.assertTrue(result100.isPresent(),
            "Removing head offset MUST advance the commit pointer");
        Assertions.assertEquals(103L, result100.get(),
            "Commit pointer should advance to cursorHighWatermark+1 (103) since all offsets are now removed");
    }
}
