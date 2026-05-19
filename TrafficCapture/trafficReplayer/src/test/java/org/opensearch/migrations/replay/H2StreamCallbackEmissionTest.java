package org.opensearch.migrations.replay;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
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
 * RFC 0001 T5.2 — verifies the H2 accumulator's stream-completion path drives
 * {@code onRequestReceived} for each completed H2 stream.
 *
 * <p>Each H2 stream produces one logical {@link RequestResponsePacketPair}. The pair
 * carries the H2 protocol identity (sourceProtocol="HTTP/2.0", sourceStreamId=N) so
 * the tuple JSON output (T7.2) can surface them.
 */
class H2StreamCallbackEmissionTest extends InstrumentationTest {

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

    private static class RecordingCallbacks implements AccumulationCallbacks {
        final AtomicInteger requestsReceived = new AtomicInteger();
        final List<RequestResponsePacketPair> pairs = new ArrayList<>();

        @Override
        public Consumer<RequestResponsePacketPair> onRequestReceived(
                @NonNull IReplayContexts.IReplayerHttpTransactionContext ctx,
                @NonNull HttpMessageAndTimestamp request, boolean isResumedConnection) {
            requestsReceived.incrementAndGet();
            return pairs::add;
        }

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
    }

    private CapturedTrafficToHttpTransactionAccumulator newAccumulator(RecordingCallbacks callbacks) {
        return new CapturedTrafficToHttpTransactionAccumulator(
                Duration.ofSeconds(30), null, callbacks);
    }

    private void feed(CapturedTrafficToHttpTransactionAccumulator accumulator,
                      org.opensearch.migrations.trafficcapture.protos.TrafficStream stream) {
        accumulator.accept(new PojoTrafficStreamAndKey(
                stream,
                PojoTrafficStreamKeyAndContext.build(stream, rootContext::createTrafficStreamContextForTest)));
    }

    @Test
    void singleStream_firesOnRequestReceivedExactlyOnce() {
        var cbs = new RecordingCallbacks();
        feed(newAccumulator(cbs), H2FixtureGenerator.bulkIndexSingleStream());
        Assertions.assertEquals(1, cbs.requestsReceived.get(),
                "single H2 stream must fire exactly one onRequestReceived callback");
    }

    @Test
    void multipleConcurrentStreams_fireOnRequestReceivedPerStream() {
        var cbs = new RecordingCallbacks();
        feed(newAccumulator(cbs), H2FixtureGenerator.searchMultiStream());
        Assertions.assertEquals(5, cbs.requestsReceived.get(),
                "5 multiplexed GET streams must fire 5 onRequestReceived callbacks");
    }

    @Test
    void emittedPair_carriesH2ProtocolMetadata() {
        var cbs = new RecordingCallbacks();
        feed(newAccumulator(cbs), H2FixtureGenerator.bulkIndexSingleStream());
        Assertions.assertEquals(1, cbs.pairs.size(),
                "complete request+response should produce one full RRP");
        var pair = cbs.pairs.get(0);
        Assertions.assertEquals("HTTP/2.0", pair.getSourceProtocol(),
                "sourceProtocol must be set by the H2 emission path");
        Assertions.assertNotNull(pair.getSourceStreamId(),
                "sourceStreamId must be set");
        Assertions.assertEquals(1, pair.getSourceStreamId(),
                "fixture uses streamId=1 for the bulk request");
    }

    @Test
    void rstStreamMidBody_doesNotEmitFullPair() {
        var cbs = new RecordingCallbacks();
        feed(newAccumulator(cbs), H2FixtureGenerator.bulkWithRstStream());
        // Request didn't complete (no endStream), so onRequestReceived never fires.
        Assertions.assertEquals(0, cbs.requestsReceived.get(),
                "RST_STREAM mid-body must not produce an onRequestReceived");
    }

    @Test
    void goAwayMidFlight_emitsBothRequestsButOnlyOneCompletesResponse() {
        var cbs = new RecordingCallbacks();
        feed(newAccumulator(cbs), H2FixtureGenerator.goAwayMidFlight());
        // Both stream 1 and stream 3 receive HEADERS endStream, so both fire onRequestReceived.
        // GOAWAY arriving after the request side completes does NOT retroactively unfire the
        // request callback — the request was fully observed on the wire.
        Assertions.assertEquals(2, cbs.requestsReceived.get(),
                "both stream 1 and stream 3 had complete request sides");
        // Only stream 1 has a corresponding response on the wire (stream 3 was orphaned by GOAWAY).
        Assertions.assertEquals(1, cbs.pairs.size(),
                "only stream 1's response continuation should fire — stream 3 is orphaned");
        Assertions.assertEquals(1, cbs.pairs.get(0).getSourceStreamId(),
                "the completed pair must be stream 1");
    }
}
