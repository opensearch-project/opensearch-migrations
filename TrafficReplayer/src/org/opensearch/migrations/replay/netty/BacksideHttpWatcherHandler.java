package org.opensearch.migrations.replay.netty;

import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.FullHttpRequest;
import org.opensearch.migrations.replay.AggregatedRawResponse;
import org.opensearch.migrations.replay.IPacketToHttpHandler;

import java.io.ByteArrayOutputStream;
import java.io.ObjectOutputStream;
import java.time.Duration;

public class BacksideHttpWatcherHandler extends SimpleChannelInboundHandler<FullHttpRequest> {

    private final Channel writeBackChannel;
    private final AggregatedRawResponse.Builder aggregatedRawResponseBuilder;

    public BacksideHttpWatcherHandler(Channel writeBackChannel,
                                      AggregatedRawResponse.Builder aggregatedRawResponseBuilder) {
        this.writeBackChannel = writeBackChannel;
        this.aggregatedRawResponseBuilder = aggregatedRawResponseBuilder;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest msg) throws Exception {
        aggregatedRawResponseBuilder.build();
        super.channelReadComplete(ctx);
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        System.err.println("inactive channel - closing");
        FrontsideHandler.closeAndFlush(writeBackChannel);
    }

}
