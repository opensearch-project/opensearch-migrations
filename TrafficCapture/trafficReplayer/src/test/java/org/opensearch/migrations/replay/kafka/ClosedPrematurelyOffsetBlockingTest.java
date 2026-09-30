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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Regression test for the head-of-line offset blocking bug.
 *
 * When incomplete data reaches CLOSED_PREMATURELY status, suppressing the Kafka offset
 * commit leaves it stuck at the head of OffsetLifecycleTracker's PriorityQueue, blocking
 * all subsequent offsets on that partition from advancing.
 *
 * This class tests two things:
 * 1. The accumulator correctly fires CLOSED_PREMATURELY when closed with incomplete streams.
 * 2. OffsetLifecycleTracker's head-of-line blocking mechanism (the PriorityQueue semantics
 *    that make the commit-on-CLOSED_PREMATURELY fix necessary).
 *
 * The commit-behavior itself (that CLOSED_PREMATURELY status triggers commitTrafficStream)
 * is tested in CommitTrafficStreamsStatusTest, which exercises the actual production code path.
 */
class ClosedPrematurelyOffsetBlockingTest extends InstrumentationTest {

    @Override
    protected TestContext makeInstrumentationContext() {
        return TestContext.withTracking(false, true);
    }

    /**
     * Verifies that the accumulator fires CLOSED_PREMATURELY when closed with incomplete data.
     * This is the precondition for the offset-blocking bug: the status must actually be produced
     * by the accumulator for it to reach commitTrafficStreams().
     *
     * The commit behavior for this status is tested in CommitTrafficStreamsStatusTest.
     */
    @Test
    @DisplayName("Accumulator closing with incomplete stream fires CLOSED_PREMATURELY")
    void accumulatorClose_withIncompleteStream_firesClosedPrematurelyStatus() {
        var baseTime = Instant.now();
        var ts = Timestamp.newBuilder()
            .setSeconds(baseTime.getEpochSecond())
            .setNanos(baseTime.getNano())
            .build();

        var trafficStream = TrafficStream.newBuilder()
            .setConnectionId("stuck-conn")
            .setNodeId("test-node")
            .setNumberOfThisLastChunk(0)
            .addSubStream(TrafficObservation.newBuilder().setTs(ts)
                .setRead(ReadObservation.newBuilder()
                    .setData(ByteString.copyFrom("GET / HTTP/1.1\r\nHost: localhost\r\n\r\n",
                        StandardCharsets.UTF_8))))
            .build();

        AtomicReference<RequestResponsePacketPair.ReconstructionStatus> capturedStatus = new AtomicReference<>();
        List<ITrafficStreamKey> capturedKeys = new ArrayList<>();

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
                    if (trafficStreamKeysBeingHeld != null) {
                        for (var tsk : trafficStreamKeysBeingHeld) {
                            tsk.getTrafficStreamsContext().close();
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
        accumulator.close();

        Assertions.assertEquals(RequestResponsePacketPair.ReconstructionStatus.CLOSED_PREMATURELY,
            capturedStatus.get(), "Status should be CLOSED_PREMATURELY for incomplete request");
        Assertions.assertFalse(capturedKeys.isEmpty(),
            "Traffic stream keys should be passed to the callback for commit handling");
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
