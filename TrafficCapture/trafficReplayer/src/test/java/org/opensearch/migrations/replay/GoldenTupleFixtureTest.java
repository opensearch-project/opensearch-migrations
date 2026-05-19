package org.opensearch.migrations.replay;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
 * RFC 0001 T11.3 — golden tuple fixtures.
 *
 * <p>Drives canonical H2 fixtures through the accumulator and asserts the resulting
 * RequestResponsePacketPair carries the expected tuple metadata (sourceProtocol +
 * sourceStreamId) — i.e. the shape that {@link ParsedHttpMessagesAsDicts#toTupleMap}
 * surfaces in the JSON output.
 *
 * <p>Each test asserts a "golden" map of expected fields. When fixtures change
 * intentionally, these tests fail loudly and the maps need to be updated.
 */
class GoldenTupleFixtureTest extends InstrumentationTest {

    private String savedH2Property;

    @BeforeEach
    void enableH2() {
        savedH2Property = System.getProperty(TrafficStreamUtils.H2_SUPPORT_SYSTEM_PROPERTY);
        System.setProperty(TrafficStreamUtils.H2_SUPPORT_SYSTEM_PROPERTY, "true");
    }

    @AfterEach
    void restore() {
        if (savedH2Property == null) System.clearProperty(TrafficStreamUtils.H2_SUPPORT_SYSTEM_PROPERTY);
        else System.setProperty(TrafficStreamUtils.H2_SUPPORT_SYSTEM_PROPERTY, savedH2Property);
    }

    private static class CapturingCallbacks implements AccumulationCallbacks {
        final List<RequestResponsePacketPair> pairs = new ArrayList<>();
        @Override
        public Consumer<RequestResponsePacketPair> onRequestReceived(
                @NonNull IReplayContexts.IReplayerHttpTransactionContext ctx,
                @NonNull HttpMessageAndTimestamp request, boolean isResumedConnection) {
            return pairs::add;
        }
        @Override public void onTrafficStreamsExpired(
                RequestResponsePacketPair.ReconstructionStatus s,
                @NonNull IReplayContexts.IChannelKeyContext c,
                @NonNull List<ITrafficStreamKey> k) {}
        @Override public void onConnectionClose(int n,
                @NonNull IReplayContexts.IChannelKeyContext c, int s,
                RequestResponsePacketPair.ReconstructionStatus st,
                @NonNull Instant when, @NonNull List<ITrafficStreamKey> k) {}
        @Override public void onTrafficStreamIgnored(
                @NonNull IReplayContexts.ITrafficStreamsLifecycleContext c) {}
    }

    /** Build a "tuple-like" map with just the protocol + streamId fields surfaced by T7.2. */
    private static Map<String, Object> goldenLike(RequestResponsePacketPair pair) {
        var m = new LinkedHashMap<String, Object>();
        if (pair.getSourceProtocol() != null) m.put("sourceProtocol", pair.getSourceProtocol());
        if (pair.getSourceStreamId() != null) m.put("sourceStreamId", pair.getSourceStreamId());
        if (pair.getTargetStreamId() != null) m.put("targetStreamId", pair.getTargetStreamId());
        m.put("requestStatus", pair.completionStatus == null ? "unknown" : pair.completionStatus.toString());
        return m;
    }

    @Test
    void bulkIndexSingleStream_tupleHasH2Metadata() {
        var cbs = new CapturingCallbacks();
        var accumulator = new CapturedTrafficToHttpTransactionAccumulator(
                Duration.ofSeconds(30), null, cbs);
        var stream = H2FixtureGenerator.bulkIndexSingleStream();
        accumulator.accept(new PojoTrafficStreamAndKey(stream,
                PojoTrafficStreamKeyAndContext.build(stream, rootContext::createTrafficStreamContextForTest)));

        Assertions.assertEquals(1, cbs.pairs.size());
        var golden = goldenLike(cbs.pairs.get(0));
        Assertions.assertEquals("HTTP/2.0", golden.get("sourceProtocol"));
        Assertions.assertEquals(1, golden.get("sourceStreamId"));
        Assertions.assertEquals("COMPLETE", golden.get("requestStatus"));
    }

    @Test
    void searchMultiStream_tupleHasH2Metadata_perStream() {
        var cbs = new CapturingCallbacks();
        var accumulator = new CapturedTrafficToHttpTransactionAccumulator(
                Duration.ofSeconds(30), null, cbs);
        var stream = H2FixtureGenerator.searchMultiStream();
        accumulator.accept(new PojoTrafficStreamAndKey(stream,
                PojoTrafficStreamKeyAndContext.build(stream, rootContext::createTrafficStreamContextForTest)));

        // 5 multiplexed GETs
        Assertions.assertEquals(5, cbs.pairs.size());
        // streamIds are odd: 1, 3, 5, 7, 9
        var observedStreamIds = cbs.pairs.stream()
                .map(RequestResponsePacketPair::getSourceStreamId)
                .sorted().toList();
        Assertions.assertEquals(List.of(1, 3, 5, 7, 9), observedStreamIds);
        cbs.pairs.forEach(p -> {
            Assertions.assertEquals("HTTP/2.0", p.getSourceProtocol());
            Assertions.assertEquals(RequestResponsePacketPair.ReconstructionStatus.COMPLETE,
                    p.completionStatus);
        });
    }

    @Test
    void bulkWithRstStream_noPair_streamReset() {
        var cbs = new CapturingCallbacks();
        var accumulator = new CapturedTrafficToHttpTransactionAccumulator(
                Duration.ofSeconds(30), null, cbs);
        var stream = H2FixtureGenerator.bulkWithRstStream();
        accumulator.accept(new PojoTrafficStreamAndKey(stream,
                PojoTrafficStreamKeyAndContext.build(stream, rootContext::createTrafficStreamContextForTest)));

        // RST_STREAM mid-body: request never completed (no clientEndStream),
        // so onRequestReceived doesn't fire. No pair to inspect.
        Assertions.assertEquals(0, cbs.pairs.size());
    }

    @Test
    void goAwayMidFlight_completedStream1_orphanedStream3() {
        var cbs = new CapturingCallbacks();
        var accumulator = new CapturedTrafficToHttpTransactionAccumulator(
                Duration.ofSeconds(30), null, cbs);
        var stream = H2FixtureGenerator.goAwayMidFlight();
        accumulator.accept(new PojoTrafficStreamAndKey(stream,
                PojoTrafficStreamKeyAndContext.build(stream, rootContext::createTrafficStreamContextForTest)));

        // Per T5.2 semantics: both stream 1 and stream 3 had complete request sides
        // (HEADERS endStream), so both fire onRequestReceived. The response continuation
        // only fires for stream 1 (which got a real response on the wire).
        var pairs = cbs.pairs;
        // Should have 1 fully-completed pair (stream 1)
        long complete = pairs.stream()
                .filter(p -> p.completionStatus == RequestResponsePacketPair.ReconstructionStatus.COMPLETE)
                .count();
        Assertions.assertEquals(1, complete, "only stream 1's response continuation should fire");
        // The completed pair carries stream 1
        var stream1Pair = pairs.stream()
                .filter(p -> p.completionStatus == RequestResponsePacketPair.ReconstructionStatus.COMPLETE)
                .findFirst().orElseThrow();
        Assertions.assertEquals(1, stream1Pair.getSourceStreamId());
        Assertions.assertEquals("HTTP/2.0", stream1Pair.getSourceProtocol());
    }
}
