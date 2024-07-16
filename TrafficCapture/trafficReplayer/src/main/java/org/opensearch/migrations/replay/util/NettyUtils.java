package org.opensearch.migrations.replay.util;

import java.util.Collection;
import java.util.stream.Stream;

import com.google.errorprone.annotations.MustBeClosed;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

public final class NettyUtils {
    @MustBeClosed
    public static Stream<ByteBuf> createRefCntNeutralCloseableByteBufStream(Stream<byte[]> byteArrStream) {
        return RefSafeStreamUtils.refSafeMap(byteArrStream, Unpooled::wrappedBuffer);
    }

    @MustBeClosed
    public static Stream<ByteBuf> createRefCntNeutralCloseableByteBufStream(Collection<byte[]> byteArrCollection) {
        return createRefCntNeutralCloseableByteBufStream(byteArrCollection.stream());
    }

    private NettyUtils() {}
}
