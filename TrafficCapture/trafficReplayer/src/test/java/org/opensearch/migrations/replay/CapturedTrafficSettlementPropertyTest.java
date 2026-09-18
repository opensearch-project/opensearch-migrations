package org.opensearch.migrations.replay;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.stream.IntStream;

import org.opensearch.migrations.replay.datatypes.ITrafficStreamKey;
import org.opensearch.migrations.replay.datatypes.PojoTrafficStreamAndKey;
import org.opensearch.migrations.replay.datatypes.PojoTrafficStreamKeyAndContext;
import org.opensearch.migrations.replay.tracing.IReplayContexts;
import org.opensearch.migrations.replay.traffic.generator.ExhaustiveTrafficStreamGenerator;
import org.opensearch.migrations.tracing.TestContext;
import org.opensearch.migrations.trafficcapture.protos.TrafficObservation;
import org.opensearch.migrations.trafficcapture.protos.TrafficStream;
import org.opensearch.migrations.trafficcapture.protos.TrafficStreamUtils;

import lombok.NonNull;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class CapturedTrafficSettlementPropertyTest {
    private static final int GENERATED_CASES = 512;

    @Test
    void randomizedTerminalSequencesSettleEveryCapturedRecordExactlyOnce() {
        var rootContext = TestContext.noOtelTracking();
        var observedFeatures = new ObservedFeatures();

        ExhaustiveTrafficStreamGenerator.generateRandomTrafficStreamsAndSizes(
            rootContext,
            IntStream.range(0, GENERATED_CASES)
        ).forEach(testCase -> {
            observedFeatures.record(testCase.trafficStreams);
            assertEveryRecordSettlesExactlyOnce(rootContext, testCase.randomSeedUsed, testCase.trafficStreams);
        });

        Assertions.assertTrue(observedFeatures.droppedRequests.get() > 0, "generator did not exercise dropped requests");
        Assertions.assertTrue(
            observedFeatures.connectionExceptions.get() > 0,
            "generator did not exercise connection exceptions"
        );
        Assertions.assertTrue(observedFeatures.closes.get() > 0, "generator did not exercise connection closes");
    }

    private static void assertEveryRecordSettlesExactlyOnce(
        TestContext rootContext,
        int seed,
        TrafficStream[] streams
    ) {
        var settlementCounts = new HashMap<RecordIdentity, Integer>();
        var accumulator = new CapturedTrafficToHttpTransactionAccumulator(
            Duration.ofSeconds(30),
            null,
            callbacks(settlementCounts)
        );

        for (var stream : streams) {
            accumulator.accept(
                new PojoTrafficStreamAndKey(
                    stream,
                    PojoTrafficStreamKeyAndContext.build(stream, rootContext::createTrafficStreamContextForTest)
                )
            );
        }
        accumulator.close();

        var expected = java.util.Arrays.stream(streams)
            .map(RecordIdentity::from)
            .toList();
        Assertions.assertEquals(
            expected.size(),
            settlementCounts.size(),
            () -> "seed " + seed + " did not settle the expected record set"
        );
        expected.forEach(identity -> Assertions.assertEquals(
            1,
            settlementCounts.getOrDefault(identity, 0),
            () -> "seed " + seed + " did not settle exactly once: " + identity
        ));
    }

    private static AccumulationCallbacks callbacks(Map<RecordIdentity, Integer> settlementCounts) {
        Consumer<ITrafficStreamKey> recordSettlement = key ->
            settlementCounts.merge(RecordIdentity.from(key), 1, Integer::sum);
        Consumer<List<ITrafficStreamKey>> recordSettlements = keys -> keys.forEach(recordSettlement);

        return new AccumulationCallbacks() {
            @Override
            public Consumer<RequestResponsePacketPair> onRequestReceived(
                @NonNull IReplayContexts.IReplayerHttpTransactionContext ctx,
                @NonNull HttpMessageAndTimestamp request,
                boolean isResumedConnection
            ) {
                return pair -> recordSettlements.accept(pair.getTrafficStreamsHeld());
            }

            @Override
            public void onTrafficStreamsExpired(
                RequestResponsePacketPair.ReconstructionStatus status,
                @NonNull IReplayContexts.IChannelKeyContext ctx,
                @NonNull List<ITrafficStreamKey> trafficStreamKeysBeingHeld
            ) {
                recordSettlements.accept(trafficStreamKeysBeingHeld);
            }

            @Override
            public void onConnectionClose(
                int channelInteractionNumber,
                @NonNull IReplayContexts.IChannelKeyContext ctx,
                int channelSessionNumber,
                RequestResponsePacketPair.ReconstructionStatus status,
                @NonNull Instant when,
                @NonNull List<ITrafficStreamKey> trafficStreamKeysBeingHeld
            ) {
                recordSettlements.accept(trafficStreamKeysBeingHeld);
            }

            @Override
            public void onTrafficStreamIgnored(@NonNull IReplayContexts.ITrafficStreamsLifecycleContext ctx) {
                recordSettlement.accept(ctx.getTrafficStreamKey());
            }
        };
    }

    private record RecordIdentity(String nodeId, String connectionId, int streamIndex) {
        static RecordIdentity from(TrafficStream stream) {
            return new RecordIdentity(
                stream.getNodeId(),
                stream.getConnectionId(),
                TrafficStreamUtils.getTrafficStreamIndex(stream)
            );
        }

        static RecordIdentity from(ITrafficStreamKey key) {
            return new RecordIdentity(key.getNodeId(), key.getConnectionId(), key.getTrafficStreamIndex());
        }
    }

    private static class ObservedFeatures {
        private final AtomicInteger droppedRequests = new AtomicInteger();
        private final AtomicInteger connectionExceptions = new AtomicInteger();
        private final AtomicInteger closes = new AtomicInteger();

        void record(TrafficStream[] streams) {
            java.util.Arrays.stream(streams)
                .flatMap(stream -> stream.getSubStreamList().stream())
                .forEach(this::record);
        }

        private void record(TrafficObservation observation) {
            if (observation.hasRequestDropped()) {
                droppedRequests.incrementAndGet();
            }
            if (observation.hasConnectionException()) {
                connectionExceptions.incrementAndGet();
            }
            if (observation.hasClose()) {
                closes.incrementAndGet();
            }
        }
    }
}
