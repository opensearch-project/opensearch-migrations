package org.opensearch.migrations.replay.datahandlers.http;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import org.opensearch.migrations.replay.datahandlers.JsonEmitter;
import org.opensearch.migrations.transform.JsonKeysForHttpMessage;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.http.DefaultHttpContent;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.util.ReferenceCountUtil;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class NettyJsonBodySerializeHandler extends ChannelInboundHandlerAdapter {
    public static final int NUM_BYTES_TO_ACCUMULATE_BEFORE_FIRING = 1024;

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        if (!(msg instanceof HttpJsonRequestWithFaultingPayload)) {
            super.channelRead(ctx, msg);
        } else {
            // Copy the payload before clearing it from the message
            var jsonMessage = (HttpJsonRequestWithFaultingPayload) msg;
            var payload = jsonMessage.payload();
            jsonMessage.setPayloadFaultMap(null);

            var messageWithoutPayload = jsonMessage;
            ctx.fireChannelRead(messageWithoutPayload);

            parsePayloadAndAttachToChannel(ctx, payload);
            
            ctx.fireChannelRead(LastHttpContent.EMPTY_LAST_CONTENT);
        }
    }

    @SuppressWarnings("unchecked")
    private void parsePayloadAndAttachToChannel(ChannelHandlerContext ctx, Map<String, Object> payload) throws Exception {
        if (payload.containsKey(JsonKeysForHttpMessage.INLINED_JSON_BODY_DOCUMENT_KEY)) {
            serializePayload(ctx, payload.get(JsonKeysForHttpMessage.INLINED_JSON_BODY_DOCUMENT_KEY));
        } else if (payload.containsKey(JsonKeysForHttpMessage.INLINED_NDJSON_BODIES_DOCUMENT_KEY)) {
            var shouldAddLastNewline = !payload.containsKey(JsonKeysForHttpMessage.INLINED_BINARY_BODY_DOCUMENT_KEY) &&
                !payload.containsKey(JsonKeysForHttpMessage.INLINED_TEXT_BODY_DOCUMENT_KEY);
            serializePayloadList(ctx,
                (List<Object>) payload.get(JsonKeysForHttpMessage.INLINED_NDJSON_BODIES_DOCUMENT_KEY),
                shouldAddLastNewline);
        }
        // Also process inline body in binary or test form
        if (payload.containsKey(JsonKeysForHttpMessage.INLINED_BINARY_BODY_DOCUMENT_KEY)) {
            var rawBody = (ByteBuf) payload.get(JsonKeysForHttpMessage.INLINED_BINARY_BODY_DOCUMENT_KEY);
            fireHttpContentOrRelease(ctx, rawBody);
        } else if (payload.containsKey(JsonKeysForHttpMessage.INLINED_TEXT_BODY_DOCUMENT_KEY)) {
            var bodyString = (String) payload.get(JsonKeysForHttpMessage.INLINED_TEXT_BODY_DOCUMENT_KEY);
            ByteBuf body = ctx.alloc().buffer();
            body.writeCharSequence(bodyString, StandardCharsets.UTF_8);
            fireHttpContentOrRelease(ctx, body);
        }
    }

    private void fireHttpContentOrRelease(ChannelHandlerContext ctx, ByteBuf buf) {
        if (buf.readableBytes() > 0) {
            ctx.fireChannelRead(new DefaultHttpContent(buf));
        } else {
            ReferenceCountUtil.release(buf);
        }
    }

    private static final ByteBuf NEWLINE = Unpooled.unreleasableBuffer(Unpooled.wrappedBuffer(new byte[]{'\n'}));

    private void serializePayloadList(ChannelHandlerContext ctx, List<Object> payloadList, boolean addLastNewline)
        throws IOException
    {
        var it = payloadList.iterator();
        while (it.hasNext()) {
            var payload = it.next();
            try (var jsonEmitter = new JsonEmitter(ctx.alloc())) {
                var pac = jsonEmitter.getChunkAndContinuations(payload, NUM_BYTES_TO_ACCUMULATE_BEFORE_FIRING);
                while (true) {
                    ctx.fireChannelRead(new DefaultHttpContent(pac.partialSerializedContents));
                    if (pac.nextSupplier == null) {
                        break;
                    }
                    pac = pac.nextSupplier.get();
                }
                if (addLastNewline || it.hasNext()) {
                    ctx.fireChannelRead(new DefaultHttpContent(NEWLINE.retainedDuplicate()));
                }
            }
        }
    }

    private void serializePayload(ChannelHandlerContext ctx, Object payload) throws IOException{
        try (var jsonEmitter = new JsonEmitter(ctx.alloc())) {
            var pac = jsonEmitter.getChunkAndContinuations(payload, NUM_BYTES_TO_ACCUMULATE_BEFORE_FIRING);
            while (true) {
                ctx.fireChannelRead(new DefaultHttpContent(pac.partialSerializedContents));
                if (pac.nextSupplier == null) {
                    break;
                }
                pac = pac.nextSupplier.get();
            }
        }
    }
}
