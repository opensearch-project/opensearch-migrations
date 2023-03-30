package org.opensearch.migrations.replay.netty;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import org.opensearch.migrations.replay.IPacketToHttpHandler;

import java.io.ByteArrayOutputStream;
import java.io.ObjectOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

public class BacksideHandler extends ChannelInboundHandlerAdapter {

    private final Channel writeBackChannel;

    public BacksideHandler(Channel writeBackChannel) {
        this.writeBackChannel = writeBackChannel;
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) {
        ctx.read();
    }

    @Override
    public void channelReadComplete(ChannelHandlerContext ctx) throws Exception {
        try (var baos = new ByteArrayOutputStream()) {
            try (var oos = new ObjectOutputStream(baos)) {
                var summary = new IPacketToHttpHandler.IResponseSummary(15, Duration.ofMillis(10));
                oos.writeObject(summary);
                oos.flush();
                var bb = Unpooled.wrappedBuffer(baos.toByteArray());
                writeBackChannel.writeAndFlush(bb)
                        .addListener((ChannelFutureListener)future -> {
                            if (!future.isSuccess()) {
                                System.err.println("Failed writeback: "+future.cause());
                                future.channel().close();
                            }
                });
                System.err.println("Wrote data to writeback channel");
            }
        }
        super.channelReadComplete(ctx);
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        System.err.println("inactive channel - closing");
        FrontsideHandler.closeAndFlush(writeBackChannel);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        cause.printStackTrace();
        FrontsideHandler.closeAndFlush(ctx.channel());
    }
}