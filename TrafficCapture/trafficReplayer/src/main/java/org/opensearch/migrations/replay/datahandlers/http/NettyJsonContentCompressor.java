package org.opensearch.migrations.replay.datahandlers.http;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.CompositeByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.DecoderException;
import io.netty.handler.codec.http.DefaultHttpContent;
import io.netty.handler.codec.http.DefaultLastHttpContent;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.util.ResourceLeakDetector;
import io.netty.util.ResourceLeakDetectorFactory;
import lombok.extern.slf4j.Slf4j;

import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

@Slf4j
public class NettyJsonContentCompressor extends ChannelInboundHandlerAdapter {

    public static final String CONTENT_ENCODING_GZIP_VALUE = "gzip";

    static class ImmediateForwardingOutputStream extends OutputStream {
        ChannelHandlerContext ctx;
        ResourceLeakDetector<ByteBuf> leakDetector = ResourceLeakDetectorFactory.instance()
                .newResourceLeakDetector(ByteBuf.class);

        public ImmediateForwardingOutputStream(ChannelHandlerContext ctx) {
            this.ctx = ctx;
        }

        @Override
        public void write(int b) throws IOException {
            write(new byte[]{(byte) b}, 0, 1);
        }

        @Override
        public void write(byte[] buff, int offset, int len) {
            var byteBuf = ByteBufAllocator.DEFAULT.buffer(len-offset);
            leakDetector.track(byteBuf);
            byteBuf.writeBytes(buff, offset, len);
            ctx.fireChannelRead(new DefaultHttpContent(byteBuf));
        }
    }

    GZIPOutputStream compressorStream;
    BufferedOutputStream bufferedOutputStream;
    ImmediateForwardingOutputStream passDownstreamOutputStream;

    public void activateCompressorComponents(ChannelHandlerContext ctx) throws IOException {
        passDownstreamOutputStream = new ImmediateForwardingOutputStream(ctx);
        bufferedOutputStream = new BufferedOutputStream(passDownstreamOutputStream);
        compressorStream = new GZIPOutputStream(bufferedOutputStream);
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        if (msg instanceof HttpJsonMessageWithFaultablePayload) {
            var contentEncoding =
                    ((HttpJsonMessageWithFaultablePayload) msg).headers().asStrictMap().get("content-encoding");
            if (contentEncoding != null && contentEncoding.contains(CONTENT_ENCODING_GZIP_VALUE)) {
                activateCompressorComponents(ctx);
            }
        } else if (msg instanceof HttpContent) {
            if (compressorStream != null) {
                var contentBuf = ((HttpContent) msg).content();
                contentBuf.readBytes(compressorStream, contentBuf.readableBytes());
                if (msg instanceof LastHttpContent) {
                    closeStream();
                    ctx.fireChannelRead(msg);
                }
                return; // fireChannelRead will be fired on the compressed contents via the compressorStream.
            } else {
                assert bufferedOutputStream == null && passDownstreamOutputStream == null;
            }
        }
        super.channelRead(ctx, msg);
    }

    private void closeStream() throws IOException {
        if (compressorStream != null) {
            compressorStream.flush();
            compressorStream.close();
            compressorStream = null;
        }
    }

    @Override
    public void channelUnregistered(ChannelHandlerContext ctx) throws Exception {
        closeStream();
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
