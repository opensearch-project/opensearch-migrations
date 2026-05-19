package org.opensearch.migrations.trafficcapture.h2;

import java.util.List;

import org.opensearch.migrations.trafficcapture.protos.Http2FrameType;

import io.netty.buffer.ByteBuf;

/**
 * Sealed interface representing the decoded payload of an HTTP/2 frame as observed by the
 * capture proxy. Each implementation is a thin view over the Netty / proto representation;
 * implementations are zero-copy wrappers and do not retain references past the listener call.
 *
 * <p>This is the boundary type passed to
 * {@link org.opensearch.migrations.trafficcapture.IChannelConnectionCaptureListener#addH2FrameRead}
 * and {@link org.opensearch.migrations.trafficcapture.IChannelConnectionCaptureListener#addH2FrameWrite}.
 *
 * <p>RFC 0001 (HTTP/2 TrafficCapture LLD §6.1).
 */
public sealed interface Http2FramePayload
        permits Http2FramePayload.Http2HeadersPayloadView,
                Http2FramePayload.Http2DataPayloadView,
                Http2FramePayload.Http2SettingsPayloadView,
                Http2FramePayload.Http2WindowUpdatePayloadView,
                Http2FramePayload.Http2RstStreamPayloadView,
                Http2FramePayload.Http2GoAwayPayloadView,
                Http2FramePayload.Http2PingPayloadView,
                Http2FramePayload.Http2PushPromisePayloadView,
                Http2FramePayload.Http2PriorityPayloadView,
                Http2FramePayload.Http2ContinuationPayloadView,
                Http2FramePayload.Http2TruncatedPayloadView {

    /** The wire-level frame type, mirroring the proto enum. */
    Http2FrameType frameType();

    /**
     * Convenience header field representation. Bytes are not copied; consumers that retain
     * them past the listener call MUST take a defensive copy.
     */
    record HeaderField(byte[] name, byte[] value, boolean sensitive) {}

    /** HEADERS frame payload (HPACK-decoded). */
    record Http2HeadersPayloadView(
            List<HeaderField> fields,
            boolean endStream,
            boolean endHeaders,
            int dependsOnStreamId,
            int weight,
            boolean exclusive) implements Http2FramePayload {
        @Override public Http2FrameType frameType() { return Http2FrameType.H2_HEADERS; }
    }

    /** DATA frame payload. The ByteBuf is borrowed; do not retain past the listener call. */
    record Http2DataPayloadView(ByteBuf data, boolean endStream, int padLength) implements Http2FramePayload {
        @Override public Http2FrameType frameType() { return Http2FrameType.H2_DATA; }
    }

    /** SETTINGS frame payload. */
    record Http2SettingsPayloadView(boolean ack, java.util.Map<Integer, Long> settings) implements Http2FramePayload {
        @Override public Http2FrameType frameType() { return Http2FrameType.H2_SETTINGS; }
    }

    /** WINDOW_UPDATE frame payload. */
    record Http2WindowUpdatePayloadView(int increment) implements Http2FramePayload {
        @Override public Http2FrameType frameType() { return Http2FrameType.H2_WINDOW_UPDATE; }
    }

    /** RST_STREAM frame payload. */
    record Http2RstStreamPayloadView(long errorCode) implements Http2FramePayload {
        @Override public Http2FrameType frameType() { return Http2FrameType.H2_RST_STREAM; }
    }

    /** GOAWAY frame payload. */
    record Http2GoAwayPayloadView(int lastStreamId, long errorCode, ByteBuf debugData) implements Http2FramePayload {
        @Override public Http2FrameType frameType() { return Http2FrameType.H2_GOAWAY; }
    }

    /** PING frame payload. */
    record Http2PingPayloadView(ByteBuf opaqueData, boolean ack) implements Http2FramePayload {
        @Override public Http2FrameType frameType() { return Http2FrameType.H2_PING; }
    }

    /** PUSH_PROMISE frame payload. */
    record Http2PushPromisePayloadView(int promisedStreamId, List<HeaderField> fields) implements Http2FramePayload {
        @Override public Http2FrameType frameType() { return Http2FrameType.H2_PUSH_PROMISE; }
    }

    /** PRIORITY frame payload (recorded, not replayed). */
    record Http2PriorityPayloadView(int dependsOnStreamId, int weight, boolean exclusive) implements Http2FramePayload {
        @Override public Http2FrameType frameType() { return Http2FrameType.H2_PRIORITY; }
    }

    /** CONTINUATION frame payload. */
    record Http2ContinuationPayloadView(ByteBuf headerBlockFragment, boolean endHeaders) implements Http2FramePayload {
        @Override public Http2FrameType frameType() { return Http2FrameType.H2_CONTINUATION; }
    }

    /**
     * Frame whose payload could not be decoded or did not fit in the TrafficStream buffer.
     * Marked with {@code truncated=true} on the resulting proto observation.
     */
    record Http2TruncatedPayloadView(Http2FrameType type) implements Http2FramePayload {
        @Override public Http2FrameType frameType() { return type; }
    }
}
