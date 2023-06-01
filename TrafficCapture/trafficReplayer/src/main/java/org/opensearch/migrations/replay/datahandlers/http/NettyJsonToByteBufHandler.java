package org.opensearch.migrations.replay.datahandlers.http;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.ByteBufOutputStream;
import io.netty.buffer.CompositeByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.DecoderException;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.util.ResourceLeakDetector;
import io.netty.util.ResourceLeakDetectorFactory;
import lombok.extern.slf4j.Slf4j;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
public class NettyJsonToByteBufHandler extends ChannelInboundHandlerAdapter {
    public static final int MAX_CHUNK_SIZE = 1024 * 1024;
    //private final HttpJsonTransformerHandler httpJsonTransformerHandler;
    List<List<Integer>> sharedInProgressChunkSizes;
    ByteBuf inProgressByteBuf;
    int payloadBufferIndex;

    public NettyJsonToByteBufHandler(List<List<Integer>> sharedInProgressChunkSizes) {
        this.sharedInProgressChunkSizes = sharedInProgressChunkSizes;
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        if (msg instanceof HttpJsonMessageWithFaultablePayload) {
            writeHeadersIntoByteBufs(ctx, (HttpJsonMessageWithFaultablePayload) msg);
        } else if (msg instanceof ByteBuf) {
            ctx.fireChannelRead((ByteBuf) msg);
        } else if (msg instanceof HttpContent) {
            writeContentsIntoByteBufs(ctx, (HttpContent) msg);
            if (msg instanceof LastHttpContent && inProgressByteBuf != null) {
                ctx.fireChannelRead(inProgressByteBuf);
                inProgressByteBuf = null;
                ++payloadBufferIndex;
            }
        } else {
            super.channelRead(ctx, msg);
        }
    }

    @Override
    public void channelUnregistered(ChannelHandlerContext ctx) throws Exception {
        if (inProgressByteBuf != null) {
            ctx.fireChannelRead(inProgressByteBuf);
            inProgressByteBuf = null;
            ++payloadBufferIndex;
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

    static final List<Integer> ZERO_LIST = List.of();
    private void writeContentsIntoByteBufs(ChannelHandlerContext ctx, HttpContent msg) {
        var headerChunkSizes = sharedInProgressChunkSizes.size() > 1 ?
                sharedInProgressChunkSizes.get(1) : ZERO_LIST;
        while (true) { // java doesn't have tail recursion, so do this the manual way
            int currentChunkProspectiveSize =
                    payloadBufferIndex >= headerChunkSizes.size() ? 0 :  headerChunkSizes.get(payloadBufferIndex);
            if (inProgressByteBuf == null && currentChunkProspectiveSize > 0) {
                inProgressByteBuf = ByteBufAllocator.DEFAULT.buffer(currentChunkProspectiveSize);
            }
            if (inProgressByteBuf != null) {
                var bytesLeftToWriteInCurrentChunk = currentChunkProspectiveSize - inProgressByteBuf.writerIndex();
                var numBytesToWrite = Math.min(bytesLeftToWriteInCurrentChunk, msg.content().readableBytes());
                inProgressByteBuf.writeBytes(msg.content(), numBytesToWrite);
                if (numBytesToWrite == bytesLeftToWriteInCurrentChunk) {
                    ctx.fireChannelRead(inProgressByteBuf);
                    inProgressByteBuf = null;
                    ++payloadBufferIndex;
                    if (msg.content().readableBytes() > 0) {
                        continue;
                    }
                }
            } else {
                ctx.fireChannelRead(msg.content());
            }
            break;
        }
    }

    private void writeHeadersIntoByteBufs(ChannelHandlerContext ctx,
                                          HttpJsonMessageWithFaultablePayload httpJson) throws IOException {
        var headerChunkSizes = sharedInProgressChunkSizes.get(0);
        try {
            if (headerChunkSizes.size() > 1) {
                 writeHeadersAsChunks(ctx, httpJson, headerChunkSizes,
                        2 * headerChunkSizes.stream().mapToInt(x->x).sum());
                 return;
            }
        } catch (Exception e) {
            log.warn("writing headers directly to chunks w/ sizes didn't work: "+e);
        }

        try (var baos = new ByteArrayOutputStream()) {
            writeHeadersIntoStream(httpJson, baos);
            ctx.fireChannelRead(Unpooled.wrappedBuffer(baos.toByteArray()));
        }
    }

    private static void writeHeadersAsChunks(ChannelHandlerContext ctx,
                                             HttpJsonMessageWithFaultablePayload httpJson,
                                             List<Integer> headerChunkSizes,
                                             int maxLastBufferSize)
            throws IOException
    {
        AtomicInteger counter = new AtomicInteger(headerChunkSizes.size());
        var bufs = headerChunkSizes.stream()
                .map(i -> ByteBufAllocator.DEFAULT.buffer(counter.decrementAndGet()==0?maxLastBufferSize:i).retain())
                .toArray(ByteBuf[]::new);
        var cbb = ByteBufAllocator.DEFAULT.compositeBuffer(bufs.length);
        ResourceLeakDetector<CompositeByteBuf> rld =
                (ResourceLeakDetector<CompositeByteBuf>) ResourceLeakDetectorFactory.instance().newResourceLeakDetector(cbb.getClass());
        rld.track(cbb);
        cbb.addComponents(true, bufs);
        log.info("cbb.refcnt="+cbb.refCnt());
        try (var bbos = new ByteBufOutputStream(cbb)) {
            writeHeadersIntoStream(httpJson, bbos);
        }
        log.info("post write cbb.refcnt="+cbb.refCnt());
        int debugCounter = 0;
        for (var bb : bufs) {
            log.info("bb[" + (debugCounter) +  "].refcnt=" + bb.refCnt());
            ctx.fireChannelRead(bb);
            bb.release();
            log.info("Post fire & decrement - bb[" + (debugCounter) +  "].refcnt=" + bb.refCnt());
            debugCounter++;
        }
        cbb.release();
    }

    private static void writeHeadersIntoStream(HttpJsonMessageWithFaultablePayload httpJson,
                                               OutputStream os) throws IOException {
        try (var osw = new OutputStreamWriter(os, StandardCharsets.UTF_8)) {
            osw.append(httpJson.method());
            osw.append(" ");
            osw.append(httpJson.uri());
            osw.append(" ");
            osw.append(httpJson.protocol());
            osw.append("\n");

            for (var kvpList : httpJson.headers().asStrictMap().entrySet()) {
                var key = kvpList.getKey();
                for (var valueEntry : kvpList.getValue()) {
                    osw.append(key);
                    osw.append(": ");
                    osw.append(valueEntry);
                    osw.append("\n");
                }
            }
            osw.append("\n");
            osw.flush();
        }
    }
}
