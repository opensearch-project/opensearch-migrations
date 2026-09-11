package org.opensearch.migrations.trafficcapture.proxyserver.netty;

import java.nio.channels.ClosedChannelException;

import io.netty.buffer.Unpooled;
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

    private static class FailingOutboundWriteHandler extends ChannelOutboundHandlerAdapter {
        @Override
        public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
            ReferenceCountUtil.release(msg);
            promise.setFailure(new IllegalStateException("client write failed"));
        }
    }
}
