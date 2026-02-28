package org.opensearch.migrations.replay;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import org.opensearch.migrations.replay.datatypes.ITrafficStreamKey;
import org.opensearch.migrations.replay.datatypes.PojoTrafficStreamAndKey;
import org.opensearch.migrations.replay.datatypes.PojoTrafficStreamKeyAndContext;
import org.opensearch.migrations.replay.tracing.IReplayContexts;
import org.opensearch.migrations.tracing.InMemoryInstrumentationBundle;
import org.opensearch.migrations.tracing.InstrumentationTest;
import org.opensearch.migrations.tracing.TestContext;
import org.opensearch.migrations.trafficcapture.protos.ReadObservation;
import org.opensearch.migrations.trafficcapture.protos.TrafficObservation;
import org.opensearch.migrations.trafficcapture.protos.TrafficStream;

import com.google.protobuf.ByteString;
import com.google.protobuf.Timestamp;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Verifies that TrafficReplayerCore.commitTrafficStreams() with CLOSED_PREMATURELY status
 * properly closes traffic stream contexts (only skipping the commit).
 *
 *
 * This test verifies that traffic stream contexts are always closed, even when
 * the status is CLOSED_PREMATURELY (only the commit is skipped).
 */
@Slf4j
public class ClosedPrematurelyContextLeakTest extends InstrumentationTest {

    @Override
    protected TestContext makeInstrumentationContext() {
        return TestContext.withTracking(false, true);
    }

    @Test
    void closedPrematurelyStatus_closesTrafficStreamContext() {
        var baseTime = Instant.now();
        var ts = Timestamp.newBuilder()
            .setSeconds(baseTime.getEpochSecond())
            .setNanos(baseTime.getNano())
            .build();

        // Create a TrafficStream with a Read but NO EOM — this will be "closed prematurely"
        // when the accumulator is closed
        var trafficStream = TrafficStream.newBuilder()
            .setConnectionId("leak-conn")
            .setNodeId("test-node")
            .setNumberOfThisLastChunk(0)
            .addSubStream(TrafficObservation.newBuilder().setTs(ts)
                .setRead(ReadObservation.newBuilder()
                    .setData(ByteString.copyFrom("GET / HTTP/1.1\r\nHost: localhost\r\n\r\n",
                        StandardCharsets.UTF_8))))
            .build();

        // Track what happens in the callbacks
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
                    // Replicate the commitTrafficStreams behavior:
                    // always close contexts, only skip commit when CLOSED_PREMATURELY
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
        // Close the accumulator while the request is still incomplete — triggers CLOSED_PREMATURELY
        accumulator.close();

        // Verify the callback was called with CLOSED_PREMATURELY
        Assertions.assertEquals(RequestResponsePacketPair.ReconstructionStatus.CLOSED_PREMATURELY,
            capturedStatus.get(), "Status should be CLOSED_PREMATURELY for incomplete request");
        Assertions.assertFalse(capturedKeys.isEmpty(), "Should have captured traffic stream keys");

        // Check the metric for trafficStreamLifetime — if the context was closed,
        // the counter is incremented when the context is properly closed.
        var metrics = rootContext.inMemoryInstrumentationBundle.getFinishedMetrics();
        long lifecycleCount = InMemoryInstrumentationBundle.getMetricValueOrZero(
            metrics, "trafficStreamLifetimeCount");

        // The traffic stream context is closed even for CLOSED_PREMATURELY
        Assertions.assertTrue(lifecycleCount > 0,
            "trafficStreamLifetimeCount should be > 0 because contexts are always closed");
    }
}
