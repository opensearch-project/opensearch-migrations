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
 * Replayer-side accumulation state for an HTTP/2 connection.
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

    /**
     * Per-stream state for one logical HTTP/2 stream.
     *
     * <p>Fields are package-private (no modifier) by design: the accumulator dispatch in
     * {@link CapturedTrafficToHttpTransactionAccumulator} (same package) mutates them
     * directly during frame processing. Exposing accessors would just be ceremony.
     */
    public static class StreamState {
        final int streamId;
        LifecyclePhase phase = LifecyclePhase.RECEIVING_HEADERS;
        final Map<String, ByteBuf> requestPseudoHeaders = new HashMap<>();
        final List<Http2HeaderField> requestHeaderFields = new ArrayList<>();
        final List<ByteBuf> requestBody = new ArrayList<>();
        List<Http2HeaderField> requestTrailers;
        final Map<String, ByteBuf> responsePseudoHeaders = new HashMap<>();
        final List<Http2HeaderField> responseHeaderFields = new ArrayList<>();
        final List<ByteBuf> responseBody = new ArrayList<>();
        List<Http2HeaderField> responseTrailers;
        Instant requestFirstFrameTs;
        Instant responseFirstFrameTs;
        boolean clientEndStream;
        boolean serverEndStream;
        Long resetErrorCode; // RST_STREAM, null when not reset
        /**
         * Set after onRequestReceived has fired for this stream. Holds the response continuation
         * so the response side can be delivered on the same callback chain. Stays null until the
         * request is fully received.
         */
        java.util.function.Consumer<RequestResponsePacketPair> responseContinuation;
        /**
         * Set when {@link #responseContinuation} is set; the in-flight pair the H2 dispatch
         * builds incrementally and hands off to {@code onRequestReceived}'s consumer.
         */
        RequestResponsePacketPair inFlightPair;
        /**
         * True once the request side has been emitted via the callback. Prevents duplicate
         * fires on subsequent HEADERS / DATA observations for the same stream.
         */
        boolean requestEmitted;

        public StreamState(int streamId) { this.streamId = streamId; }

        public int getStreamId() { return streamId; }
        public LifecyclePhase getPhase() { return phase; }
        public Map<String, ByteBuf> getRequestPseudoHeaders() { return requestPseudoHeaders; }
        public List<Http2HeaderField> getRequestHeaderFields() { return requestHeaderFields; }
        public List<ByteBuf> getRequestBody() { return requestBody; }
        public List<Http2HeaderField> getRequestTrailers() { return requestTrailers; }
        public void setRequestTrailers(List<Http2HeaderField> trailers) { this.requestTrailers = trailers; }
        public Map<String, ByteBuf> getResponsePseudoHeaders() { return responsePseudoHeaders; }
        public List<Http2HeaderField> getResponseHeaderFields() { return responseHeaderFields; }
        public List<ByteBuf> getResponseBody() { return responseBody; }
        public List<Http2HeaderField> getResponseTrailers() { return responseTrailers; }
        public void setResponseTrailers(List<Http2HeaderField> trailers) { this.responseTrailers = trailers; }

        public boolean isComplete() {
            return clientEndStream && serverEndStream;
        }

        public boolean isReset() { return resetErrorCode != null; }
    }

    /** Per-stream lifecycle phases. */
    public enum LifecyclePhase {
        RECEIVING_HEADERS,
        RECEIVING_BODY,
        AWAITING_RESPONSE,
        RECEIVING_RESPONSE_HEADERS,
        RECEIVING_RESPONSE_BODY,
        CLOSED
    }

    /** Live per-stream accumulations keyed by H2 streamId. */
    final Map<Integer, StreamState> liveStreams = new HashMap<>();

    /** GOAWAY watermark — streams with id > this are orphaned. -1 means no GOAWAY received. */
    int goAwayLastStreamId = -1;
    Long goAwayErrorCode;

    /** Most recently observed client / server SETTINGS for forensics. */
    final Map<Integer, Long> clientSettings = new HashMap<>();
    final Map<Integer, Long> serverSettings = new HashMap<>();

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

    public int getGoAwayLastStreamId() { return goAwayLastStreamId; }
    public Long getGoAwayErrorCode() { return goAwayErrorCode; }
    public Map<Integer, Long> getClientSettings() { return clientSettings; }
    public Map<Integer, Long> getServerSettings() { return serverSettings; }

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
