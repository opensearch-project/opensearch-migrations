package org.opensearch.migrations.replay.datahandlers.http;

import org.opensearch.migrations.replay.datahandlers.PayloadAccessFaultingMap;
import org.opensearch.migrations.transform.IJsonTransformer;
import org.opensearch.migrations.transform.JsonKeysForHttpMessage;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.util.IllegalReferenceCountException;
import io.netty.util.ReferenceCountUtil;
import lombok.Value;
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
            try {
                ctx.fireChannelRead(transform(transformer, httpMsg));
            } catch (Exception e) {
                var remainingBytes = httpMsg.payload().get(JsonKeysForHttpMessage.INLINED_BINARY_BODY_DOCUMENT_KEY);
                ReferenceCountUtil.release(remainingBytes); // release because we're not passing it along for cleanup
                throw new TransformationException(e);
            }
        } else {
            super.channelRead(ctx, msg);
        }
    }

    public static HttpJsonRequestWithFaultingPayload transform(
        IJsonTransformer transformer,
        HttpJsonRequestWithFaultingPayload httpJsonMessage
    ) {
        var originalHttpJsonMessage = httpJsonMessage;

        var protectionArtifacts = protectByteBufInHttpMessage(httpJsonMessage);

        Object returnedObject = transformer.transformJson(httpJsonMessage);

        var transformedRequest = HttpJsonRequestWithFaultingPayload.fromObject(returnedObject);

        unProtectByteBufInHttpMessage(transformedRequest, protectionArtifacts);

        if (originalHttpJsonMessage != transformedRequest) {
            // clear originalHttpJsonMessage for faster garbage collection if not persisted along
            originalHttpJsonMessage.clear();
        }
        return transformedRequest;
    }

    @Value
    private static class ProtectHttpMessageReturnVal {
        ByteBuf originalPayloadByteBuf;
        ByteBuf protectedPayloadByteBuf;
    }

    private static ProtectHttpMessageReturnVal protectByteBufInHttpMessage(HttpJsonMessageWithFaultingPayload httpJsonMessage) {
        // We don't trust custom transformations to get refCount right so adding protections.

        // Keep track if disableThrowingPayloadNotLoaded was changed
        boolean wasThrowingPayloadEnabled = false;
        if (httpJsonMessage.payload() instanceof PayloadAccessFaultingMap) {
            var payload = ((PayloadAccessFaultingMap) httpJsonMessage.payload());
            wasThrowingPayloadEnabled = !payload.isDisableThrowingPayloadNotLoaded();
            payload.setDisableThrowingPayloadNotLoaded(true);
        }
        var originalPayloadByteBuf = (ByteBuf) httpJsonMessage.payload().get(JsonKeysForHttpMessage.INLINED_BINARY_BODY_DOCUMENT_KEY);
        ByteBuf protectedByteBuf = null;
        if (originalPayloadByteBuf != null) {
            protectedByteBuf = Unpooled.unreleasableBuffer(originalPayloadByteBuf);
            httpJsonMessage.payload().put(JsonKeysForHttpMessage.INLINED_BINARY_BODY_DOCUMENT_KEY,
                protectedByteBuf);
        }
        // Revert disableThrowingPayloadNotLoaded
        if (wasThrowingPayloadEnabled && httpJsonMessage.payload() instanceof PayloadAccessFaultingMap) {
            var payload = ((PayloadAccessFaultingMap) httpJsonMessage.payload());
            payload.setDisableThrowingPayloadNotLoaded(false);
        }

        return new ProtectHttpMessageReturnVal(originalPayloadByteBuf, protectedByteBuf);
    }

    // Undo protectByteBufInHttpMessage after transformations
    private static void unProtectByteBufInHttpMessage(HttpJsonMessageWithFaultingPayload httpJsonMessage,
        ProtectHttpMessageReturnVal protectionBufs) throws TransformationException {
        if (protectionBufs.originalPayloadByteBuf != null) {
            // replace protected byteBuf if was hidden and still there
            // no risk of PayloadNotLoadedException since originalPayloadByteBuf already retrieved
            var transformedInlinedBinary = httpJsonMessage.payload().get(JsonKeysForHttpMessage.INLINED_BINARY_BODY_DOCUMENT_KEY);
            assert protectionBufs.protectedPayloadByteBuf != null : "Expected protectedByteBuf to be defined if innerPayloadByteBuf is";
            // Shallow check to see if object is the same or replaced
            if (protectionBufs.protectedPayloadByteBuf == transformedInlinedBinary) {
                protectionBufs.originalPayloadByteBuf.readerIndex(protectionBufs.protectedPayloadByteBuf.readerIndex());
                protectionBufs.originalPayloadByteBuf.writerIndex(protectionBufs.protectedPayloadByteBuf.writerIndex());
                httpJsonMessage.payload().put(JsonKeysForHttpMessage.INLINED_BINARY_BODY_DOCUMENT_KEY, protectionBufs.originalPayloadByteBuf);
            } else {
                // Transformation changed byteBuf, no longer needing originalPayloadByteBuf
                protectionBufs.originalPayloadByteBuf.release();
                if (transformedInlinedBinary == null) {
                    httpJsonMessage.payload().remove(JsonKeysForHttpMessage.INLINED_BINARY_BODY_DOCUMENT_KEY);
                } else if (transformedInlinedBinary instanceof ByteBuf) {
                    // Transformation set new ByteBuf, verify it's valid
                    var transformedInlinedBinaryByteBuf = (ByteBuf) transformedInlinedBinary;
                    // Touch for easier debugging if later leak detected
                    // e.g. if received unreleasable ByteBuf
                    transformedInlinedBinaryByteBuf.touch();
                    if (transformedInlinedBinaryByteBuf.refCnt() != 1) {
                        throw new TransformationException(
                            new IllegalReferenceCountException(
                                "Invalid transformed binary ByteBuf refCnt, expected 1, got " +
                                    transformedInlinedBinaryByteBuf.refCnt()));
                    }
                } else {
                    throw new TransformationException(
                        new UnsupportedOperationException(
                            "Type of " + transformedInlinedBinary.getClass() + " for " +
                                JsonKeysForHttpMessage.INLINED_BINARY_BODY_DOCUMENT_KEY + " not supported."));
                }
            }
        }
    }
}
