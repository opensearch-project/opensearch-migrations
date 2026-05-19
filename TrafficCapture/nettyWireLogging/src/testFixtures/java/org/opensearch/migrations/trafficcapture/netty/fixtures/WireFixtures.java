package org.opensearch.migrations.trafficcapture.netty.fixtures;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.http2.DefaultHttp2Connection;
import io.netty.handler.codec.http2.DefaultHttp2Headers;
import io.netty.handler.codec.http2.DefaultHttp2HeadersEncoder;
import io.netty.handler.codec.http2.Http2Connection;
import io.netty.handler.codec.http2.Http2Headers;

/**
 * — wire-level byte fixtures for HTTP/2 frame sniffer tests.
 *
 * <p>Each method returns a raw byte sequence representing one or more H2 frames as they
 * would appear on the wire. Used by {@code H2FrameSnifferHandlerTest} and any future
 * proxy-side integration tests that need deterministic byte-level inputs.
 *
 * <p>Frame layout (RFC 7540 §4.1): 9-byte header
 * (length:24, type:8, flags:8, R:1, streamId:31) + payload.
 */
public final class WireFixtures {

    private WireFixtures() {}

    public static final byte[] PREFACE = "PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n"
            .getBytes(java.nio.charset.StandardCharsets.US_ASCII);

    public static final int FLAG_END_STREAM = 0x01;
    public static final int FLAG_END_HEADERS = 0x04;
    public static final int FLAG_PADDED = 0x08;
    public static final int FLAG_PRIORITY = 0x20;
    public static final int FLAG_ACK = 0x01;

    /** Build one H2 frame with the given header + payload bytes. */
    public static byte[] buildFrame(int type, int flags, int streamId, byte[] payload) {
        int len = payload.length;
        byte[] out = new byte[9 + len];
        out[0] = (byte) ((len >> 16) & 0xff);
        out[1] = (byte) ((len >> 8) & 0xff);
        out[2] = (byte) (len & 0xff);
        out[3] = (byte) (type & 0xff);
        out[4] = (byte) (flags & 0xff);
        out[5] = (byte) ((streamId >> 24) & 0x7f);
        out[6] = (byte) ((streamId >> 16) & 0xff);
        out[7] = (byte) ((streamId >> 8) & 0xff);
        out[8] = (byte) (streamId & 0xff);
        System.arraycopy(payload, 0, out, 9, len);
        return out;
    }

    /** Empty SETTINGS frame (length=0, type=4, flags=0, streamId=0). */
    public static byte[] settingsDefault() {
        return buildFrame(0x4, 0, 0, new byte[0]);
    }

    /** SETTINGS ACK frame (length=0, type=4, flags=ACK, streamId=0). */
    public static byte[] settingsAck() {
        return buildFrame(0x4, FLAG_ACK, 0, new byte[0]);
    }

    /** RST_STREAM with the given error code (4-byte payload). */
    public static byte[] rstStream(int streamId, int errorCode) {
        var payload = new byte[]{
                (byte) ((errorCode >> 24) & 0xff),
                (byte) ((errorCode >> 16) & 0xff),
                (byte) ((errorCode >> 8) & 0xff),
                (byte) (errorCode & 0xff)};
        return buildFrame(0x3, 0, streamId, payload);
    }

    /** GOAWAY with no debug data (8-byte payload: lastStreamId + errorCode). */
    public static byte[] goAwayNoError(int lastStreamId) {
        var payload = new byte[8];
        payload[0] = (byte) ((lastStreamId >> 24) & 0x7f);
        payload[1] = (byte) ((lastStreamId >> 16) & 0xff);
        payload[2] = (byte) ((lastStreamId >> 8) & 0xff);
        payload[3] = (byte) (lastStreamId & 0xff);
        // errorCode = 0 (NO_ERROR), bytes 4..7 already zero.
        return buildFrame(0x7, 0, 0, payload);
    }

    /**
     * HEADERS frame with the given pseudo-header set, encoded via Netty's HPACK encoder.
     * Useful when testing HPACK round-trips through the sniffer.
     */
    public static byte[] headersFrame(int streamId, int flags, Http2Headers headers) throws Exception {
        Http2Connection conn = new DefaultHttp2Connection(false);
        DefaultHttp2HeadersEncoder encoder = new DefaultHttp2HeadersEncoder();
        ByteBuf headerBlock = Unpooled.buffer();
        try {
            encoder.encodeHeaders(streamId, headers, headerBlock);
            byte[] block = new byte[headerBlock.readableBytes()];
            headerBlock.getBytes(0, block);
            return buildFrame(0x1, flags, streamId, block);
        } finally {
            headerBlock.release();
        }
    }

    public static byte[] simpleGetHeadersFrame(int streamId) throws Exception {
        return headersFrame(streamId, FLAG_END_HEADERS | FLAG_END_STREAM,
                new DefaultHttp2Headers().method("GET").path("/").scheme("https").authority("h"));
    }

    /** DATA frame on streamId with the given payload bytes. */
    public static byte[] dataFrame(int streamId, int flags, byte[] payload) {
        return buildFrame(0x0, flags, streamId, payload);
    }

    /** Concatenate fixture byte arrays for streaming into a sniffer pipeline. */
    public static byte[] concat(byte[]... arrays) {
        int total = 0;
        for (var a : arrays) total += a.length;
        var out = new byte[total];
        int off = 0;
        for (var a : arrays) {
            System.arraycopy(a, 0, out, off, a.length);
            off += a.length;
        }
        return out;
    }
}
