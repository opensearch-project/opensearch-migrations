package org.opensearch.migrations.replay.netty;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import org.opensearch.migrations.coreutils.MetricsAttributeKey;
import org.opensearch.migrations.coreutils.MetricsEvent;
import lombok.extern.slf4j.Slf4j;
import org.opensearch.migrations.coreutils.MetricsLogger;
import org.opensearch.migrations.replay.AggregatedRawResponse;

@Slf4j
public class BacksideSnifferHandler extends ChannelInboundHandlerAdapter {

    private final AggregatedRawResponse.Builder aggregatedRawResponseBuilder;
    private Runnable firstByteReceivedCallback;
    private static final MetricsLogger metricsLogger = new MetricsLogger("BacksideSnifferHandler");

    public BacksideSnifferHandler(AggregatedRawResponse.Builder aggregatedRawResponseBuilder,
                                  Runnable firstByteReceivedCallback) {
        this.aggregatedRawResponseBuilder = aggregatedRawResponseBuilder;
        this.firstByteReceivedCallback = firstByteReceivedCallback;
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) {
        ctx.read();
        ctx.fireChannelActive();
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        var bb = (ByteBuf) msg;
        if (firstByteReceivedCallback != null && bb.readableBytes() > 0) {
            firstByteReceivedCallback.run();
            firstByteReceivedCallback = null;
        }
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
        log.atWarn().setCause(cause).setMessage("Caught exception").log();
        metricsLogger.atError(MetricsEvent.RECEIVING_RESPONSE_COMPONENT_FAILED, cause)
                .setAttribute(MetricsAttributeKey.CHANNEL_ID, ctx.channel().id().asLongText()).emit();
    }
}