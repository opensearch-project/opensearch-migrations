package org.opensearch.migrations.trafficcapture;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import org.opensearch.migrations.trafficcapture.h2.Http2FramePayload;
import org.opensearch.migrations.trafficcapture.h2.Http2FramePayload.HeaderField;
import org.opensearch.migrations.trafficcapture.protos.Http2FrameType;
import org.opensearch.migrations.trafficcapture.protos.TrafficObservation;
import org.opensearch.migrations.trafficcapture.protos.TrafficStream;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * RFC 0001 T2.6 \u2014 verifies the serializer round-trips H2 frame and ALPN observations through
 * the offload path. Builds an InMemory capture factory, drives a small synthetic H2 sequence
 * (ALPN, SETTINGS, HEADERS, DATA, RST_STREAM), then parses the resulting {@link TrafficStream}
 * bytes and asserts the observation tags + payload contents.
 */
class StreamChannelConnectionCaptureSerializerH2Test {

    @Test
    void alpn_then_h2Frames_roundTripIntoTrafficStream() throws Exception {
        var done = new java.util.concurrent.CompletableFuture<Void>();
        var captured = new java.util.ArrayList<byte[]>();
        var manager = new StreamLifecycleManager<Void>() {
            @Override
            public CodedOutputStreamHolder createStream() {
                return new CodedOutputStreamAndByteBufferWrapper(64 * 1024);
            }
            @Override
            public CompletableFuture<Void> closeStream(CodedOutputStreamHolder holder, int index) {
                var bb = ((CodedOutputStreamAndByteBufferWrapper) holder).getByteBuffer();
                captured.add(java.util.Arrays.copyOfRange(bb.array(), 0, bb.position()));
                done.complete(null);
                return CompletableFuture.completedFuture(null);
            }
        };
        var serializer = new StreamChannelConnectionCaptureSerializer<Void>("node-1", "conn-A", manager);

        var ts = Instant.parse("2025-01-01T00:00:00Z");

        serializer.addAlpnNegotiatedEvent(ts, "h2", "h2,http/1.1");

        // SETTINGS (rawFrame need not be exact, but must round-trip).
        var settingsRaw = Unpooled.wrappedBuffer(new byte[]{0, 0, 0, 0x4, 0, 0, 0, 0, 0});
        serializer.addH2FrameRead(ts, 0, Http2FrameType.H2_SETTINGS, 0, settingsRaw,
                new Http2FramePayload.Http2SettingsPayloadView(false, Map.of(1, 4096L, 4, 65535L)));

        // HEADERS (POST /_bulk on stream 1).
        var headerFields = List.of(
                new HeaderField(":method".getBytes(), "POST".getBytes(), false),
                new HeaderField(":path".getBytes(), "/_bulk".getBytes(), false),
                new HeaderField(":scheme".getBytes(), "https".getBytes(), false),
                new HeaderField(":authority".getBytes(), "localhost".getBytes(), false));
        ByteBuf headersRaw = Unpooled.buffer().writeBytes(new byte[]{1, 2, 3});
        serializer.addH2FrameRead(ts, 1, Http2FrameType.H2_HEADERS, 0x05, headersRaw,
                new Http2FramePayload.Http2HeadersPayloadView(headerFields, true, true, 0, 16, false));

        // DATA + END_STREAM
        ByteBuf dataRaw = Unpooled.buffer().writeBytes("body".getBytes());
        serializer.addH2FrameRead(ts, 1, Http2FrameType.H2_DATA, 0x01, dataRaw,
                new Http2FramePayload.Http2DataPayloadView(Unpooled.wrappedBuffer("body".getBytes()), true, 0));

        // RST_STREAM on a different stream
        serializer.addH2FrameRead(ts, 3, Http2FrameType.H2_RST_STREAM, 0,
                Unpooled.buffer().writeInt(0x8 /* CANCEL */),
                new Http2FramePayload.Http2RstStreamPayloadView(0x8L));

        serializer.addCloseEvent(ts);
        serializer.flushCommitAndResetStream(true).get();
        done.get();

        // Parse the recorded stream.
        Assertions.assertEquals(1, captured.size(), "expected exactly one TrafficStream");
        TrafficStream stream = TrafficStream.parseFrom(captured.get(0));

        var subs = stream.getSubStreamList();
        // ALPN, SETTINGS, HEADERS, DATA, RST_STREAM, CLOSE
        Assertions.assertEquals(6, subs.size(), "expected 6 substream observations");

        Assertions.assertEquals(TrafficObservation.CaptureCase.ALPN, subs.get(0).getCaptureCase());
        Assertions.assertEquals("h2", subs.get(0).getAlpn().getNegotiatedProtocol());
        Assertions.assertEquals("h2,http/1.1", subs.get(0).getAlpn().getOfferedByClient());

        Assertions.assertEquals(TrafficObservation.CaptureCase.HTTP2FRAME, subs.get(1).getCaptureCase());
        var settingsObs = subs.get(1).getHttp2Frame();
        Assertions.assertEquals(Http2FrameType.H2_SETTINGS, settingsObs.getType());
        Assertions.assertEquals(0, settingsObs.getStreamId());
        Assertions.assertTrue(settingsObs.hasSettings());
        Assertions.assertEquals(4096, settingsObs.getSettings().getSettingsOrThrow(1));
        Assertions.assertEquals(65535, settingsObs.getSettings().getSettingsOrThrow(4));

        var headersObs = subs.get(2).getHttp2Frame();
        Assertions.assertEquals(Http2FrameType.H2_HEADERS, headersObs.getType());
        Assertions.assertEquals(1, headersObs.getStreamId());
        Assertions.assertEquals(0x05, headersObs.getFlags());
        Assertions.assertTrue(headersObs.hasHeaders());
        var hp = headersObs.getHeaders();
        Assertions.assertTrue(hp.getEndStream());
        Assertions.assertTrue(hp.getEndHeaders());
        Assertions.assertEquals(4, hp.getFieldsCount());
        Assertions.assertEquals(":method", hp.getFields(0).getName().toStringUtf8());
        Assertions.assertEquals("POST", hp.getFields(0).getValue().toStringUtf8());

        var dataObs = subs.get(3).getHttp2Frame();
        Assertions.assertEquals(Http2FrameType.H2_DATA, dataObs.getType());
        Assertions.assertEquals(1, dataObs.getStreamId());
        Assertions.assertTrue(dataObs.hasData());
        Assertions.assertTrue(dataObs.getData().getEndStream());
        Assertions.assertEquals("body", dataObs.getData().getData().toStringUtf8());

        var rstObs = subs.get(4).getHttp2Frame();
        Assertions.assertEquals(Http2FrameType.H2_RST_STREAM, rstObs.getType());
        Assertions.assertEquals(3, rstObs.getStreamId());
        Assertions.assertEquals(0x8, rstObs.getRstStream().getErrorCode());

        Assertions.assertEquals(TrafficObservation.CaptureCase.CLOSE, subs.get(5).getCaptureCase());
    }
}
