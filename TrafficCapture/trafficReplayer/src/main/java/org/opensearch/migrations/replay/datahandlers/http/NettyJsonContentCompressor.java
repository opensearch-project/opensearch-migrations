package org.opensearch.migrations.replay.datahandlers.http;

import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.DecoderException;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.stream.ChunkedWriteHandler;
import lombok.extern.slf4j.Slf4j;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.List;
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

    public void activateCompressorComponents(ChannelHandlerContext ctx) throws IOException {
        passChunkDownstreamOutputStream = new ChunkWriterOutputStream(ctx);
        bufferedOutputStream = new BufferedOutputStream(passChunkDownstreamOutputStream);
        compressorStream = new GZIPOutputStream(bufferedOutputStream);
        ctx.pipeline().addAfter(ctx.name(), "postCompressChunkedHandler",
                new ChunkedWriteHandler());
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        log.trace("channelRead: " + msg);
        if (msg instanceof HttpJsonMessageWithFaultablePayload) {
            var transferEncoding =
                    ((HttpJsonMessageWithFaultablePayload) msg).headers().asStrictMap().get("transfer-encoding");
            if (transferEncoding != null && transferEncoding.contains(TRANSFER_ENCODING_GZIP_VALUE)) {
                activateCompressorComponents(ctx);
                convertHeadersToChunked(((HttpJsonMessageWithFaultablePayload)msg).headers().strictHeadersMap);
            }
        } else if (compressorStream != null) {
            if (msg instanceof HttpContent) {
                var contentBuf = ((HttpContent) msg).content();
                contentBuf.readBytes(compressorStream, contentBuf.readableBytes());
                return; // fireChannelRead will be fired on the compressed contents via the compressorStream.
            } else {
                assert bufferedOutputStream == null && passChunkDownstreamOutputStream == null;
            }
        }
        super.channelRead(ctx, msg);
    }

    private void convertHeadersToChunked(StrictCaseInsensitiveHttpHeadersMap httpHeaders) {
        //httpHeaders.put("Content-Transfer-Encoding", List.of("chunked"));
        httpHeaders.remove("Content-length");
    }

    @Override
    public void channelUnregistered(ChannelHandlerContext ctx) throws Exception {
        if (compressorStream != null) {
            //compressorStream.flush();
            compressorStream.close();
            compressorStream = null;
        }
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
