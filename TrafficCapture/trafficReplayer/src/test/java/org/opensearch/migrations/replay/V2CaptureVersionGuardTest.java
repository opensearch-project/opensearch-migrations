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
import org.opensearch.migrations.trafficcapture.protos.Http2FrameObservation;
import org.opensearch.migrations.trafficcapture.protos.Http2FrameType;
import org.opensearch.migrations.trafficcapture.protos.Http2HeadersPayload;
import org.opensearch.migrations.trafficcapture.protos.Http2HeaderField;
import org.opensearch.migrations.trafficcapture.protos.TrafficObservation;
import org.opensearch.migrations.trafficcapture.protos.TrafficStream;

import com.google.protobuf.ByteString;
import com.google.protobuf.Timestamp;
import lombok.NonNull;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Phase-1 fail-fast guard (RFC 0001 §5/D5 rule 5): the H1-only accumulator must throw
 * loudly when it sees an HTTP/2 frame or ALPN observation, rather than silently dropping
 * it under the generic "unaccounted for observation" warn-and-continue path. Any
 * partial-accumulation outcome would produce misleading replay output.
 *
 * <p>This guard is removed in Phase 4 when the H2 accumulator dispatch lands.
 */
class V2CaptureVersionGuardTest extends InstrumentationTest {

    private CapturedTrafficToHttpTransactionAccumulator newAccumulator() {
        return new CapturedTrafficToHttpTransactionAccumulator(
                Duration.ofSeconds(30), null, new AccumulationCallbacks() {
            @Override
            public Consumer<RequestResponsePacketPair> onRequestReceived(
                    @NonNull IReplayContexts.IReplayerHttpTransactionContext ctx,
                    @NonNull HttpMessageAndTimestamp request,
                    boolean isResumedConnection) { return p -> {}; }

            @Override
            public void onTrafficStreamsExpired(
                    RequestResponsePacketPair.ReconstructionStatus status,
                    @NonNull IReplayContexts.IChannelKeyContext ctx,
                    @NonNull List<ITrafficStreamKey> trafficStreamKeysBeingHeld) {}

            @Override
            public void onConnectionClose(
                    int channelInteractionNumber,
                    @NonNull IReplayContexts.IChannelKeyContext ctx,
                    int channelSessionNumber,
                    RequestResponsePacketPair.ReconstructionStatus status,
                    @NonNull Instant when,
                    @NonNull List<ITrafficStreamKey> trafficStreamKeysBeingHeld) {}

            @Override
            public void onTrafficStreamIgnored(
                    @NonNull IReplayContexts.ITrafficStreamsLifecycleContext ctx) {}
        });
    }

    private void feed(CapturedTrafficToHttpTransactionAccumulator a, TrafficStream s) {
        a.accept(new PojoTrafficStreamAndKey(
                s, PojoTrafficStreamKeyAndContext.build(s, rootContext::createTrafficStreamContextForTest)));
    }

    @Test
    void v2_h2FrameObservation_throwsUnsupportedV2CaptureException() {
        var ts = Timestamp.newBuilder().setSeconds(1700000000L).build();
        var stream = TrafficStream.newBuilder()
                .setConnectionId("h2-conn")
                .setNodeId("node")
                .setNumberOfThisLastChunk(1)
                .setCaptureFormatVersion("v2")
                .setNegotiatedAlpn("h2")
                .addSubStream(TrafficObservation.newBuilder().setTs(ts)
                        .setHttp2Frame(Http2FrameObservation.newBuilder()
                                .setStreamId(1)
                                .setType(Http2FrameType.H2_HEADERS)
                                .setFlags(0x5)
                                .setHeaders(Http2HeadersPayload.newBuilder()
                                        .addFields(Http2HeaderField.newBuilder()
                                                .setName(ByteString.copyFromUtf8(":method"))
                                                .setValue(ByteString.copyFromUtf8("GET")))
                                        .setEndStream(true)
                                        .setEndHeaders(true))))
                .build();

        var ex = Assertions.assertThrows(
                CapturedTrafficToHttpTransactionAccumulator.UnsupportedV2CaptureException.class,
                () -> feed(newAccumulator(), stream),
                "H1-only accumulator must reject H2 frame observations");
        Assertions.assertTrue(ex.getMessage().contains("HTTP/2 observation"),
                "error must reference HTTP/2; was: " + ex.getMessage());
        Assertions.assertTrue(ex.getMessage().contains("RFC 0001"),
                "error should point to the RFC; was: " + ex.getMessage());
    }

    @Test
    void v2_alpnObservation_throwsUnsupportedV2CaptureException() {
        var ts = Timestamp.newBuilder().setSeconds(1700000000L).build();
        var stream = TrafficStream.newBuilder()
                .setConnectionId("alpn-conn")
                .setNodeId("node")
                .setNumberOfThisLastChunk(1)
                .setCaptureFormatVersion("v2")
                .setNegotiatedAlpn("h2")
                .addSubStream(TrafficObservation.newBuilder().setTs(ts)
                        .setAlpn(AlpnNegotiationObservation.newBuilder()
                                .setNegotiatedProtocol("h2")
                                .setOfferedByClient("h2,http/1.1")))
                .build();

        Assertions.assertThrows(
                CapturedTrafficToHttpTransactionAccumulator.UnsupportedV2CaptureException.class,
                () -> feed(newAccumulator(), stream),
                "H1-only accumulator must reject ALPN observations");
    }

    @Test
    void v2WithOnlyH1Observations_doesNotThrow() {
        // A v2 capture proxy in front of an H1-only client emits H1 observations with
        // captureFormatVersion="v2" + negotiatedAlpn="http/1.1". This must continue to work.
        var ts = Timestamp.newBuilder().setSeconds(1700000000L).build();
        byte[] body = ("GET / HTTP/1.1\r\nHost: x\r\n\r\n").getBytes();
        var stream = TrafficStream.newBuilder()
                .setConnectionId("h1-on-v2-conn")
                .setNodeId("node")
                .setNumberOfThisLastChunk(1)
                .setCaptureFormatVersion("v2")
                .setNegotiatedAlpn("http/1.1")
                .addSubStream(TrafficObservation.newBuilder().setTs(ts)
                        .setRead(org.opensearch.migrations.trafficcapture.protos.ReadObservation
                                .newBuilder().setData(ByteString.copyFrom(body))))
                .addSubStream(TrafficObservation.newBuilder().setTs(ts)
                        .setEndOfMessageIndicator(
                                org.opensearch.migrations.trafficcapture.protos.EndOfMessageIndication
                                        .newBuilder().setFirstLineByteLength(14).setHeadersByteLength(body.length)))
                .build();

        Assertions.assertDoesNotThrow(() -> feed(newAccumulator(), stream),
                "v2 envelope alone must not trigger the H2 guard when no H2 observations are present");
    }
}
