package org.opensearch.migrations.replay.datahandlers.http;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.zip.GZIPOutputStream;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.http.DefaultHttpContent;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.LastHttpContent;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class NettyJsonContentCompressor extends ChannelInboundHandlerAdapter {

    public static final String CONTENT_ENCODING_GZIP_VALUE = "gzip";

    static class ImmediateForwardingOutputStream extends OutputStream {
        ChannelHandlerContext ctx;

        public ImmediateForwardingOutputStream(ChannelHandlerContext ctx) {
            this.ctx = ctx;
        }

        @Override
        public void write(int b) throws IOException {
            write(new byte[] { (byte) b }, 0, 1);
        }

        @Override
        public void write(byte[] buff, int offset, int len) {
            var byteBuf = ctx.alloc().buffer(len - offset);
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
        if (msg instanceof HttpJsonMessageWithFaultingPayload) {
            var contentEncoding = ((HttpJsonMessageWithFaultingPayload) msg).headers()
                .asStrictMap()
                .get("content-encoding");
            if (contentEncoding != null && contentEncoding.contains(CONTENT_ENCODING_GZIP_VALUE)) {
                activateCompressorComponents(ctx);
            }
        } else if (msg instanceof HttpContent) {
            if (compressorStream != null) {
                var contentBuf = ((HttpContent) msg).content();
                try {
                    contentBuf.readBytes(compressorStream, contentBuf.readableBytes());
                    if (msg instanceof LastHttpContent) {
                        closeStream();
                        ctx.fireChannelRead(LastHttpContent.EMPTY_LAST_CONTENT);
                    }
                    return; // fireChannelRead will be fired on the compressed contents via the compressorStream.
                } finally {
                    contentBuf.release();
                }
            } else {
                assert (bufferedOutputStream == null && passDownstreamOutputStream == null)
                    : "Expected contents with data to be passed through the compression stream before it was closed OR "
                        + "to be passed-through without compression, but this object was used for compression and "
                        + "has since been closed.";
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
}
