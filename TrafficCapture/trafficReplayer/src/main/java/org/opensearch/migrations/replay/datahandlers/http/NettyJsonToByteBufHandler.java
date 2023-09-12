package org.opensearch.migrations.replay.datahandlers.http;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.ByteBufOutputStream;
import io.netty.buffer.CompositeByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
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

/**
 * This class does the remaining serialization of the contents coming into it into ByteBuf
 * objects.  This handler may be called in cases where both the content needed to be
 * reformatted OR the content is being passed through directly.
 *
 * ByteBufs that arrive here (because an earlier pipeline did a conversion) are simply passed
 * to the next handler in the pipeline.  However, the headers that are remaining in the
 * HttpJsonMessage and the HttpContents that may be coming in untouched from the original
 * reconstructed request are converted to ByteBufs.  There is an attempt to match ByteBuf
 * sizes to those that were found in the original request, using a simple policy to use the
 * same sizes until we run out of data.  If we have more data than in the original request
 * (headers), the number of additional ByteBuf packets and their size is an implementation
 * detail.
 */
@Slf4j
public class NettyJsonToByteBufHandler extends ChannelInboundHandlerAdapter {
    // TODO: Eventually, we can count up the size of all of the entries in the headers - but for now, I'm being lazy
    public static final int MAX_HEADERS_BYTE_SIZE = 64 * 1024;
    List<List<Integer>> sharedInProgressChunkSizes;
    ByteBuf inProgressByteBuf;
    int payloadBufferIndex;

    public NettyJsonToByteBufHandler(List<List<Integer>> sharedInProgressChunkSizes) {
        this.sharedInProgressChunkSizes = sharedInProgressChunkSizes;
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        if (msg instanceof HttpJsonMessageWithFaultingPayload) {
            writeHeadersIntoByteBufs(ctx, (HttpJsonMessageWithFaultingPayload) msg);
        } else if (msg instanceof ByteBuf) {
            ctx.fireChannelRead((ByteBuf) msg);
        } else if (msg instanceof HttpContent) {
            writeContentsIntoByteBufs(ctx, (HttpContent) msg);
            if (msg instanceof LastHttpContent) {
                if (inProgressByteBuf != null) {
                    ctx.fireChannelRead(inProgressByteBuf);
                    inProgressByteBuf = null;
                    ++payloadBufferIndex;
                }
                ctx.fireChannelRead(LastHttpContent.EMPTY_LAST_CONTENT);
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

    static final List<Integer> ZERO_LIST = List.of();

    /**
     * As discussed in the class javadoc, this function converts the HttpContent messages
     * into ByteBufs that were the same size as the packets in the original request.
     * @param ctx
     * @param msg
     */
    private void writeContentsIntoByteBufs(ChannelHandlerContext ctx, HttpContent msg) {
        var headerChunkSizes = sharedInProgressChunkSizes.size() > 1 ?
                sharedInProgressChunkSizes.get(1) : ZERO_LIST;
        while (true) { // java doesn't have tail recursion, so do this the manual way
            int currentChunkProspectiveSize =
                    payloadBufferIndex >= headerChunkSizes.size() ? 0 :  headerChunkSizes.get(payloadBufferIndex);
            if (inProgressByteBuf == null && currentChunkProspectiveSize > 0) {
                inProgressByteBuf = ctx.alloc().buffer(currentChunkProspectiveSize);
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

    /**
     * Same idea as writeContentsIntoByteBufs, but there's an extra step of serializing the
     * headers first.  That's done by simply writing them to a ByteArray stream, then slicing
     * the array into pieces.  Notice that the output of the headers will preserve ordering
     * and capitalization.
     *
     * @param ctx
     * @param httpJson
     * @throws IOException
     */
    private void writeHeadersIntoByteBufs(ChannelHandlerContext ctx,
                                          HttpJsonMessageWithFaultingPayload httpJson) throws IOException {
        var headerChunkSizes = sharedInProgressChunkSizes.get(0);
        try {
            if (headerChunkSizes.size() > 1) {
                 writeHeadersAsChunks(ctx, httpJson, headerChunkSizes, MAX_HEADERS_BYTE_SIZE);
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
                                             HttpJsonMessageWithFaultingPayload httpJson,
                                             List<Integer> headerChunkSizes,
                                             int maxLastBufferSize)
            throws IOException
    {
        AtomicInteger chunkIdx = new AtomicInteger(headerChunkSizes.size());
        var bufs = headerChunkSizes.stream()
                .map(i -> ctx.alloc().buffer(chunkIdx.decrementAndGet()==0?maxLastBufferSize:i).retain())
                .toArray(ByteBuf[]::new);
        var cbb = ctx.alloc().compositeBuffer(bufs.length);
        ResourceLeakDetector<CompositeByteBuf> rld =
                (ResourceLeakDetector<CompositeByteBuf>) ResourceLeakDetectorFactory.instance().newResourceLeakDetector(cbb.getClass());
        rld.track(cbb);
        cbb.addComponents(true, bufs);
        log.debug("cbb.refcnt="+cbb.refCnt());
        try (var bbos = new ByteBufOutputStream(cbb)) {
            writeHeadersIntoStream(httpJson, bbos);
        }
        log.debug("post write cbb.refcnt="+cbb.refCnt());
        int debugCounter = 0;
        for (var bb : bufs) {
            log.debug("bb[" + (debugCounter) +  "].refcnt=" + bb.refCnt());
            ctx.fireChannelRead(bb);
            bb.release();
            log.debug("Post fire & decrement - bb[" + (debugCounter) +  "].refcnt=" + bb.refCnt());
            debugCounter++;
        }
        cbb.release();
    }

    private static void writeHeadersIntoStream(HttpJsonMessageWithFaultingPayload httpJson,
                                               OutputStream os) throws IOException {
        try (var osw = new OutputStreamWriter(os, StandardCharsets.UTF_8)) {
            osw.append(httpJson.method());
            osw.append(" ");
            osw.append(httpJson.path());
            osw.append(" ");
            osw.append(httpJson.protocol());
            osw.append("\r\n");

            for (var kvpList : httpJson.headers().asStrictMap().entrySet()) {
                var key = kvpList.getKey();
                for (var valueEntry : kvpList.getValue()) {
                    osw.append(key);
                    osw.append(": ");
                    osw.append(valueEntry);
                    osw.append("\r\n");
                }
            }
            osw.append("\r\n");
            osw.flush();
        }
    }
}
