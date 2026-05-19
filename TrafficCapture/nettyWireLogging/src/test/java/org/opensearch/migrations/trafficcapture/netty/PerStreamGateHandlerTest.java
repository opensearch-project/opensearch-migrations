package org.opensearch.migrations.trafficcapture.netty;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http2.DefaultHttp2Headers;
import io.netty.handler.codec.http2.Http2Headers;
import io.netty.util.ResourceLeakDetector;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * — coverage for {@link PerStreamGateHandler} per-stream lifecycle.
 *
 * <p>Verifies:
 * <ul>
 *   <li>frames on un-tracked stream IDs pass through immediately</li>
 *   <li>connection-level frames (streamId=0) always pass through</li>
 *   <li>HEADERS that trigger gating queue subsequent frames until commit</li>
 *   <li>commit drain releases queued frames in arrival order</li>
 *   <li>non-gated streams running concurrently with gated ones are not held</li>
 *   <li>handlerRemoved releases all queued buffers (PARANOID leak detector)</li>
 * </ul>
 */
class PerStreamGateHandlerTest {

    @BeforeAll
    static void leakDetectionParanoid() {
        ResourceLeakDetector.setLevel(ResourceLeakDetector.Level.PARANOID);
    }

    @Test
    void unTrackedStream_passesThrough() {
        var commit = new CompletableFuture<>();
        var gate = new PerStreamGateHandler(h -> false, (sid, h) -> commit);
        var ch = new EmbeddedChannel(gate);

        var buf = Unpooled.wrappedBuffer(new byte[]{1, 2, 3});
        gate.routeFrameToGate(ch.pipeline().firstContext(), /*streamId*/ 1, buf);

        var out = (ByteBuf) ch.readInbound();
        Assertions.assertNotNull(out);
        out.release();
        ch.finishAndReleaseAll();
    }

    @Test
    void connectionLevelFrame_passesThroughEvenWhenStreamGated() {
        var commit = new CompletableFuture<>();
        var gate = new PerStreamGateHandler(h -> true, (sid, h) -> commit);
        var ch = new EmbeddedChannel(gate);
        var ctx = ch.pipeline().firstContext();

        gate.onHeadersForStream(ctx, 1, headersFor("POST"));

        // Connection-scoped (streamId=0) WINDOW_UPDATE must pass even with stream 1 gated.
        var connFrame = Unpooled.wrappedBuffer(new byte[]{4, 5, 6});
        gate.routeFrameToGate(ctx, 0, connFrame);
        var out = (ByteBuf) ch.readInbound();
        Assertions.assertNotNull(out);
        out.release();

        // Frame on stream 1 is queued.
        var streamFrame = Unpooled.wrappedBuffer(new byte[]{7, 8, 9});
        gate.routeFrameToGate(ctx, 1, streamFrame);
        Assertions.assertNull(ch.readInbound(), "gated stream frame must not pass through");

        // Now commit and drain.
        commit.complete(null);
        ch.runPendingTasks();
        var drained = (ByteBuf) ch.readInbound();
        Assertions.assertNotNull(drained);
        drained.release();

        streamFrame.release();
        ch.finishAndReleaseAll();
    }

    @Test
    void gatedStream_drainsInArrivalOrderAfterCommit() {
        var commit = new CompletableFuture<>();
        var gate = new PerStreamGateHandler(h -> true, (sid, h) -> commit);
        var ch = new EmbeddedChannel(gate);
        var ctx = ch.pipeline().firstContext();

        gate.onHeadersForStream(ctx, 1, headersFor("POST"));

        var f1 = Unpooled.wrappedBuffer(new byte[]{1});
        var f2 = Unpooled.wrappedBuffer(new byte[]{2});
        var f3 = Unpooled.wrappedBuffer(new byte[]{3});

        gate.routeFrameToGate(ctx, 1, f1);
        gate.routeFrameToGate(ctx, 1, f2);
        gate.routeFrameToGate(ctx, 1, f3);
        Assertions.assertNull(ch.readInbound());

        commit.complete(null);
        ch.runPendingTasks();

        var d1 = (ByteBuf) ch.readInbound();
        var d2 = (ByteBuf) ch.readInbound();
        var d3 = (ByteBuf) ch.readInbound();
        Assertions.assertEquals(1, d1.getByte(0));
        Assertions.assertEquals(2, d2.getByte(0));
        Assertions.assertEquals(3, d3.getByte(0));
        d1.release(); d2.release(); d3.release();

        f1.release(); f2.release(); f3.release();
        ch.finishAndReleaseAll();
    }

    @Test
    void nonGatedStream_runsConcurrentlyWithGatedStream() {
        var commit = new CompletableFuture<>();
        var gateOnPost = new PerStreamGateHandler(
                h -> "POST".contentEquals(h.method()),
                (sid, h) -> commit);
        var ch = new EmbeddedChannel(gateOnPost);
        var ctx = ch.pipeline().firstContext();

        gateOnPost.onHeadersForStream(ctx, 1, headersFor("POST"));
        gateOnPost.onHeadersForStream(ctx, 3, headersFor("GET"));

        var postFrame = Unpooled.wrappedBuffer(new byte[]{0xa});
        var getFrame = Unpooled.wrappedBuffer(new byte[]{0xb});
        gateOnPost.routeFrameToGate(ctx, 1, postFrame);
        gateOnPost.routeFrameToGate(ctx, 3, getFrame);

        // Only the GET frame (stream 3) is forwarded immediately.
        var first = (ByteBuf) ch.readInbound();
        Assertions.assertNotNull(first);
        Assertions.assertEquals(0xb, first.getByte(0));
        Assertions.assertNull(ch.readInbound(), "POST frame must remain queued until commit");
        first.release();

        commit.complete(null);
        ch.runPendingTasks();
        var second = (ByteBuf) ch.readInbound();
        Assertions.assertNotNull(second);
        Assertions.assertEquals(0xa, second.getByte(0));
        second.release();
        ch.finishAndReleaseAll();
    }

    @Test
    void closeStream_releasesQueuedBuffers() {
        var commit = new CompletableFuture<>();
        var gate = new PerStreamGateHandler(h -> true, (sid, h) -> commit);
        var ch = new EmbeddedChannel(gate);
        var ctx = ch.pipeline().firstContext();

        gate.onHeadersForStream(ctx, 5, headersFor("POST"));
        var f1 = Unpooled.wrappedBuffer(new byte[]{1, 2, 3});
        gate.routeFrameToGate(ctx, 5, f1);
        gate.closeStream(5);

        // Now commit fires; nothing should drain because the stream was closed.
        commit.complete(null);
        ch.runPendingTasks();
        Assertions.assertNull(ch.readInbound());

        f1.release();
        ch.finishAndReleaseAll();
    }

    @Test
    void commitFutureFailure_stillDrains() {
        var commit = new CompletableFuture<>();
        var counter = new AtomicInteger();
        var gate = new PerStreamGateHandler(h -> true, (sid, h) -> {
            counter.incrementAndGet();
            return commit;
        });
        var ch = new EmbeddedChannel(gate);
        var ctx = ch.pipeline().firstContext();

        gate.onHeadersForStream(ctx, 1, headersFor("POST"));
        var f1 = Unpooled.wrappedBuffer(new byte[]{0xc});
        gate.routeFrameToGate(ctx, 1, f1);

        commit.completeExceptionally(new RuntimeException("offload failed"));
        ch.runPendingTasks();

        var drained = (ByteBuf) ch.readInbound();
        Assertions.assertNotNull(drained, "even on offload failure the queue must drain (best-effort forwarding)");
        Assertions.assertEquals(0xc, drained.getByte(0));
        drained.release();
        f1.release();
        ch.finishAndReleaseAll();
    }

    private static Http2Headers headersFor(String method) {
        return new DefaultHttp2Headers().method(method).path("/_search").scheme("https").authority("h");
    }
}
