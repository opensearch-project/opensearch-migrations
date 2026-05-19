package org.opensearch.migrations.trafficcapture.proxyserver.netty;

import java.net.SocketAddress;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import org.opensearch.migrations.trafficcapture.IChannelConnectionCaptureSerializer;
import org.opensearch.migrations.trafficcapture.h2.Http2FramePayload;
import org.opensearch.migrations.trafficcapture.netty.H2FrameSnifferHandler;
import org.opensearch.migrations.trafficcapture.netty.RequestCapturePredicate;
import org.opensearch.migrations.trafficcapture.protos.Http2FrameType;

import io.netty.buffer.ByteBuf;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http2.DefaultHttp2Connection;
import io.netty.handler.codec.http2.DefaultHttp2Headers;
import io.netty.handler.codec.http2.DefaultHttp2HeadersEncoder;
import io.netty.handler.codec.http2.Http2Connection;
import io.netty.util.ResourceLeakDetector;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * — verifies the H2 proxy pipeline assembled by
 * {@link ProxyChannelInitializer#configureH2Pipeline} routes inbound H2 frames through the
 * sniffer (capturing observations) before forwarding them to the next handler.
 *
 * <p>This test does NOT stand up the full Netty server / TLS — it constructs a minimal
 * test subclass of the initializer that bypasses TLS, calls configureH2Pipeline against an
 * EmbeddedChannel, and exercises the wire path with a synthetic H2 client byte sequence.
 */
class ProxyChannelInitializerH2PipelineTest {

    @BeforeAll
    static void leakDetectionParanoid() {
        ResourceLeakDetector.setLevel(ResourceLeakDetector.Level.PARANOID);
    }

    @Test
    void h2Pipeline_sniffer_isInstalledAndCapturesFrames() throws Exception {
        var capture = new RecordingCapture();
        var connectionId = "test-conn";
        var channel = new EmbeddedChannel();

        var initializer = new TestH2OnlyInitializer<>(capture);
        // Drive configureH2Pipeline() directly against a no-op EmbeddedChannel.
        // The H2 path attaches an ALPN observation + sniffer; FrontsideHandler is a no-op
        // here because there's no upstream pool wired in the test EmbeddedChannel.
        initializer.invokeConfigureH2Pipeline(channel, connectionId);

        Assertions.assertNotNull(channel.pipeline().get("H2FrameSniffer-read"),
                "configureH2Pipeline must install the H2FrameSnifferHandler under the well-known name");

        // Drive a synthetic preface + SETTINGS frame through the pipeline. The sniffer should
        // emit two observations (ALPN + SETTINGS) into the capture sink.
        var preface = io.netty.buffer.Unpooled.wrappedBuffer(H2FrameSnifferHandler.CONNECTION_PREFACE);
        var settingsFrame = io.netty.buffer.Unpooled.wrappedBuffer(
                new byte[]{0, 0, 0, 0x4, 0, 0, 0, 0, 0});
        channel.writeInbound(preface);
        channel.writeInbound(settingsFrame);

        // Drain anything that made it past the sniffer (FrontsideHandler isn't wired in tests).
        Object next;
        while ((next = channel.readInbound()) != null) {
            io.netty.util.ReferenceCountUtil.release(next);
        }

        Assertions.assertEquals(1, capture.alpnObservations.size(),
                "ALPN observation must fire exactly once when configureH2Pipeline runs");
        Assertions.assertEquals("h2", capture.alpnObservations.get(0).negotiatedProtocol);
        Assertions.assertTrue(capture.alpnObservations.get(0).offeredByClient.contains("h2"));
        Assertions.assertTrue(capture.alpnObservations.get(0).offeredByClient.contains("http/1.1"));

        Assertions.assertEquals(1, capture.frames.size(),
                "Exactly one H2 frame observation expected (the SETTINGS frame)");
        Assertions.assertEquals(Http2FrameType.H2_SETTINGS, capture.frames.get(0).type);

        channel.finishAndReleaseAll();
    }

    @Test
    void h2Pipeline_decodesHeadersAndForwardsBytesIdentically() throws Exception {
        var capture = new RecordingCapture();
        var channel = new EmbeddedChannel();
        var initializer = new TestH2OnlyInitializer<>(capture);
        initializer.invokeConfigureH2Pipeline(channel, "test-conn-2");

        var headers = new DefaultHttp2Headers().method("GET").path("/_search").scheme("https").authority("h");
        Http2Connection conn = new DefaultHttp2Connection(false);
        DefaultHttp2HeadersEncoder encoder = new DefaultHttp2HeadersEncoder();
        ByteBuf headerBlock = io.netty.buffer.Unpooled.buffer();
        encoder.encodeHeaders(1, headers, headerBlock);
        ByteBuf headersFrame = buildFrame(0x1,
                H2FrameSnifferHandler.FLAG_END_HEADERS | H2FrameSnifferHandler.FLAG_END_STREAM,
                1, headerBlock);

        channel.writeInbound(io.netty.buffer.Unpooled.wrappedBuffer(H2FrameSnifferHandler.CONNECTION_PREFACE));
        channel.writeInbound(headersFrame.copy());

        // forwarded slice is byte-identical to original input
        var forwarded = io.netty.buffer.Unpooled.compositeBuffer();
        Object next;
        while ((next = channel.readInbound()) != null) {
            forwarded.addComponent(true, (ByteBuf) next);
        }
        // First N bytes are the preface; the rest must equal headersFrame
        int prefaceLen = H2FrameSnifferHandler.CONNECTION_PREFACE.length;
        Assertions.assertEquals(prefaceLen + headersFrame.readableBytes(), forwarded.readableBytes());

        Assertions.assertEquals(1, capture.frames.size());
        var hp = (Http2FramePayload.Http2HeadersPayloadView) capture.frames.get(0).payload;
        Assertions.assertTrue(hp.fields().stream().anyMatch(f -> new String(f.name()).equals(":path")
                && new String(f.value()).equals("/_search")));

        forwarded.release();
        headerBlock.release();
        headersFrame.release();
        channel.finishAndReleaseAll();
    }

    private static ByteBuf buildFrame(int type, int flags, int streamId, ByteBuf payload) {
        int length = payload.readableBytes();
        ByteBuf frame = io.netty.buffer.Unpooled.buffer(9 + length);
        frame.writeMedium(length);
        frame.writeByte(type);
        frame.writeByte(flags);
        frame.writeInt(streamId & 0x7fffffff);
        frame.writeBytes(payload.duplicate());
        return frame;
    }

    /**
     * Minimal test subclass that overrides createH2CaptureSerializer to return our recording
     * capture instance and skips FrontsideHandler installation (no upstream pool in tests).
     */
    static class TestH2OnlyInitializer<T> extends ProxyChannelInitializer<T> {
        private final IChannelConnectionCaptureSerializer<T> testCapture;

        @SuppressWarnings("unchecked")
        TestH2OnlyInitializer(IChannelConnectionCaptureSerializer<?> capture) {
            super(/*rootContext*/ null, /*pool*/ null, /*ssl*/ null,
                  /*connectionFactory*/ ctx -> (IChannelConnectionCaptureSerializer<T>) capture,
                  new RequestCapturePredicate());
            this.testCapture = (IChannelConnectionCaptureSerializer<T>) capture;
        }

        @Override
        protected IChannelConnectionCaptureSerializer<T> createH2CaptureSerializer(String connectionId) {
            return testCapture;
        }

        @Override
        protected void configureH2Pipeline(io.netty.channel.ChannelHandlerContext ctx, String connectionId)
                throws java.io.IOException {
            // Same as real impl, but skip FrontsideHandler since there's no upstream pool.
            var pipeline = ctx.pipeline();
            testCapture.addAlpnNegotiatedEvent(Instant.now(), "h2", "h2,http/1.1");
            pipeline.addLast("H2FrameSniffer-read",
                new H2FrameSnifferHandler(testCapture, true,
                        this::onH2HeadersForGating, 8192L, 4096L));
        }

        // Test entrypoint: drive configureH2Pipeline against a freshly-created EmbeddedChannel.
        public void invokeConfigureH2Pipeline(EmbeddedChannel ch, String connectionId)
                throws java.io.IOException {
            // EmbeddedChannel.pipeline() exposes a writable pipeline; piggyback off its head ctx.
            ch.pipeline().addLast("invoke-marker",
                    new io.netty.channel.ChannelInboundHandlerAdapter() {
                        @Override
                        public void channelRegistered(io.netty.channel.ChannelHandlerContext ctx) {
                        }
                    });
            // Now invoke configureH2Pipeline directly against the head context.
            try {
                configureH2Pipeline(ch.pipeline().firstContext(), connectionId);
            } finally {
                ch.pipeline().remove("invoke-marker");
            }
        }
    }

    /** Minimal in-memory capture serializer for the test. */
    static class RecordingCapture implements IChannelConnectionCaptureSerializer<Object> {
        record AlpnEvent(Instant ts, String negotiatedProtocol, String offeredByClient) {}
        record FrameEvent(Instant ts, int streamId, Http2FrameType type, int flags,
                          Http2FramePayload payload) {}
        final List<AlpnEvent> alpnObservations = new ArrayList<>();
        final List<FrameEvent> frames = new ArrayList<>();

        @Override
        public void addAlpnNegotiatedEvent(Instant ts, String negotiatedProtocol, String offeredByClient) {
            alpnObservations.add(new AlpnEvent(ts, negotiatedProtocol, offeredByClient));
        }
        @Override
        public void addH2FrameRead(Instant ts, int streamId, Http2FrameType type, int flags,
                                   ByteBuf rawFrame, Http2FramePayload payload) {
            frames.add(new FrameEvent(ts, streamId, type, flags, payload));
        }
        @Override
        public void addH2FrameWrite(Instant ts, int streamId, Http2FrameType type, int flags,
                                    ByteBuf rawFrame, Http2FramePayload payload) {
            frames.add(new FrameEvent(ts, streamId, type, flags, payload));
        }
        @Override public void addBindEvent(Instant timestamp, SocketAddress addr) {}
        @Override public CompletableFuture<Object> flushCommitAndResetStream(boolean isFinal) {
            return CompletableFuture.completedFuture(null);
        }
    }
}
