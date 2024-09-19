package org.opensearch.migrations.replay.datahandlers.http;

import org.opensearch.migrations.replay.datahandlers.PayloadAccessFaultingMap;
import org.opensearch.migrations.transform.IJsonTransformer;
import org.opensearch.migrations.transform.JsonKeysForHttpMessage;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.util.ReferenceCountUtil;
import lombok.extern.slf4j.Slf4j;

@Slf4j
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
            HttpJsonMessageWithFaultingPayload newHttpJson;
            try {
                var output = transformer.transformJson(httpMsg);
                newHttpJson = new HttpJsonMessageWithFaultingPayload(output);
            } catch (Exception e) {
                var remainingBytes = httpMsg.payload().get(JsonKeysForHttpMessage.INLINED_BINARY_BODY_DOCUMENT_KEY);
                ReferenceCountUtil.release(remainingBytes); // release because we're not passing it along for cleanup
                throw new TransformationException(e);
            }
            ctx.fireChannelRead(newHttpJson);
        } else {
            super.channelRead(ctx, msg);
        }
    }
}
