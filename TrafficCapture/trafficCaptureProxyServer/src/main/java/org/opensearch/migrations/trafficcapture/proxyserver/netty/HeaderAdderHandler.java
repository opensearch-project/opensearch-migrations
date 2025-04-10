package org.opensearch.migrations.trafficcapture.proxyserver.netty;

import java.nio.charset.StandardCharsets;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.util.ReferenceCountUtil;

/**
 * This handler inserts a predefined HTTP header line into the stream immediately before the end of the
 * HTTP request headers (i.e., before the first CRLF or LF-only line break that separates headers from the body).
 * <p>
 * It maintains lightweight internal state to ensure the header is only inserted once per request.
 * This state is automatically cleared during the {@code write(...)} phase, making the handler safe to reuse
 * across multiple HTTP requests on a persistent connection (e.g., HTTP/1.1 keep-alive).
 * <p>
 * As long as a response is written for each request, the handler will reset itself appropriately and
 * continue to function correctly without requiring any manual intervention.
 */
public class HeaderAdderHandler extends ChannelDuplexHandler {
    private static final ByteBuf CRLF_BYTE_BUF =
        Unpooled.unreleasableBuffer(Unpooled.wrappedBuffer("\r\n".getBytes(StandardCharsets.UTF_8)));
    private static final ByteBuf LF_BYTE_BUF =
        Unpooled.unreleasableBuffer(Unpooled.wrappedBuffer("\n".getBytes(StandardCharsets.UTF_8)));
    boolean insertedHeader = false;
    private final ByteBuf headerLineToAdd;
    boolean useCarriageReturn;

    public HeaderAdderHandler(ByteBuf headerLineToAdd) {
        this.headerLineToAdd = headerLineToAdd.retain();
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        if (!(msg instanceof ByteBuf) || insertedHeader) {
            super.channelRead(ctx, msg);
            return;
        }
        var buf = (ByteBuf) msg;
        buf.markReaderIndex();
        while (buf.isReadable()) {
            var nextByte = buf.readByte();
            if (nextByte == '\r') {
                useCarriageReturn = true;
            } else if (nextByte == '\n') {
                final var upToIndex = buf.readerIndex();
                var composite = ctx.alloc().compositeBuffer(4);
                buf.resetReaderIndex();
                final var startingIndex = buf.readerIndex();
                composite.addComponent(true, buf.retainedSlice(startingIndex, upToIndex-startingIndex));
                composite.addComponent(true, headerLineToAdd.retainedDuplicate());
                composite.addComponent(true, (useCarriageReturn ? CRLF_BYTE_BUF : LF_BYTE_BUF).duplicate());
                composite.addComponent(true, buf.retainedSlice(upToIndex, buf.readableBytes()-upToIndex));
                buf.release();
                insertedHeader = true;
                super.channelRead(ctx, composite);
                return;
            }
        }
        buf.resetReaderIndex();
        super.channelRead(ctx, msg);
    }

    @Override
    public void channelUnregistered(ChannelHandlerContext ctx) throws Exception {
        ReferenceCountUtil.release(headerLineToAdd);
        super.channelUnregistered(ctx);
    }

    @Override
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
        resetState();
        super.write(ctx, msg, promise);
    }

    private void resetState() {
        insertedHeader = false;
    }
}
