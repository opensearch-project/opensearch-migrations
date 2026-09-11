package org.opensearch.migrations.trafficcapture.proxyserver.netty;

import java.io.IOException;
import java.net.URI;
import java.nio.channels.ClosedChannelException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.opensearch.migrations.tracing.IContextTracker;
import org.opensearch.migrations.trafficcapture.CodedOutputStreamAndByteBufferWrapper;
import org.opensearch.migrations.trafficcapture.CodedOutputStreamHolder;
import org.opensearch.migrations.trafficcapture.OrderedStreamLifecyleManager;
import org.opensearch.migrations.trafficcapture.StreamChannelConnectionCaptureSerializer;
import org.opensearch.migrations.trafficcapture.netty.CaptureFailurePolicy;
import org.opensearch.migrations.trafficcapture.netty.CaptureProcessState;
import org.opensearch.migrations.trafficcapture.netty.ConditionallyReliableLoggingHttpHandler;
import org.opensearch.migrations.trafficcapture.netty.RequestCapturePredicate;
import org.opensearch.migrations.trafficcapture.protos.TrafficRecord;
import org.opensearch.migrations.trafficcapture.proxyserver.RootCaptureContext;

import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.util.ReferenceCountUtil;
import io.opentelemetry.api.OpenTelemetry;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class ProxyForwardingHandlersTest {

    @Test
    void frontsideForwardingExceptionClosesTheClientChannelWithTerminalCapture() throws Exception {
        var capture = new TerminalTrackingStreamManager();
        var clientChannel = new EmbeddedChannel(
            captureHandler(capture),
            new ThrowingInboundHandler(),
            new FrontsideHandler(null)
        );

        Assertions.assertThrows(
            ClosedChannelException.class,
            () -> clientChannel.writeInbound(Unpooled.wrappedBuffer(new byte[] { 1 }))
        );
        clientChannel.runPendingTasks();

        Assertions.assertFalse(clientChannel.isOpen());
        assertTerminalCapture(capture);
        clientChannel.finishAndReleaseAll();
    }

    private static class ThrowingInboundHandler extends ChannelInboundHandlerAdapter {
        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) {
            ReferenceCountUtil.release(msg);
            throw new IllegalStateException("forwarding failed");
        }
    }

    @Test
    void targetToClientWriteFailureClosesBothChannelsWithTerminalCapture() throws Exception {
        var capture = new TerminalTrackingStreamManager();
        var clientChannel = new EmbeddedChannel(
            captureHandler(capture),
            new FailingOutboundWriteHandler()
        );
        var targetChannel = new EmbeddedChannel(new BacksideHandler(clientChannel));

        targetChannel.writeInbound(Unpooled.wrappedBuffer(new byte[] { 1, 2, 3 }));
        clientChannel.runPendingTasks();
        targetChannel.runPendingTasks();

        Assertions.assertFalse(clientChannel.isOpen());
        Assertions.assertFalse(targetChannel.isOpen());
        assertTerminalCapture(capture);
        clientChannel.finishAndReleaseAll();
        targetChannel.finishAndReleaseAll();
    }

    @Test
    void targetExceptionExplicitlyClosesBothChannelsWithTerminalCapture() throws Exception {
        var capture = new TerminalTrackingStreamManager();
        var clientChannel = new EmbeddedChannel(captureHandler(capture));
        var targetChannel = new EmbeddedChannel(new BacksideHandler(clientChannel));

        targetChannel.pipeline().fireExceptionCaught(new IllegalStateException("target failed"));
        clientChannel.runPendingTasks();
        targetChannel.runPendingTasks();

        Assertions.assertFalse(clientChannel.isOpen());
        Assertions.assertFalse(targetChannel.isOpen());
        assertTerminalCapture(capture);
        clientChannel.finishAndReleaseAll();
        targetChannel.finishAndReleaseAll();
    }

    @Test
    void openInactiveChannelIsStillClosed() {
        var channel = new OpenInactiveEmbeddedChannel();

        Assertions.assertTrue(channel.isOpen());
        Assertions.assertFalse(channel.isActive());

        FrontsideHandler.closeAndFlush(channel);
        channel.runPendingTasks();

        Assertions.assertFalse(channel.isOpen());
        channel.finishAndReleaseAll();
    }

    @Test
    void inactiveTargetClosesTheSourceWithTerminalCapture() throws Exception {
        var targetChannel = new OpenInactiveEmbeddedChannel();
        var capture = new TerminalTrackingStreamManager();
        var clientChannel = new EmbeddedChannel(
            captureHandler(capture),
            new FrontsideHandler(new FixedConnectionPool(targetChannel))
        );

        clientChannel.writeInbound(Unpooled.wrappedBuffer(new byte[] { 1 }));
        clientChannel.runPendingTasks();
        targetChannel.runPendingTasks();

        Assertions.assertFalse(clientChannel.isOpen());
        assertTerminalCapture(capture);
        clientChannel.finishAndReleaseAll();
        targetChannel.finishAndReleaseAll();
    }

    private static ConditionallyReliableLoggingHttpHandler<Void> captureHandler(
        TerminalTrackingStreamManager streamManager
    ) throws IOException {
        var rootContext = new RootCaptureContext(
            OpenTelemetry.noop(),
            IContextTracker.DO_NOTHING_TRACKER
        );
        return new ConditionallyReliableLoggingHttpHandler<>(
            rootContext,
            "writer",
            "connection",
            context -> new StreamChannelConnectionCaptureSerializer<>(
                "writer",
                "connection",
                streamManager
            ),
            new RequestCapturePredicate(),
            request -> false,
            new CaptureProcessState(CaptureFailurePolicy.FAIL_CLOSED)
        );
    }

    private static void assertTerminalCapture(TerminalTrackingStreamManager capture) {
        Assertions.assertEquals(1, capture.records.size());
        Assertions.assertEquals(
            1,
            capture.records.stream()
                .flatMap(record -> record.getObservationsList().stream())
                .filter(observation -> observation.hasClose())
                .count()
        );
    }

    private static class TerminalTrackingStreamManager
        extends OrderedStreamLifecyleManager<Void> {
        private final List<TrafficRecord> records = new ArrayList<>();

        @Override
        public CodedOutputStreamAndByteBufferWrapper createStream() {
            return new CodedOutputStreamAndByteBufferWrapper(1024 * 1024);
        }

        @Override
        protected java.util.concurrent.CompletableFuture<Void> kickoffCloseStream(
            CodedOutputStreamHolder outputStreamHolder,
            int index
        ) {
            try {
                var stream = (CodedOutputStreamAndByteBufferWrapper) outputStreamHolder;
                stream.getOutputStream().flush();
                records.add(TrafficRecord.parseFrom(stream.getByteBuffer().flip()));
                return java.util.concurrent.CompletableFuture.completedFuture(null);
            } catch (IOException e) {
                return java.util.concurrent.CompletableFuture.failedFuture(e);
            }
        }
    }

    private static class FixedConnectionPool extends BacksideConnectionPool {
        private final EmbeddedChannel targetChannel;

        private FixedConnectionPool(EmbeddedChannel targetChannel) {
            super(URI.create("http://127.0.0.1:1"), null, 0, Duration.ZERO);
            this.targetChannel = targetChannel;
        }

        @Override
        public ChannelFuture getOutboundConnectionFuture(io.netty.channel.EventLoop eventLoop) {
            return targetChannel.newSucceededFuture();
        }
    }

    private static class OpenInactiveEmbeddedChannel extends EmbeddedChannel {
        @Override
        public boolean isActive() {
            return false;
        }
    }

    private static class FailingOutboundWriteHandler extends ChannelOutboundHandlerAdapter {
        @Override
        public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
            ReferenceCountUtil.release(msg);
            promise.setFailure(new IllegalStateException("client write failed"));
        }
    }
}
