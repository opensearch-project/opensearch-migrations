package org.opensearch.migrations.replay.datahandlers.http.helpers;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.http.LastHttpContent;

public class LastHttpContentListener extends ChannelInboundHandlerAdapter {

    private final Runnable onLastContentReceived;

    public LastHttpContentListener(Runnable onLastContentReceived) {
        this.onLastContentReceived = onLastContentReceived;
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        if (msg instanceof LastHttpContent) {
            onLastContentReceived.run();
        }
        super.channelRead(ctx, msg);
    }
}
