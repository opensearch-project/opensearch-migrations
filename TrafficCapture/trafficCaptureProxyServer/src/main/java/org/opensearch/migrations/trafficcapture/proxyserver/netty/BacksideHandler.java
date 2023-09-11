package org.opensearch.migrations.trafficcapture.proxyserver.netty;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import lombok.extern.slf4j.Slf4j;
import org.opensearch.migrations.coreutils.MetricsLogger;

@Slf4j
public class BacksideHandler extends ChannelInboundHandlerAdapter {

    private final Channel writeBackChannel;
    private static final MetricsLogger metricsLogger = new MetricsLogger("BacksideHandler");

    public BacksideHandler(Channel writeBackChannel) {
        this.writeBackChannel = writeBackChannel;
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) {
        ctx.read();
        metricsLogger.atSuccess()
                .addKeyValue("channelId", ctx.channel().id().asLongText())
                .setMessage("BacksideHandler channel is active.").log();
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        metricsLogger.atSuccess()
                .addKeyValue("channelId", ctx.channel().id().asLongText())
                .setMessage("Component of response seen").log();
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
        metricsLogger.atSuccess()
                .addKeyValue("channelId", ctx.channel().id().asLongText())
                .setMessage("BacksideHandler channel is closed.").log();
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        cause.printStackTrace();
        String channelId = ctx.channel().id().asLongText();
        FrontsideHandler.closeAndFlush(ctx.channel());
        metricsLogger.atError(cause)
                .addKeyValue("channelId", channelId)
                .setMessage("Exception caught by BacksideChannel").log();
    }
}