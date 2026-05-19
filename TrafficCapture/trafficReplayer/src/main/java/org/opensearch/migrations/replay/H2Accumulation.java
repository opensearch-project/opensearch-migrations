package org.opensearch.migrations.replay;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.opensearch.migrations.replay.datatypes.ITrafficStreamKey;
import org.opensearch.migrations.trafficcapture.protos.Http2HeaderField;
import org.opensearch.migrations.trafficcapture.protos.Http2HeadersPayload;
import org.opensearch.migrations.trafficcapture.protos.TrafficStream;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import lombok.NonNull;

/**
 * Replayer-side accumulation state for an HTTP/2 connection (RFC 0001 §8.1).
 *
 * <p>Unlike {@link Accumulation} (the H1 path), an H2 connection multiplexes many
 * concurrent streams. State is keyed by streamId in {@link #liveStreams}. The H1
 * common fields ({@code trafficChannelKey}, {@code numberOfResets}, etc.) are inherited
 * from the base class — those represent the connection-level lifecycle, which both
 * protocols share.
 *
 * <p>Per-frame dispatch lives in
 * {@link CapturedTrafficToHttpTransactionAccumulator#addH2Observation}; this class is
 * a passive state holder.
 */
public class H2Accumulation extends Accumulation {

    /** Per-stream state for one logical HTTP/2 stream. */
    public static class StreamState {
        public final int streamId;
        public LifecyclePhase phase = LifecyclePhase.RECEIVING_HEADERS;
        public Map<String, ByteBuf> requestPseudoHeaders = new HashMap<>();
        public List<Http2HeaderField> requestHeaderFields = new ArrayList<>();
        public List<ByteBuf> requestBody = new ArrayList<>();
        public List<Http2HeaderField> requestTrailers;
        public Map<String, ByteBuf> responsePseudoHeaders = new HashMap<>();
        public List<Http2HeaderField> responseHeaderFields = new ArrayList<>();
        public List<ByteBuf> responseBody = new ArrayList<>();
        public List<Http2HeaderField> responseTrailers;
        public Instant requestFirstFrameTs;
        public Instant responseFirstFrameTs;
        public boolean clientEndStream;
        public boolean serverEndStream;
        public Long resetErrorCode; // RST_STREAM, null when not reset

        public StreamState(int streamId) { this.streamId = streamId; }

        public boolean isComplete() {
            return clientEndStream && serverEndStream;
        }

        public boolean isReset() { return resetErrorCode != null; }
    }

    /** Per-stream lifecycle phases (RFC 0001 §8.2). */
    public enum LifecyclePhase {
        RECEIVING_HEADERS,
        RECEIVING_BODY,
        AWAITING_RESPONSE,
        RECEIVING_RESPONSE_HEADERS,
        RECEIVING_RESPONSE_BODY,
        CLOSED
    }

    /** Live per-stream accumulations keyed by H2 streamId. */
    public final Map<Integer, StreamState> liveStreams = new HashMap<>();

    /** GOAWAY watermark — streams with id > this are orphaned. -1 means no GOAWAY received. */
    public int goAwayLastStreamId = -1;
    public Long goAwayErrorCode;

    /** Most recently observed client / server SETTINGS for forensics. */
    public Map<Integer, Long> clientSettings = new HashMap<>();
    public Map<Integer, Long> serverSettings = new HashMap<>();

    public H2Accumulation(@NonNull ITrafficStreamKey key, TrafficStream ts, boolean isResumedConnection) {
        super(key, ts, isResumedConnection);
    }

    public StreamState getOrCreateStream(int streamId) {
        return liveStreams.computeIfAbsent(streamId, StreamState::new);
    }

    public Optional<StreamState> getStream(int streamId) {
        return Optional.ofNullable(liveStreams.get(streamId));
    }

    public void closeStream(int streamId) {
        var s = liveStreams.remove(streamId);
        if (s != null) {
            for (var b : s.requestBody) b.release();
            for (var b : s.responseBody) b.release();
            for (var v : s.requestPseudoHeaders.values()) v.release();
            for (var v : s.responsePseudoHeaders.values()) v.release();
            s.phase = LifecyclePhase.CLOSED;
        }
    }

    /**
     * Convert proto Http2HeadersPayload → an in-memory representation on a StreamState.
     * Pseudo-headers are split out by their leading colon so the adapter can find them
     * without scanning regular headers.
     */
    public static void applyHeadersToRequest(StreamState s, Http2HeadersPayload payload) {
        for (var f : payload.getFieldsList()) {
            var name = f.getName().toStringUtf8();
            if (name.startsWith(":")) {
                s.requestPseudoHeaders.put(name, Unpooled.copiedBuffer(f.getValue().toByteArray()));
            } else {
                s.requestHeaderFields.add(f);
            }
        }
    }

    public static void applyHeadersToResponse(StreamState s, Http2HeadersPayload payload) {
        for (var f : payload.getFieldsList()) {
            var name = f.getName().toStringUtf8();
            if (name.startsWith(":")) {
                s.responsePseudoHeaders.put(name, Unpooled.copiedBuffer(f.getValue().toByteArray()));
            } else {
                s.responseHeaderFields.add(f);
            }
        }
    }

    @Override
    public String toString() {
        return "H2Accumulation{" +
                "connectionId=" + trafficChannelKey.getConnectionId() +
                ", liveStreams=" + liveStreams.size() +
                ", goAwayLastStreamId=" + goAwayLastStreamId +
                "}";
    }
}
