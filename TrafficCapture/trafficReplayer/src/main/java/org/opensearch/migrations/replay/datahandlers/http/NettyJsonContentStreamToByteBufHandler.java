package org.opensearch.migrations.replay.datahandlers.http;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.CompositeByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.http.DefaultLastHttpContent;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.LastHttpContent;

import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;

public class NettyJsonContentStreamToByteBufHandler extends ChannelInboundHandlerAdapter {
    private static final String TRANSFER_ENCODING_CHUNKED_VALUE = "chunked";
    public static final String CONTENT_LENGTH_HEADER_NAME = "Content-Length";

    enum MODE {
        CHUNKED, FIXED
    }

    MODE streamMode = MODE.CHUNKED;
    int contentBytesReceived;
    CompositeByteBuf bufferedContents;
    HttpJsonMessageWithFaultablePayload bufferedJsonMessage;

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        if (msg instanceof HttpJsonMessageWithFaultablePayload) {
            bufferedJsonMessage = (HttpJsonMessageWithFaultablePayload) msg;
            var transferEncoding =
                    bufferedJsonMessage.headers().asStrictMap().get("transfer-encoding");
            streamMode = (transferEncoding != null && transferEncoding.contains(TRANSFER_ENCODING_CHUNKED_VALUE)) ?
                    MODE.CHUNKED : MODE.FIXED;
            if (streamMode == MODE.CHUNKED) {
                bufferedJsonMessage.headers().asStrictMap().remove(CONTENT_LENGTH_HEADER_NAME);
                ctx.fireChannelRead(bufferedJsonMessage);
            } else {
                bufferedContents = ByteBufAllocator.DEFAULT.compositeHeapBuffer();
            }
        } else if (msg instanceof HttpContent) {
            boolean lastContent = (msg instanceof LastHttpContent);
            var dataByteBuf = ((HttpContent) msg).content();
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
                    bufferedContents.addComponents(true, dataByteBuf.retain());
                    if (lastContent) { finalizeFixedContentStream(ctx); }
                    break;
                default:
                    throw new RuntimeException("Unknown transfer encoding mode "+streamMode);
            }
        } else {
            super.channelRead(ctx, msg);
        }
    }

    private void handleAsChunked(ChannelHandlerContext ctx, ByteBuf dataByteBuf) {
        var chunkSizePreamble = (Integer.toHexString(dataByteBuf.readableBytes()) + "\r\n")
                .getBytes(StandardCharsets.UTF_8);
        var compositeWrappedData = ByteBufAllocator.DEFAULT.compositeBuffer(2);
        compositeWrappedData.addComponents(true, Unpooled.wrappedBuffer(chunkSizePreamble));
        compositeWrappedData.addComponents(true, dataByteBuf);
        compositeWrappedData.addComponents(true, Unpooled.wrappedBuffer("\r\n".getBytes(StandardCharsets.UTF_8)));
        ctx.fireChannelRead(compositeWrappedData);
    }

    private void sendEndChunk(ChannelHandlerContext ctx) {
        // make this a singleton
        var lastChunkByteBuf = Unpooled.wrappedBuffer("0\r\n\r\n".getBytes(StandardCharsets.UTF_8));
        ctx.fireChannelRead(lastChunkByteBuf);
        ctx.fireChannelRead(DefaultLastHttpContent.EMPTY_LAST_CONTENT);
    }

    private void finalizeFixedContentStream(ChannelHandlerContext ctx) {
        bufferedJsonMessage.headers().put(CONTENT_LENGTH_HEADER_NAME, contentBytesReceived).toString();
        ctx.fireChannelRead(bufferedJsonMessage);
        bufferedJsonMessage = null;
        ctx.fireChannelRead(bufferedContents);
        ctx.fireChannelRead(DefaultLastHttpContent.EMPTY_LAST_CONTENT);
    }
}
