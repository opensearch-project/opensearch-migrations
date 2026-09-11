package org.opensearch.migrations.trafficcapture.proxyserver.netty;

import java.net.URI;
import java.nio.channels.ClosedChannelException;
import java.time.Duration;

import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.util.ReferenceCountUtil;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class ProxyForwardingHandlersTest {

    @Test
    void frontsideForwardingExceptionClosesTheClientChannel() {
        var clientChannel = new EmbeddedChannel(
            new ThrowingInboundHandler(),
            new FrontsideHandler(null)
        );

        Assertions.assertThrows(
            ClosedChannelException.class,
            () -> clientChannel.writeInbound(Unpooled.wrappedBuffer(new byte[] { 1 }))
        );
        clientChannel.runPendingTasks();

        Assertions.assertFalse(clientChannel.isOpen());
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
    void targetToClientWriteFailureClosesBothChannels() {
        var clientChannel = new EmbeddedChannel(new FailingOutboundWriteHandler());
        var targetChannel = new EmbeddedChannel(new BacksideHandler(clientChannel));

        targetChannel.writeInbound(Unpooled.wrappedBuffer(new byte[] { 1, 2, 3 }));
        clientChannel.runPendingTasks();
        targetChannel.runPendingTasks();

        Assertions.assertFalse(clientChannel.isOpen());
        Assertions.assertFalse(targetChannel.isOpen());
        clientChannel.finishAndReleaseAll();
        targetChannel.finishAndReleaseAll();
    }

    @Test
    void targetExceptionExplicitlyClosesBothChannels() {
        var clientChannel = new EmbeddedChannel();
        var targetChannel = new EmbeddedChannel(new BacksideHandler(clientChannel));

        targetChannel.pipeline().fireExceptionCaught(new IllegalStateException("target failed"));
        clientChannel.runPendingTasks();
        targetChannel.runPendingTasks();

        Assertions.assertFalse(clientChannel.isOpen());
        Assertions.assertFalse(targetChannel.isOpen());
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
    void inactiveTargetClosesTheSourceInsteadOfDroppingTrafficIndefinitely() {
        var targetChannel = new OpenInactiveEmbeddedChannel();
        var clientChannel = new EmbeddedChannel(
            new FrontsideHandler(new FixedConnectionPool(targetChannel))
        );

        clientChannel.writeInbound(Unpooled.wrappedBuffer(new byte[] { 1 }));
        clientChannel.runPendingTasks();
        targetChannel.runPendingTasks();

        Assertions.assertFalse(clientChannel.isOpen());
        clientChannel.finishAndReleaseAll();
        targetChannel.finishAndReleaseAll();
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
