package org.opensearch.migrations.bulkload.netty;

import java.util.function.IntConsumer;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.http.HttpContent;
import lombok.AllArgsConstructor;

/**
 * TODO - this is a copy from the replayer project.  Both copies should be folded into a shared package in
 * common/nettyUtils.
 */
@AllArgsConstructor
public class ReadMeteringHandler extends ChannelInboundHandlerAdapter {
    private final IntConsumer sizeConsumer;

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        if (msg instanceof ByteBuf) {
            sizeConsumer.accept(((ByteBuf) msg).readableBytes());
        } else if (msg instanceof HttpContent) {
            sizeConsumer.accept(((HttpContent) msg).content().readableBytes());
        }
        super.channelRead(ctx, msg);
    }
}
