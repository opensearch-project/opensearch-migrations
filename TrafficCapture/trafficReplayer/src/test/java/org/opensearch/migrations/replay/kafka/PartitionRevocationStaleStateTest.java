package org.opensearch.migrations.replay.kafka;

// REBUILD-LIMBO-NOTE(G10): Remaining marked members cover the retired accumulator and synthetic-close
// predecessor. G5 promotes only the connection-lifetime context assertions.

// REBUILD-LIMBO-START(G10)
/*

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import org.opensearch.migrations.replay.AccumulationCallbacks;
import org.opensearch.migrations.replay.CapturedTrafficToHttpTransactionAccumulator;
import org.opensearch.migrations.replay.HttpMessageAndTimestamp;
import org.opensearch.migrations.replay.RequestResponsePacketPair;
import org.opensearch.migrations.replay.datatypes.ITrafficStreamKey;
import org.opensearch.migrations.replay.datatypes.PojoTrafficStreamAndKey;
import org.opensearch.migrations.replay.tracing.ChannelContextManager;
import org.opensearch.migrations.replay.tracing.IReplayContexts;
import org.opensearch.migrations.replay.tracing.ReplayContexts;
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

*/
// REBUILD-LIMBO-END(G10)
import org.opensearch.migrations.replay.identity.CapturedConnectionId;
import org.opensearch.migrations.replay.identity.ConnectionProcessingId;
import org.opensearch.migrations.replay.identity.PartitionGenerationId;
import org.opensearch.migrations.replay.tracing.ChannelContextManager;
import org.opensearch.migrations.replay.tracing.RootReplayerContext;
import org.opensearch.migrations.tracing.InMemoryInstrumentationBundle;

import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Verifies that connection contexts are scoped to complete process-local lifetimes.
 */
public class PartitionRevocationStaleStateTest {
    /**
     * A later generation for the same captured connection is a distinct lifetime and may coexist
     * while the prior owner finishes.
     */
    @Test
    void generationBumpCreatesASeparateContextWithoutCrossRouting() {
        try (var telemetry = new InMemoryInstrumentationBundle(false, true)) {
            var manager = new ChannelContextManager(
                new RootReplayerContext(telemetry.openTelemetrySdk)
            );
            var generationOne = connectionId(1, 0);
            var generationTwo = connectionId(2, 0);
            var first = manager.retainOrCreateContext(generationOne);
            var second = manager.retainOrCreateContext(generationTwo);

            Assertions.assertNotSame(first, second);
            Assertions.assertEquals(generationOne, first.getConnectionProcessingId());
            Assertions.assertEquals(generationTwo, second.getConnectionProcessingId());

            manager.releaseContextFor(first);
            var secondRetainedAgain = manager.retainOrCreateContext(generationTwo);
            Assertions.assertSame(second, secondRetainedAgain);
            manager.releaseContextFor(second);
            manager.releaseContextFor(secondRetainedAgain);
        }
    }

    /**
     * A fresh process-local lifetime in the same partition generation must not reuse the prior
     * context.
     */
    @Test
    void freshLifetimeWithinOneGenerationUsesASeparateContext() {
        try (var telemetry = new InMemoryInstrumentationBundle(false, true)) {
            var manager = new ChannelContextManager(
                new RootReplayerContext(telemetry.openTelemetrySdk)
            );
            var firstLifetime = connectionId(4, 0);
            var secondLifetime = connectionId(4, 1);
            var first = manager.retainOrCreateContext(firstLifetime);
            var second = manager.retainOrCreateContext(secondLifetime);

            Assertions.assertNotSame(first, second);
            manager.releaseContextFor(first);
            manager.releaseContextFor(second);
        }
    }

    private static ConnectionProcessingId connectionId(long generation, long lifetime) {
        return new ConnectionProcessingId(
            new PartitionGenerationId(new TopicPartition("topic", 1), generation),
            new CapturedConnectionId("writer", "connection"),
            lifetime
        );
    }

// REBUILD-LIMBO-START(G10)
/*
    // -------------------------------------------------------------------------
    // CapturedTrafficToHttpTransactionAccumulator tests
    // -------------------------------------------------------------------------

*/
// REBUILD-LIMBO-END(G10)
}
    /**
     * When a traffic stream arrives for a connection that already has an accumulation
     * from a lower generation, the old accumulation must be discarded via
     * onTrafficStreamsExpired(TRAFFIC_SOURCE_READER_INTERRUPTED) and a fresh one created.
     * The interrupted-close status routes through replayEngine.cancelConnection so the
     * channel session is marked cancelled and won't self-heal when the re-delivered
     * Kafka records (post-rebalance fetch-position reset) create a fresh accumulation.
     */
// REBUILD-LIMBO-START(G10)
/*
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
                    boolean isResumedConnection
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
            RequestResponsePacketPair.ReconstructionStatus.TRAFFIC_SOURCE_READER_INTERRUPTED,
            expiredStatuses.get(0),
            "Stale accumulation must be expired with TRAFFIC_SOURCE_READER_INTERRUPTED so the " +
                "channel session is cancelled and won't self-heal");
    }

*/
// REBUILD-LIMBO-END(G10)
    /**
     * Same generation → accumulation is reused, no spurious expiry fired.
     */
// REBUILD-LIMBO-START(G10)
/*
    @Test
    void accumulator_sameGenerationReusesAccumulation() {
        var expiredCount = new AtomicInteger();

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

*/
// REBUILD-LIMBO-END(G10)
    /**
     * TrafficSourceReaderInterruptedClose fed to the accumulator must fire
     * onConnectionClose with ReconstructionStatus.TRAFFIC_SOURCE_READER_INTERRUPTED.
     * Before fix: accumulator doesn't handle this type — no onConnectionClose fires.
     */
// REBUILD-LIMBO-START(G10)
/*
    @Test
    void accumulatorHandlesTrafficSourceReaderInterruptedClose() {
        var capturedStatus = new AtomicReference<RequestResponsePacketPair.ReconstructionStatus>();
        var accumulator = new CapturedTrafficToHttpTransactionAccumulator(
            Duration.ofSeconds(30), null, new AccumulationCallbacks() {
                @Override
                public Consumer<RequestResponsePacketPair> onRequestReceived(
                    @NonNull IReplayContexts.IReplayerHttpTransactionContext ctx,
                    @NonNull HttpMessageAndTimestamp request,
                    boolean isResumedConnection
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
        accumulator.accept(new TrafficSourceReaderInterruptedClose(key));

        Assertions.assertEquals(
            RequestResponsePacketPair.ReconstructionStatus.TRAFFIC_SOURCE_READER_INTERRUPTED,
            capturedStatus.get(),
            "TrafficSourceReaderInterruptedClose must trigger onConnectionClose with TRAFFIC_SOURCE_READER_INTERRUPTED status"
        );
    }
}

*/
// REBUILD-LIMBO-END(G10)
