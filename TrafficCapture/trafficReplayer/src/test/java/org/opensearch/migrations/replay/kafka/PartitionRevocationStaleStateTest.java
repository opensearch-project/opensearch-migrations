package org.opensearch.migrations.replay.kafka;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import org.opensearch.migrations.replay.AccumulationCallbacks;
import org.opensearch.migrations.replay.CapturedTrafficToHttpTransactionAccumulator;
import org.opensearch.migrations.replay.HttpMessageAndTimestamp;
import org.opensearch.migrations.replay.RequestResponsePacketPair;
import org.opensearch.migrations.replay.datatypes.ITrafficStreamKey;
import org.opensearch.migrations.replay.datatypes.PojoTrafficStreamAndKey;
import org.opensearch.migrations.replay.tracing.ChannelContextManager;
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
import org.opensearch.migrations.replay.tracing.ReplayContexts;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Verifies that stale per-connection state (ChannelContextManager and
 * CapturedTrafficToHttpTransactionAccumulator) is discarded when a Kafka partition
 * is revoked and reassigned (detected via a generation bump on the ITrafficStreamKey).
 */
public class PartitionRevocationStaleStateTest extends InstrumentationTest {

    @Override
    protected TestContext makeInstrumentationContext() {
        return TestContext.withTracking(false, true);
    }

    // -------------------------------------------------------------------------
    // ChannelContextManager tests
    // -------------------------------------------------------------------------

    /**
     * When the same connectionId arrives with a higher generation, the old context
     * must be replaced (and force-closed) rather than reused.
     */
    @Test
    void channelContextManager_staleContextReplacedOnGenerationBump() {
        var mgr = new ChannelContextManager(rootContext);

        var keyGen1 = makeKafkaKey("node1", "conn1", 1, 0, 0);
        var keyGen2 = makeKafkaKey("node1", "conn1", 2, 0, 1);

        var ctxGen1 = mgr.retainOrCreateContext(keyGen1);
        var ctxGen2 = mgr.retainOrCreateContext(keyGen2);

        Assertions.assertNotSame(ctxGen1, ctxGen2,
            "A new context must be created when the generation increases");
    }

    /**
     * Same generation → same context object is returned (existing behaviour preserved).
     */
    @Test
    void channelContextManager_sameGenerationReturnsSameContext() {
        var mgr = new ChannelContextManager(rootContext);

        var keyA = makeKafkaKey("node1", "conn1", 1, 0, 0);
        var keyB = makeKafkaKey("node1", "conn1", 1, 0, 1);

        var ctxA = mgr.retainOrCreateContext(keyA);
        var ctxB = mgr.retainOrCreateContext(keyB);

        Assertions.assertSame(ctxA, ctxB,
            "Same generation must reuse the existing context");
    }

    // -------------------------------------------------------------------------
    // CapturedTrafficToHttpTransactionAccumulator tests
    // -------------------------------------------------------------------------

    /**
     * When a traffic stream arrives for a connection that already has an accumulation
     * from a lower generation, the old accumulation must be discarded via
     * onTrafficStreamsExpired(CLOSED_PREMATURELY) and a fresh one created.
     */
    @Test
    void accumulator_staleAccumulationDiscardedOnGenerationBump() {
        var expiredStatuses = new ArrayList<RequestResponsePacketPair.ReconstructionStatus>();
        var expiredKeys = new ArrayList<ITrafficStreamKey>();
        var requestsReceived = new AtomicInteger();

        var accumulator = new CapturedTrafficToHttpTransactionAccumulator(
            Duration.ofSeconds(30), null, new AccumulationCallbacks() {
                @Override
                public Consumer<RequestResponsePacketPair> onRequestReceived(
                    @NonNull IReplayContexts.IReplayerHttpTransactionContext ctx,
                    @NonNull HttpMessageAndTimestamp request,
                    boolean isHandoffConnection
                ) {
                    requestsReceived.incrementAndGet();
                    return pair -> {};
                }

                @Override
                public void onTrafficStreamsExpired(
                    RequestResponsePacketPair.ReconstructionStatus status,
                    @NonNull IReplayContexts.IChannelKeyContext ctx,
                    @NonNull List<ITrafficStreamKey> trafficStreamKeysBeingHeld
                ) {
                    expiredStatuses.add(status);
                    expiredKeys.addAll(trafficStreamKeysBeingHeld);
                    for (var tsk : trafficStreamKeysBeingHeld) {
                        tsk.getTrafficStreamsContext().close();
                    }
                }

                @Override
                public void onConnectionClose(int n, @NonNull IReplayContexts.IChannelKeyContext ctx,
                    int s, RequestResponsePacketPair.ReconstructionStatus status,
                    @NonNull Instant when, @NonNull List<ITrafficStreamKey> trafficStreamKeysBeingHeld
                ) {}

                @Override
                public void onTrafficStreamIgnored(
                    @NonNull IReplayContexts.ITrafficStreamsLifecycleContext ctx
                ) {}
            }
        );

        var ts = makeReadTrafficStream("node1", "conn1");
        var keyGen1 = makeKafkaKey("node1", "conn1", 1, 0, 0);
        var keyGen2 = makeKafkaKey("node1", "conn1", 2, 0, 1);

        // Feed gen-1 stream — starts accumulating a request
        accumulator.accept(new PojoTrafficStreamAndKey(ts, buildKeyWithContext(keyGen1)));

        // Feed gen-2 stream for the same connection — old accumulation must be evicted
        accumulator.accept(new PojoTrafficStreamAndKey(ts, buildKeyWithContext(keyGen2)));

        Assertions.assertEquals(1, expiredStatuses.size(),
            "onTrafficStreamsExpired must be called exactly once for the stale accumulation");
        Assertions.assertEquals(
            RequestResponsePacketPair.ReconstructionStatus.CLOSED_PREMATURELY,
            expiredStatuses.get(0),
            "Stale accumulation must be expired with CLOSED_PREMATURELY");
    }

    /**
     * Same generation → accumulation is reused, no spurious expiry fired.
     */
    @Test
    void accumulator_sameGenerationReusesAccumulation() {
        var expiredCount = new AtomicInteger();

        var accumulator = new CapturedTrafficToHttpTransactionAccumulator(
            Duration.ofSeconds(30), null, new AccumulationCallbacks() {
                @Override
                public Consumer<RequestResponsePacketPair> onRequestReceived(
                    @NonNull IReplayContexts.IReplayerHttpTransactionContext ctx,
                    @NonNull HttpMessageAndTimestamp request,
                    boolean isHandoffConnection
                ) {
                    return pair -> {};
                }

                @Override
                public void onTrafficStreamsExpired(
                    RequestResponsePacketPair.ReconstructionStatus status,
                    @NonNull IReplayContexts.IChannelKeyContext ctx,
                    @NonNull List<ITrafficStreamKey> trafficStreamKeysBeingHeld
                ) {
                    expiredCount.incrementAndGet();
                    for (var tsk : trafficStreamKeysBeingHeld) {
                        tsk.getTrafficStreamsContext().close();
                    }
                }

                @Override
                public void onConnectionClose(int n, @NonNull IReplayContexts.IChannelKeyContext ctx,
                    int s, RequestResponsePacketPair.ReconstructionStatus status,
                    @NonNull Instant when, @NonNull List<ITrafficStreamKey> trafficStreamKeysBeingHeld
                ) {}

                @Override
                public void onTrafficStreamIgnored(
                    @NonNull IReplayContexts.ITrafficStreamsLifecycleContext ctx
                ) {}
            }
        );

        var ts = makeReadTrafficStream("node1", "conn1");
        var keyGen1a = makeKafkaKey("node1", "conn1", 1, 0, 0);
        var keyGen1b = makeKafkaKey("node1", "conn1", 1, 0, 1);

        accumulator.accept(new PojoTrafficStreamAndKey(ts, buildKeyWithContext(keyGen1a)));
        accumulator.accept(new PojoTrafficStreamAndKey(ts, buildKeyWithContext(keyGen1b)));

        Assertions.assertEquals(0, expiredCount.get(),
            "No expiry should fire when the generation is unchanged");
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private TrafficStreamKeyWithKafkaRecordId makeKafkaKey(
        String nodeId, String connectionId, int generation, int partition, long offset
    ) {
        var ts = TrafficStream.newBuilder()
            .setNodeId(nodeId)
            .setConnectionId(connectionId)
            .setNumberOfThisLastChunk(0)
            .build();
        return new TrafficStreamKeyWithKafkaRecordId(
            k -> new ReplayContexts.KafkaRecordContext(
                rootContext,
                new ChannelContextManager(rootContext).retainOrCreateContext(k),
                "",
                1
            ),
            ts,
            generation,
            partition,
            offset
        );
    }

    private TrafficStreamKeyWithKafkaRecordId buildKeyWithContext(TrafficStreamKeyWithKafkaRecordId base) {
        // Re-wrap with a fresh context so the accumulator can manage it
        var ts = TrafficStream.newBuilder()
            .setNodeId(base.getNodeId())
            .setConnectionId(base.getConnectionId())
            .setNumberOfThisLastChunk(0)
            .build();
        return new TrafficStreamKeyWithKafkaRecordId(
            k -> new ReplayContexts.KafkaRecordContext(
                rootContext,
                new ChannelContextManager(rootContext).retainOrCreateContext(k),
                "",
                1
            ),
            ts,
            base.getGeneration(),
            base.getPartition(),
            base.getOffset()
        );
    }

    private static TrafficStream makeReadTrafficStream(String nodeId, String connectionId) {
        var ts = Timestamp.newBuilder()
            .setSeconds(Instant.now().getEpochSecond())
            .build();
        return TrafficStream.newBuilder()
            .setNodeId(nodeId)
            .setConnectionId(connectionId)
            .setNumberOfThisLastChunk(0)
            .addSubStream(TrafficObservation.newBuilder()
                .setTs(ts)
                .setRead(ReadObservation.newBuilder()
                    .setData(ByteString.copyFrom("GET / HTTP/1.1\r\nHost: localhost\r\n\r\n",
                        StandardCharsets.UTF_8)))
                .build())
            .build();
    }

    // -------------------------------------------------------------------------
    // Phase 4: Synthetic close events
    // -------------------------------------------------------------------------

    /**
     * SyntheticPartitionReassignmentClose fed to the accumulator must fire
     * onConnectionClose with ReconstructionStatus.REASSIGNED.
     * Before fix: accumulator doesn't handle this type — no onConnectionClose fires.
     */
    @Test
    void accumulatorHandlesSyntheticClose() {
        var capturedStatus = new AtomicReference<RequestResponsePacketPair.ReconstructionStatus>();
        var accumulator = new CapturedTrafficToHttpTransactionAccumulator(
            Duration.ofSeconds(30), null, new AccumulationCallbacks() {
                @Override
                public Consumer<RequestResponsePacketPair> onRequestReceived(
                    @NonNull IReplayContexts.IReplayerHttpTransactionContext ctx,
                    @NonNull HttpMessageAndTimestamp request,
                    boolean isHandoffConnection
                ) { return pair -> {}; }

                @Override
                public void onTrafficStreamsExpired(
                    RequestResponsePacketPair.ReconstructionStatus status,
                    @NonNull IReplayContexts.IChannelKeyContext ctx,
                    @NonNull List<ITrafficStreamKey> trafficStreamKeysBeingHeld
                ) {}

                @Override
                public void onConnectionClose(
                    int n, @NonNull IReplayContexts.IChannelKeyContext ctx,
                    int s, RequestResponsePacketPair.ReconstructionStatus status,
                    @NonNull Instant when,
                    @NonNull List<ITrafficStreamKey> trafficStreamKeysBeingHeld
                ) {
                    capturedStatus.set(status);
                    for (var tsk : trafficStreamKeysBeingHeld) {
                        tsk.getTrafficStreamsContext().close();
                    }
                }

                @Override
                public void onTrafficStreamIgnored(
                    @NonNull IReplayContexts.ITrafficStreamsLifecycleContext ctx
                ) {}
            }
        );

        var key = makeKafkaKey("node1", "conn-synth", 1, 0, 0);
        accumulator.accept(new SyntheticPartitionReassignmentClose(key));

        Assertions.assertEquals(
            RequestResponsePacketPair.ReconstructionStatus.REASSIGNED,
            capturedStatus.get(),
            "SyntheticPartitionReassignmentClose must trigger onConnectionClose with REASSIGNED status"
        );
    }
}
