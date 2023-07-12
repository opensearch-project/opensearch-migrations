package org.opensearch.migrations.replay.datahandlers.http;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.LastHttpContent;
import lombok.SneakyThrows;
import org.opensearch.migrations.replay.datahandlers.JsonAccumulator;
import org.opensearch.migrations.replay.datahandlers.PayloadAccessFaultingMap;

/**
 * This accumulates HttpContent messages through a JsonAccumulator and eventually fires off a
 * fully parsed json object as parsed by the JsonAccumulator (not by a signal that end of content
 * has been reached).
 *
 * This handler currently has undefined behavior if multiple json objects are within the stream of
 * HttpContent messages.  This will also NOT fire a
 */
public class NettyJsonBodyAccumulateHandler extends ChannelInboundHandlerAdapter {

    public static class IncompleteJsonBodyException extends NoContentException {}

    JsonAccumulator jsonAccumulator;
    HttpJsonMessageWithFaultingPayload capturedHttpJsonMessage;

    @SneakyThrows
    public NettyJsonBodyAccumulateHandler() {
        this.jsonAccumulator = new JsonAccumulator();
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        if (msg instanceof HttpJsonMessageWithFaultingPayload) {
            capturedHttpJsonMessage = (HttpJsonMessageWithFaultingPayload) msg;
        } else if (msg instanceof HttpContent) {
            var jsonObject = jsonAccumulator.consumeByteBuffer(((HttpContent)msg).content().nioBuffer());
            if (jsonObject != null) {
                capturedHttpJsonMessage.payload().put(PayloadAccessFaultingMap.INLINED_JSON_BODY_DOCUMENT_KEY, jsonObject);
                ctx.fireChannelRead(capturedHttpJsonMessage);
            } else if (msg instanceof LastHttpContent) {
                throw new IncompleteJsonBodyException();
            }
        } else {
            super.channelRead(ctx, msg);
        }
    }
}
