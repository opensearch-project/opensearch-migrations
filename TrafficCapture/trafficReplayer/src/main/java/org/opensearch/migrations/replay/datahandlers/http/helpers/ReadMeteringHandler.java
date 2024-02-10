package org.opensearch.migrations.replay.datahandlers.http.helpers;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.http.HttpContent;
import lombok.AllArgsConstructor;

import java.util.function.IntConsumer;

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
