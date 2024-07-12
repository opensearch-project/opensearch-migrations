package org.opensearch.migrations.replay.netty;

import org.opensearch.migrations.replay.AggregatedRawResponse;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class BacksideSnifferHandler extends ChannelInboundHandlerAdapter {

    private final AggregatedRawResponse.Builder aggregatedRawResponseBuilder;

    public BacksideSnifferHandler(AggregatedRawResponse.Builder aggregatedRawResponseBuilder) {
        this.aggregatedRawResponseBuilder = aggregatedRawResponseBuilder;
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) {
        ctx.read();
        ctx.fireChannelActive();
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        var bb = (ByteBuf) msg;
        byte[] output = new byte[bb.readableBytes()];
        bb.duplicate().readBytes(output);
        aggregatedRawResponseBuilder.addResponsePacket(output);
        ctx.fireChannelRead(msg);
    }
}
