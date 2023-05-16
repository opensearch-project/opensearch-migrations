package org.opensearch.migrations.replay.datahandlers.http;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import org.opensearch.migrations.transform.JsonTransformer;

import java.util.Map;

public class NettyJsonBodyConvertHandler extends ChannelInboundHandlerAdapter {
    private final JsonTransformer transformer;

    public NettyJsonBodyConvertHandler(JsonTransformer transformer) {
        this.transformer = transformer;
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        if (msg instanceof HttpJsonMessageWithFaultablePayload) {
            var output = transformer.transformJson(msg);
            var newHttpJson = new HttpJsonMessageWithFaultablePayload(((Map<String,Object>)output));
            ctx.fireChannelRead(newHttpJson);
        } else {
            super.channelRead(ctx, msg);
        }
    }
}
