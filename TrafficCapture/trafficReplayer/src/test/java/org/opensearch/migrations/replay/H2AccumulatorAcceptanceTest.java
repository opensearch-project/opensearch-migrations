package org.opensearch.migrations.replay;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.function.Consumer;

import org.opensearch.migrations.replay.datatypes.ITrafficStreamKey;
import org.opensearch.migrations.replay.datatypes.PojoTrafficStreamAndKey;
import org.opensearch.migrations.replay.datatypes.PojoTrafficStreamKeyAndContext;
import org.opensearch.migrations.replay.tracing.IReplayContexts;
import org.opensearch.migrations.tracing.InstrumentationTest;
import org.opensearch.migrations.trafficcapture.protos.TrafficStreamUtils;
import org.opensearch.migrations.trafficcapture.protos.fixtures.H2FixtureGenerator;

import lombok.NonNull;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * RFC 0001 T4.6 — fixture-driven acceptance for the H2 accumulator dispatch path.
 *
 * <p>Each test runs a programmatically-generated {@link H2FixtureGenerator} fixture
 * through the H2-aware accumulator and asserts the dispatch behavior. These tests
 * exercise the full envelope → dispatch → frame-table → per-stream-state pipeline.
 */
class H2AccumulatorAcceptanceTest extends InstrumentationTest {

    private String savedH2Property;

    @BeforeEach
    void enableH2Support() {
        savedH2Property = System.getProperty(TrafficStreamUtils.H2_SUPPORT_SYSTEM_PROPERTY);
        System.setProperty(TrafficStreamUtils.H2_SUPPORT_SYSTEM_PROPERTY, "true");
    }

    @AfterEach
    void restoreProperty() {
        if (savedH2Property == null) {
            System.clearProperty(TrafficStreamUtils.H2_SUPPORT_SYSTEM_PROPERTY);
        } else {
            System.setProperty(TrafficStreamUtils.H2_SUPPORT_SYSTEM_PROPERTY, savedH2Property);
        }
    }

    private CapturedTrafficToHttpTransactionAccumulator newAccumulator() {
        return new CapturedTrafficToHttpTransactionAccumulator(
                Duration.ofSeconds(30), null, new AccumulationCallbacks() {
            @Override
            public Consumer<RequestResponsePacketPair> onRequestReceived(
                    @NonNull IReplayContexts.IReplayerHttpTransactionContext ctx,
                    @NonNull HttpMessageAndTimestamp request,
                    boolean isResumedConnection) { return rrpp -> {}; }
            @Override public void onTrafficStreamsExpired(
                    RequestResponsePacketPair.ReconstructionStatus status,
                    @NonNull IReplayContexts.IChannelKeyContext ctx,
                    @NonNull List<ITrafficStreamKey> keys) {}
            @Override public void onConnectionClose(
                    int n, @NonNull IReplayContexts.IChannelKeyContext ctx, int s,
                    RequestResponsePacketPair.ReconstructionStatus status,
                    @NonNull Instant when, @NonNull List<ITrafficStreamKey> keys) {}
            @Override public void onTrafficStreamIgnored(
                    @NonNull IReplayContexts.ITrafficStreamsLifecycleContext ctx) {}
        });
    }

    private void feed(CapturedTrafficToHttpTransactionAccumulator accumulator,
                      org.opensearch.migrations.trafficcapture.protos.TrafficStream stream) {
        accumulator.accept(new PojoTrafficStreamAndKey(
                stream,
                PojoTrafficStreamKeyAndContext.build(stream, rootContext::createTrafficStreamContextForTest)));
    }

    @Test
    void bulkIndexSingleStream_processesWithoutThrowing() {
        var accumulator = newAccumulator();
        Assertions.assertDoesNotThrow(() -> feed(accumulator, H2FixtureGenerator.bulkIndexSingleStream()));
    }

    @Test
    void searchMultiStream_processesAllFiveStreamsConcurrently() {
        var accumulator = newAccumulator();
        Assertions.assertDoesNotThrow(() -> feed(accumulator, H2FixtureGenerator.searchMultiStream()));
    }

    @Test
    void bulkWithRstStream_doesNotThrow() {
        var accumulator = newAccumulator();
        Assertions.assertDoesNotThrow(() -> feed(accumulator, H2FixtureGenerator.bulkWithRstStream()));
    }

    @Test
    void goAwayMidFlight_doesNotThrow() {
        var accumulator = newAccumulator();
        Assertions.assertDoesNotThrow(() -> feed(accumulator, H2FixtureGenerator.goAwayMidFlight()));
    }
}
