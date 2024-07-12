package org.opensearch.migrations.replay.datahandlers.http;

import org.opensearch.migrations.replay.datahandlers.JsonAccumulator;
import org.opensearch.migrations.replay.tracing.IReplayContexts;
import org.opensearch.migrations.transform.JsonKeysForHttpMessage;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.LastHttpContent;
import lombok.SneakyThrows;

/**
 * This accumulates HttpContent messages through a JsonAccumulator and eventually fires off a
 * fully parsed json object as parsed by the JsonAccumulator (not by a signal that end of content
 * has been reached).
 *
 * This handler currently has undefined behavior if multiple json objects are within the stream of
 * HttpContent messages.  This will also NOT fire a
 */
public class NettyJsonBodyAccumulateHandler extends ChannelInboundHandlerAdapter {

    private final IReplayContexts.IRequestTransformationContext context;

    public static class IncompleteJsonBodyException extends NoContentException {}

    JsonAccumulator jsonAccumulator;
    HttpJsonMessageWithFaultingPayload capturedHttpJsonMessage;

    @SneakyThrows
    public NettyJsonBodyAccumulateHandler(IReplayContexts.IRequestTransformationContext context) {
        this.context = context;
        this.jsonAccumulator = new JsonAccumulator();
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        if (msg instanceof HttpJsonMessageWithFaultingPayload) {
            capturedHttpJsonMessage = (HttpJsonMessageWithFaultingPayload) msg;
        } else if (msg instanceof HttpContent) {
            var jsonObject = jsonAccumulator.consumeByteBuffer(((HttpContent) msg).content().nioBuffer());
            if (jsonObject != null) {
                capturedHttpJsonMessage.payload()
                    .put(JsonKeysForHttpMessage.INLINED_JSON_BODY_DOCUMENT_KEY, jsonObject);
                context.onJsonPayloadParseSucceeded();
                ctx.fireChannelRead(capturedHttpJsonMessage);
            } else if (msg instanceof LastHttpContent) {
                throw new IncompleteJsonBodyException();
            }
        } else {
            super.channelRead(ctx, msg);
        }
    }
}
