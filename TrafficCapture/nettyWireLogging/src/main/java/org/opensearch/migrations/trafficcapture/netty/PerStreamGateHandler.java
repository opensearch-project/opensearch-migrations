package org.opensearch.migrations.trafficcapture.netty;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Predicate;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.http2.Http2Headers;
import io.netty.util.ReferenceCountUtil;
import lombok.extern.slf4j.Slf4j;

/**
 * Per-stream offload-blocking gate ().
 *
 * <p>Sits between {@link H2FrameSnifferHandler} and the byte-forwarding handler. For each H2
 * stream, holds inbound frames (HEADERS, DATA, WINDOW_UPDATE) until the offload commit
 * signal resolves, then drains the queue in arrival order. Frames for non-gated streams
 * and connection-level frames pass through immediately.
 *
 * <p>Per the LLD, the gate is the only frame-aware handler on the outbound (forwarding)
 * direction. Everything else is byte passthrough. The buffering bound is the client's
 * advertised {@code SETTINGS_INITIAL_WINDOW_SIZE} per gated stream — by holding the
 * {@code WINDOW_UPDATE} frames the proxy would normally send, the client must stop
 * sending DATA after that many bytes.
 *
 * <p>The sniffer drives this handler via {@link #onHeadersForStream}; the wiring lives in
 * the proxy's {@code configureH2Pipeline}. Subclasses (or the integrating handler) provide
 * the offload-commit future.
 */
@Slf4j
public class PerStreamGateHandler extends ChannelInboundHandlerAdapter {

    public enum State { OPEN, GATED, COMMITTED }

    private static final class StreamState {
        State state;
        java.util.Deque<ByteBuf> queue = new java.util.ArrayDeque<>();
        StreamState(State initial) { this.state = initial; }
    }

    /**
     * Functional interface invoked when the gate decides a HEADERS frame opens a mutating
     * request stream. The returned future, when complete, triggers drain.
     */
    @FunctionalInterface
    public interface OffloadCommitTrigger {
        CompletableFuture<?> commitForStream(int streamId, Http2Headers headers);
    }

    private final Predicate<Http2Headers> shouldGatePredicate;
    private final OffloadCommitTrigger commitTrigger;
    private final Map<Integer, StreamState> streams = new HashMap<>();

    public PerStreamGateHandler(Predicate<Http2Headers> shouldGatePredicate,
                                OffloadCommitTrigger commitTrigger) {
        this.shouldGatePredicate = shouldGatePredicate;
        this.commitTrigger = commitTrigger;
    }

    /**
     * Called by the sniffer when it decodes a HEADERS frame. Decides whether this stream
     * should be gated based on the configured predicate.
     */
    public void onHeadersForStream(ChannelHandlerContext ctx, int streamId, Http2Headers headers) {
        if (shouldGatePredicate.test(headers)) {
            streams.put(streamId, new StreamState(State.GATED));
            log.atDebug().setMessage("Gating streamId={} pending offload commit").addArgument(streamId).log();
            commitTrigger.commitForStream(streamId, headers).whenComplete((result, t) -> {
                if (t != null) {
                    log.atWarn().setCause(t).setMessage(
                            "Offload commit failed for streamId={}; draining anyway").addArgument(streamId).log();
                }
                ctx.executor().execute(() -> drainStream(ctx, streamId));
            });
        } else {
            streams.put(streamId, new StreamState(State.OPEN));
        }
    }

    private void drainStream(ChannelHandlerContext ctx, int streamId) {
        var s = streams.get(streamId);
        if (s == null) return;
        s.state = State.COMMITTED;
        for (var buf : s.queue) {
            ctx.fireChannelRead(buf);
        }
        s.queue.clear();
    }

    /** Mark a stream closed (RST_STREAM, END_STREAM acked, etc.). Releases queued buffers. */
    public void closeStream(int streamId) {
        var s = streams.remove(streamId);
        if (s != null) {
            for (var buf : s.queue) {
                ReferenceCountUtil.release(buf);
            }
            s.queue.clear();
        }
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        if (!(msg instanceof ByteBuf buf)) {
            super.channelRead(ctx, msg);
            return;
        }
        // Without parsing the frame we'd have to delegate gating to the sniffer (which we do
        // via onHeadersForStream). For un-tagged byte chunks, just pass through — the
        // sniffer has already decided gating before forwarding.
        // Per-frame queueing on a gated stream is enabled via routeFrameToGate below; until
        // wired, all bytes flow through.
        super.channelRead(ctx, buf);
    }

    /**
     * Route a single H2 frame slice through the gate. Called from the proxy's
     * {@code configureH2Pipeline} integration when frame-by-frame gating is required.
     * Connection-level frames (streamId=0) and frames on un-tracked streams pass through.
     */
    public void routeFrameToGate(ChannelHandlerContext ctx, int streamId, ByteBuf frameSlice) {
        if (streamId == 0) {
            ctx.fireChannelRead(frameSlice);
            return;
        }
        var s = streams.get(streamId);
        if (s == null || s.state != State.GATED) {
            ctx.fireChannelRead(frameSlice);
            return;
        }
        // GATED: queue.
        s.queue.add(frameSlice.retain());
    }

    @Override
    public void handlerRemoved(ChannelHandlerContext ctx) throws Exception {
        for (var s : streams.values()) {
            for (var buf : s.queue) {
                ReferenceCountUtil.release(buf);
            }
        }
        streams.clear();
    }
}
