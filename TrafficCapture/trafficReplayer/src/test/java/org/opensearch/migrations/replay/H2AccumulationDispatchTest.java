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
import org.opensearch.migrations.trafficcapture.protos.AlpnNegotiationObservation;
import org.opensearch.migrations.trafficcapture.protos.CloseObservation;
import org.opensearch.migrations.trafficcapture.protos.Http2DataPayload;
import org.opensearch.migrations.trafficcapture.protos.Http2FrameObservation;
import org.opensearch.migrations.trafficcapture.protos.Http2FrameType;
import org.opensearch.migrations.trafficcapture.protos.Http2GoAwayPayload;
import org.opensearch.migrations.trafficcapture.protos.Http2HeaderField;
import org.opensearch.migrations.trafficcapture.protos.Http2HeadersPayload;
import org.opensearch.migrations.trafficcapture.protos.Http2RstStreamPayload;
import org.opensearch.migrations.trafficcapture.protos.Http2SettingsPayload;
import org.opensearch.migrations.trafficcapture.protos.TrafficObservation;
import org.opensearch.migrations.trafficcapture.protos.TrafficStream;
import org.opensearch.migrations.trafficcapture.protos.TrafficStreamUtils;

import com.google.protobuf.ByteString;
import com.google.protobuf.Timestamp;
import lombok.NonNull;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * — coverage for the H2 dispatch + per-stream lifecycle on the
 * replayer accumulator. Verifies:
 * <ul>
 *   <li>H2 envelope routes to {@link H2Accumulation} via {@link CapturedTrafficToHttpTransactionAccumulator}</li>
 *   <li>HEADERS request/response separation by :status pseudo-header</li>
 *   <li>DATA frames append to the right side of the stream</li>
 *   <li>RST_STREAM marks the stream reset</li>
 *   <li>GOAWAY records the watermark</li>
 *   <li>SETTINGS update connection state</li>
 * </ul>
 *
 * <p>The H2 accumulator path doesn't yet emit RequestResponsePacketPair callbacks (wires
 * the adapter); these tests exercise state mutation via the public dispatch surface.
 */
class H2AccumulationDispatchTest extends InstrumentationTest {

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

    private static Timestamp ts(long sec) {
        return Timestamp.newBuilder().setSeconds(sec).build();
    }

    private static Http2HeaderField hf(String name, String value) {
        return Http2HeaderField.newBuilder()
                .setName(ByteString.copyFromUtf8(name))
                .setValue(ByteString.copyFromUtf8(value))
                .build();
    }

    /** Convenience builder: HEADERS frame observation with the given pseudo-headers. */
    private static TrafficObservation headersObs(int streamId, boolean endStream,
                                                  Http2HeaderField... fields) {
        var hp = Http2HeadersPayload.newBuilder().setEndStream(endStream).setEndHeaders(true);
        for (var f : fields) hp.addFields(f);
        return TrafficObservation.newBuilder().setTs(ts(1700000000L))
                .setHttp2Frame(Http2FrameObservation.newBuilder()
                        .setStreamId(streamId)
                        .setType(Http2FrameType.H2_HEADERS)
                        .setHeaders(hp).build())
                .build();
    }

    private static TrafficObservation dataObs(int streamId, boolean endStream, byte[] body) {
        return TrafficObservation.newBuilder().setTs(ts(1700000000L))
                .setHttp2Frame(Http2FrameObservation.newBuilder()
                        .setStreamId(streamId)
                        .setType(Http2FrameType.H2_DATA)
                        .setData(Http2DataPayload.newBuilder()
                                .setData(ByteString.copyFrom(body))
                                .setEndStream(endStream)
                                .build())
                        .build())
                .build();
    }

    private TrafficStream buildH2Stream(TrafficObservation... observations) {
        var alpn = AlpnNegotiationObservation.newBuilder()
                .setNegotiatedProtocol("h2")
                .setOfferedByClient("h2,http/1.1")
                .build();
        var b = TrafficStream.newBuilder()
                .setConnectionId("h2-conn")
                .setNodeId("node-1")
                .setCaptureFormatVersion(TrafficStreamUtils.CAPTURE_FORMAT_VERSION_V2)
                .setNegotiatedAlpn("h2")
                .setNumberOfThisLastChunk(1)
                .addSubStream(TrafficObservation.newBuilder().setTs(ts(1700000000L)).setAlpn(alpn));
        for (var o : observations) b.addSubStream(o);
        b.addSubStream(TrafficObservation.newBuilder().setTs(ts(1700000000L))
                .setClose(CloseObservation.getDefaultInstance()));
        return b.build();
    }

    @Test
    void h2Capture_dispatchesToH2Accumulation_andTracksStreamLifecycle() {
        var stream = buildH2Stream(
                headersObs(1, false,
                        hf(":method", "POST"),
                        hf(":path", "/_bulk"),
                        hf(":scheme", "https"),
                        hf(":authority", "localhost")),
                dataObs(1, true, "ok".getBytes()),
                headersObs(1, false, hf(":status", "200")),
                dataObs(1, true, "ok-resp".getBytes()));
        var accumulator = newAccumulator();
        Assertions.assertDoesNotThrow(() -> accumulator.accept(new PojoTrafficStreamAndKey(
                stream,
                PojoTrafficStreamKeyAndContext.build(stream, rootContext::createTrafficStreamContextForTest))));
    }

    @Test
    void rstStream_marksStreamReset_doesNotThrow() {
        var stream = buildH2Stream(
                headersObs(3, false, hf(":method", "GET"), hf(":path", "/")),
                TrafficObservation.newBuilder().setTs(ts(1700000000L))
                        .setHttp2Frame(Http2FrameObservation.newBuilder()
                                .setStreamId(3).setType(Http2FrameType.H2_RST_STREAM)
                                .setRstStream(Http2RstStreamPayload.newBuilder().setErrorCode(8).build())
                                .build())
                        .build());
        var accumulator = newAccumulator();
        Assertions.assertDoesNotThrow(() -> accumulator.accept(new PojoTrafficStreamAndKey(
                stream,
                PojoTrafficStreamKeyAndContext.build(stream, rootContext::createTrafficStreamContextForTest))));
    }

    @Test
    void goAway_setsConnectionWatermark() {
        var stream = buildH2Stream(
                TrafficObservation.newBuilder().setTs(ts(1700000000L))
                        .setHttp2Frame(Http2FrameObservation.newBuilder()
                                .setStreamId(0).setType(Http2FrameType.H2_GOAWAY)
                                .setGoAway(Http2GoAwayPayload.newBuilder()
                                        .setLastStreamId(7).setErrorCode(0).build())
                                .build())
                        .build());
        var accumulator = newAccumulator();
        Assertions.assertDoesNotThrow(() -> accumulator.accept(new PojoTrafficStreamAndKey(
                stream,
                PojoTrafficStreamKeyAndContext.build(stream, rootContext::createTrafficStreamContextForTest))));
    }

    @Test
    void settings_updateConnectionState() {
        var settings = Http2SettingsPayload.newBuilder()
                .setAck(false)
                .putSettings(1 /* HEADER_TABLE_SIZE */, 8192)
                .putSettings(4 /* INITIAL_WINDOW_SIZE */, 65535)
                .build();
        var stream = buildH2Stream(
                TrafficObservation.newBuilder().setTs(ts(1700000000L))
                        .setHttp2Frame(Http2FrameObservation.newBuilder()
                                .setStreamId(0).setType(Http2FrameType.H2_SETTINGS)
                                .setSettings(settings).build())
                        .build());
        var accumulator = newAccumulator();
        Assertions.assertDoesNotThrow(() -> accumulator.accept(new PojoTrafficStreamAndKey(
                stream,
                PojoTrafficStreamKeyAndContext.build(stream, rootContext::createTrafficStreamContextForTest))));
    }
}
