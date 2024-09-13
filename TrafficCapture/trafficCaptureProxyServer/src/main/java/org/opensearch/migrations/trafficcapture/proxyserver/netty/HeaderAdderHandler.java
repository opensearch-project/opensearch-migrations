package org.opensearch.migrations.trafficcapture.proxyserver.netty;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;

public class HeaderAdderHandler extends ChannelInboundHandlerAdapter {
    boolean insertedHeader = false;
    private final ByteBuf headerLineToAdd;

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
            if (buf.readByte() == '\n') {
                final var upToIndex = buf.readerIndex();
                var composite = Unpooled.compositeBuffer(3);
                buf.resetReaderIndex();
                composite.addComponent(true, buf.retainedSlice(0, upToIndex));
                composite.addComponent(true, headerLineToAdd.duplicate());
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
}
