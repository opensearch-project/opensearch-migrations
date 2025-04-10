package org.opensearch.migrations.trafficcapture.proxyserver.netty;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.CompositeByteBuf;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.util.ReferenceCountUtil;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;

/**
 * This handler performs in-place filtering of specific HTTP headers during HTTP request parsing.
 * <p>
 * It maintains minimal internal state to track whether a target header has been processed.
 * This state is automatically cleared on each response flush ({@code write(...)}) and is designed
 * to work seamlessly across multiple HTTP/1.1 requests on a persistent connection.
 * <p>
 * As long as a response is written for each request—as is typical in HTTP/1.1 scenarios—the handler
 * will reset itself correctly and continue functioning as expected.
 */
@Slf4j
public class HeaderRemoverHandler extends ChannelDuplexHandler {
    final String headerToRemove;
    CompositeByteBuf previousRemaining;
    // This handler has 3 states - copying, dropping, or testing.  when previousRemaining != null, we're testing.
    // when dropUntilNewline == true, we're dropping, otherwise, we're copying (when previousRemaining==null)
    // The starting state is previousRemaining == null and dropUntilNewline = false
    boolean dropUntilNewline;
    MessagePosition requestPosition = MessagePosition.IN_HEADER;

    private enum MessagePosition {
        IN_HEADER, ONE_NEW_LINE, AFTER_HEADERS,
    }

    public HeaderRemoverHandler(String headerToRemove) {
        if (!headerToRemove.endsWith(":")) {
            throw new IllegalArgumentException("The headerToRemove must end with a ':'");
        }
        this.headerToRemove = headerToRemove;
    }

    @SneakyThrows
    void lambdaSafeSuperChannelRead(ChannelHandlerContext ctx, ByteBuf bb) {
        super.channelRead(ctx, bb);
    }

    /**
     * @return true if there's a discongruity in the incoming buf and the contents that preceded this call will
     * need to be buffered by the caller
     */
    boolean matchNextBytes(ChannelHandlerContext ctx, ByteBuf buf) {
        final var sourceReaderIdx = buf.readerIndex();
        for (int i=previousRemaining.writerIndex(); ; ++i) {
            if (!buf.isReadable()) { // partial match
                previousRemaining.addComponent(true,
                    buf.retainedSlice(sourceReaderIdx, i-previousRemaining.writerIndex()));
                return true;
            }
            if (i == headerToRemove.length()) { // match!
                previousRemaining.release(); // drop those in limbo ...
                previousRemaining = null;
                dropUntilNewline = true; // ... plus other bytes until we reset
                return true;
            }
            buf.markReaderIndex();
            if (Character.toLowerCase(headerToRemove.charAt(i)) != Character.toLowerCase(buf.readByte())) { // no match
                flushAndClearPreviousRemaining(ctx);
                buf.resetReaderIndex();
                dropUntilNewline = false;
                return false;
            }
        }
    }

    void flushAndClearPreviousRemaining(ChannelHandlerContext ctx) {
        if (previousRemaining != null) {
            previousRemaining.forEach(bb -> lambdaSafeSuperChannelRead(ctx, bb.retain()));
            previousRemaining.removeComponents(0, previousRemaining.numComponents());
            previousRemaining.release();
            previousRemaining = null;
        }
    }

    boolean advanceByteBufUntilNewline(ByteBuf bb) {
        while (bb.isReadable()) { // sonar lint doesn't like if the while statement has an empty body
            if (bb.readByte() == '\n') { return true; }
        }
        return false;
    }

    ByteBuf addSliceToRunningBuf(ChannelHandlerContext ctx, ByteBuf priorBuf, ByteBuf sourceBuf,
                                 int start, int len) {
        if (len == 0) {
            return priorBuf;
        }
        var slicedSourceBuf = sourceBuf.retainedSlice(start, len);
        if (priorBuf == null) {
            return slicedSourceBuf;
        }
        CompositeByteBuf cbb;
        if (!(priorBuf instanceof CompositeByteBuf)) {
            cbb = ctx.alloc().compositeBuffer(4);
            cbb.addComponent(true, priorBuf);
        } else {
            cbb = (CompositeByteBuf) priorBuf;
        }
        cbb.addComponent(true, slicedSourceBuf);
        return cbb;
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        if (!(msg instanceof ByteBuf) || requestPosition == MessagePosition.AFTER_HEADERS) {
            super.channelRead(ctx, msg);
            return;
        }

        var sourceBuf = (ByteBuf) msg;
        var currentSourceSegmentStart =
            (previousRemaining != null || dropUntilNewline || requestPosition == MessagePosition.ONE_NEW_LINE)
                ? -1 : sourceBuf.readerIndex();
        ByteBuf cleanedIncomingBuf = null;
        sourceBuf.markReaderIndex();

        while (sourceBuf.isReadable()) {
            if (requestPosition == MessagePosition.ONE_NEW_LINE) {
                final var nextByte = sourceBuf.readByte();
                if (nextByte == '\n' || nextByte == '\r') {
                    requestPosition = MessagePosition.AFTER_HEADERS;
                    if (currentSourceSegmentStart == -1) {
                        currentSourceSegmentStart = sourceBuf.readerIndex() - 1;
                    }
                    sourceBuf.readerIndex(sourceBuf.writerIndex());
                    break;
                } else {
                    previousRemaining = ctx.alloc().compositeBuffer(16);
                    requestPosition = MessagePosition.IN_HEADER;
                    sourceBuf.resetReaderIndex();
                    continue;
                }
            }

            if (previousRemaining != null) {
                final var sourceReaderIdx = sourceBuf.readerIndex();
                if (matchNextBytes(ctx, sourceBuf)) {
                    if (currentSourceSegmentStart >= 0 &&
                        sourceReaderIdx != currentSourceSegmentStart)  // would be 0-length
                    {
                        cleanedIncomingBuf = addSliceToRunningBuf(ctx, cleanedIncomingBuf, sourceBuf,
                            currentSourceSegmentStart, sourceReaderIdx-currentSourceSegmentStart);
                        currentSourceSegmentStart = -1;
                    }
                } else if (currentSourceSegmentStart == -1) {
                    currentSourceSegmentStart = sourceReaderIdx;
                }
            } else {
                if (advanceByteBufUntilNewline(sourceBuf)) {
                    sourceBuf.markReaderIndex();
                    requestPosition = MessagePosition.ONE_NEW_LINE;
                } else {
                    break;
                }
            }
        }

        if (currentSourceSegmentStart >= 0) {
            cleanedIncomingBuf = addSliceToRunningBuf(ctx, cleanedIncomingBuf, sourceBuf,
                currentSourceSegmentStart, sourceBuf.readerIndex()-currentSourceSegmentStart);
        }
        sourceBuf.release();
        if (cleanedIncomingBuf != null) {
            super.channelRead(ctx, cleanedIncomingBuf);
        }
    }

    @Override
    public void channelUnregistered(ChannelHandlerContext ctx) throws Exception {
        ReferenceCountUtil.release(previousRemaining);
        super.channelUnregistered(ctx);
    }

    @Override
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
        resetState(ctx);
        super.write(ctx, msg, promise);
    }

    private void resetState(ChannelHandlerContext ctx) {
        flushAndClearPreviousRemaining(ctx);
        dropUntilNewline = false;
        requestPosition = MessagePosition.IN_HEADER;
    }
}
