package org.opensearch.migrations.trafficcapture.proxyserver.netty;

import java.nio.charset.StandardCharsets;
import java.util.List;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.CompositeByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.util.ReferenceCountUtil;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class HeaderRemoverHandler extends ChannelInboundHandlerAdapter {
    final String headerToRemove;
    CompositeByteBuf previousRemaining;
    // This handler has 3 states - copying, dropping, or testing.  when previousRemaining != null, we're testing.
    // when dropUntilNewline == true, we're dropping, otherwise, we're copying (when previousRemaining==null)
    // The starting state is previousRemaining == null and dropUntilNewline = false
    boolean dropUntilNewline;

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
                previousRemaining.forEach(bb -> lambdaSafeSuperChannelRead(ctx, bb));
                previousRemaining.removeComponents(0, previousRemaining.numComponents());
                previousRemaining.release();
                previousRemaining = null;
                buf.resetReaderIndex();
                dropUntilNewline = false;
                return false;
            }
        }
    }

    boolean advanceByteBufUntilNewline(ByteBuf bb) {
        while (bb.isReadable()) { // sonar lint doesn't like if the while statement has an empty body
            if (bb.readByte() == '\n') { return true; }
        }
        return false;
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        if (!(msg instanceof ByteBuf)) {
            super.channelRead(ctx, msg);
            return;
        }

        var sourceBuf = (ByteBuf) msg;
        var currentSourceSegmentStart = (previousRemaining != null || dropUntilNewline) ? -1 : sourceBuf.readerIndex();
        var cleanedIncomingBuf = ctx.alloc().compositeBuffer(4);

        while (sourceBuf.isReadable()) {
            if (previousRemaining != null) {
                final var sourceReaderIdx = sourceBuf.readerIndex();
                if (matchNextBytes(ctx, sourceBuf)) {
                    if (currentSourceSegmentStart >= 0 &&
                        sourceReaderIdx != currentSourceSegmentStart)  // would be 0-length
                    {
                        cleanedIncomingBuf.addComponent(true,
                        sourceBuf.retainedSlice(currentSourceSegmentStart, sourceReaderIdx-currentSourceSegmentStart));
                        currentSourceSegmentStart = -1;
                    }
                } else if (currentSourceSegmentStart == -1) {
                    currentSourceSegmentStart = sourceReaderIdx;
                }
            } else {
                if (advanceByteBufUntilNewline(sourceBuf)) {
                    previousRemaining = ctx.alloc().compositeBuffer(16);
                } else {
                    break;
                }
            }
        }
        if (currentSourceSegmentStart >= 0) {
            cleanedIncomingBuf.addComponent(true,
                sourceBuf.retainedSlice(currentSourceSegmentStart, sourceBuf.readerIndex()-currentSourceSegmentStart));
        }
        super.channelRead(ctx, cleanedIncomingBuf);
    }

    @Override
    public void channelUnregistered(ChannelHandlerContext ctx) throws Exception {
        ReferenceCountUtil.release(previousRemaining);
        super.channelUnregistered(ctx);
    }
}
