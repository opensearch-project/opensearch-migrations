package org.opensearch.migrations.replay.netty;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import org.opensearch.migrations.coreutils.MetricsAttributeKey;
import org.opensearch.migrations.coreutils.MetricsEvent;
import org.opensearch.migrations.coreutils.MetricsLogger;
import org.opensearch.migrations.replay.AggregatedRawResponse;

public class BacksideSnifferHandler extends ChannelInboundHandlerAdapter {

    private final AggregatedRawResponse.Builder aggregatedRawResponseBuilder;
    private static final MetricsLogger metricsLogger = new MetricsLogger("BacksideSnifferHandler");

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
        var origReaderIdx = bb.markReaderIndex();
        byte[] output = new byte[bb.readableBytes()];
        bb.readBytes(output);
        aggregatedRawResponseBuilder.addResponsePacket(output);
        bb.resetReaderIndex();
        ctx.fireChannelRead(msg);
        metricsLogger.atSuccess(MetricsEvent.RECEIVED_RESPONSE_COMPONENT)
                .setAttribute(MetricsAttributeKey.CHANNEL_ID, ctx.channel().id().asLongText())
                .setAttribute(MetricsAttributeKey.SIZE_IN_BYTES, output.length).emit();
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        cause.printStackTrace();
        metricsLogger.atError(MetricsEvent.RECEIVING_RESPONSE_COMPONENT_FAILED, cause)
                .setAttribute(MetricsAttributeKey.CHANNEL_ID, ctx.channel().id().asLongText()).emit();
    }
}