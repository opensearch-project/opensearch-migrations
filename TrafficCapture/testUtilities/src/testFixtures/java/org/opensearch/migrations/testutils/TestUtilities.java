package org.opensearch.migrations.testutils;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.Unpooled;

public class TestUtilities {
    public static ByteBuf getByteBuf(byte[] src, boolean usePool) {
        var unpooled = Unpooled.wrappedBuffer(src);
        if (usePool) {
            var pooled = ByteBufAllocator.DEFAULT.buffer(src.length);
            pooled.writeBytes(unpooled);
            unpooled.release();
            return pooled;
        } else {
            return unpooled;
        }
    }

}
