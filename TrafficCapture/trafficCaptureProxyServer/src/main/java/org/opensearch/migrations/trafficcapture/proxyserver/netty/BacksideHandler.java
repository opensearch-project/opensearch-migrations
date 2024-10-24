package org.opensearch.migrations.trafficcapture.proxyserver.netty;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import lombok.extern.slf4j.Slf4j;

@Slf4j
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
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        writeBackChannel.writeAndFlush(msg);
    }

    @Override
    public void channelReadComplete(ChannelHandlerContext ctx) throws Exception {
        ctx.read();
        super.channelReadComplete(ctx);
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        log.debug("inactive channel - closing (" + ctx.channel() + ")");
        FrontsideHandler.closeAndFlush(writeBackChannel);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        log.atError().setCause(cause).setMessage("Caught error for channel: {}")
            .addArgument(() -> ctx.channel().id().asLongText())
            .log();
        FrontsideHandler.closeAndFlush(ctx.channel());
    }
}
