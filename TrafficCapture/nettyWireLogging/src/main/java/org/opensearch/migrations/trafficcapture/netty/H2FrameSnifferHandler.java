package org.opensearch.migrations.trafficcapture.netty;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;

import org.opensearch.migrations.trafficcapture.IChannelConnectionCaptureSerializer;
import org.opensearch.migrations.trafficcapture.h2.Http2FramePayload;
import org.opensearch.migrations.trafficcapture.h2.Http2FramePayload.HeaderField;
import org.opensearch.migrations.trafficcapture.protos.Http2FrameType;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.CompositeByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;
import io.netty.handler.codec.http2.DefaultHttp2HeadersDecoder;
import io.netty.handler.codec.http2.Http2Exception;
import io.netty.handler.codec.http2.Http2Headers;
import io.netty.util.AsciiString;
import lombok.extern.slf4j.Slf4j;

/**
 * Minimal-parse HTTP/2 frame sniffer ().
 *
 * <p>The handler sits in the Netty pipeline between the {@link io.netty.handler.ssl.SslHandler}
 * and the byte-forwarding handler. It parses the 9-byte H2 frame header to identify frame
 * boundaries, decodes HEADERS / CONTINUATION frames using a public Netty
 * {@link DefaultHttp2HeadersDecoder}, observes SETTINGS to keep its decoder's
 * {@code SETTINGS_HEADER_TABLE_SIZE} in sync, and emits a
 * {@link org.opensearch.migrations.trafficcapture.protos.Http2FrameObservation} per frame via
 * the capture serializer. Bytes forwarded downstream are byte-identical retained slices of the
 * input buffer — no re-encoding occurs.
 *
 * <p>Per, the sniffer is a transparent forwarder: it inspects HEADERS only enough to
 * fire a gating signal (via {@code onHeadersForGating}) for mutating-method streams that
 * the per-stream gate must hold; it does not buffer DATA or rewrite frames.
 *
 * <p>Lifecycle: one instance per direction per H2 connection. Memory cost ≈ 4KB (HPACK
 * decoder's dynamic table).
 */
@Slf4j
public class H2FrameSnifferHandler extends ByteToMessageDecoder {

    private enum State { AWAITING_PREFACE, AWAITING_FRAME_HEADER, AWAITING_FRAME_PAYLOAD }

    /** Connection preface bytes per RFC 7540 §3.5. */
    public static final byte[] CONNECTION_PREFACE =
            "PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n".getBytes(java.nio.charset.StandardCharsets.US_ASCII);

    public static final int FRAME_HEADER_BYTES = 9;

    // RFC 7540 §11.2 frame type codes
    public static final int FRAME_TYPE_DATA = 0x0;
    public static final int FRAME_TYPE_HEADERS = 0x1;
    public static final int FRAME_TYPE_PRIORITY = 0x2;
    public static final int FRAME_TYPE_RST_STREAM = 0x3;
    public static final int FRAME_TYPE_SETTINGS = 0x4;
    public static final int FRAME_TYPE_PUSH_PROMISE = 0x5;
    public static final int FRAME_TYPE_PING = 0x6;
    public static final int FRAME_TYPE_GOAWAY = 0x7;
    public static final int FRAME_TYPE_WINDOW_UPDATE = 0x8;
    public static final int FRAME_TYPE_CONTINUATION = 0x9;

    public static final int FLAG_ACK = 0x01;
    public static final int FLAG_END_STREAM = 0x01;
    public static final int FLAG_END_HEADERS = 0x04;
    public static final int FLAG_PADDED = 0x08;
    public static final int FLAG_PRIORITY = 0x20;

    // SETTINGS identifiers (RFC 7540 §6.5.2)
    public static final int SETTINGS_HEADER_TABLE_SIZE = 0x1;

    private final IChannelConnectionCaptureSerializer<?> capture;
    private final boolean isClientToProxyDirection;
    private final BiConsumer<Integer, Http2Headers> onHeadersForGating;
    private final DefaultHttp2HeadersDecoder hpackDecoder;

    // CONTINUATION coalescing buffer
    private int pendingHeaderStreamId = -1;
    private int pendingHeaderFrameType = -1;
    private CompositeByteBuf pendingHeaderBlock;
    private boolean pendingHeaderEndStream;
    private int pendingHeaderFlags;
    private int pendingHeaderPriority;
    private int pendingHeaderWeight;
    private boolean pendingHeaderExclusive;
    private int pendingHeaderPromisedStreamId;

    private State state;
    private int frameLength;
    private int frameType;
    private int frameFlags;
    private int frameStreamId;

    public H2FrameSnifferHandler(IChannelConnectionCaptureSerializer<?> capture,
                                 boolean isClientToProxyDirection,
                                 BiConsumer<Integer, Http2Headers> onHeadersForGating,
                                 long maxHeaderListSize,
                                 long maxHeaderTableSize) {
        this.capture = capture;
        this.isClientToProxyDirection = isClientToProxyDirection;
        this.onHeadersForGating = onHeadersForGating;
        this.hpackDecoder = new DefaultHttp2HeadersDecoder(true /* validateHeaders */, maxHeaderListSize);
        try {
            this.hpackDecoder.maxHeaderTableSize(maxHeaderTableSize);
        } catch (Http2Exception e) {
            throw new IllegalArgumentException(
                    "Invalid initial maxHeaderTableSize=" + maxHeaderTableSize, e);
        }
        this.state = isClientToProxyDirection ? State.AWAITING_PREFACE : State.AWAITING_FRAME_HEADER;
    }

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) throws Exception {
        int forwardStart = in.readerIndex();
        try {
            while (true) {
                if (state == State.AWAITING_PREFACE) {
                    if (in.readableBytes() < CONNECTION_PREFACE.length) {
                        return;
                    }
                    if (!matchesPreface(in)) {
                        ctx.fireExceptionCaught(new H2ProtocolError(
                                "bad H2 connection preface from peer"));
                        return;
                    }
                    in.skipBytes(CONNECTION_PREFACE.length);
                    state = State.AWAITING_FRAME_HEADER;
                }
                if (state == State.AWAITING_FRAME_HEADER) {
                    if (in.readableBytes() < FRAME_HEADER_BYTES) break;
                    int idx = in.readerIndex();
                    frameLength = in.getUnsignedMedium(idx);
                    frameType = in.getByte(idx + 3) & 0xff;
                    frameFlags = in.getByte(idx + 4) & 0xff;
                    frameStreamId = in.getInt(idx + 5) & 0x7fffffff;
                    state = State.AWAITING_FRAME_PAYLOAD;
                }
                if (state == State.AWAITING_FRAME_PAYLOAD) {
                    if (in.readableBytes() < FRAME_HEADER_BYTES + frameLength) break;
                    ByteBuf rawFrame = in.readRetainedSlice(FRAME_HEADER_BYTES + frameLength);
                    try {
                        processFrame(ctx, rawFrame);
                    } finally {
                        rawFrame.release();
                    }
                    state = State.AWAITING_FRAME_HEADER;
                }
            }
        } finally {
            int forwardLen = in.readerIndex() - forwardStart;
            if (forwardLen > 0) {
                ByteBuf forwarded = in.retainedSlice(forwardStart, forwardLen);
                out.add(forwarded);
            }
        }
    }

    /**
     * Process a single complete frame: emit observation, fire gating signal for HEADERS,
     * track SETTINGS_HEADER_TABLE_SIZE updates so the decoder remains in sync.
     */
    private void processFrame(ChannelHandlerContext ctx, ByteBuf rawFrame) throws Exception {
        var timestamp = Instant.now();
        var protoType = mapFrameType(frameType);

        if (frameType == FRAME_TYPE_SETTINGS && (frameFlags & FLAG_ACK) == 0) {
            // SETTINGS payload is sequence of (uint16 id, uint32 value) starting at offset 9.
            applySettingsToDecoder(rawFrame);
        }

        Http2FramePayload payload;
        Http2Headers decodedHeaders = null;

        switch (frameType) {
            case FRAME_TYPE_HEADERS:
            case FRAME_TYPE_CONTINUATION: {
                decodedHeaders = coalesceAndMaybeDecode(rawFrame);
                if (decodedHeaders != null) {
                    // We have a complete header block. Build the headers payload from the
                    // FIRST coalesced HEADERS frame's flags/priority data (CONTINUATION carries
                    // no priority).
                    boolean endStream = pendingHeaderEndStream;
                    boolean endHeaders = (frameFlags & FLAG_END_HEADERS) != 0;
                    payload = new Http2FramePayload.Http2HeadersPayloadView(
                            toHeaderFields(decodedHeaders),
                            endStream,
                            endHeaders,
                            pendingHeaderPriority,
                            pendingHeaderWeight,
                            pendingHeaderExclusive);
                    if (onHeadersForGating != null && isClientToProxyDirection) {
                        onHeadersForGating.accept(pendingHeaderStreamId, decodedHeaders);
                    }
                    // Reset coalescing state
                    resetPendingHeaders();
                } else if (frameType == FRAME_TYPE_CONTINUATION) {
                    payload = new Http2FramePayload.Http2ContinuationPayloadView(
                            rawFrame.slice(FRAME_HEADER_BYTES, frameLength),
                            (frameFlags & FLAG_END_HEADERS) != 0);
                } else {
                    // HEADERS opening a multi-fragment block — emit as a "truncated" view
                    // until CONTINUATION provides the rest. The next CONTINUATION's emission
                    // will be the final decoded block.
                    payload = new Http2FramePayload.Http2TruncatedPayloadView(Http2FrameType.H2_HEADERS);
                }
                break;
            }
            case FRAME_TYPE_DATA: {
                int dataOffset = FRAME_HEADER_BYTES;
                int dataLength = frameLength;
                int padLength = 0;
                if ((frameFlags & FLAG_PADDED) != 0 && frameLength > 0) {
                    padLength = rawFrame.getByte(FRAME_HEADER_BYTES) & 0xff;
                    dataOffset++;
                    dataLength = Math.max(0, frameLength - 1 - padLength);
                }
                payload = new Http2FramePayload.Http2DataPayloadView(
                        rawFrame.slice(dataOffset, dataLength),
                        (frameFlags & FLAG_END_STREAM) != 0,
                        padLength);
                break;
            }
            case FRAME_TYPE_SETTINGS: {
                payload = parseSettings(rawFrame, (frameFlags & FLAG_ACK) != 0);
                break;
            }
            case FRAME_TYPE_WINDOW_UPDATE: {
                int increment = rawFrame.getInt(FRAME_HEADER_BYTES) & 0x7fffffff;
                payload = new Http2FramePayload.Http2WindowUpdatePayloadView(increment);
                break;
            }
            case FRAME_TYPE_RST_STREAM: {
                long errorCode = rawFrame.getUnsignedInt(FRAME_HEADER_BYTES);
                payload = new Http2FramePayload.Http2RstStreamPayloadView(errorCode);
                break;
            }
            case FRAME_TYPE_GOAWAY: {
                int lastStreamId = rawFrame.getInt(FRAME_HEADER_BYTES) & 0x7fffffff;
                long errorCode = rawFrame.getUnsignedInt(FRAME_HEADER_BYTES + 4);
                int debugLen = frameLength - 8;
                ByteBuf debugData = debugLen > 0
                        ? rawFrame.slice(FRAME_HEADER_BYTES + 8, debugLen)
                        : rawFrame.alloc().buffer(0, 0);
                payload = new Http2FramePayload.Http2GoAwayPayloadView(lastStreamId, errorCode, debugData);
                break;
            }
            case FRAME_TYPE_PING: {
                payload = new Http2FramePayload.Http2PingPayloadView(
                        rawFrame.slice(FRAME_HEADER_BYTES, 8),
                        (frameFlags & FLAG_ACK) != 0);
                break;
            }
            case FRAME_TYPE_PRIORITY: {
                int dep = rawFrame.getInt(FRAME_HEADER_BYTES);
                int weight = rawFrame.getByte(FRAME_HEADER_BYTES + 4) & 0xff;
                boolean exclusive = (dep & 0x80000000) != 0;
                payload = new Http2FramePayload.Http2PriorityPayloadView(
                        dep & 0x7fffffff, weight, exclusive);
                break;
            }
            case FRAME_TYPE_PUSH_PROMISE: {
                int promisedStreamId = rawFrame.getInt(FRAME_HEADER_BYTES) & 0x7fffffff;
                ByteBuf headerBlock = rawFrame.slice(FRAME_HEADER_BYTES + 4, frameLength - 4);
                Http2Headers ph;
                try {
                    ph = hpackDecoder.decodeHeaders(0, headerBlock.duplicate());
                } catch (Http2Exception e) {
                    log.atWarn().setCause(e).setMessage("HPACK decode failure on PUSH_PROMISE; emitting truncated").log();
                    ph = null;
                }
                payload = new Http2FramePayload.Http2PushPromisePayloadView(
                        promisedStreamId,
                        ph == null ? List.of() : toHeaderFields(ph));
                break;
            }
            default: {
                // Extension or unknown frame type: pass through, observation marked truncated.
                payload = new Http2FramePayload.Http2TruncatedPayloadView(protoType);
                break;
            }
        }

        try {
            if (isClientToProxyDirection) {
                capture.addH2FrameRead(timestamp, frameStreamId, protoType, frameFlags, rawFrame, payload);
            } else {
                capture.addH2FrameWrite(timestamp, frameStreamId, protoType, frameFlags, rawFrame, payload);
            }
        } catch (Exception e) {
            log.atError().setCause(e).setMessage(
                    "Error emitting H2 frame observation: streamId={} type={} flags={}")
                .addArgument(frameStreamId)
                .addArgument(protoType)
                .addArgument(frameFlags)
                .log();
        }
    }

    /** RFC 7540 §6.5.2 SETTINGS_HEADER_TABLE_SIZE handling. */
    private void applySettingsToDecoder(ByteBuf rawFrame) {
        int payloadOffset = FRAME_HEADER_BYTES;
        int end = payloadOffset + frameLength;
        for (int i = payloadOffset; i + 6 <= end; i += 6) {
            int id = rawFrame.getUnsignedShort(i);
            long value = rawFrame.getUnsignedInt(i + 2);
            if (id == SETTINGS_HEADER_TABLE_SIZE) {
                try {
                    hpackDecoder.maxHeaderTableSize(value);
                } catch (Http2Exception e) {
                    log.atWarn().setCause(e).setMessage(
                            "Failed to apply SETTINGS_HEADER_TABLE_SIZE={} to HPACK decoder")
                        .addArgument(value).log();
                }
            }
        }
    }

    private Http2FramePayload.Http2SettingsPayloadView parseSettings(ByteBuf rawFrame, boolean ack) {
        Map<Integer, Long> settings = new HashMap<>();
        int payloadOffset = FRAME_HEADER_BYTES;
        int end = payloadOffset + frameLength;
        for (int i = payloadOffset; i + 6 <= end; i += 6) {
            settings.put(rawFrame.getUnsignedShort(i), rawFrame.getUnsignedInt(i + 2));
        }
        return new Http2FramePayload.Http2SettingsPayloadView(ack, settings);
    }

    /**
     * Coalesce HEADERS+CONTINUATION fragments per RFC 7540 §6.10. Returns the decoded
     * {@link Http2Headers} once END_HEADERS is set, or {@code null} when waiting for more
     * CONTINUATION fragments.
     */
    private Http2Headers coalesceAndMaybeDecode(ByteBuf rawFrame) {
        boolean endHeaders = (frameFlags & FLAG_END_HEADERS) != 0;
        int headerOffset = FRAME_HEADER_BYTES;
        int headerLength = frameLength;

        if (frameType == FRAME_TYPE_HEADERS) {
            // Strip optional pad-length and priority fields (RFC 7540 §6.2)
            if ((frameFlags & FLAG_PADDED) != 0 && headerLength > 0) {
                int pad = rawFrame.getByte(FRAME_HEADER_BYTES) & 0xff;
                headerOffset++;
                headerLength = Math.max(0, headerLength - 1 - pad);
            }
            if ((frameFlags & FLAG_PRIORITY) != 0 && headerLength >= 5) {
                int dep = rawFrame.getInt(headerOffset);
                int weight = rawFrame.getByte(headerOffset + 4) & 0xff;
                pendingHeaderPriority = dep & 0x7fffffff;
                pendingHeaderWeight = weight;
                pendingHeaderExclusive = (dep & 0x80000000) != 0;
                headerOffset += 5;
                headerLength -= 5;
            }
            pendingHeaderStreamId = frameStreamId;
            pendingHeaderFrameType = FRAME_TYPE_HEADERS;
            pendingHeaderEndStream = (frameFlags & FLAG_END_STREAM) != 0;
            pendingHeaderFlags = frameFlags;
        }

        ByteBuf fragment = rawFrame.slice(headerOffset, headerLength);

        if (endHeaders && pendingHeaderBlock == null) {
            // Common case: HEADERS without CONTINUATION.
            return decodeOrNull(pendingHeaderStreamId, fragment);
        }

        if (pendingHeaderBlock == null) {
            pendingHeaderBlock = rawFrame.alloc().compositeBuffer();
        }
        pendingHeaderBlock.addComponent(true, fragment.retain());

        if (!endHeaders) {
            return null;
        }

        Http2Headers decoded;
        try {
            decoded = decodeOrNull(pendingHeaderStreamId, pendingHeaderBlock.duplicate());
        } finally {
            pendingHeaderBlock.release();
            pendingHeaderBlock = null;
        }
        return decoded;
    }

    private Http2Headers decodeOrNull(int streamId, ByteBuf headerBlock) {
        try {
            return hpackDecoder.decodeHeaders(streamId, headerBlock.duplicate());
        } catch (Http2Exception e) {
            log.atWarn().setCause(e).setMessage(
                    "HPACK decode failure on streamId={}; emitting truncated headers")
                .addArgument(streamId).log();
            return null;
        }
    }

    private void resetPendingHeaders() {
        pendingHeaderStreamId = -1;
        pendingHeaderFrameType = -1;
        pendingHeaderEndStream = false;
        pendingHeaderFlags = 0;
        pendingHeaderPriority = 0;
        pendingHeaderWeight = 0;
        pendingHeaderExclusive = false;
        pendingHeaderPromisedStreamId = 0;
    }

    private static List<HeaderField> toHeaderFields(Http2Headers headers) {
        var list = new ArrayList<HeaderField>(headers.size());
        for (var entry : headers) {
            byte[] name = asBytes(entry.getKey());
            byte[] value = asBytes(entry.getValue());
            list.add(new HeaderField(name, value, false));
        }
        return list;
    }

    private static byte[] asBytes(CharSequence cs) {
        if (cs instanceof AsciiString as) {
            return as.toByteArray();
        }
        return cs.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    private static boolean matchesPreface(ByteBuf in) {
        int idx = in.readerIndex();
        for (int i = 0; i < CONNECTION_PREFACE.length; i++) {
            if (in.getByte(idx + i) != CONNECTION_PREFACE[i]) return false;
        }
        return true;
    }

    private static Http2FrameType mapFrameType(int wireType) {
        return switch (wireType) {
            case FRAME_TYPE_DATA -> Http2FrameType.H2_DATA;
            case FRAME_TYPE_HEADERS -> Http2FrameType.H2_HEADERS;
            case FRAME_TYPE_PRIORITY -> Http2FrameType.H2_PRIORITY;
            case FRAME_TYPE_RST_STREAM -> Http2FrameType.H2_RST_STREAM;
            case FRAME_TYPE_SETTINGS -> Http2FrameType.H2_SETTINGS;
            case FRAME_TYPE_PUSH_PROMISE -> Http2FrameType.H2_PUSH_PROMISE;
            case FRAME_TYPE_PING -> Http2FrameType.H2_PING;
            case FRAME_TYPE_GOAWAY -> Http2FrameType.H2_GOAWAY;
            case FRAME_TYPE_WINDOW_UPDATE -> Http2FrameType.H2_WINDOW_UPDATE;
            case FRAME_TYPE_CONTINUATION -> Http2FrameType.H2_CONTINUATION;
            default -> Http2FrameType.H2_DATA; // unknown extension frame type, captured as truncated
        };
    }

    @Override
    public void handlerRemoved0(ChannelHandlerContext ctx) {
        if (pendingHeaderBlock != null) {
            pendingHeaderBlock.release();
            pendingHeaderBlock = null;
        }
    }

    /** Thrown for protocol violations the sniffer cannot recover from (e.g., bad preface). */
    public static class H2ProtocolError extends RuntimeException {
        public H2ProtocolError(String message) {
            super(message);
        }
    }
}
