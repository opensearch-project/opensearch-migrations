package org.opensearch.migrations.replay.datahandlers.http;

import java.nio.charset.StandardCharsets;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.CompositeByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.LastHttpContent;

/**
 * This class is meant to be situated within a netty pipeline (see @RequestPipelineOrchestrator)
 * that consumes an HttpJsonMessageWithFaultingPayload object that contains the headers, followed
 * by HttpContent blocks.
 *
 * This class is responsible for determining how the HttpContent sequence should be packaged into
 * the final HTTP request, deciding between using a chunked transfer encoding or a fixed length
 * payload.  It uses the headers in the HttpJsonMessage to do that.  It will then convert the
 * HttpContent sequence accordingly into ByteBuf objects containing whatever packaging is necessary
 * and/or updating the headers to reflect the fixed length of the transfer.
 *
 * The HttpJsonMessage's headers will otherwise remain intact.  Another handler will take
 * responsibility to serialize that for the final HTTP Request.
 *
 * Notice that this class will emit ByteBufs and the next handler in the pipeline,
 * NettyJsonToByteBufHandler will simply pass those ByteBufs through, while repackaging HttpContent
 * messages, seemingly similar to what this class does!  However, these two handlers have slightly
 * calling contexts.  This handler will only be utilized when there needed to be a material change
 * on the incoming HttpContent objects from the original request.  The next handler will be called
 * in cases where both the content needed to be reformatted OR the content is being passed through
 * directly, hence the reason for the overlap.
 */
public class NettyJsonContentStreamToByteBufHandler extends ChannelInboundHandlerAdapter {
    private static final String TRANSFER_ENCODING_CHUNKED_VALUE = "chunked";
    public static final String CONTENT_LENGTH_HEADER_NAME = "Content-Length";

    enum MODE {
        CHUNKED,
        FIXED
    }

    MODE streamMode = MODE.CHUNKED;
    int contentBytesReceived;
    CompositeByteBuf bufferedContents;
    HttpJsonMessageWithFaultingPayload bufferedJsonMessage;

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        if (msg instanceof HttpJsonMessageWithFaultingPayload) {
            handleReadJsonMessageObject(ctx, (HttpJsonMessageWithFaultingPayload) msg);
        } else if (msg instanceof HttpContent) {
            handleReadBody(ctx, (HttpContent) msg);
        } else {
            super.channelRead(ctx, msg);
        }
    }

    private void handleReadBody(ChannelHandlerContext ctx, HttpContent msg) {
        boolean lastContent = (msg instanceof LastHttpContent);
        var dataByteBuf = msg.content();
        contentBytesReceived += dataByteBuf.readableBytes();
        switch (streamMode) {
            case CHUNKED:
                if (dataByteBuf.readableBytes() > 0) {
                    handleAsChunked(ctx, dataByteBuf);
                }
                if (lastContent) {
                    sendEndChunk(ctx);
                }
                break;
            case FIXED:
                bufferedContents.addComponents(true, dataByteBuf);
                if (lastContent) {
                    finalizeFixedContentStream(ctx);
                }
                break;
            default:
                throw new IllegalStateException("Unknown transfer encoding mode " + streamMode);
        }
    }

    private void handleReadJsonMessageObject(ChannelHandlerContext ctx, HttpJsonMessageWithFaultingPayload msg) {
        bufferedJsonMessage = msg;
        var transferEncoding = bufferedJsonMessage.headers().asStrictMap().get("transfer-encoding");
        streamMode = (transferEncoding != null && transferEncoding.contains(TRANSFER_ENCODING_CHUNKED_VALUE))
            ? MODE.CHUNKED
            : MODE.FIXED;
        if (streamMode == MODE.CHUNKED) {
            bufferedJsonMessage.headers().asStrictMap().remove(CONTENT_LENGTH_HEADER_NAME);
            ctx.fireChannelRead(bufferedJsonMessage);
        } else {
            bufferedContents = ctx.alloc().compositeHeapBuffer();
        }
    }

    private void handleAsChunked(ChannelHandlerContext ctx, ByteBuf dataByteBuf) {
        var chunkSizePreamble = (Integer.toHexString(dataByteBuf.readableBytes()) + "\r\n").getBytes(
            StandardCharsets.UTF_8
        );
        var compositeWrappedData = ctx.alloc().compositeBuffer(2);
        compositeWrappedData.addComponents(true, Unpooled.wrappedBuffer(chunkSizePreamble));
        compositeWrappedData.addComponents(true, dataByteBuf);
        compositeWrappedData.addComponents(true, Unpooled.wrappedBuffer("\r\n".getBytes(StandardCharsets.UTF_8)));
        ctx.fireChannelRead(compositeWrappedData);
    }

    private void sendEndChunk(ChannelHandlerContext ctx) {
        // make this a singleton
        var lastChunkByteBuf = Unpooled.wrappedBuffer("0\r\n\r\n".getBytes(StandardCharsets.UTF_8));
        ctx.fireChannelRead(lastChunkByteBuf);
        ctx.fireChannelRead(LastHttpContent.EMPTY_LAST_CONTENT);
    }

    private void finalizeFixedContentStream(ChannelHandlerContext ctx) {
        bufferedJsonMessage.headers().put(CONTENT_LENGTH_HEADER_NAME, contentBytesReceived);
        ctx.fireChannelRead(bufferedJsonMessage);
        bufferedJsonMessage = null;
        ctx.fireChannelRead(bufferedContents);
        ctx.fireChannelRead(LastHttpContent.EMPTY_LAST_CONTENT);
    }
}
