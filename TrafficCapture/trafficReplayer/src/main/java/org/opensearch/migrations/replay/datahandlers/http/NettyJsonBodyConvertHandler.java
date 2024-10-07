package org.opensearch.migrations.replay.datahandlers.http;

import org.opensearch.migrations.replay.datahandlers.PayloadAccessFaultingMap;
import org.opensearch.migrations.replay.datahandlers.PayloadNotLoadedException;
import org.opensearch.migrations.transform.IJsonTransformer;
import org.opensearch.migrations.transform.JsonKeysForHttpMessage;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
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
        if (msg instanceof HttpJsonRequestWithFaultingPayload) {
            var httpMsg = (HttpJsonRequestWithFaultingPayload) msg;
            if (httpMsg.payload() instanceof PayloadAccessFaultingMap) {
                // no reason for transforms to fault if there wasn't a body in the message
                ((PayloadAccessFaultingMap) httpMsg.payload()).setDisableThrowingPayloadNotLoaded(true);
            }
            HttpJsonRequestWithFaultingPayload newHttpJson;
            try {
                newHttpJson = transform(transformer, httpMsg);
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

    public static HttpJsonRequestWithFaultingPayload transform(
        IJsonTransformer transformer,
        HttpJsonRequestWithFaultingPayload httpJsonMessage
    ) {
        var originalHttpJsonMessage = httpJsonMessage;

        ByteBuf innerPayloadByteBuf = null;
        ByteBuf protectedByteBuf = null;
        try {
            innerPayloadByteBuf = (ByteBuf) httpJsonMessage.payload().get(JsonKeysForHttpMessage.INLINED_BINARY_BODY_DOCUMENT_KEY);
            if (innerPayloadByteBuf != null) {
                protectedByteBuf = Unpooled.unreleasableBuffer(innerPayloadByteBuf);
                httpJsonMessage.payload().put(JsonKeysForHttpMessage.INLINED_BINARY_BODY_DOCUMENT_KEY,
                    protectedByteBuf);
            }
        } catch (PayloadNotLoadedException e) {
            // Skip byteBuf protection if payload not loaded
        }

        var returnedObject = transformer.transformJson(httpJsonMessage);

        if (returnedObject != httpJsonMessage) {
            httpJsonMessage = new HttpJsonRequestWithFaultingPayload(returnedObject);
        }

        if (innerPayloadByteBuf != null) {
            // replace protected byteBuf if was hidden and still there
            var transformedInlinedBinary = httpJsonMessage.payload().get(JsonKeysForHttpMessage.INLINED_BINARY_BODY_DOCUMENT_KEY);
            assert protectedByteBuf != null : "Expected protectedByteBuf to be defined if innerPayloadByteBuf is";
            if (protectedByteBuf.equals(transformedInlinedBinary)) {
                innerPayloadByteBuf.readerIndex(protectedByteBuf.readerIndex());
                innerPayloadByteBuf.writerIndex(protectedByteBuf.writerIndex());
                httpJsonMessage.payload().put(JsonKeysForHttpMessage.INLINED_BINARY_BODY_DOCUMENT_KEY, innerPayloadByteBuf);
            } else {
                innerPayloadByteBuf.release();
                if (transformedInlinedBinary == null) {
                    httpJsonMessage.payload().remove(JsonKeysForHttpMessage.INLINED_BINARY_BODY_DOCUMENT_KEY);
                } else if (!(transformedInlinedBinary instanceof ByteBuf)) {
                    throw new UnsupportedOperationException("Type of " + JsonKeysForHttpMessage.INLINED_BINARY_BODY_DOCUMENT_KEY
                        + " not supported.");
                }
            }
        }

        if (originalHttpJsonMessage != httpJsonMessage) {
            // clear originalHttpJsonMessage for faster garbage collection if not persisted along
            originalHttpJsonMessage.clear();
        }
        return httpJsonMessage;
    }

}
