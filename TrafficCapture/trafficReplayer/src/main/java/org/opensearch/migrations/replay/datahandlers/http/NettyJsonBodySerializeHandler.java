package org.opensearch.migrations.replay.datahandlers.http;

import java.io.IOException;
import java.util.Map;

import org.opensearch.migrations.replay.datahandlers.JsonEmitter;
import org.opensearch.migrations.transform.JsonKeysForHttpMessage;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.http.DefaultHttpContent;
import io.netty.handler.codec.http.LastHttpContent;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class NettyJsonBodySerializeHandler extends ChannelInboundHandlerAdapter {
    public static final int NUM_BYTES_TO_ACCUMULATE_BEFORE_FIRING = 1024;

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        if (msg instanceof HttpJsonMessageWithFaultingPayload) {
            var jsonMessage = (HttpJsonMessageWithFaultingPayload) msg;
            var payload = jsonMessage.payload();
            jsonMessage.setPayloadFaultMap(null);
            var payloadContents = (Map<String, Object>) payload.get(
                JsonKeysForHttpMessage.INLINED_JSON_BODY_DOCUMENT_KEY
            );
            ctx.fireChannelRead(msg);
            if (payloadContents != null) {
                serializePayload(ctx, payloadContents);
            }
        } else {
            super.channelRead(ctx, msg);
        }
    }

    private void serializePayload(ChannelHandlerContext ctx, Map<String, Object> payload) throws IOException {
        try (var jsonEmitter = new JsonEmitter(ctx.alloc())) {
            var pac = jsonEmitter.getChunkAndContinuations(payload, NUM_BYTES_TO_ACCUMULATE_BEFORE_FIRING);
            while (true) {
                ctx.fireChannelRead(new DefaultHttpContent(pac.partialSerializedContents));
                if (pac.nextSupplier == null) {
                    ctx.fireChannelRead(LastHttpContent.EMPTY_LAST_CONTENT);
                    break;
                }
                pac = pac.nextSupplier.get();
            }
        }
    }
}
