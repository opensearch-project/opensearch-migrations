package org.opensearch.migrations.replay.datahandlers.http;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.DecoderException;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.util.concurrent.EventExecutorGroup;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.zip.GZIPOutputStream;

@Slf4j
public class NettyJsonContentCompressor extends ChannelInboundHandlerAdapter {

    public static final String TRANSFER_ENCODING_GZIP_VALUE = "gzip";

    static class ChunkWriterOutputStream extends OutputStream {
        ChannelHandlerContext ctx;

        public ChunkWriterOutputStream(ChannelHandlerContext ctx) {
            this.ctx = ctx;
        }

        @Override
        public void write(int b) throws IOException {
            write(new byte[]{(byte) b}, 0, 1);
        }

        @Override
        public void write(byte[] buff, int offset, int len) throws IOException {
            ctx.fireChannelRead(Unpooled.wrappedBuffer(buff, offset, len));
        }
    }

    GZIPOutputStream compressorStream;
    BufferedOutputStream bufferedOutputStream;
    ChunkWriterOutputStream passChunkDownstreamOutputStream;

    public void initializeStreams(ChannelHandlerContext ctx) throws IOException {
        passChunkDownstreamOutputStream = new ChunkWriterOutputStream(ctx);
        bufferedOutputStream = new BufferedOutputStream(passChunkDownstreamOutputStream);
        compressorStream = new GZIPOutputStream(bufferedOutputStream);
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        log.trace("channelRead: " + msg);
        if (msg instanceof HttpJsonMessageWithFaultablePayload) {
            var transferEncoding =
                    ((HttpJsonMessageWithFaultablePayload) msg).headers().asStrictMap().get("transfer-encoding");
            if (TRANSFER_ENCODING_GZIP_VALUE.equals(transferEncoding)) {
                initializeStreams(ctx);
            }
        } else if (compressorStream != null && msg instanceof HttpContent) {
            var contentBuf = ((HttpContent) msg).content();
            contentBuf.readBytes(compressorStream, contentBuf.readableBytes());
        } else {
            super.channelRead(ctx, msg);
        }
    }


    @Override
    public void channelUnregistered(ChannelHandlerContext ctx) throws Exception {
        compressorStream.flush();
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        channelUnregistered(ctx);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        if (cause instanceof DecoderException) {
            super.exceptionCaught(ctx, cause);
        } else {
            super.exceptionCaught(ctx, cause);
        }
    }
}
