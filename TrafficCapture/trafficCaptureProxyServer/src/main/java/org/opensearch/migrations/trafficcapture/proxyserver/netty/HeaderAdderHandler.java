package org.opensearch.migrations.trafficcapture.proxyserver.netty;

import java.nio.charset.StandardCharsets;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.util.ReferenceCountUtil;

public class HeaderAdderHandler extends ChannelInboundHandlerAdapter {
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
                super.channelRead(ctx, composite);
                insertedHeader = true;
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
}
