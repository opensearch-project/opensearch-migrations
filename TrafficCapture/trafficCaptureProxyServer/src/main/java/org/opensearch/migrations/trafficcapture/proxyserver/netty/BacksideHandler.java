package org.opensearch.migrations.trafficcapture.proxyserver.netty;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import lombok.extern.slf4j.Slf4j;
import org.opensearch.migrations.coreutils.MetricsAttributeKey;
import org.opensearch.migrations.coreutils.MetricsEvent;
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
        metricsLogger.atSuccess(MetricsEvent.BACKSIDE_HANDLER_CHANNEL_ACTIVE)
                .setAttribute(MetricsAttributeKey.CHANNEL_ID, ctx.channel().id().asLongText()).emit();
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        metricsLogger.atSuccess(MetricsEvent.RECEIVED_RESPONSE_COMPONENT)
                .setAttribute(MetricsAttributeKey.CHANNEL_ID, ctx.channel().id().asLongText()).emit();
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
        metricsLogger.atSuccess(MetricsEvent.BACKSIDE_HANDLER_CHANNEL_CLOSED)
                .setAttribute(MetricsAttributeKey.CHANNEL_ID, ctx.channel().id().asLongText()).emit();
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        log.atError().setCause(cause).setMessage("Caught error").log();
        String channelId = ctx.channel().id().asLongText();
        FrontsideHandler.closeAndFlush(ctx.channel());
        metricsLogger.atError(MetricsEvent.BACKSIDE_HANDLER_EXCEPTION, cause)
                .setAttribute(MetricsAttributeKey.CHANNEL_ID, channelId).emit();
    }
}