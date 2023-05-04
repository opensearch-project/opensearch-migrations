package org.opensearch.migrations.replay.datahandlers.http;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.ByteBufOutputStream;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.DecoderException;
import lombok.extern.slf4j.Slf4j;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
public class NettyJsonHeaderToByteBufHandler extends ChannelInboundHandlerAdapter {
    //private final HttpJsonTransformerHandler httpJsonTransformerHandler;
    List<List<Integer>> sharedInProgressChunkSizes;
    short currentSectionToPullChunksFrom;
    short chunksSentForSection;

    public NettyJsonHeaderToByteBufHandler(List<List<Integer>> sharedInProgressChunkSizes) {
        this.sharedInProgressChunkSizes = sharedInProgressChunkSizes;
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        log.trace("channelRead: "+msg);
        if (msg instanceof HttpJsonMessageWithFaultablePayload) {
            var byteBufs = writeHeadersIntoByteBufs((HttpJsonMessageWithFaultablePayload) msg);
            for (var bb : byteBufs) {
                ctx.fireChannelRead(bb);
            }
        } else if (msg instanceof ByteBuf) {
            ctx.fireChannelRead((ByteBuf) msg);
        }
        super.channelRead(ctx, msg);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        if (cause instanceof DecoderException) {
            super.exceptionCaught(ctx, cause);
        } else {
            super.exceptionCaught(ctx, cause);
        }
    }

    private ByteBuf[] writeHeadersIntoByteBufs(HttpJsonMessageWithFaultablePayload httpJson) throws IOException {
        var headerChunkSizes = sharedInProgressChunkSizes.get(0);
        try {
            if (headerChunkSizes.size() > 1) {
                return getHeadersAsChunks(httpJson, headerChunkSizes,
                        2 * headerChunkSizes.stream().mapToInt(x->x).sum());
            }
        } catch (Exception e) {
            log.warn("writing headers directly to chunks w/ sizes didn't work: "+e);
        }

        try (var baos = new ByteArrayOutputStream()) {
            writeHeadersIntoStream(httpJson, baos);
            return new ByteBuf[] { Unpooled.wrappedBuffer(baos.toByteArray()) };
        }
    }

    private static ByteBuf[] getHeadersAsChunks(HttpJsonMessageWithFaultablePayload httpJson,
                                                List<Integer> headerChunkSizes,
                                                int maxLastBufferSize)
            throws IOException
    {
        AtomicInteger counter = new AtomicInteger(headerChunkSizes.size());
        var bufs = headerChunkSizes.stream()
                .map(i -> ByteBufAllocator.DEFAULT.buffer(counter.decrementAndGet()==0?maxLastBufferSize:i))
                .toArray(ByteBuf[]::new);
        ByteBuf cbb = Unpooled.wrappedBuffer(bufs);
        try (var bbos = new ByteBufOutputStream(cbb)) {
            writeHeadersIntoStream(httpJson, bbos);
        }
        return bufs;
    }

    private static void writeHeadersIntoStream(HttpJsonMessageWithFaultablePayload httpJson,
                                               OutputStream os) throws IOException {
        try (var osw = new OutputStreamWriter(os, StandardCharsets.UTF_8)) {
            osw.append(httpJson.method());
            osw.append(httpJson.uri());
            osw.append(httpJson.protocol());
            osw.append("\n");

            for (var kvpList : httpJson.headers().entrySet()) {
                var key = kvpList.getKey();
                for (var valueEntry : kvpList.getValue()) {
                    osw.append(key);
                    osw.append(":");
                    osw.append(valueEntry);
                    osw.append("\n");
                }
            }
            osw.append("\n\n");
            osw.flush();
        }
    }
}
