package org.opensearch.migrations.replay.datahandlers.http;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.http.HttpContent;
import lombok.SneakyThrows;
import org.opensearch.migrations.replay.datahandlers.JsonAccumulator;
import org.opensearch.migrations.replay.datahandlers.PayloadFaultMap;

public class NettyJsonBodyAccumulateHandler extends ChannelInboundHandlerAdapter {
    JsonAccumulator jsonAccumulator;
    HttpJsonMessageWithFaultablePayload capturedHttpJsonMessage;

    @SneakyThrows
    public NettyJsonBodyAccumulateHandler() {
        this.jsonAccumulator = new JsonAccumulator();
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        if (msg instanceof HttpJsonMessageWithFaultablePayload) {
            capturedHttpJsonMessage = (HttpJsonMessageWithFaultablePayload) msg;
        } else if (msg instanceof HttpContent) {
            var jsonObject = jsonAccumulator.consumeByteBuffer(((HttpContent)msg).content().nioBuffer());
            if (jsonObject != null) {
                capturedHttpJsonMessage.payload().put(PayloadFaultMap.INLINED_JSON_BODY_DOCUMENT_KEY, jsonObject);
                ctx.fireChannelRead(capturedHttpJsonMessage);
            }
        } else {
            super.channelRead(ctx, msg);
        }
    }
}
