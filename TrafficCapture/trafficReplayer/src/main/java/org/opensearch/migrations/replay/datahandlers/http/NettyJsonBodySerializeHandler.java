package org.opensearch.migrations.replay.datahandlers.http;

import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.util.concurrent.EventExecutorGroup;
import org.opensearch.migrations.replay.datahandlers.JsonEmitter;
import org.opensearch.migrations.replay.datahandlers.PayloadFaultMap;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

public class NettyJsonBodySerializeHandler extends ChannelInboundHandlerAdapter {
    public static final int NUM_BYTES_TO_ACCUMULATE_BEFORE_FIRING = 1024;
    final JsonEmitter jsonEmitter;

    public NettyJsonBodySerializeHandler() {
        this.jsonEmitter = new JsonEmitter();
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        if (msg instanceof HttpJsonMessageWithFaultablePayload) {
            var jsonMessage = (HttpJsonMessageWithFaultablePayload) msg;
            var payload = jsonMessage.payload();
            jsonMessage.setPayload(null);
            ctx.fireChannelRead(msg);
            if (payload != null) {
                serializePayload(ctx, payload);
            }
        } else {
            super.channelRead(ctx, msg);
        }
    }

    private void serializePayload(ChannelHandlerContext ctx, PayloadFaultMap payload) throws IOException {
        var pac = jsonEmitter.getChunkAndContinuations(payload, NUM_BYTES_TO_ACCUMULATE_BEFORE_FIRING);
        while (true) {
            ctx.fireChannelRead(pac.partialSerializedContents);
            pac.partialSerializedContents.release();
            if (pac.nextSupplier == null) {
                break;
            }
            pac = pac.nextSupplier.get();
        }
    }
}
