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
import org.opensearch.migrations.trafficcapture.protos.TrafficObservation;
import org.opensearch.migrations.trafficcapture.protos.TrafficStream;
import org.opensearch.migrations.trafficcapture.protos.TrafficStreamUtils;
import org.opensearch.migrations.trafficcapture.protos.UnsupportedCaptureFormatException;

import com.google.protobuf.Timestamp;
import lombok.NonNull;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Verifies the RFC 0001 §5/D5 fail-fast rule: an H2-unaware replayer must reject v2 captures
 * with a clear error rather than silently misinterpreting H2 frames as H1 bytes.
 *
 * <p>The guard is keyed off the {@code replayer.h2.enabled} system property. Until Phase 4
 * lands the H2 accumulator path, this property must be unset/false in production. These tests
 * stash and restore the property to keep parallel test workers isolated.
 */
class VersionGuardTest extends InstrumentationTest {

    private String savedProperty;

    @BeforeEach
    void stashProperty() {
        savedProperty = System.getProperty(TrafficStreamUtils.H2_SUPPORT_SYSTEM_PROPERTY);
        System.clearProperty(TrafficStreamUtils.H2_SUPPORT_SYSTEM_PROPERTY);
    }

    @AfterEach
    void restoreProperty() {
        if (savedProperty == null) {
            System.clearProperty(TrafficStreamUtils.H2_SUPPORT_SYSTEM_PROPERTY);
        } else {
            System.setProperty(TrafficStreamUtils.H2_SUPPORT_SYSTEM_PROPERTY, savedProperty);
        }
    }

    private TrafficStream buildV2Capture() {
        var ts = Timestamp.newBuilder().setSeconds(1700000000L).build();
        var alpn = AlpnNegotiationObservation.newBuilder()
                .setNegotiatedProtocol("h2")
                .setOfferedByClient("h2,http/1.1")
                .build();
        var frame = Http2FrameObservation.newBuilder()
                .setStreamId(1)
                .setType(Http2FrameType.H2_PING)
                .build();
        return TrafficStream.newBuilder()
                .setConnectionId("v2-conn")
                .setNodeId("v2-node")
                .setCaptureFormatVersion(TrafficStreamUtils.CAPTURE_FORMAT_VERSION_V2)
                .setNegotiatedAlpn("h2")
                .setNumberOfThisLastChunk(1)
                .addSubStream(TrafficObservation.newBuilder().setTs(ts).setAlpn(alpn))
                .addSubStream(TrafficObservation.newBuilder().setTs(ts).setHttp2Frame(frame))
                .build();
    }

    private TrafficStream buildV1Capture() {
        var ts = Timestamp.newBuilder().setSeconds(1700000000L).build();
        return TrafficStream.newBuilder()
                .setConnectionId("v1-conn")
                .setNodeId("v1-node")
                .setNumberOfThisLastChunk(1)
                .addSubStream(TrafficObservation.newBuilder().setTs(ts))
                .build();
    }

    private TrafficStream buildFutureCapture() {
        var ts = Timestamp.newBuilder().setSeconds(1700000000L).build();
        return TrafficStream.newBuilder()
                .setConnectionId("future-conn")
                .setNodeId("future-node")
                .setCaptureFormatVersion("v999")
                .setNumberOfThisLastChunk(1)
                .addSubStream(TrafficObservation.newBuilder().setTs(ts))
                .build();
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

    @Test
    void v2Capture_failsFast_whenH2SupportDisabled() {
        var v2 = buildV2Capture();
        var accum = newAccumulator();
        var stream = new PojoTrafficStreamAndKey(
                v2, PojoTrafficStreamKeyAndContext.build(v2, rootContext::createTrafficStreamContextForTest));

        var ex = Assertions.assertThrows(UnsupportedCaptureFormatException.class,
                () -> accum.accept(stream),
                "v2 capture must fail fast when H2 support flag is unset");
        Assertions.assertTrue(ex.getMessage().contains("v2"),
                "error message should mention the offending version: " + ex.getMessage());
        Assertions.assertTrue(ex.getMessage().contains(TrafficStreamUtils.H2_SUPPORT_SYSTEM_PROPERTY),
                "error message should reference the toggle property: " + ex.getMessage());
        Assertions.assertTrue(ex.getMessage().contains("0001-http2-trafficcapture"),
                "error message should reference the RFC: " + ex.getMessage());
    }

    @Test
    void v2Capture_passes_whenH2SupportEnabled() {
        System.setProperty(TrafficStreamUtils.H2_SUPPORT_SYSTEM_PROPERTY, "true");
        var v2 = buildV2Capture();
        var accum = newAccumulator();
        var stream = new PojoTrafficStreamAndKey(
                v2, PojoTrafficStreamKeyAndContext.build(v2, rootContext::createTrafficStreamContextForTest));

        Assertions.assertDoesNotThrow(() -> accum.accept(stream),
                "v2 capture must be accepted when H2 support flag is true");
    }

    @Test
    void v1Capture_alwaysPasses_regardlessOfFlag() {
        var v1 = buildV1Capture();
        var accum = newAccumulator();
        var stream = new PojoTrafficStreamAndKey(
                v1, PojoTrafficStreamKeyAndContext.build(v1, rootContext::createTrafficStreamContextForTest));
        Assertions.assertDoesNotThrow(() -> accum.accept(stream),
                "v1 capture must always be accepted (legacy compatibility)");
    }

    @Test
    void unrecognizedVersion_failsFast() {
        var future = buildFutureCapture();
        var ex = Assertions.assertThrows(UnsupportedCaptureFormatException.class,
                () -> TrafficStreamUtils.requireSupportedCaptureFormatVersion(future));
        Assertions.assertTrue(ex.getMessage().contains("v999"),
                "error message should call out the unrecognized version: " + ex.getMessage());
    }
}
