package org.opensearch.migrations.replay.datahandlers.http;

import java.util.ArrayList;
import java.util.List;

import org.opensearch.migrations.replay.datahandlers.JsonAccumulator;
import org.opensearch.migrations.replay.tracing.IReplayContexts;
import org.opensearch.migrations.transform.JsonKeysForHttpMessage;

import io.netty.buffer.CompositeByteBuf;
import io.netty.buffer.Unpooled;
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

    JsonAccumulator jsonAccumulator;
    HttpJsonMessageWithFaultingPayload capturedHttpJsonMessage;
    List<Object> parsedJsonObjects;
    CompositeByteBuf accumulatedBody;

    @SneakyThrows
    public NettyJsonBodyAccumulateHandler(IReplayContexts.IRequestTransformationContext context) {
        this.context = context;
        this.jsonAccumulator = new JsonAccumulator();
        // use 1024 (as opposed to the default of 16) because we really don't ever want the hit of a consolidation.
        // For this buffer to continue to be used, we are far-off the happy-path.
        // Consolidating will likely burn more cycles
        this.accumulatedBody = Unpooled.compositeBuffer(1024);
        this.parsedJsonObjects = new ArrayList<>();
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        if (msg instanceof HttpJsonMessageWithFaultingPayload) {
            capturedHttpJsonMessage = (HttpJsonMessageWithFaultingPayload) msg;
        } else if (msg instanceof HttpContent) {
            var contentBuf = ((HttpContent) msg).content();
            accumulatedBody.addComponent(true, contentBuf.retainedDuplicate());
            var nioBuf = contentBuf.nioBuffer();
            jsonAccumulator.consumeByteBuffer(nioBuf);
            Object nextObj;
            while ((nextObj = jsonAccumulator.getNextTopLevelObject()) != null) {
                parsedJsonObjects.add(nextObj);
            }
            if (msg instanceof LastHttpContent) {
                if (!parsedJsonObjects.isEmpty()) {
                    var payload = capturedHttpJsonMessage.payload();
                    if (parsedJsonObjects.size() > 1) {
                        payload.put(JsonKeysForHttpMessage.INLINED_NDJSON_BODIES_DOCUMENT_KEY, parsedJsonObjects);
                    } else {
                        payload.put(JsonKeysForHttpMessage.INLINED_JSON_BODY_DOCUMENT_KEY, parsedJsonObjects.get(0));
                    }
                    if (!jsonAccumulator.hasPartialValues()) {
                        context.onJsonPayloadParseSucceeded();
                    }
                }
                if (jsonAccumulator.hasPartialValues()) {
                    if (jsonAccumulator.getTotalBytesFullyConsumed() > Integer.MAX_VALUE) {
                        throw new IndexOutOfBoundsException("JSON contents were too large " +
                            jsonAccumulator.getTotalBytesFullyConsumed() + " for a single composite ByteBuf");
                    }
                    // skip the contents that were already parsed and included in the payload as parsed json
                    accumulatedBody.readerIndex((int) jsonAccumulator.getTotalBytesFullyConsumed());
                    // and pass the remaining stream
                    capturedHttpJsonMessage.payload()
                        .put(JsonKeysForHttpMessage.INLINED_BINARY_BODY_DOCUMENT_KEY, accumulatedBody);
                } else {
                    accumulatedBody.release();
                    accumulatedBody = null;
                }
                ctx.fireChannelRead(capturedHttpJsonMessage);
            }
        } else {
            super.channelRead(ctx, msg);
        }
    }
}
