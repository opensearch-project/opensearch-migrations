package org.opensearch.migrations.replay.datahandlers.http;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.List;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufOutputStream;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.LastHttpContent;
import lombok.Lombok;
import lombok.extern.slf4j.Slf4j;

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
    List<List<Integer>> sharedInProgressChunkSizes;
    ByteBuf inProgressByteBuf;
    int payloadBufferIndex;

    public NettyJsonToByteBufHandler(List<List<Integer>> sharedInProgressChunkSizes) {
        this.sharedInProgressChunkSizes = sharedInProgressChunkSizes;
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        if (msg instanceof HttpJsonRequestWithFaultingPayload) {
            writeHeadersIntoByteBufs(ctx, (HttpJsonRequestWithFaultingPayload) msg);
        } else if (msg instanceof ByteBuf) {
            ctx.fireChannelRead(msg);
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
        var headerChunkSizes = sharedInProgressChunkSizes.size() > 1 ? sharedInProgressChunkSizes.get(1) : ZERO_LIST;
        while (true) { // java doesn't have tail recursion, so do this the manual way
            int currentChunkProspectiveSize = payloadBufferIndex >= headerChunkSizes.size()
                ? 0
                : headerChunkSizes.get(payloadBufferIndex);
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
    private void writeHeadersIntoByteBufs(ChannelHandlerContext ctx, HttpJsonRequestWithFaultingPayload httpJson)
        throws IOException {
        var headerChunkSizes = sharedInProgressChunkSizes.get(0);
        try {
            if (headerChunkSizes.size() > 1) {
                writeHeadersAsChunks(ctx, httpJson, headerChunkSizes);
                return;
            }
        } catch (Exception e) {
            log.atWarn().setCause(e)
                .setMessage("writing headers directly to chunks w/ sizes didn't work for {}")
                .addArgument(httpJson)
                .log();
        }

        try (var baos = new ByteArrayOutputStream()) {
            writeHeadersIntoStream(httpJson, baos);
            ctx.fireChannelRead(Unpooled.wrappedBuffer(baos.toByteArray()));
        }
    }

    private static void writeHeadersAsChunks(
        ChannelHandlerContext ctx,
        HttpJsonRequestWithFaultingPayload httpJson,
        List<Integer> headerChunkSizes
    ) throws IOException {
        var initialSize = headerChunkSizes.stream().mapToInt(Integer::intValue).sum();

        ByteBuf buf = null;
        try {
            buf = ctx.alloc().buffer(initialSize);
            try (var bbos = new ByteBufOutputStream(buf)) {
                writeHeadersIntoStream(httpJson, bbos);
            }

            int index = 0;
            var chunkSizeIterator = headerChunkSizes.iterator();
            while (index < buf.writerIndex()) {
                if (!chunkSizeIterator.hasNext()) {
                    throw Lombok.sneakyThrow(new IllegalStateException("Ran out of input chunks for mapping"));
                }
                var inputChunkSize = chunkSizeIterator.next();
                var scaledChunkSize = (int) (((long) buf.writerIndex() * inputChunkSize) + (initialSize - 1))
                    / initialSize;
                int actualChunkSize = Math.min(buf.writerIndex() - index, scaledChunkSize);
                ctx.fireChannelRead(buf.retainedSlice(index, actualChunkSize));
                index += actualChunkSize;
            }
        } finally {
            if (buf != null) {
                buf.release();
            }
        }
    }

    private static void writeHeadersIntoStream(HttpJsonRequestWithFaultingPayload httpJson, OutputStream os)
        throws IOException {
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
