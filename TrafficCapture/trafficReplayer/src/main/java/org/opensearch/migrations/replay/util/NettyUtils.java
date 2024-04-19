package org.opensearch.migrations.replay.util;

import com.google.errorprone.annotations.MustBeClosed;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import java.util.Collection;

import java.util.stream.Stream;

public final class NettyUtils {
    @MustBeClosed
    public static Stream<ByteBuf> createCloseableByteBufStream(Stream<byte[]> byteArrStream) {
        return RefSafeStreamUtils.refSafeMap(byteArrStream, Unpooled::wrappedBuffer);
    }

    @MustBeClosed
    public static Stream<ByteBuf> createCloseableByteBufStream(Collection<byte[]> byteArrCollection) {
        return createCloseableByteBufStream(byteArrCollection.stream());
    }

    private NettyUtils() {}
}