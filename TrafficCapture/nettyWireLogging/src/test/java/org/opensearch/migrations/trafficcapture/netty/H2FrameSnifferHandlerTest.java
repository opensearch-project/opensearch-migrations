package org.opensearch.migrations.trafficcapture.netty;

import java.io.IOException;
import java.net.SocketAddress;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

import org.opensearch.migrations.trafficcapture.IChannelConnectionCaptureSerializer;
import org.opensearch.migrations.trafficcapture.h2.Http2FramePayload;
import org.opensearch.migrations.trafficcapture.protos.Http2FrameType;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http2.DefaultHttp2Connection;
import io.netty.handler.codec.http2.DefaultHttp2HeadersEncoder;
import io.netty.handler.codec.http2.DefaultHttp2Headers;
import io.netty.handler.codec.http2.Http2Connection;
import io.netty.handler.codec.http2.Http2Headers;
import io.netty.util.ResourceLeakDetector;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Phase 2 (RFC 0001 T2.5) unit coverage for {@link H2FrameSnifferHandler}.
 *
 * <p>Drives the cumulator FSM with deterministic byte sequences (split mid-frame, split
 * mid-header, etc.) and asserts the captured observations + the byte-identical forwarded
 * slices. Runs with {@link ResourceLeakDetector.Level#PARANOID} per the implementation
 * plan to catch ref-count leaks.
 */
class H2FrameSnifferHandlerTest {

    @BeforeAll
    static void leakDetectionParanoid() {
        ResourceLeakDetector.setLevel(ResourceLeakDetector.Level.PARANOID);
    }

    private static final byte[] PREFACE = H2FrameSnifferHandler.CONNECTION_PREFACE;

    private RecordingCapture capture;
    private List<Integer> gateSignals;
    private EmbeddedChannel channel;

    @AfterEach
    void cleanup() {
        if (channel != null) channel.finishAndReleaseAll();
    }

    private H2FrameSnifferHandler newSniffer(boolean clientToProxy) {
        capture = new RecordingCapture();
        gateSignals = new ArrayList<>();
        return new H2FrameSnifferHandler(capture, clientToProxy,
                (sid, hdrs) -> gateSignals.add(sid),
                /*maxHeaderListSize*/ 8192,
                /*maxHeaderTableSize*/ 4096);
    }

    @Test
    void preface_then_settings_isCapturedAndForwardedVerbatim() {
        channel = new EmbeddedChannel(newSniffer(true));

        // SETTINGS frame: empty payload (length=0, type=4, flags=0, streamId=0)
        ByteBuf settingsFrame = Unpooled.wrappedBuffer(new byte[]{
                0, 0, 0, 0x4, 0, 0, 0, 0, 0
        });
        ByteBuf input = Unpooled.buffer().writeBytes(PREFACE).writeBytes(settingsFrame.duplicate());

        channel.writeInbound(input);

        // Forwarded byte-equality
        ByteBuf forwarded = readAllInbound(channel);
        Assertions.assertEquals(PREFACE.length + settingsFrame.readableBytes(), forwarded.readableBytes());
        for (int i = 0; i < PREFACE.length; i++) {
            Assertions.assertEquals(PREFACE[i], forwarded.getByte(i));
        }
        forwarded.release();
        settingsFrame.release();

        Assertions.assertEquals(1, capture.frames.size());
        var ev = capture.frames.get(0);
        Assertions.assertEquals(Http2FrameType.H2_SETTINGS, ev.type);
        Assertions.assertEquals(0, ev.streamId);
        Assertions.assertTrue(ev.payload instanceof Http2FramePayload.Http2SettingsPayloadView);
    }

    @Test
    void headers_singleFrame_decodesAndFiresGate() throws Exception {
        channel = new EmbeddedChannel(newSniffer(true));

        var headers = new DefaultHttp2Headers()
                .scheme("https").authority("localhost").method("POST").path("/_bulk");
        var encodedBlock = encodeHeaderBlock(headers);
        ByteBuf headersFrame = buildFrame(/*type*/ 0x1,
                /*flags*/ H2FrameSnifferHandler.FLAG_END_HEADERS | H2FrameSnifferHandler.FLAG_END_STREAM,
                /*streamId*/ 1, encodedBlock);

        channel.writeInbound(Unpooled.buffer().writeBytes(PREFACE));
        channel.writeInbound(headersFrame.copy());

        Assertions.assertEquals(1, capture.frames.size(), "expected exactly one frame observation");
        var ev = capture.frames.get(0);
        Assertions.assertEquals(Http2FrameType.H2_HEADERS, ev.type);
        Assertions.assertEquals(1, ev.streamId);
        Assertions.assertTrue(ev.payload instanceof Http2FramePayload.Http2HeadersPayloadView);
        var hp = (Http2FramePayload.Http2HeadersPayloadView) ev.payload;
        Assertions.assertTrue(hp.endStream());
        Assertions.assertTrue(hp.endHeaders());
        Assertions.assertTrue(hp.fields().stream().anyMatch(f -> new String(f.name()).equals(":method")
                && new String(f.value()).equals("POST")));

        Assertions.assertEquals(List.of(1), gateSignals, "gate signal must fire for the streamId");

        readAllInbound(channel).release();
        encodedBlock.release();
        headersFrame.release();
    }

    @Test
    void data_frame_extractsPayload() {
        channel = new EmbeddedChannel(newSniffer(true));

        byte[] body = "hello".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        ByteBuf dataFrame = buildFrame(/*type*/ 0x0,
                /*flags*/ H2FrameSnifferHandler.FLAG_END_STREAM,
                /*streamId*/ 3, Unpooled.wrappedBuffer(body));
        channel.writeInbound(Unpooled.buffer().writeBytes(PREFACE));
        channel.writeInbound(dataFrame.copy());

        Assertions.assertEquals(1, capture.frames.size());
        var ev = capture.frames.get(0);
        Assertions.assertEquals(Http2FrameType.H2_DATA, ev.type);
        var dp = (Http2FramePayload.Http2DataPayloadView) ev.payload;
        Assertions.assertTrue(dp.endStream());
        byte[] copy = new byte[body.length];
        dp.data().getBytes(0, copy);
        Assertions.assertArrayEquals(body, copy);

        readAllInbound(channel).release();
        dataFrame.release();
    }

    @Test
    void splitFrameAcrossReads_isStillCorrectlyParsed() throws Exception {
        channel = new EmbeddedChannel(newSniffer(true));

        var headers = new DefaultHttp2Headers().method("GET").path("/").scheme("https").authority("h");
        var encodedBlock = encodeHeaderBlock(headers);
        ByteBuf headersFrame = buildFrame(0x1,
                H2FrameSnifferHandler.FLAG_END_HEADERS | H2FrameSnifferHandler.FLAG_END_STREAM,
                5, encodedBlock);

        channel.writeInbound(Unpooled.buffer().writeBytes(PREFACE));
        // Split each input into 1-byte writes
        for (int i = 0; i < headersFrame.readableBytes(); i++) {
            channel.writeInbound(headersFrame.copy(i, 1));
        }

        Assertions.assertEquals(1, capture.frames.size());
        Assertions.assertEquals(5, capture.frames.get(0).streamId);

        readAllInbound(channel).release();
        encodedBlock.release();
        headersFrame.release();
    }

    @Test
    void rstStream_emitsObservation() {
        channel = new EmbeddedChannel(newSniffer(true));
        ByteBuf rstFrame = buildFrame(0x3, 0, 7, Unpooled.copyInt(0x8 /* CANCEL */));
        channel.writeInbound(Unpooled.buffer().writeBytes(PREFACE));
        channel.writeInbound(rstFrame.copy());

        Assertions.assertEquals(1, capture.frames.size());
        var ev = capture.frames.get(0);
        Assertions.assertEquals(Http2FrameType.H2_RST_STREAM, ev.type);
        Assertions.assertEquals(0x8L, ((Http2FramePayload.Http2RstStreamPayloadView) ev.payload).errorCode());
        readAllInbound(channel).release();
        rstFrame.release();
    }

    @Test
    void goAway_emitsObservationWithLastStreamId() {
        channel = new EmbeddedChannel(newSniffer(false));  // response-side, no preface
        ByteBuf goAwayPayload = Unpooled.buffer()
                .writeInt(11 /* lastStreamId */)
                .writeInt(0 /* NO_ERROR */);
        ByteBuf goAwayFrame = buildFrame(0x7, 0, 0, goAwayPayload);

        channel.writeInbound(goAwayFrame.copy());

        Assertions.assertEquals(1, capture.frames.size());
        var ev = capture.frames.get(0);
        Assertions.assertEquals(Http2FrameType.H2_GOAWAY, ev.type);
        var ga = (Http2FramePayload.Http2GoAwayPayloadView) ev.payload;
        Assertions.assertEquals(11, ga.lastStreamId());
        Assertions.assertEquals(0L, ga.errorCode());

        readAllInbound(channel).release();
        goAwayFrame.release();
        goAwayPayload.release();
    }

    @Test
    void responseSide_doesNotFireGateSignal() throws Exception {
        channel = new EmbeddedChannel(newSniffer(false));  // response-side
        var headers = new DefaultHttp2Headers().status("200");
        var encodedBlock = encodeHeaderBlock(headers);
        ByteBuf headersFrame = buildFrame(0x1,
                H2FrameSnifferHandler.FLAG_END_HEADERS | H2FrameSnifferHandler.FLAG_END_STREAM,
                1, encodedBlock);
        channel.writeInbound(headersFrame.copy());

        Assertions.assertEquals(1, capture.frames.size(), "headers observation expected");
        Assertions.assertTrue(gateSignals.isEmpty(), "gate signal must NOT fire on response direction");

        readAllInbound(channel).release();
        encodedBlock.release();
        headersFrame.release();
    }

    @Test
    void badPreface_firesExceptionCaught() {
        var sniffer = newSniffer(true);
        channel = new EmbeddedChannel(sniffer);
        var caught = new AtomicReference<Throwable>();
        channel.pipeline().addLast(new io.netty.channel.ChannelInboundHandlerAdapter() {
            @Override
            public void exceptionCaught(io.netty.channel.ChannelHandlerContext ctx, Throwable cause) {
                caught.set(cause);
            }
        });

        // 24 bytes of garbage — not the H2 preface
        byte[] garbage = new byte[H2FrameSnifferHandler.CONNECTION_PREFACE.length];
        for (int i = 0; i < garbage.length; i++) garbage[i] = (byte) 'X';
        channel.writeInbound(Unpooled.wrappedBuffer(garbage));

        Assertions.assertNotNull(caught.get(), "bad preface must fire exceptionCaught");
        Assertions.assertTrue(caught.get() instanceof H2FrameSnifferHandler.H2ProtocolError,
                "exception should be H2ProtocolError, was " + caught.get().getClass());

        // No frames captured.
        Assertions.assertEquals(0, capture.frames.size());
        readAllInbound(channel).release();
    }

    private ByteBuf encodeHeaderBlock(Http2Headers headers) throws Exception {
        Http2Connection conn = new DefaultHttp2Connection(/*server*/ false);
        DefaultHttp2HeadersEncoder encoder = new DefaultHttp2HeadersEncoder();
        ByteBuf out = Unpooled.buffer();
        encoder.encodeHeaders(/*streamId*/ 1, headers, out);
        return out;
    }

    /** Build a 9-byte-header H2 frame given a typed payload. */
    private static ByteBuf buildFrame(int type, int flags, int streamId, ByteBuf payload) {
        int length = payload.readableBytes();
        ByteBuf frame = Unpooled.buffer(9 + length);
        frame.writeMedium(length);
        frame.writeByte(type);
        frame.writeByte(flags);
        frame.writeInt(streamId & 0x7fffffff);
        frame.writeBytes(payload.duplicate());
        return frame;
    }

    private static ByteBuf readAllInbound(EmbeddedChannel ch) {
        var composite = Unpooled.compositeBuffer();
        Object next;
        while ((next = ch.readInbound()) != null) {
            composite.addComponent(true, (ByteBuf) next);
        }
        return composite;
    }

    /** Minimal capture serializer that records every H2 frame observation. */
    static class RecordingCapture implements IChannelConnectionCaptureSerializer<Object> {
        record FrameEvent(boolean read, Instant ts, int streamId, Http2FrameType type, int flags,
                          Http2FramePayload payload) {}

        final List<FrameEvent> frames = new ArrayList<>();

        @Override
        public void addH2FrameRead(Instant ts, int streamId, Http2FrameType type, int flags,
                                   ByteBuf rawFrame, Http2FramePayload payload) {
            frames.add(new FrameEvent(true, ts, streamId, type, flags, payload));
        }

        @Override
        public void addH2FrameWrite(Instant ts, int streamId, Http2FrameType type, int flags,
                                    ByteBuf rawFrame, Http2FramePayload payload) {
            frames.add(new FrameEvent(false, ts, streamId, type, flags, payload));
        }

        @Override public void addBindEvent(Instant timestamp, SocketAddress addr) {}
        @Override public CompletableFuture<Object> flushCommitAndResetStream(boolean isFinal) {
            return CompletableFuture.completedFuture(null);
        }
    }
}
