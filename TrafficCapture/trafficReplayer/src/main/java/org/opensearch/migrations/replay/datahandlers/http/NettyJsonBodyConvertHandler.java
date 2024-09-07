package org.opensearch.migrations.replay.datahandlers.http;

import org.opensearch.migrations.replay.datahandlers.PayloadAccessFaultingMap;
import org.opensearch.migrations.transform.IJsonTransformer;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;

public class NettyJsonBodyConvertHandler extends ChannelInboundHandlerAdapter {
    private final IJsonTransformer transformer;

    public NettyJsonBodyConvertHandler(IJsonTransformer transformer) {
        this.transformer = transformer;
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        if (msg instanceof HttpJsonMessageWithFaultingPayload) {
            var httpMsg = (HttpJsonMessageWithFaultingPayload) msg;
            if (httpMsg.payload() instanceof PayloadAccessFaultingMap) {
                // no reason for transforms to fault if there wasn't a body in the message
                ((PayloadAccessFaultingMap) httpMsg.payload()).setDisableThrowingPayloadNotLoaded(true);
            }
            var output = transformer.transformJson(httpMsg);
            var newHttpJson = new HttpJsonMessageWithFaultingPayload(output);
            ctx.fireChannelRead(newHttpJson);
        } else {
            super.channelRead(ctx, msg);
        }
    }
}
