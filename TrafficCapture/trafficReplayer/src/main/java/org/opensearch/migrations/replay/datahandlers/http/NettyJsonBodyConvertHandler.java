package org.opensearch.migrations.replay.datahandlers.http;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import org.opensearch.migrations.transform.IJsonTransformer;

import java.util.Map;

public class NettyJsonBodyConvertHandler extends ChannelInboundHandlerAdapter {
    private final IJsonTransformer transformer;

    public NettyJsonBodyConvertHandler(IJsonTransformer transformer) {
        this.transformer = transformer;
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        if (msg instanceof HttpJsonMessageWithFaultingPayload) {
            var output = transformer.transformJson(msg);
            var newHttpJson = new HttpJsonMessageWithFaultingPayload(((Map<String,Object>)output));
            ctx.fireChannelRead(newHttpJson);
        } else {
            super.channelRead(ctx, msg);
        }
    }
}
