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
import org.opensearch.migrations.trafficcapture.protos.CloseObservation;
import org.opensearch.migrations.trafficcapture.protos.EndOfMessageIndication;
import org.opensearch.migrations.trafficcapture.protos.ReadObservation;
import org.opensearch.migrations.trafficcapture.protos.TrafficObservation;
import org.opensearch.migrations.trafficcapture.protos.TrafficStream;
import org.opensearch.migrations.trafficcapture.protos.WriteObservation;

import com.google.protobuf.ByteString;
import com.google.protobuf.Timestamp;
import lombok.NonNull;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Verifies that captures produced by older (v1) capture proxies — which never set
 * {@code captureFormatVersion} or {@code negotiatedAlpn} on the {@link TrafficStream}
 * envelope — continue to drive the H1 accumulator path identically after RFC 0001
 * schema changes.
 *
 * <p>The v1 wire format and the H1 portion of the v2 wire format are byte-identical:
 * proto3's open-set field default for unset {@code string} is the empty string. A v1
 * record reaches the new accumulator with both new envelope fields empty, and dispatch
 * must classify it as H1 without inspecting the substream.
 */
class BackwardsCompatibilityTest extends InstrumentationTest {

    private static final byte[] HTTP_GET_REQUEST =
            ("GET /_search HTTP/1.1\r\nHost: localhost\r\n\r\n").getBytes();
    private static final byte[] HTTP_200_RESPONSE =
            ("HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\nok").getBytes();

    /**
     * Builds a TrafficStream that mimics what an older capture proxy would have written:
     * connectionId + nodeId + read/EOM/write + close, no captureFormatVersion, no
     * negotiatedAlpn, no H2 observations.
     */
    private TrafficStream buildLegacyV1Stream() {
        var ts = Timestamp.newBuilder().setSeconds(1700000000L).build();
        return TrafficStream.newBuilder()
                .setConnectionId("legacy-conn")
                .setNodeId("legacy-node")
                .setNumberOfThisLastChunk(1)
                .addSubStream(TrafficObservation.newBuilder().setTs(ts)
                        .setRead(ReadObservation.newBuilder()
                                .setData(ByteString.copyFrom(HTTP_GET_REQUEST))))
                .addSubStream(TrafficObservation.newBuilder().setTs(ts)
                        .setEndOfMessageIndicator(EndOfMessageIndication.newBuilder()
                                .setFirstLineByteLength(23)
                                .setHeadersByteLength(HTTP_GET_REQUEST.length)))
                .addSubStream(TrafficObservation.newBuilder().setTs(ts)
                        .setWrite(WriteObservation.newBuilder()
                                .setData(ByteString.copyFrom(HTTP_200_RESPONSE))))
                .addSubStream(TrafficObservation.newBuilder().setTs(ts)
                        .setClose(CloseObservation.getDefaultInstance()))
                .build();
        // Note: no .setCaptureFormatVersion() and no .setNegotiatedAlpn() — these stay empty.
    }

    @Test
    void v1Capture_emitsExactlyOneRequestResponsePair() throws Exception {
        var legacyStream = buildLegacyV1Stream();

        // Sanity: confirm the envelope really is v1-shaped.
        Assertions.assertEquals("", legacyStream.getCaptureFormatVersion(),
                "v1 capture must have empty captureFormatVersion");
        Assertions.assertEquals("", legacyStream.getNegotiatedAlpn(),
                "v1 capture must have empty negotiatedAlpn");

        var pairs = new ArrayList<RequestResponsePacketPair>();
        var requestsReceived = new AtomicInteger(0);
        var connectionsClosed = new AtomicInteger(0);

        var accumulator = new CapturedTrafficToHttpTransactionAccumulator(
                Duration.ofSeconds(30), null, new AccumulationCallbacks() {
            @Override
            public Consumer<RequestResponsePacketPair> onRequestReceived(
                    @NonNull IReplayContexts.IReplayerHttpTransactionContext ctx,
                    @NonNull HttpMessageAndTimestamp request,
                    boolean isResumedConnection) {
                requestsReceived.incrementAndGet();
                return pairs::add;
            }

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
                    @NonNull List<ITrafficStreamKey> trafficStreamKeysBeingHeld) {
                connectionsClosed.incrementAndGet();
            }

            @Override
            public void onTrafficStreamIgnored(
                    @NonNull IReplayContexts.ITrafficStreamsLifecycleContext ctx) {}
        });

        accumulator.accept(new PojoTrafficStreamAndKey(
                legacyStream,
                PojoTrafficStreamKeyAndContext.build(legacyStream, rootContext::createTrafficStreamContextForTest)));
        accumulator.close();

        Assertions.assertEquals(1, requestsReceived.get(),
                "v1 capture should produce exactly one onRequestReceived callback");
        Assertions.assertEquals(1, pairs.size(),
                "v1 capture should produce exactly one full RequestResponsePacketPair");
        Assertions.assertEquals(1, connectionsClosed.get(),
                "v1 capture's CloseObservation should drive onConnectionClose");

        var pair = pairs.get(0);
        Assertions.assertArrayEquals(HTTP_GET_REQUEST, bytesOf(pair.requestData),
                "request bytes must round-trip byte-identically through the accumulator");
        Assertions.assertArrayEquals(HTTP_200_RESPONSE, bytesOf(pair.responseData),
                "response bytes must round-trip byte-identically through the accumulator");
    }

    private static byte[] bytesOf(HttpMessageAndTimestamp m) {
        var packets = m.packetBytes;
        int total = packets.stream().mapToInt(b -> b.length).sum();
        var out = new byte[total];
        int off = 0;
        for (var b : packets) {
            System.arraycopy(b, 0, out, off, b.length);
            off += b.length;
        }
        return out;
    }
}
